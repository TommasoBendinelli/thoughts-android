// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder
import com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames
import com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames.Rename
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant

/**
 * This phone's half of the source-folder-names change (connected folders `docs-x` →
 * `docs-<slug>-<hex>`; see the source-folder-names spec, "Clients").
 *
 * Fedora moves its folders first and sends the map M (`source_folder_renames`) with the Project
 * catalogue. During Sync, under the Sync lock and before anything reads source paths, every M
 * entry this phone has not applied yet is applied here; an interrupted run is resumed at the
 * next start or Sync. Same steps as [DefaultFolderMigration]:
 * 1. a journal records the run and the M it applies ([Layout.journal]);
 * 2. every `<root>/<old>` folder (public working tree, private root) becomes `<root>/<new>`
 *    with one rename; when `<new>` already exists only missing entries move, identical files
 *    are dropped and differing ones stop the migration with a [Problem];
 * 3. Rule P on every local state file and table that stores source paths or folder names
 *    (never on source text); files named `sha256(path)` are renamed to `sha256(P(path))`;
 *    on a UNIQUE clash in Room the row already stored under the new path (Fedora's) wins;
 * 4. rebuildable caches keyed by path are dropped and the comment scope index is rebuilt;
 * 5. the journal is marked done and M joins the applied list.
 * Every step is idempotent. Nothing here publishes anything: Fedora already moved its copy.
 */
internal class SourceFolderNamesMigration(
    private val layout: DefaultFolderMigration.Layout,
    private val sql: DefaultFolderSql? = null,
    private val lockedRewrites: List<(Map<String, String>) -> Any?> = emptyList(),
) {
    /** A state the migration must not resolve on its own; Sync shows the message. */
    class Problem(message: String) : IllegalStateException(message)

    data class Report(
        val renamedFolders: Int = 0,
        val movedFolders: Int = 0,
        val rewrittenFiles: Int = 0,
        val renamedFiles: Int = 0,
        val rewrittenRows: Int = 0,
        val droppedCaches: Int = 0,
    )

    private fun journal(): JsonObject? = runCatching { JsonParser.parseString(layout.journal.readText()).asJsonObject }.getOrNull()

    fun phase(): String? = journal()?.get("phase")?.takeIf { it.isJsonPrimitive }?.asString

    /** A run started and did not finish (crash or [Problem]). */
    fun interrupted(): Boolean = layout.journal.isFile && phase() != PHASE_DONE

    /** Every rename a finished run applied. */
    fun applied(): List<Rename> = SourceFolderNames.parse(journal()?.get("applied"))

    /** The renames the unfinished run is applying. */
    private fun inProgress(): List<Rename> =
        if (interrupted()) SourceFolderNames.parse(journal()?.get("renames")) else emptyList()

    /** The entries of Fedora's [renames] this phone has not applied yet. */
    fun pending(renames: List<Rename>): List<Rename> {
        val done = applied().map { it.old to it.new }.toSet()
        return renames.filter { (it.old to it.new) !in done }
    }

    fun needed(renames: List<Rename>): Boolean = interrupted() || pending(renames).isNotEmpty()

    fun run(fedora: List<Rename>): Report {
        val previous = journal()
        val started = previous?.takeIf { it.get("phase")?.asString != PHASE_DONE }?.get("started_at")?.asString
            ?: Instant.now().toString()
        val applied = applied()
        val renames = (inProgress() + pending(fedora)).distinct()
        requireOneToOne(applied + renames)
        if (renames.isEmpty()) {
            if (interrupted()) writeJournal(PHASE_DONE, started, applied, emptyList(), Report(), null)
            return Report()
        }
        val map = SourceFolderNames.toMap(renames)
        writeJournal(PHASE_STARTED, started, applied, renames, null, null)
        // From now on paths in an old spelling (bundles, requests, editors) are read in the new one.
        SourceFolderNames.installLocal(SourceFolderNames.toMap(applied + renames))
        val problems = mutableListOf<String>()
        writeJournal(PHASE_MOVING, started, applied, renames, null, null)
        val (renamed, merged) = moveFolders(renames, problems)
        if (problems.isNotEmpty()) fail(started, applied, renames, problems)
        writeJournal(PHASE_REWRITING, started, applied, renames, null, null)
        var report = Report(renamedFolders = renamed, movedFolders = merged)
        report = rewriteSourceEditorState(map, report, problems)
        if (problems.isNotEmpty()) fail(started, applied, renames, problems)
        var rewritten = report.rewrittenFiles
        for (tree in layout.jsonTrees) rewritten += rewriteJsonTree(tree, map)
        lockedRewrites.forEach { it(map) }
        val dropped = dropCaches()
        val rows = sql?.let { rewriteDatabase(it, map) } ?: 0
        report = report.copy(rewrittenFiles = rewritten, rewrittenRows = rows, droppedCaches = dropped)
        writeJournal(PHASE_DONE, started, (applied + renames).distinct(), emptyList(), report, null)
        return report
    }

    private fun requireOneToOne(renames: List<Rename>) {
        val olds = renames.groupBy(Rename::old).filterValues { list -> list.map(Rename::new).toSet().size > 1 }
        val news = renames.groupBy(Rename::new).filterValues { list -> list.map(Rename::old).toSet().size > 1 }
        if (olds.isNotEmpty() || news.isNotEmpty()) throw Problem(
            "The server renamed a source folder differently than this phone already did (" +
                (olds.keys + news.keys).take(3).joinToString() + "). Tap Sync again after the server's folder names are checked.",
        )
    }

    private fun fail(started: String, applied: List<Rename>, renames: List<Rename>, problems: List<String>): Nothing {
        val message = "The source folders could not be renamed on this phone: " + problems.take(5).joinToString("; ") +
            (if (problems.size > 5) "; and ${problems.size - 5} more" else "") +
            ". Keep the copy you want, remove the other, then tap Sync."
        writeJournal(PHASE_STARTED, started, applied, renames, null, problems)
        throw Problem(message)
    }

    // Step 2 ---------------------------------------------------------------------------------

    /** Renamed folders (one rename) and merged folders (the new one existed already). */
    private fun moveFolders(renames: List<Rename>, problems: MutableList<String>): Pair<Int, Int> {
        var renamed = 0
        var merged = 0
        for (root in layout.folderRoots) for (rename in renames.sortedBy(Rename::old)) {
            val from = File(root, rename.old)
            if (!from.exists()) continue
            val to = File(root, rename.new)
            if (!to.exists()) {
                if (from.renameTo(to)) renamed++ else problems += "${from.path} could not be renamed to ${to.name}"
                continue
            }
            moveEntry(from, to, problems)
            if (!from.exists()) merged++
        }
        return renamed to merged
    }

    /** Moves [from] to [to]: missing entries move, identical files are dropped, differing ones are [problems]. */
    private fun moveEntry(from: File, to: File, problems: MutableList<String>) {
        when {
            !to.exists() -> {
                to.parentFile?.mkdirs()
                if (!from.renameTo(to)) problems += "${from.path} could not be moved to ${to.path}"
            }
            from.isDirectory && to.isDirectory -> {
                from.listFiles().orEmpty().sortedBy(File::getName).forEach { moveEntry(it, File(to, it.name), problems) }
                if (from.listFiles().isNullOrEmpty()) from.delete()
            }
            from.isFile && to.isFile && DefaultFolderMigration.sameBytes(from, to) -> from.delete()
            else -> problems += "${from.path} differs from ${to.path}"
        }
    }

    // Step 3: the source editor's files named after a source path --------------------------

    private fun rewriteSourceEditorState(map: Map<String, String>, report: Report, problems: MutableList<String>): Report {
        val inverse = map.entries.associate { (old, new) -> new to old }
        val keys = pathKeyMap(map, inverse)
        var renamed = 0
        var rewritten = 0
        val offline = File(layout.sourceEditor, "offline")
        /** [newerWins]: state rebuilt from the bundle, where a copy in the new spelling replaces the old one. */
        fun keyed(directory: File, pathField: String, siblings: Boolean, newerWins: Boolean) {
            for (file in directory.listFiles().orEmpty().sortedBy(File::getName)) {
                if (!file.isFile || file.extension != "json" || !SHA256.matches(file.nameWithoutExtension)) continue
                val raw = runCatching { file.readText() }.getOrNull() ?: continue
                val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull()?.takeIf(JsonElement::isJsonObject) ?: continue
                val path = parsed.asJsonObject.get(pathField)?.takeIf { it.isJsonPrimitive }?.asString
                val replacement = SourceFolderNames.rewriteJsonValues(parsed, map, SKIP_KEYS)
                val text = if (replacement === parsed) raw else replacement.toString()
                val key = file.nameWithoutExtension
                // The name follows the path; a crash may have left new contents under the old name.
                val newKey = path?.takeIf { key == sha256(it) || key == sha256(SourceFolderNames.rewrite(it, inverse)) }
                    ?.let { sha256(SourceFolderNames.rewrite(it, map)) } ?: key
                if (newKey != key && siblings) {
                    // Other files of this source (a proposal journal's synced copy and document store) move first.
                    for (sibling in directory.listFiles().orEmpty()) {
                        if (sibling.name.startsWith("$key.") && sibling != file) {
                            moveEntry(sibling, File(directory, newKey + sibling.name.removePrefix(key)), problems)
                            renamed++
                        }
                    }
                }
                val target = File(directory, "$newKey.json")
                if (target == file) {
                    if (text != raw) { DefaultFolderMigration.atomicWrite(file, text); rewritten++ }
                } else if (target.isFile) {
                    if (newerWins || target.readText() == text) { file.delete(); renamed++ }
                    else problems += "${file.path} and ${target.path} describe the same source differently"
                } else {
                    DefaultFolderMigration.atomicWrite(target, text)
                    file.delete()
                    renamed++
                    if (text != raw) rewritten++
                }
            }
        }
        keyed(File(layout.sourceEditor, "drafts"), "source_file", siblings = false, newerWins = false)
        keyed(File(offline, "working"), "source_file", siblings = false, newerWins = true)
        keyed(File(offline, "materialization"), "source_file", siblings = false, newerWins = true)
        keyed(layout.proposalReview, "filename", siblings = true, newerWins = false)
        // Merge recoveries hold only source text: their names come from the paths known elsewhere.
        val recoveries = File(offline, "merge-recoveries")
        for (file in recoveries.listFiles().orEmpty().sortedBy(File::getName)) {
            val key = file.name.substringBefore('.')
            val newKey = keys[key] ?: continue
            moveEntry(file, File(recoveries, newKey + file.name.removePrefix(key)), problems)
            renamed++
        }
        // Everything else in the source editor's state names paths only in its contents.
        rewritten += rewriteJsonTree(layout.sourceEditor, map)
        return report.copy(rewrittenFiles = rewritten, renamedFiles = renamed)
    }

    /** sha256(old path) → sha256(P(path)) for every source path of a renamed folder this phone knows. */
    private fun pathKeyMap(map: Map<String, String>, inverse: Map<String, String>): Map<String, String> {
        val paths = linkedSetOf<String>()
        fun collect(directory: File, field: String) {
            for (file in directory.listFiles().orEmpty()) {
                if (!file.isFile || file.extension != "json") continue
                runCatching { JsonParser.parseString(file.readText()).asJsonObject.get(field)?.asString }
                    .getOrNull()?.let(paths::add)
            }
        }
        val offline = File(layout.sourceEditor, "offline")
        collect(File(layout.sourceEditor, "drafts"), "source_file")
        collect(File(offline, "working"), "source_file")
        collect(File(offline, "materialization"), "source_file")
        collect(layout.proposalReview, "filename")
        layout.folderRoots.firstOrNull()?.let { root ->
            for (new in map.values) {
                File(root, new).walkTopDown().onEnter { it.name == new || !it.name.startsWith(".") }
                    .filter(File::isFile).forEach { paths += SourceFolderNames.rewrite(it.relativeTo(root).invariantSeparatorsPath, inverse) }
            }
        }
        return buildMap {
            for (path in paths) {
                val renamed = SourceFolderNames.rewrite(path, map)
                if (renamed != path) put(sha256(path), sha256(renamed))
            }
        }
    }

    // Step 3: other JSON state --------------------------------------------------------------

    private fun rewriteJsonTree(tree: File, map: Map<String, String>): Int {
        if (!tree.exists()) return 0
        val workingTree = layout.folderRoots.firstOrNull()?.canonicalFile
        var rewritten = 0
        tree.walkTopDown()
            .onEnter { directory ->
                directory == tree || (directory.name !in EXCLUDED_DIRECTORIES && !directory.name.endsWith(".documents") &&
                    directory.canonicalFile != workingTree)
            }
            .filter { it.isFile && it.extension == "json" && it.length() <= MAX_JSON_BYTES }
            .forEach { file ->
                val raw = runCatching { file.readText() }.getOrNull() ?: return@forEach
                if (!SourceFolderNames.contains(raw, map)) return@forEach
                val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return@forEach
                val replacement = SourceFolderNames.rewriteJsonValues(parsed, map, SKIP_KEYS)
                if (replacement !== parsed) { DefaultFolderMigration.atomicWrite(file, replacement.toString()); rewritten++ }
            }
        return rewritten
    }

    // Step 4 ---------------------------------------------------------------------------------

    private fun dropCaches(): Int {
        var dropped = 0
        val snapshots = File(layout.sourceEditor, "snapshots")
        if (snapshots.exists()) { snapshots.deleteRecursively(); dropped++ }
        File(layout.sourceEditor, "offline").walkTopDown()
            .onEnter { it.name !in EXCLUDED_DIRECTORIES || it.name == "offline" }
            .filter { it.isFile && it.name in DERIVED_CACHES }
            .toList()
            .forEach { if (it.delete()) dropped++ }
        return dropped
    }

    // Step 3: Room ---------------------------------------------------------------------------

    private fun rewriteDatabase(sql: DefaultFolderSql, map: Map<String, String>): Int = sql.transaction {
        var changed = 0
        val olds = map.keys.sorted()
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] as String }
        for (table in tables) {
            if (table in SKIPPED_TABLES || table.startsWith("sqlite_")) continue
            val columns = sql.query("PRAGMA table_info(`$table`)")
                .filter { (it[2] as? String).orEmpty().uppercase().contains("TEXT") }
                .map { it[1] as String }
                .filter { "$table.$it" !in SKIPPED_COLUMNS }
            for (column in columns) {
                // instr() is case-sensitive like Rule P; the token check happens in Kotlin.
                val filter = olds.joinToString(" OR ") { "instr(`$column`, ?) > 0" }
                val rows = sql.query("SELECT rowid, `$column` FROM `$table` WHERE $filter", olds.toTypedArray<Any?>())
                for (row in rows) {
                    val value = row[1] as? String ?: continue
                    val rewritten = rewriteColumnValue(value, map)
                    if (rewritten == value) continue
                    // A row already stored under the new path is newer (Fedora sent it): it wins.
                    if (sql.update("UPDATE OR IGNORE `$table` SET `$column` = ? WHERE rowid = ?", arrayOf(rewritten, row[0])) == 0) {
                        sql.update("DELETE FROM `$table` WHERE rowid = ?", arrayOf(row[0]))
                    }
                    changed++
                }
            }
        }
        if ("CurrentSourceAnnotation" in tables) sql.update("DELETE FROM CurrentSourceAnnotation")
        // Review cards cached in memory are reread when the review generation moves.
        if (changed > 0 && "ReviewMeta" in tables) sql.update("UPDATE ReviewMeta SET generation = generation + 1 WHERE id = 1")
        if ("ThoughtsCommentScope" in tables) {
            val stale = sql.query("SELECT scopeKey FROM ThoughtsCommentScope WHERE " + olds.joinToString(" OR ") { "instr(scopeKey, ?) > 0" },
                olds.toTypedArray<Any?>()).any { SourceFolderNames.contains(it[0] as String, map) }
            if (stale || changed > 0) changed += rebuildCommentScopeIndex(sql)
        }
        changed
    }

    companion object {
        const val PHASE_STARTED = "started"
        const val PHASE_MOVING = "moving"
        const val PHASE_REWRITING = "rewriting"
        const val PHASE_DONE = "done"
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val MAX_JSON_BYTES = 64L * 1024 * 1024
        /** Content-addressed objects and source text: never rewritten. */
        private val EXCLUDED_DIRECTORIES = setOf(
            "objects", "object-cache", "synctex-native", "navigation-bases", "snapshots", "document-artifacts",
            "merge-recoveries", "bundle-staging", ".thoughts",
        )
        /** Rebuildable caches keyed by source path or folder. */
        private val DERIVED_CACHES = setOf(
            "source-location-index.json", "source-open-index-v2.json", "overview-projection-v1.json", "overview-objects-v1.bin",
        )
        /** Room bookkeeping, Sync history, and tables rebuilt instead of rewritten. */
        private val SKIPPED_TABLES = setOf(
            "android_metadata", "room_master_table", "sync_runs", "sync_steps", "sync_events",
            "ThoughtsCommentScope", "CurrentSourceAnnotation",
        )
        /** Free text that may mention a folder by name (Rule P never rewrites prose). */
        private val SKIPPED_COLUMNS = setOf(
            "VoiceCapture.transcript", "VoiceCapture.transcriptionJson", "VoiceCapture.detectedLanguagesJson",
            "ThoughtsSyncState.lastSyncErrorMessage", "ThoughtsOutbox.failureDetail",
        )
        /** Keys never rewritten: source text, and M itself where a store keeps it. */
        val SKIP_KEYS: (String) -> Boolean = { key -> DefaultFolder.SOURCE_TEXT(key) || key == RENAMES_KEY }
        const val RENAMES_KEY = "source_folder_renames"

        /** Rule P on one stored value: JSON field by field (source text kept), plain text unless it is multi-line. */
        internal fun rewriteColumnValue(value: String, map: Map<String, String>): String {
            val start = value.firstOrNull { !it.isWhitespace() }
            if (start == '{' || start == '[') {
                val parsed = runCatching { JsonParser.parseString(value) }.getOrNull()
                if (parsed != null && (parsed.isJsonObject || parsed.isJsonArray)) {
                    val rewritten = SourceFolderNames.rewriteJsonValues(parsed, map, SKIP_KEYS)
                    return if (rewritten === parsed) value else rewritten.toString()
                }
            }
            return if ('\n' in value) value else SourceFolderNames.rewrite(value, map)
        }

        fun journal(context: Context): File = File(context.noBackupFilesDir, "thoughts-sync/source-folder-names-migration.json")

        /**
         * Reads the journal once at process start: renames this phone applied or started applying
         * are followed by every path read from then on ([SourceFolderNames.localRenames]).
         */
        fun install(context: Context) {
            installFrom(journal(context))
        }

        internal fun installFrom(journal: File) {
            val state = runCatching { JsonParser.parseString(journal.readText()).asJsonObject }.getOrNull() ?: return
            val renames = SourceFolderNames.parse(state.get("applied")) +
                (if (state.get("phase")?.asString != PHASE_DONE) SourceFolderNames.parse(state.get("renames")) else emptyList())
            runCatching { SourceFolderNames.installLocal(SourceFolderNames.toMap(renames.distinct())) }
        }

        fun forContext(context: Context, database: androidx.room.RoomDatabase): SourceFolderNamesMigration {
            val files = context.filesDir
            return SourceFolderNamesMigration(
                DefaultFolderMigration.Layout(
                    journal = journal(context),
                    folderRoots = listOf(
                        com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore.workingDirectory(context),
                        ThoughtsLocalStore.privateRoot(),
                    ),
                    sourceEditor = File(files, "source-editor"),
                    proposalReview = File(files, "proposal-review"),
                    jsonTrees = listOf(
                        ThoughtsLocalStore.sourceRequests(), ThoughtsLocalStore.sourceReceipts(),
                        ThoughtsLocalStore.renderRequests(), ThoughtsLocalStore.renderReceipts(),
                        ThoughtsLocalStore.comments(), File(ThoughtsLocalStore.privateRoot(), "reviews"),
                        File(files, "review-source-transactions"), File(files, "comment-delivery-outbox"),
                        File(files, "source-comment-snapshot.json"), File(files, "proposal-original-previews"),
                        File(files, "library-imports"), File(files, "annotations"),
                    ),
                ),
                RoomDefaultFolderSql(database),
                listOf(
                    { map -> LibraryProjectStore(context).rewriteSourceFolderNames(map) },
                    { map -> DocumentIdentityStore().rewriteSourceFolderNames(map) },
                ),
            )
        }

        private fun sha256(text: String): String = DefaultFolderMigration.sha256(text)
    }

    // Journal ----------------------------------------------------------------------------------

    private fun writeJournal(
        phase: String,
        startedAt: String,
        applied: List<Rename>,
        renames: List<Rename>,
        report: Report?,
        problems: List<String>?,
    ) {
        DefaultFolderMigration.atomicWrite(layout.journal, JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("phase", phase)
            addProperty("started_at", startedAt)
            addProperty("updated_at", Instant.now().toString())
            add("renames", SourceFolderNames.toJson(renames))
            add("applied", SourceFolderNames.toJson(applied))
            problems?.let { list -> add("problems", JsonArray().apply { list.forEach(::add) }) }
            report?.let {
                add("report", JsonObject().apply {
                    addProperty("renamed_folders", it.renamedFolders)
                    addProperty("moved_folders", it.movedFolders)
                    addProperty("rewritten_files", it.rewrittenFiles)
                    addProperty("renamed_files", it.renamedFiles)
                    addProperty("rewritten_rows", it.rewrittenRows)
                    addProperty("dropped_caches", it.droppedCaches)
                })
            }
        }.toString())
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable

/** The few SQL operations the default-folder migration needs (Room on the phone, JDBC in tests). */
internal interface DefaultFolderSql {
    fun <T> transaction(block: () -> T): T
    fun query(sql: String, args: Array<Any?> = emptyArray()): List<List<Any?>>
    /** Runs an INSERT, UPDATE or DELETE and returns the number of rows it changed. */
    fun update(sql: String, args: Array<Any?> = emptyArray()): Int
}

internal class RoomDefaultFolderSql(private val database: androidx.room.RoomDatabase) : DefaultFolderSql {
    private val db get() = database.openHelper.writableDatabase

    override fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable { block() })

    override fun query(sql: String, args: Array<Any?>): List<List<Any?>> = db.query(sql, args).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                when (cursor.getType(column)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                    android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column)
                    else -> cursor.getString(column)
                }
            })
        }
    }

    override fun update(sql: String, args: Array<Any?>): Int = db.compileStatement(sql).use { statement ->
        args.forEachIndexed { index, value ->
            when (value) {
                null -> statement.bindNull(index + 1)
                is Long -> statement.bindLong(index + 1, value)
                is Int -> statement.bindLong(index + 1, value.toLong())
                is Double -> statement.bindDouble(index + 1, value)
                is ByteArray -> statement.bindBlob(index + 1, value)
                else -> statement.bindString(index + 1, value.toString())
            }
        }
        statement.executeUpdateDelete()
    }
}

/**
 * This phone's half of the default-folder rename (`.thoughts/projects/<h>/documents` →
 * `…/default`; see DEFAULT-FOLDER-RENAME spec, "Client local migration").
 *
 * Runs during Sync, under the Sync lock and before anything reads source paths, once Fedora
 * names a default folder `default` (Project catalogue or Library snapshot) while this phone
 * has not moved yet; an interrupted run is resumed at the next start or Sync. Steps:
 * 1. a journal records the run ([Layout.journal]);
 * 2. every `<h>/documents` folder (working tree, staged imports) becomes `<h>/default` with one
 *    rename; when `default/` already exists only missing entries move, identical files are
 *    dropped and differing ones stop the migration with a [Problem];
 * 3. Rule R on every local state file and table that stores source paths; files named
 *    `sha256(path)` are renamed to `sha256(Rule R(path))`;
 * 4. rebuildable caches keyed by path are dropped;
 * 5. the journal is marked done.
 * Every step is idempotent: a rerun after a crash continues where the last one stopped.
 * Nothing here publishes anything; Fedora already moved its copy.
 */
internal class DefaultFolderMigration(
    private val layout: Layout,
    private val sql: DefaultFolderSql? = null,
    private val lockedRewrites: List<() -> Any?> = emptyList(),
) {
    internal data class Layout(
        val journal: File,
        /** Roots holding `.thoughts/projects/<h>/<segment>` folders; the first is the working tree. */
        val folderRoots: List<File>,
        val sourceEditor: File,
        val proposalReview: File,
        /** Further state files or directories whose JSON files hold source paths. */
        val jsonTrees: List<File> = emptyList(),
    )

    /** A state the migration must not resolve on its own; Sync shows the message. */
    class Problem(message: String) : IllegalStateException(message)

    data class Report(
        val movedFolders: Int = 0,
        val rewrittenFiles: Int = 0,
        val renamedFiles: Int = 0,
        val rewrittenRows: Int = 0,
        val droppedCaches: Int = 0,
    )

    fun phase(): String? = runCatching {
        JsonParser.parseString(layout.journal.readText()).asJsonObject.get("phase")?.asString
    }.getOrNull()

    fun done(): Boolean = phase() == PHASE_DONE

    /** A run started and did not finish (crash or [Problem]). */
    fun interrupted(): Boolean = layout.journal.isFile && !done()

    fun run(): Report {
        val started = runCatching { JsonParser.parseString(layout.journal.readText()).asJsonObject }.getOrNull()
        if (started?.get("phase")?.asString == PHASE_DONE) return Report()
        writeJournal(PHASE_STARTED, started?.get("started_at")?.asString ?: Instant.now().toString(), null, null)
        // From now on the phone uses `default`: moved files are found there, legacy bundles are read through Rule R.
        DefaultFolder.localStateMigrated = true
        val problems = mutableListOf<String>()
        val moved = moveDefaultFolders(problems)
        if (problems.isNotEmpty()) fail(problems)
        var report = Report(movedFolders = moved)
        report = rewriteSourceEditorState(report, problems)
        if (problems.isNotEmpty()) fail(problems)
        var rewritten = report.rewrittenFiles
        for (tree in layout.jsonTrees) rewritten += rewriteJsonTree(tree)
        lockedRewrites.forEach { it() }
        val dropped = dropCaches()
        val rows = sql?.let(::rewriteDatabase) ?: 0
        report = report.copy(rewrittenFiles = rewritten, rewrittenRows = rows, droppedCaches = dropped)
        writeJournal(PHASE_DONE, started?.get("started_at")?.asString ?: Instant.now().toString(), report, null)
        return report
    }

    private fun fail(problems: List<String>): Nothing {
        val message = "The default folders could not be renamed on this phone: " + problems.take(5).joinToString("; ") +
            (if (problems.size > 5) "; and ${problems.size - 5} more" else "") +
            ". Keep the copy you want, remove the other, then tap Sync."
        writeJournal(PHASE_STARTED, null, null, problems)
        throw Problem(message)
    }

    // Step 2 ---------------------------------------------------------------------------------

    private fun moveDefaultFolders(problems: MutableList<String>): Int {
        var moved = 0
        for (root in layout.folderRoots) {
            val projects = File(root, ".thoughts/projects")
            for (project in projects.listFiles().orEmpty().sortedBy(File::getName)) {
                if (!project.isDirectory || !PROJECT_KEY.matches(project.name)) continue
                val legacy = File(project, DefaultFolder.LEGACY)
                if (!legacy.exists()) continue
                val canonical = File(project, DefaultFolder.CANONICAL)
                if (!canonical.exists()) {
                    if (!legacy.renameTo(canonical)) problems += "${legacy.path} could not be renamed"
                    else moved++
                    continue
                }
                moveEntry(legacy, canonical, problems)
                if (!legacy.exists()) moved++
            }
        }
        return moved
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
            from.isFile && to.isFile && sameBytes(from, to) -> from.delete()
            else -> problems += "${from.path} differs from ${to.path}"
        }
    }

    // Step 3: the source editor's files named after a source path --------------------------

    private fun rewriteSourceEditorState(report: Report, problems: MutableList<String>): Report {
        val keys = pathKeyMap()
        var renamed = 0
        var rewritten = 0
        val offline = File(layout.sourceEditor, "offline")
        fun keyed(directory: File, pathField: String, siblings: Boolean) {
            for (file in directory.listFiles().orEmpty().sortedBy(File::getName)) {
                if (!file.isFile || file.extension != "json" || !SHA256.matches(file.nameWithoutExtension)) continue
                val raw = runCatching { file.readText() }.getOrNull() ?: continue
                val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull()?.takeIf(JsonElement::isJsonObject) ?: continue
                val path = (parsed.asJsonObject.get(pathField))?.takeIf { it.isJsonPrimitive }?.asString
                val replacement = DefaultFolder.rewriteJsonValues(parsed)
                val text = if (replacement === parsed) raw else replacement.toString()
                val key = file.nameWithoutExtension
                val newKey = path?.takeIf { key == sha256(it) || key == sha256(legacySpelling(it)) }
                    ?.let { sha256(DefaultFolder.rewriteLegacyDefaultFolder(it)) } ?: key
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
                    if (text != raw) { atomicWrite(file, text); rewritten++ }
                } else if (target.isFile) {
                    if (target.readText() == text) { file.delete(); renamed++ }
                    else problems += "${file.path} and ${target.path} describe the same source differently"
                } else {
                    atomicWrite(target, text)
                    file.delete()
                    renamed++
                    if (text != raw) rewritten++
                }
            }
        }
        keyed(File(layout.sourceEditor, "drafts"), "source_file", siblings = false)
        keyed(File(offline, "working"), "source_file", siblings = false)
        keyed(File(offline, "materialization"), "source_file", siblings = false)
        keyed(layout.proposalReview, "filename", siblings = true)
        // Merge recoveries hold only source text: their names come from the paths known elsewhere.
        val recoveries = File(offline, "merge-recoveries")
        for (file in recoveries.listFiles().orEmpty().sortedBy(File::getName)) {
            val key = file.name.substringBefore('.')
            val newKey = keys[key] ?: continue
            moveEntry(file, File(recoveries, newKey + file.name.removePrefix(key)), problems)
            renamed++
        }
        // Everything else in the source editor's state names paths only in its contents.
        rewritten += rewriteJsonTree(layout.sourceEditor)
        return report.copy(rewrittenFiles = rewritten, renamedFiles = renamed)
    }

    /** sha256(legacy path) → sha256(Rule R(path)) for every source path this phone knows. */
    private fun pathKeyMap(): Map<String, String> {
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
            val projects = File(root, ".thoughts/projects")
            projects.listFiles().orEmpty().filter { PROJECT_KEY.matches(it.name) }.forEach { project ->
                DefaultFolder.SEGMENTS.map { File(project, it) }.filter(File::isDirectory).forEach { folder ->
                    folder.walkTopDown().filter(File::isFile).forEach { file ->
                        paths += file.relativeTo(root).invariantSeparatorsPath
                    }
                }
            }
        }
        return buildMap {
            for (path in paths) {
                val legacy = legacySpelling(path)
                val canonical = DefaultFolder.rewriteLegacyDefaultFolder(legacy)
                if (legacy != canonical) put(sha256(legacy), sha256(canonical))
            }
        }
    }

    private fun legacySpelling(path: String): String = DefaultFolder.legacySpelling(path)

    // Step 3: other JSON state --------------------------------------------------------------

    private fun rewriteJsonTree(tree: File): Int {
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
                if (!DefaultFolder.containsLegacy(raw)) return@forEach
                val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return@forEach
                val replacement = DefaultFolder.rewriteJsonValues(parsed)
                if (replacement !== parsed) { atomicWrite(file, replacement.toString()); rewritten++ }
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

    private fun rewriteDatabase(sql: DefaultFolderSql): Int = sql.transaction {
        var changed = 0
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] as String }
        for (table in tables) {
            if (table in SKIPPED_TABLES || table.startsWith("sqlite_")) continue
            val columns = sql.query("PRAGMA table_info(`$table`)")
                .filter { (it[2] as? String).orEmpty().uppercase().contains("TEXT") }
                .map { it[1] as String }
            for (column in columns) {
                val rows = sql.query("SELECT rowid, `$column` FROM `$table` WHERE `$column` LIKE ?", arrayOf("%/documents%"))
                for (row in rows) {
                    val value = row[1] as? String ?: continue
                    val rewritten = DefaultFolder.rewriteLegacyDefaultFolder(value)
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
        if ("ThoughtsCommentScope" in tables) changed += rebuildCommentScopes(sql)
        changed
    }

    /** Like MIGRATION_26_TO_27: the scope index again from the rewritten comment payloads. */
    private fun rebuildCommentScopes(sql: DefaultFolderSql): Int {
        if (sql.query("SELECT 1 FROM ThoughtsCommentScope WHERE scopeKey LIKE ? LIMIT 1", arrayOf("%/documents%")).isEmpty()) return 0
        return rebuildCommentScopeIndex(sql)
    }

    // Journal ----------------------------------------------------------------------------------

    private fun writeJournal(phase: String, startedAt: String?, report: Report?, problems: List<String>?) {
        val previous = runCatching { JsonParser.parseString(layout.journal.readText()).asJsonObject }.getOrNull()
        atomicWrite(layout.journal, JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("phase", phase)
            addProperty("started_at", startedAt ?: previous?.get("started_at")?.asString ?: Instant.now().toString())
            addProperty("updated_at", Instant.now().toString())
            problems?.let { list -> add("problems", com.google.gson.JsonArray().apply { list.forEach(::add) }) }
            report?.let {
                add("report", JsonObject().apply {
                    addProperty("moved_folders", it.movedFolders)
                    addProperty("rewritten_files", it.rewrittenFiles)
                    addProperty("renamed_files", it.renamedFiles)
                    addProperty("rewritten_rows", it.rewrittenRows)
                    addProperty("dropped_caches", it.droppedCaches)
                })
            }
        }.toString())
    }

    companion object {
        const val PHASE_STARTED = "started"
        const val PHASE_DONE = "done"
        private val PROJECT_KEY = Regex("[0-9a-f]{64}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val MAX_JSON_BYTES = 64L * 1024 * 1024
        /** Content-addressed objects and source text: never rewritten. */
        private val EXCLUDED_DIRECTORIES = setOf(
            "objects", "object-cache", "synctex-native", "navigation-bases", "snapshots", "document-artifacts",
            "merge-recoveries", "bundle-staging", ".thoughts",
        )
        /** Rebuildable caches keyed by source path. */
        private val DERIVED_CACHES = setOf(
            "source-location-index.json", "source-open-index-v2.json", "overview-projection-v1.json", "overview-objects-v1.bin",
        )
        /** Room bookkeeping, Sync history, and tables rebuilt instead of rewritten. */
        private val SKIPPED_TABLES = setOf(
            "android_metadata", "room_master_table", "sync_runs", "sync_steps", "sync_events",
            "ThoughtsCommentScope", "CurrentSourceAnnotation",
        )

        fun journal(context: Context): File = File(context.noBackupFilesDir, "thoughts-sync/default-folder-migration.json")

        /** Reads the journal once at process start: a started or finished run means this phone uses `default`. */
        fun install(context: Context) {
            DefaultFolder.localStateMigrated = journal(context).isFile
        }

        fun forContext(context: Context, database: androidx.room.RoomDatabase): DefaultFolderMigration {
            val files = context.filesDir
            return DefaultFolderMigration(
                Layout(
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
                    { LibraryProjectStore(context).rewriteLegacyDefaultFolder() },
                    { DocumentIdentityStore().rewriteLegacyDefaultFolder() },
                ),
            )
        }

        /** True when [projectsState] (the stored Project catalogue) names a default folder `default`. */
        fun fedoraUsesDefault(projects: Iterable<JsonElement>): Boolean =
            DefaultFolder.librarySegment(projects) == DefaultFolder.CANONICAL

        internal fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

        internal fun sameBytes(a: File, b: File): Boolean = a.length() == b.length() && a.readBytes().contentEquals(b.readBytes())

        internal fun atomicWrite(destination: File, text: String) {
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(text.toByteArray(StandardCharsets.UTF_8))
                    output.fd.sync()
                }
                check(temporary.renameTo(destination)) { "${destination.path} could not be replaced" }
            } finally {
                temporary.delete()
            }
        }
    }
}

/** Like MIGRATION_26_TO_27: drops the comment scope index and builds it again from the comment payloads. */
internal fun rebuildCommentScopeIndex(sql: DefaultFolderSql): Int {
    sql.update("DELETE FROM ThoughtsCommentScope")
    var inserted = 0
    for ((source, query) in listOf(
        "projection" to "SELECT entityId, payloadJson FROM ThoughtsProjection WHERE entityType = 'logical_comment' AND collection = 'comments'",
        "local" to "SELECT entityId, payloadJson FROM ThoughtsLocalCommentState",
    )) for (row in sql.query(query)) {
        val id = row[0] as String
        for (key in logicalCommentScopeKeys(id, row[1] as String)) {
            inserted += sql.update("INSERT OR REPLACE INTO ThoughtsCommentScope VALUES (?, ?, ?)", arrayOf(key, id, source))
        }
    }
    return inserted
}

// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.os.Environment
import android.system.Os
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

object ThoughtsLocalStore {
    @Volatile private var privateRoot: File? = null

    fun install(context: Context) {
        privateRoot = File(context.filesDir, "thoughts")
    }

    private fun root(): File = checkNotNull(privateRoot) { "MJ server storage is not installed" }

    fun renderRequests(): File = File(root(), "render-requests")
    fun renderReceipts(): File = File(root(), "render-receipts")
    fun buildLogs(): File = File(root(), "build-logs")
    fun sourceRequests(): File = File(root(), "source-requests")
    fun sourceReceipts(): File = File(root(), "source-receipts")
    fun sourceBundles(): File = File(root(), "source-bundles")
    fun currentPdfs(): File = File(root(), "current-pdfs")
    /** Where PDF imports wait for their first Sync: the Project's default folder named [segment]. */
    fun projectDocuments(projectId: String, segment: String = com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.LEGACY): File =
        File(root(), com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.path(projectId, segment))

    /** The private root holding [projectDocuments] folders, for the default-folder migration. */
    internal fun privateRoot(): File = root()
    fun comments(): File = File(root(), "comments")
    fun linkOverrides(): File = File(root(), "link-overrides.json")
    /** Agent conversation removals waiting for Sync (agent_conversation records). */
    fun agentConversations(): File = File(root(), "agent-conversations.json")
    /** Liked/disliked passages of review card answers (review_card_feedback records). */
    fun reviewCardFeedback(): File = File(root(), "reviews/card-feedback.json")
    /** Liked/disliked passages of Library PDFs read normally (document_feedback records). */
    fun documentFeedback(): File = File(root(), "reviews/document-feedback.json")
    fun reviewIncoming(): File = File(root(), "reviews/incoming")
    fun reviewOutgoing(): File = File(root(), "reviews/outgoing")
    fun reviewOutgoingCards(): File = File(root(), "reviews/outgoing-cards")

    @Synchronized
    fun migrateLegacyProjectionOnce() {
        val marker = File(root(), ".private-projection-migrated")
        if (marker.isFile) return
        copyMissing(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "docs-clean-comments"),
            comments(),
        )
        marker.parentFile?.mkdirs()
        FileOutputStream(marker).use { stream ->
            stream.write("legacy comments copied; Fedora generations are canonical\n".toByteArray())
            stream.fd.sync()
        }
    }

    /**
     * Move old Syncthing version/conflict PDFs out of public storage without treating them as
     * Library documents. Verified entries remain recoverable for seven days; unmatched entries
     * are retained indefinitely for manual inspection.
     */
    @Synchronized
    fun quarantineLegacyPdfCaches(nowEpochMillis: Long = System.currentTimeMillis()) {
        pruneVerifiedQuarantine(nowEpochMillis)
        val snapshot = SourceLinkedPdfIndex(currentPdfs()).snapshot()
        val canonicalHashes = if (snapshot.generationCurrent) snapshot.libraryPdfSha256ByFilename else emptyMap()
        val legacyRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "docs-clean-pdfs",
        )
        if (!legacyRoot.isDirectory) return
        val candidates = legacyRoot.walkTopDown()
            .filter(File::isFile)
            .filter { file ->
                file.extension.equals("pdf", ignoreCase = true) &&
                    (file.name.contains(".sync-conflict-", ignoreCase = true) ||
                        file.toPath().any { component -> component.toString() == ".stversions" })
            }
            .toList()
        if (candidates.isEmpty()) return

        val batch = File(quarantineRoot(), Instant.ofEpochMilli(nowEpochMillis).toString().replace(':', '-'))
        val entries = JsonArray()
        candidates.forEach { source ->
            val logicalFilename = logicalLegacyFilename(source.name)
            val expectedCanonical = logicalFilename?.let(canonicalHashes::get)
            val canonical = logicalFilename?.let { File(currentPdfs(), it) }
            val canonicalVerified = expectedCanonical != null && canonical?.isFile == true &&
                sha256(canonical) == expectedCanonical
            val relative = runCatching { source.relativeTo(legacyRoot).path }.getOrDefault(source.name)
            val destination = File(batch, relative)
            val observedSha = runCatching { sha256(source) }.getOrNull() ?: return@forEach
            destination.parentFile?.mkdirs()
            if (!moveAndVerify(source, destination, observedSha)) return@forEach
            entries.add(JsonObject().apply {
                addProperty("original_path", source.absolutePath)
                addProperty("quarantine_path", destination.absolutePath)
                addProperty("observed_sha256", observedSha)
                logicalFilename?.let { addProperty("logical_filename", it) }
                expectedCanonical?.let { addProperty("canonical_sha256", it) }
                addProperty("canonical_verified", canonicalVerified)
                addProperty("quarantined_at_epoch_millis", nowEpochMillis)
                if (canonicalVerified) {
                    addProperty("eligible_prune_at_epoch_millis", nowEpochMillis + QUARANTINE_RETENTION_MILLIS)
                }
            })
        }
        if (entries.size() > 0) {
            writeJson(
                File(batch, MANIFEST_FILENAME),
                JsonObject().apply {
                    addProperty("schema_version", 1)
                    add("entries", entries)
                },
            )
        }
    }

    private fun pruneVerifiedQuarantine(nowEpochMillis: Long) {
        quarantineRoot().listFiles().orEmpty().filter(File::isDirectory).forEach { batch ->
            val manifestFile = File(batch, MANIFEST_FILENAME)
            val manifest = runCatching { JsonParser.parseString(manifestFile.readText()).asJsonObject }.getOrNull()
                ?: return@forEach
            val entries = manifest.getAsJsonArray("entries") ?: return@forEach
            var changed = false
            entries.forEach { value ->
                val entry = value.asJsonObject
                if (entry.get("canonical_verified")?.asBoolean != true || entry.has("pruned_at_epoch_millis")) {
                    return@forEach
                }
                val eligibleAt = entry.get("eligible_prune_at_epoch_millis")?.asLong ?: Long.MAX_VALUE
                if (nowEpochMillis < eligibleAt) return@forEach
                val quarantined = entry.get("quarantine_path")?.asString?.let(::File) ?: return@forEach
                if (!isInside(quarantined, quarantineRoot())) return@forEach
                if (!quarantined.exists() || quarantined.delete()) {
                    entry.addProperty("pruned_at_epoch_millis", nowEpochMillis)
                    changed = true
                }
            }
            if (changed) writeJson(manifestFile, manifest)
        }
    }

    private fun logicalLegacyFilename(filename: String): String? {
        val withoutConflict = filename.replace(
            Regex("\\.sync-conflict-[^.]+(?=\\.pdf$)", RegexOption.IGNORE_CASE),
            "",
        )
        val withoutVersion = withoutConflict.replace(
            Regex("~\\d{8}-\\d{6}(?=\\.pdf$)", RegexOption.IGNORE_CASE),
            "",
        )
        return withoutVersion.takeIf { it.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*\\.pdf$", RegexOption.IGNORE_CASE)) }
    }

    private fun moveAndVerify(source: File, destination: File, expectedSha256: String): Boolean {
        return try {
            try {
                Os.rename(source.absolutePath, destination.absolutePath)
            } catch (_: Throwable) {
                FileOutputStream(destination).use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                    output.fd.sync()
                }
                if (sha256(destination) != expectedSha256 || !source.delete()) {
                    destination.delete()
                    return false
                }
            }
            if (sha256(destination) != expectedSha256) {
                if (!source.exists()) runCatching { Os.rename(destination.absolutePath, source.absolutePath) }
                false
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun writeJson(destination: File, json: JsonObject) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write((Gson().toJson(json) + "\n").toByteArray())
                output.fd.sync()
            }
            check(temporary.renameTo(destination)) { "Could not activate quarantine manifest" }
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isInside(file: File, directory: File): Boolean = runCatching {
        file.canonicalPath.startsWith(directory.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun quarantineRoot(): File = File(root(), "legacy-quarantine")

    private fun copyMissing(source: File, destination: File) {
        if (!source.isDirectory) return
        source.walkTopDown().filter(File::isFile).forEach { legacy ->
            val relative = legacy.relativeTo(source)
            val target = File(destination, relative.path)
            if (target.isFile) return@forEach
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}.legacy.tmp")
            FileOutputStream(temporary).use { output ->
                legacy.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
            check(temporary.renameTo(target) || target.isFile) {
                "Could not migrate ${legacy.path} into the app's private cache"
            }
            temporary.delete()
        }
    }

    private const val MANIFEST_FILENAME = "manifest.json"
    private const val QUARANTINE_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L
}

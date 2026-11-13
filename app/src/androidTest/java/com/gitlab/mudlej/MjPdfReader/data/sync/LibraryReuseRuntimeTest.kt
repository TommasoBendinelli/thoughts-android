// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LibraryReuseRuntimeTest {
    @Test fun immutablePdfReuseAndPreciseCacheInvalidationOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = File(context.cacheDir, "sync-reuse-runtime").apply { deleteRecursively(); mkdirs() }
        try {
            val previous = File(parent, "previous").apply { mkdirs() }
            val staging = File(parent, "staging").apply { mkdirs() }
            val pdf = File(previous, "unchanged.pdf").apply { writeText("original"); setReadOnly() }
            val inode = Os.stat(pdf.absolutePath).st_ino
            moveReusedLibraryFiles(previous, staging, listOf(pdf.name))
            val reused = File(staging, pdf.name)
            assertEquals(inode, Os.stat(reused.absolutePath).st_ino)
            assertEquals("original", reused.readText())
            val cache = FileVerificationCache()
            val original = cache.sha256(reused)
            val modified = reused.lastModified()
            assertTrue(reused.setWritable(true))
            reused.writeText("modified") // Same length, and even restore the original mtime.
            reused.setLastModified(modified)
            assertNotEquals(original, cache.sha256(reused))
            assertNull(cache.read(reused, original, 100))
        } finally { parent.deleteRecursively() }
    }

    /** Opt-in production verification: rebuild existing text, then run the normal global sync. */
    @Test fun rebuildAndSyncExistingDocumentWhenRequested() {
        val target = InstrumentationRegistry.getArguments().getString("verifySyncTarget")
        org.junit.Assume.assumeTrue(!target.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = ThoughtsLocalStore.currentPdfs()
        val before = root.listFiles().orEmpty().filter { it.extension == "pdf" }.associate {
            it.name to (Os.stat(it.absolutePath).st_ino to verifiedFileCache.sha256(it))
        }
        com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue().enqueuePdf(File(root, target!!))
        val coordinator = SyncCommandCoordinator(context)
        val started = coordinator.start(null, ExplicitSyncOrigin.BACKGROUND_COMMAND)
        assertTrue(started.toJson(), started.accepted)
        val id = requireNotNull(started.operationId)
        val deadline = android.os.SystemClock.elapsedRealtime() + 180_000
        var result = coordinator.status(id)
        while (!result.terminal && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(500)
            result = coordinator.status(id)
        }
        val record = SyncOperationStore(context).operationWithEvents(id)
        File(context.cacheDir, "sync-optimization-verification.json").writeText(com.google.gson.Gson().toJson(record))
        assertEquals(result.toJson(), "succeeded", result.state)
        var reused = 0
        before.forEach { (name, entry) ->
            val file = File(root, name)
            if (name != target && file.isFile && verifiedFileCache.sha256(file) == entry.second) {
                assertEquals("Unchanged PDF was copied: $name", entry.first, Os.stat(file.absolutePath).st_ino)
                reused++
            }
        }
        val offline = File(context.filesDir, "source-editor/offline")
        val active = com.google.gson.JsonParser.parseString(File(offline, "active-index.json").readText()).asJsonObject
        val manifest = com.google.gson.JsonParser.parseString(File(offline, "objects/" + active["bundle_id"].asString).readText()).asJsonObject
        val pdf = manifest.getAsJsonArray("pdfs").single { it.asJsonObject["filename"].asString == target }.asJsonObject
        assertEquals("Sources must match the activated PDF", verifiedFileCache.sha256(File(root, target)), pdf["pdf_sha256"].asString)
        File(context.cacheDir, "sync-optimization-verification-summary.json").writeText(
            "{\"operation_id\":\"$id\",\"unchanged_pdfs_reused\":$reused,\"matching_sources\":true}"
        )
    }
}

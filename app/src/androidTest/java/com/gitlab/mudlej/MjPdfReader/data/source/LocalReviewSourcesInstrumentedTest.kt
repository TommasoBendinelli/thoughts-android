// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalReviewSourcesInstrumentedTest {
    @Test fun localCommitRecoveryAndLaterEditProtection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val source = OfflineSourceBundleStore(context)
        val local = LocalReviewSources(context)
        val transport = File(context.filesDir, "test-review-transport")
        val path = "docs-test/tex/source/local-review-${UUID.randomUUID()}.tex"
        val before = "Before.\n\nA complete answer.\n\nAfter.\n"
        assertTrue(source.writeWorkingContents(path, before))
        assertTrue(source.sourceWorkingFile(path)!!.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        val id = UUID.randomUUID().toString()
        val edit = ReviewSourceMarkup.create(path, before, "A complete answer.", id)
        val record = JsonObject().apply { addProperty("card_id", id) }
        val destination = File(transport, "cards/$id.json")
        fun stored() = com.gitlab.mudlej.MjPdfReader.data.review.ReviewDatabase(context).records()
            .firstOrNull { it.path == destination.path }?.let { JsonParser.parseString(it.payload).asJsonObject }
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            local.commit(edit, record, destination)
            assertEquals(edit.after, source.sourceWorkingFile(path)!!.readText())
            assertEquals(record, stored())
            local.requireRecoveryComplete()
            val journal = File(context.filesDir, "review-source-transactions/$id.json")
            val change = record.getAsJsonObject("source_change")
            fun interrupt(contents: String) {
                assertTrue(source.writeWorkingContents(path, contents))
                com.gitlab.mudlej.MjPdfReader.data.AppDatabase.getInstance(context).openHelper.writableDatabase
                    .execSQL("DELETE FROM ReviewRecord WHERE path=?", arrayOf(destination.path))
                journal.parentFile!!.mkdirs()
                journal.writeText(JsonObject().apply {
                    addProperty("path", path); add("before_sha256", change.get("before_sha256"))
                    add("after_sha256", change.get("after_sha256")); addProperty("destination", destination.absolutePath)
                    add("record", record)
                }.toString())
            }
            for (interruptedSource in listOf(before, edit.after)) {
                interrupt(interruptedSource)
                local.recover(transport)
                local.requireRecoveryComplete()
                assertEquals(edit.after, source.sourceWorkingFile(path)!!.readText())
                assertEquals(record, stored())
            }
            interrupt("A newer human edit.\n")
            local.recover(transport)
            assertNull(stored())
            assertFalse(destination.exists())
            assertTrue(journal.exists())
            assertEquals("A newer human edit.\n", source.sourceWorkingFile(path)!!.readText())
            assertTrue(runCatching { local.requireRecoveryComplete() }.isFailure)
            journal.delete()
            println("LOCAL_REVIEW_RECOVERY elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}")
        } finally {
            source.sourceWorkingFile(path)?.delete()
            transport.deleteRecursively()
            File(context.filesDir, "review-source-transactions/$id.json").delete()
        }
    }
}

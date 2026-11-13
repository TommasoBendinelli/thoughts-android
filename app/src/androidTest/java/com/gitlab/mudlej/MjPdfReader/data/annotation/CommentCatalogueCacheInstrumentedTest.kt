// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsProjectionEntity
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.google.gson.JsonParser
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CommentCatalogueCacheInstrumentedTest {
    @Test fun localAndDownloadedChangesRefreshCacheWithoutBlockingRendering() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = "test-${TestRunIdentity.id}-catalogue-cache"
        val filename = "$id.pdf"
        val stem = filename.removeSuffix(".pdf")
        val hash = com.gitlab.mudlej.MjPdfReader.testing.registerTestPdf(context, filename)
        val timestamp = Instant.now().toString()
        val store = LogicalCommentStore(context)
        val catalogue = CommentCatalogue(context = context)
        val updates = Channel<CommentCatalogueSnapshot>(Channel.UNLIMITED)
        val observer = launch {
            catalogue.changes().collect { updates.send(catalogue.loadSnapshot()) }
        }
        suspend fun awaitSnapshot(predicate: (CommentCatalogueSnapshot) -> Boolean): CommentCatalogueSnapshot =
            withTimeout(15_000) {
                var value = updates.receive()
                while (!predicate(value)) value = updates.receive()
                value
            }
        try {
            awaitSnapshot { it.metadataFor(stem).liveComments == 0 }
            store.edit(filename, hash, listOf(AnnotationEdit.AddPdfComment(
                0, id, RectF(40f, 100f, 60f, 80f), "Local text", "Tester", timestamp, timestamp,
            )))
            awaitSnapshot { it.metadataFor(stem).searchableText.contains("Local text") }
            instrumentation.runOnMainSync {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                val started = SystemClock.elapsedRealtime()
                repeat(200) {
                    // A newer committed database generation may replace the snapshot asynchronously.
                    assertTrue(catalogue.snapshot().metadataFor(stem).searchableText.contains("Local text"))
                    assertEquals(1, catalogue.metadataFor(stem).liveComments)
                }
                assertTrue("Cached UI reads should not rebuild the catalogue", SystemClock.elapsedRealtime() - started < 500)
            }
            val current = store.snapshot(filename).logicalComments.single { it.id == id }
            val payload = JsonParser.parseString(current.payloadJson).asJsonObject
            val dao = AppDatabase.getInstance(context).thoughtsSyncDao()
            suspend fun download(revision: Long, deleted: Boolean) {
                payload.addProperty("revision", revision)
                payload.addProperty("deleted", deleted)
                payload.addProperty("contents", "Downloaded text")
                dao.putProjection(ThoughtsProjectionEntity(
                    "logical_comment", "comments", id, revision, deleted, payload.toString(), revision,
                ))
            }
            val revision = payload.get("revision").asLong
            download(revision + 1, false)
            awaitSnapshot { it.metadataFor(stem).searchableText.contains("Downloaded text") }
            download(revision + 2, true)
            awaitSnapshot { it.metadataFor(stem).liveComments == 0 }
            assertEquals(0, catalogue.metadataFor(stem).liveComments)
        } finally {
            observer.cancel()
            updates.close()
        }
    }
}

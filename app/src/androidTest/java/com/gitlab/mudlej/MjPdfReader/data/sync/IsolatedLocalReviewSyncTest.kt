// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.source.LocalReviewSources
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourceMarkup
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.tbend.thoughts.sdk.SourceApplyRequest
import org.tbend.thoughts.sdk.SyncClient
import java.io.File
import java.time.Instant
import java.util.UUID

class IsolatedLocalReviewSyncTest {
    @Test fun pairedCreationEditBeforeSyncAndDeletionReachServerTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = checkNotNull(ThoughtsServerSecretStore(context).config())
        check(config.baseUrl == BuildConfig.TEST_SERVER_URL && context.packageName.endsWith(".sandbox"))
        val client = SyncClient(config.baseUrl, config.token)
        assertTrue("client_review_sources_v1" in client.ready().capabilities)
        val id = UUID.randomUUID().toString()
        val path = "docs-test/tex/source/review-${TestRunIdentity.id}.tex"
        val before = "Before.\n\nA complete answer.\n\nAfter.\n"
        val empty = client.uploadObject(byteArrayOf(), "text/plain").sha256
        val original = client.uploadObject(before.toByteArray(), "text/plain").sha256
        val seeded = client.applySource(SourceApplyRequest(UUID.randomUUID().toString(), client.deviceSequence().latestDeviceSequence + 1,
            path, 0, empty, empty, original, Instant.now().toString()))
        assertEquals("applied", seeded.outcome)
        val sources = OfflineSourceBundleStore(context)
        assertTrue(sources.writeWorkingContents(path, before))
        val local = LocalReviewSources(context)
        val edit = ReviewSourceMarkup.create(path, before, "A complete answer.", id)
        val timestamp = Instant.now().toString()
        val record = JsonObject().apply {
            addProperty("schema_version", 3); addProperty("card_id", id); addProperty("status", "approved")
            addProperty("revision", 1); addProperty("origin_id", "isolated-test"); addProperty("created_at", timestamp); addProperty("updated_at", timestamp)
            add("primary", JsonObject().apply {
                addProperty("pdf_stem", "test-review"); addProperty("pdf_sha256", "a".repeat(64)); addProperty("page", 1)
                addProperty("page_visual_sha256", "b".repeat(64)); addProperty("question", "What is the answer?")
                add("rect", JsonArray().apply { add(.1); add(.1); add(.5); add(.4) }); add("source_anchor", edit.anchor)
            })
        }
        val cardFile = File(ReviewStore.defaultOutgoingCardsRoot(), "test/$id.json")
        local.commit(edit, record, cardFile)
        val store = ReviewStore(context)
        store.updateQuestion(id, "Updated before first Sync").getOrThrow()
        val repository = FedoraRepository(context)
        val dao = AppDatabase.getInstance(context).thoughtsSyncDao()
        val started = android.os.SystemClock.elapsedRealtime()
        repository.syncDeltas()
        assertTrue(dao.undelivered().isEmpty())
        val serverSource = client.sources().sources.single { it.path == path }
        assertEquals(edit.after, client.downloadObject(serverSource.objectSha256).toString(Charsets.UTF_8))
        assertEquals("Updated before first Sync", client.projections().projections.single { it.entityId == id }.payload
            .getAsJsonObject("primary").get("question").asString)
        val deletion = ReviewSourceMarkup.remove(path, edit.after, id, edit.anchor!!.getAsJsonArray("segments").single().asJsonObject, false)
        val eventId = UUID.randomUUID().toString()
        val event = JsonObject().apply {
            addProperty("schema_version", 3); addProperty("event_id", eventId); addProperty("card_id", id)
            addProperty("rating", "delete"); addProperty("marker_removal", "complete"); addProperty("revision", 1)
            addProperty("origin_id", "isolated-test"); addProperty("reviewed_at", Instant.now().toString())
        }
        local.commit(deletion, event, File(ReviewStore.defaultOutgoingEventsRoot(), "test/$eventId.json"))
        repository.syncDeltas()
        assertTrue(dao.undelivered().isEmpty())
        val finalSource = client.sources().sources.single { it.path == path }
        assertEquals(before, client.downloadObject(finalSource.objectSha256).toString(Charsets.UTF_8))
        repository.syncDeltas()
        assertEquals(finalSource.revision, client.sources().sources.single { it.path == path }.revision)
        assertTrue(client.projections().projections.any { it.entityId == eventId })
        println("LOCAL_REVIEW_SYNC create_delete_repeat_ms=${android.os.SystemClock.elapsedRealtime() - started}")
        sources.sourceWorkingFile(path)?.delete()
        Unit
    }
}

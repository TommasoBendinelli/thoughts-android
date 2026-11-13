// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewDatabase
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.SourceAnnotationMarkup
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UnmergeableReviewLinkInstrumentedTest {
    @Test fun refusedNewCardKeepsTheCardUnlinkedAndLeavesTheSourceUnmarked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val id = UUID.randomUUID().toString()
        val path = "docs-test/tex/source/${TestRunIdentity.id}-$id.tex"
        val unmarked = "Intro.\nAnswer line.\nEnd.\n"
        val marked = "Intro.\n\\ReviewCardMaskStart{$id}{0}%\nAnswer line.\n\\ReviewCardMaskEnd{$id}{0}%\nEnd.\n"
        val sources = OfflineSourceBundleStore(context)
        assertTrue(sources.writeWorkingContents(path, marked))
        val segment = SourceAnnotationMarkup.scan(marked, false).getAsJsonObject("reviews").getAsJsonArray(id)[0]
            .asJsonObject.deepCopy().apply { addProperty("source_file", path) }
        val payload = JsonParser.parseString("""{"schema_version":4,"card_id":"$id","status":"approved","revision":1,"origin_id":"fixture","created_at":"2026-10-03T07:03:49Z","updated_at":"2026-10-03T07:03:49Z","source_authority":"client-v1","source_link":{"state":"linked"},"source_change":{"transaction_id":"$id","path":"$path","before_sha256":"${"a".repeat(64)}","after_sha256":"${"b".repeat(64)}"},"primary":{"pdf_stem":"fixture","pdf_sha256":"${"c".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"d".repeat(64)}","question":"Keep this card?","source_anchor":{"version":2,"segments":[$segment]}}}""").asJsonObject
        val store = ReviewStore(context)
        store.cards() // Initialize the durable review database before seeding the fixture.
        val database = ReviewDatabase(context)
        val recordPath = "fixture/outgoing/$id.json"
        database.transaction { database.put(recordPath, "cards", 2, payload) }
        val refused = ThoughtsOutboxEntity("op-$id", 1, "review_card", id, "cards", null, "2026-10-03T07:03:49Z",
            payload.toString(), 1, deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED, failureCode = "http_409",
            failureDetail = "Concurrent source edits conflict with the local card operation; both remain pending")
        try {
            assertTrue(refused.isUnmergeableReviewLink())
            assertTrue(removeUnsentReviewMarkers(sources, payload))
            assertEquals(unmarked, sources.sourceWorkingFile(path)!!.readText())
            // A second pass finds nothing left to remove.
            assertTrue(removeUnsentReviewMarkers(sources, payload))

            assertTrue(store.replaceRejected(refused, unlinkedReviewCardPayload(payload)))
            val pending = store.pendingPayloads("cards").orEmpty().single { it.get("card_id").asString == id }
            assertFalse(pending.has("source_change"))
            assertEquals("linking_failed", pending.getAsJsonObject("source_link").get("state").asString)
            assertEquals("Keep this card?", pending.getAsJsonObject("primary").get("question").asString)
            assertFalse(store.replaceRejected(refused, unlinkedReviewCardPayload(payload)))
        } finally {
            database.transaction { database.put(recordPath, "cards", 0, payload) }
            sources.sourceWorkingFile(path)?.delete()
        }
    }
}

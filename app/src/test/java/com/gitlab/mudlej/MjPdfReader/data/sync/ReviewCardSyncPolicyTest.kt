// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewOutgoingCardFileLock
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewCardSyncPolicyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `capture identity binds card revision and complete payload`() {
        val created = cardPayload(
            revision = 1,
            question = "Original",
            createdAt = "2026-08-30T10:00:00Z",
            updatedAt = "2026-08-30T10:00:00Z",
        )

        val first = checkNotNull(reviewCardCaptureMetadata(created))
        val retry = checkNotNull(reviewCardCaptureMetadata(created.deepCopy()))
        val changed = checkNotNull(
            reviewCardCaptureMetadata(
                created.deepCopy().apply {
                    getAsJsonObject("primary").addProperty("question", "Changed")
                },
            ),
        )

        assertEquals(first.operationId, retry.operationId)
        assertEquals(first.payloadSha256, retry.payloadSha256)
        assertEquals(null, first.baseRevision)
        assertNotEquals(first.operationId, changed.operationId)
        assertNotEquals(first.payloadSha256, changed.payloadSha256)
    }

    @Test
    fun `edited capture uses the immediately prior payload revision`() {
        val edited = cardPayload(
            revision = 9,
            question = "Edited",
            createdAt = "2026-08-29T10:00:00Z",
            updatedAt = "2026-08-30T10:00:00Z",
        )
        val legacyFirstRevisionEdit = cardPayload(
            revision = 1,
            question = "Edited legacy card",
            createdAt = "2026-08-29T10:00:00Z",
            updatedAt = "2026-08-30T10:00:00Z",
        )

        assertEquals(8L, reviewCardCaptureMetadata(edited)?.baseRevision)
        assertEquals(0L, reviewCardCaptureMetadata(legacyFirstRevisionEdit)?.baseRevision)
    }

    @Test
    fun `acknowledgement deletes only the exact payload it delivered`() {
        val root = temporary.newFolder("outgoing-review-cards")
        val cardId = "10000000-0000-4000-8000-000000000001"
        val file = File(root, "phone/$cardId.json").apply { parentFile?.mkdirs() }
        val oldPayload = cardPayload(
            cardId = cardId,
            revision = 4,
            question = "Older edit",
            createdAt = "2026-08-29T10:00:00Z",
            updatedAt = "2026-08-30T10:00:00Z",
        )
        val currentPayload = cardPayload(
            cardId = cardId,
            revision = 5,
            question = "Newer edit",
            createdAt = "2026-08-29T10:00:00Z",
            updatedAt = "2026-08-30T10:01:00Z",
        )
        file.writeText(currentPayload.toString())

        assertFalse(
            deleteAcknowledgedReviewCardPayload(root, cardId, oldPayload.toString()),
        )
        assertTrue(file.isFile)
        assertTrue(
            deleteAcknowledgedReviewCardPayload(root, cardId, currentPayload.toString()),
        )
        assertFalse(file.exists())
    }

    @Test
    fun `question publication and acknowledgement compare delete are serialized`() {
        val reviewRoot = temporary.newFolder("locked-review-root")
        val incomingRoot = temporary.newFolder("locked-review-incoming")
        val outgoingEvents = temporary.newFolder("locked-review-events")
        val outgoingCards = temporary.newFolder("locked-review-cards")
        val cardId = "10000000-0000-4000-8000-000000000002"
        val oldPayload = cardPayload(
            cardId = cardId,
            revision = 4,
            question = "Older edit",
            createdAt = "2026-08-29T10:00:00Z",
            updatedAt = "2026-08-30T10:00:00Z",
        )
        val outgoingFile = File(outgoingCards, "test-phone/$cardId.json").apply {
            parentFile?.mkdirs()
            writeText(oldPayload.toString())
        }
        val store = ReviewStore(
            context = null,
            root = reviewRoot,
            incomingRoot = incomingRoot,
            outgoingEventsRoot = outgoingEvents,
            outgoingCardsRoot = outgoingCards,
        )
        val acknowledgementStarted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val acknowledgement = ReviewOutgoingCardFileLock.withLock {
                val submitted = executor.submit<Boolean> {
                    acknowledgementStarted.countDown()
                    deleteAcknowledgedReviewCardPayload(
                        outgoingCards,
                        cardId,
                        oldPayload.toString(),
                    )
                }
                assertTrue(acknowledgementStarted.await(2, TimeUnit.SECONDS))

                val updated = store.updateQuestion(
                    cardId = cardId,
                    question = "Newer edit",
                    updatedAt = Instant.parse("2026-08-30T10:01:00Z"),
                ).getOrThrow()

                assertEquals(5, updated.revision)
                assertFalse(submitted.isDone)
                submitted
            }

            assertFalse(acknowledgement.get(2, TimeUnit.SECONDS))
            assertTrue(outgoingFile.isFile)
            val current = JsonParser.parseString(outgoingFile.readText()).asJsonObject
            assertEquals(5, current.get("revision").asInt)
            assertEquals("Newer edit", current.getAsJsonObject("primary").get("question").asString)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `lost response reconciliation retires every acknowledged review event shape`() {
        val outgoingEvents = temporary.newFolder("lost-response-review-events")
        val outgoingCards = temporary.newFolder("lost-response-review-cards")
        val cardId = "10000000-0000-4000-8000-000000000003"
        val ratingId = UUID.randomUUID().toString()
        val deleteId = UUID.randomUUID().toString()
        val retractionId = UUID.randomUUID().toString()
        val payloads = listOf(
            ratingId to """{"schema_version":3,"event_id":"$ratingId","card_id":"$cardId","rating":"good","reviewed_at":"2026-08-30T10:00:00Z","origin_id":"phone"}""",
            deleteId to """{"schema_version":3,"event_id":"$deleteId","card_id":"$cardId","rating":"delete","reviewed_at":"2026-08-30T10:01:00Z","origin_id":"phone"}""",
            retractionId to """{"schema_version":3,"event_id":"$retractionId","card_id":"$cardId","retracted_event_id":"$deleteId","reviewed_at":"2026-08-30T10:02:00Z","origin_id":"phone"}""",
        )
        val files = payloads.map { (eventId, payload) ->
            val file = File(outgoingEvents, "phone/$eventId.json").apply {
                parentFile?.mkdirs()
                writeText(payload)
            }
            val operation = ThoughtsOutboxEntity(
                operationId = UUID.randomUUID().toString(),
                deviceSequence = 1,
                entityType = "review_event",
                entityId = eventId,
                collection = "events",
                baseRevision = null,
                clientTimestamp = "2026-08-30T10:00:00Z",
                payloadJson = payload,
                createdAtEpochMillis = 1,
            )
            assertTrue(
                cleanupAcknowledgedReviewPayload(
                    operation,
                    outgoingEventsRoot = outgoingEvents,
                    outgoingCardsRoot = outgoingCards,
                ),
            )
            file
        }

        assertTrue(files.none(File::exists))
    }

    private fun cardPayload(
        cardId: String = "10000000-0000-4000-8000-000000000001",
        revision: Int,
        question: String,
        createdAt: String,
        updatedAt: String,
    ) = JsonParser.parseString(
        """{"schema_version":3,"card_type":"visual_region","card_id":"$cardId","status":"approved","revision":$revision,"origin_id":"phone","created_at":"$createdAt","updated_at":"$updatedAt","primary":{"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"$question"}}""",
    ).asJsonObject
}

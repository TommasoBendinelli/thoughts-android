// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewLinkRecoveryTest {
    // Shape observed on a phone: a manual link nested inside another card's markers.
    private val payload = """{"card_id":"c91c6362-55fe-4f5d-8c91-ca7a549cecff","revision":4,"source_link":{"state":"linked"},"source_change":{"transaction_id":"c91c6362-55fe-4f5d-8c91-ca7a549cecff","path":"docs-concepts/tex/source/distillation.tex","before_sha256":"${"a".repeat(64)}","after_sha256":"${"b".repeat(64)}"}}"""
    private fun operation() = ThoughtsOutboxEntity("op", 7051, "review_card", "c91c6362-55fe-4f5d-8c91-ca7a549cecff",
        "cards", 3, "2026-09-29T08:12:35.490Z", payload, 1, deliveryState = "blocked",
        failureCode = "http_409", failureDetail = "Another review overlaps this passage. Edit the source instead")

    @Test fun overlappingLinkRejectionIsRetired() {
        assertTrue(operation().isRejectedOverlappingReviewLink())
        assertTrue(operation().copy(failureDetail = "This passage contains other review markers").isRejectedOverlappingReviewLink())
    }

    @Test fun otherFailuresStayBlocked() {
        assertFalse(operation().copy(deliveryState = "pending").isRejectedOverlappingReviewLink())
        assertFalse(operation().copy(failureCode = "http_500").isRejectedOverlappingReviewLink())
        assertFalse(operation().copy(failureDetail = "Upload the verified review source objects first").isRejectedOverlappingReviewLink())
        assertFalse(operation().copy(entityType = "review_event").isRejectedOverlappingReviewLink())
        assertFalse(operation().copy(payloadJson = """{"card_id":"x","revision":4}""").isRejectedOverlappingReviewLink())
        assertFalse(operation().copy(payloadJson = "invalid").isRejectedOverlappingReviewLink())
    }

    @Test fun changedPassageOfANewCardIsRetried() {
        val changed = operation().copy(failureDetail = "The review passage or its markers changed. Sync and reopen the card")
        assertTrue(changed.isRetryableChangedReviewPassage())
        assertFalse(changed.isRejectedOverlappingReviewLink())
        assertFalse(operation().isRetryableChangedReviewPassage())
        assertFalse(changed.copy(deliveryState = "pending").isRetryableChangedReviewPassage())
        assertFalse(changed.copy(entityType = "review_event").isRetryableChangedReviewPassage())
        assertFalse(changed.copy(payloadJson = """{"card_id":"x","revision":4}""").isRetryableChangedReviewPassage())
    }

    @Test fun unmergeableNewCardLinksAreUnlinked() {
        val conflict = operation().copy(failureDetail = "Concurrent source edits conflict with the local card operation; both remain pending")
        assertTrue(conflict.isUnmergeableReviewLink())
        assertTrue(operation().copy(failureDetail = "The review passage or its markers changed. Sync and reopen the card").isUnmergeableReviewLink())
        assertFalse(operation().isUnmergeableReviewLink())
        assertFalse(conflict.copy(deliveryState = "pending").isUnmergeableReviewLink())
        assertFalse(conflict.copy(failureDetail = "Upload the verified review source objects first").isUnmergeableReviewLink())
        assertFalse(conflict.copy(payloadJson = """{"card_id":"x","revision":1}""").isUnmergeableReviewLink())
    }

    @Test fun unlinkedPayloadDropsOnlyTheSourceLink() {
        val payload = com.google.gson.JsonParser.parseString(
            """{"card_id":"c","revision":1,"question":"Q?","source_authority":"client-v1","source_link":{"state":"linked"},""" +
                """"source_change":{"path":"p.tex"},"primary":{"page":2,"source_anchor":{"version":2}}}""").asJsonObject
        val unlinked = unlinkedReviewCardPayload(payload)
        assertFalse(unlinked.has("source_change"))
        assertFalse(unlinked.getAsJsonObject("primary").has("source_anchor"))
        assertTrue(unlinked.getAsJsonObject("source_link").get("state").asString == "linking_failed")
        assertTrue(unlinked.get("question").asString == "Q?" && unlinked.getAsJsonObject("primary").get("page").asInt == 2)
        // The refused original is left untouched for the superseded outbox record.
        assertTrue(payload.has("source_change"))
    }

    @Test fun unlinkedPayloadKeepsFedorasReason() {
        val payload = com.google.gson.JsonParser.parseString(payload).asJsonObject
        val unlinked = unlinkedReviewCardPayload(payload, "Concurrent source edits conflict with the local card operation; both remain pending")
        assertTrue(unlinked.getAsJsonObject("source_link").get("reason").asString.startsWith("Concurrent source edits"))
        assertFalse(unlinkedReviewCardPayload(payload).getAsJsonObject("source_link").has("reason"))
    }

    @Test fun movingACardsExistingMarkersIsToldApartFromCreation() {
        val objects = java.nio.file.Files.createTempDirectory("review-objects").toFile()
        try {
            val cardId = "c91c6362-55fe-4f5d-8c91-ca7a549cecff"
            java.io.File(objects, "a".repeat(64)).writeText("Text \\ReviewCardMaskStart{$cardId}{0}answer\\ReviewCardMaskEnd{$cardId}{0}\n")
            val moving = com.google.gson.JsonParser.parseString(payload).asJsonObject
            assertTrue(movesReviewMarkers(moving, objects))
            java.io.File(objects, "a".repeat(64)).writeText("Text answer\n")
            assertFalse(movesReviewMarkers(moving, objects))
            java.io.File(objects, "a".repeat(64)).delete()
            assertFalse(movesReviewMarkers(moving, objects))
            assertFalse(movesReviewMarkers(com.google.gson.JsonParser.parseString("""{"card_id":"$cardId"}""").asJsonObject, objects))
        } finally { objects.deleteRecursively() }
    }
}

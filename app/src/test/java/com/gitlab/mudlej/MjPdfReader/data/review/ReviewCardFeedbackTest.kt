// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Polarity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Spec conformance C1 (codec), C3 (merge table of §5) and C7 (create/edit/delete/undo). */
class ReviewCardFeedbackTest {
    private lateinit var directory: File
    private val previousFile = ReviewCardFeedback.file
    private val cardId = "0072b138-d3fe-416a-83e7-1407058f0e2b"
    private val feedbackId = "5b0f6c1e-2a47-4d1b-9c35-7e0a4f2b9d13"
    private val documentId = "c1e62ecc-2dfb-4259-97cc-e319b1186848"
    private val pdfSha = "f4c8827551095a63ce58c6b1e2c5946014b7b3f62d0f96a780a2931f601a561e"

    /** The §2 example record, with unknown fields at the top level, in the anchor and in a region. */
    private val example = """{"schema_version":1,"feedback_id":"$feedbackId","card_id":"$cardId","polarity":"negative",""" +
        """"comment":"This sign is wrong: the KL term is subtracted.","anchor":{"version":1,"quote":{"exact":"the KL term keeps """ +
        """the policy close","prefix":"In practice, ","suffix":" to the reference model."},"pdf":{"document_id":"$documentId",""" +
        """"pdf_sha256":"$pdfSha","regions":[{"page":7,"rect":[0.1765,0.4116,0.8256,0.4290],"glyphs":"future"},""" +
        """{"page":7,"rect":[0.1765,0.4290,0.3537,0.4460]}]},"segment_id":"0","segment_content_sha256":""" +
        """"3775b1627bce1bbc61bdd810a212daab7db3fbfc7932002aacc578a9954d87cd","anchor_extra":{"k":[1,2.50]}},"revision":1,""" +
        """"created_at":"2026-10-20T10:14:03.512Z","updated_at":"2026-10-20T10:14:03.512Z","created_origin_id":"android-pixel",""" +
        """"origin_id":"android-pixel","deleted":false,"study_label":"S3"}"""

    @Before fun setUp() {
        directory = Files.createTempDirectory("card-feedback").toFile()
        ReviewCardFeedback.file = { File(directory, "card-feedback.json") }
    }

    @After fun tearDown() {
        ReviewCardFeedback.file = previousFile
        directory.deleteRecursively()
    }

    private fun parse(json: String) = ReviewCardFeedback.parse(JsonParser.parseString(json).asJsonObject)!!

    private fun record(
        revision: Int,
        updatedAt: String,
        origin: String = "mac-okular",
        deleted: Boolean = false,
        comment: String = "",
        polarity: String = "positive",
    ): ReviewCardFeedback.Feedback = parse(example).payload.deepCopy().apply {
        addProperty("revision", revision)
        addProperty("updated_at", updatedAt)
        addProperty("origin_id", origin)
        addProperty("deleted", deleted)
        addProperty("comment", comment)
        addProperty("polarity", polarity)
        if (deleted) addProperty("deleted_at", updatedAt)
    }.let { ReviewCardFeedback.parse(it)!! }

    private fun anchor() = ReviewCardFeedback.NewAnchor(
        "the KL term keeps the policy close", "In practice, ", " to the reference model.", documentId, pdfSha,
        listOf(ReviewCardFeedback.Region(7, 0.1765, 0.4116, 0.8256, 0.4290)),
    )

    // C1 -------------------------------------------------------------------------------------

    @Test fun exampleRecordRoundTripsWithUnknownFields() {
        val feedback = parse(example)
        assertEquals(example, feedback.payload.toString())
        assertEquals(Polarity.NEGATIVE, feedback.polarity)
        assertEquals("the KL term keeps the policy close", feedback.exact)
        assertEquals(listOf(ReviewCardFeedback.Region(7, 0.1765, 0.4116, 0.8256, 0.4290),
            ReviewCardFeedback.Region(7, 0.1765, 0.4290, 0.3537, 0.4460)), feedback.regions)
        assertEquals("0", feedback.segmentId)
        // Stored and read back, then rewritten by an edit: the unknown fields survive.
        ReviewCardFeedback.installServer(listOf(feedback.payload))
        assertEquals(example, ReviewCardFeedback.find(feedbackId)!!.payload.toString())
        val edited = ReviewCardFeedback.edit(feedbackId, Polarity.POSITIVE, "ok", "android-test", Instant.parse("2026-10-20T11:00:00Z"))!!
        val payload = edited.payload
        assertEquals("S3", payload.get("study_label").asString)
        assertEquals("future", payload.getAsJsonObject("anchor").getAsJsonObject("pdf").getAsJsonArray("regions")[0]
            .asJsonObject.get("glyphs").asString)
        assertEquals("""{"k":[1,2.50]}""", payload.getAsJsonObject("anchor").get("anchor_extra").toString())
    }

    @Test fun recordsBreakingTheRulesAreRefused() {
        fun mutated(edit: JsonObject.() -> Unit) = ReviewCardFeedback.parse(JsonParser.parseString(example).asJsonObject.apply(edit))
        assertNull(mutated { addProperty("polarity", "neutral") })
        assertNull(mutated { addProperty("feedback_id", "not-a-uuid") })
        assertNull(mutated { addProperty("feedback_id", feedbackId.uppercase()) })
        assertNull(mutated { getAsJsonObject("anchor").getAsJsonObject("quote").addProperty("exact", "  ") })
        assertNull(mutated { remove("card_id") })
    }

    // C3: the §5 table ----------------------------------------------------------------------

    @Test fun higherRevisionWinsAndEqualRevisionsGoToTheLaterUpdate() {
        val a = record(2, "2026-10-20T11:00:00.000Z", "mac-okular", comment = "A")
        val b = record(2, "2026-10-20T11:30:00.000Z", "android", polarity = "negative")
        // Either arrival order gives B's whole record: fields are not merged.
        assertEquals(b, ReviewCardFeedback.merge(ReviewCardFeedback.merge(null, a), b))
        assertEquals(b, ReviewCardFeedback.merge(ReviewCardFeedback.merge(null, b), a))
        val three = record(3, "2026-10-20T09:00:00.000Z")
        assertTrue(ReviewCardFeedback.wins(three, b))
        assertFalse(ReviewCardFeedback.wins(record(1, "2026-10-21T00:00:00.000Z"), b)) // a stale revision is superseded
        // Same revision and time: the origin id decides, as on the server.
        assertTrue(ReviewCardFeedback.wins(record(2, "2026-10-20T11:30:00.000Z", "zz"), b))
    }

    @Test fun deleteIsStickyAgainstAnEditOfTheSameRevision() {
        val deleted = record(2, "2026-10-20T11:00:00.000Z", deleted = true)
        val laterEdit = record(2, "2026-10-20T12:00:00.000Z", comment = "offline edit")
        assertEquals(deleted, ReviewCardFeedback.merge(deleted, laterEdit))
        // Undo writes the tombstone's revision + 1 and restores.
        val undo = record(3, "2026-10-20T11:00:05.000Z")
        assertEquals(undo, ReviewCardFeedback.merge(deleted, undo))
        // Two offline edits (rev 3) beat a delete of rev 2: a higher revision always applies.
        assertEquals(record(3, "2026-10-20T10:00:00.000Z", comment = "twice"),
            ReviewCardFeedback.merge(deleted, record(3, "2026-10-20T10:00:00.000Z", comment = "twice")))
        // A later delete with a higher revision applies.
        val deletedAgain = record(4, "2026-10-20T13:00:00.000Z", deleted = true)
        assertEquals(deletedAgain, ReviewCardFeedback.merge(undo, deletedAgain))
    }

    @Test fun aDeleteBeatsAnEditOfTheSameRevisionWhateverTheTimesAndArrivalOrder() {
        val deleted = record(2, "2026-10-20T11:00:00.000Z", origin = "mac-okular", deleted = true)
        for (time in listOf("2026-10-20T10:00:00.000Z", "2026-10-20T11:00:00.000Z", "2026-10-20T12:00:00.000Z")) {
            for (origin in listOf("aaa", "zzz")) {
                val edit = record(2, time, origin = origin, comment = "offline edit")
                assertEquals("$time $origin edit then delete", deleted,
                    ReviewCardFeedback.merge(ReviewCardFeedback.merge(null, edit), deleted))
                assertEquals("$time $origin delete then edit", deleted,
                    ReviewCardFeedback.merge(ReviewCardFeedback.merge(null, deleted), edit))
                assertFalse(ReviewCardFeedback.wins(edit, deleted))
                assertTrue(ReviewCardFeedback.wins(deleted, edit))
            }
        }
        // Undo (the tombstone's revision + 1) revives it.
        val undo = record(3, "2026-10-20T11:00:05.000Z")
        assertEquals(undo, ReviewCardFeedback.merge(deleted, undo))
        // Two deletes of one revision still go to the later one.
        val laterDelete = record(2, "2026-10-20T11:30:00.000Z", deleted = true)
        assertEquals(laterDelete, ReviewCardFeedback.merge(deleted, laterDelete))
        assertEquals(laterDelete, ReviewCardFeedback.merge(laterDelete, deleted))
    }

    @Test fun aLocalEditOvertakenByTheServersDeleteIsShownDeleted() {
        // The phone's edit (rev 2) reached the server first and was accepted; a delete of rev 2
        // from another device then replaced it, so the server no longer lists the record.
        ReviewCardFeedback.installServer(listOf(parse(example).payload))
        ReviewCardFeedback.edit(feedbackId, Polarity.POSITIVE, "mine", "android", Instant.parse("2026-10-20T12:00:00Z"))
        val accepted = ReviewCardFeedback.pendingUploads().single().operationId
        ReviewCardFeedback.installServer(emptyList(), accepted = setOf(accepted))
        assertNull(ReviewCardFeedback.find(feedbackId))
        assertEquals(emptyList<ReviewCardFeedback.Feedback>(), ReviewCardFeedback.forCard(cardId))
        // The same edit arriving after the delete is superseded: also gone.
        ReviewCardFeedback.installServer(listOf(parse(example).payload))
        ReviewCardFeedback.edit(feedbackId, Polarity.POSITIVE, "mine", "android", Instant.parse("2026-10-20T12:00:00Z"))
        val superseded = ReviewCardFeedback.pendingUploads().single().operationId
        ReviewCardFeedback.installServer(emptyList(), superseded = setOf(superseded))
        assertNull(ReviewCardFeedback.find(feedbackId))
        // Another device's Undo (rev 3) brings it back.
        val undo = record(3, "2026-10-20T12:05:00.000Z", origin = "mac-okular")
        ReviewCardFeedback.installServer(listOf(undo.payload))
        assertEquals(listOf(undo), ReviewCardFeedback.forCard(cardId))
    }

    @Test fun mergeKeepsTheFieldsTheFirstWriteFixed() {
        val stored = parse(example)
        val incoming = record(2, "2026-10-20T11:00:00.000Z").payload.deepCopy().apply {
            addProperty("card_id", "another-card")
            addProperty("created_at", "2030-01-01T00:00:00.000Z")
            addProperty("created_origin_id", "intruder")
            getAsJsonObject("anchor").getAsJsonObject("quote").addProperty("exact", "something else")
        }.let { ReviewCardFeedback.parse(it)!! }
        val merged = ReviewCardFeedback.merge(stored, incoming)
        assertEquals(2L, merged.revision)
        assertEquals(cardId, merged.cardId)
        assertEquals("2026-10-20T10:14:03.512Z", merged.createdAt)
        assertEquals("android-pixel", merged.createdOriginId)
        assertEquals(stored.payload.get("anchor"), merged.payload.get("anchor"))
    }

    @Test fun bothOfflineCreationsOnThePassageAreKept() {
        val first = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "", anchor(), "android")
        val second = ReviewCardFeedback.create(cardId, Polarity.NEGATIVE, "", anchor(), "android")
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(first.id, second.id).sorted(), ReviewCardFeedback.forCard(cardId).map { it.id }.sorted())
    }

    // C7 -------------------------------------------------------------------------------------

    @Test fun likeCreatesPositiveFeedbackWithAnEmptyComment() {
        val now = Instant.parse("2026-10-20T10:14:03.512345Z")
        val liked = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "", anchor(), "android-test", now)
        assertTrue(ReviewCardFeedback.validUuid4(liked.id))
        assertEquals(liked.id, liked.payload.get("feedback_id").asString)
        assertEquals("positive", liked.payload.get("polarity").asString)
        assertEquals("", liked.payload.get("comment").asString)
        assertEquals(1L, liked.revision)
        assertEquals("2026-10-20T10:14:03.512Z", liked.createdAt)
        assertEquals(liked.createdAt, liked.updatedAt)
        assertEquals("android-test", liked.createdOriginId)
        assertFalse(liked.payload.has("deleted_at"))
        assertFalse(liked.deleted)
        val anchor = liked.payload.getAsJsonObject("anchor")
        assertEquals(1, anchor.get("version").asInt)
        assertEquals(" to the reference model.", anchor.getAsJsonObject("quote").get("suffix").asString)
        assertEquals(1, ReviewCardFeedback.pendingUploads().size)
        assertNull(ReviewCardFeedback.pendingUploads().single().baseRevision)
    }

    @Test fun editIncrementsTheRevisionAndKeepsCreationOnlyWhenSomethingChanged() {
        val created = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "", anchor(), "android", Instant.parse("2026-10-20T10:00:00Z"))
        assertNull(ReviewCardFeedback.edit(created.id, Polarity.POSITIVE, "   ", "android", Instant.parse("2026-10-20T10:01:00Z")))
        val edited = ReviewCardFeedback.edit(created.id, Polarity.NEGATIVE, "  Too vague.\n", "mac", Instant.parse("2026-10-20T10:02:00Z"))!!
        assertEquals(2L, edited.revision)
        assertEquals("Too vague.", edited.comment)
        assertEquals(created.createdAt, edited.createdAt)
        assertEquals("2026-10-20T10:02:00.000Z", edited.updatedAt)
        assertEquals("android", edited.createdOriginId)
        assertEquals("mac", edited.originId)
        assertEquals(1L, ReviewCardFeedback.pendingUploads().single().baseRevision)
        // A clock that went back still writes a later updated_at.
        val back = ReviewCardFeedback.edit(created.id, Polarity.POSITIVE, "x", "android", Instant.parse("2026-10-19T00:00:00Z"))!!
        assertEquals("2026-10-20T10:02:00.001Z", back.updatedAt)
    }

    @Test fun deleteThenUndoGivesRevisionPlusTwoNotDeleted() {
        val created = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "kept", anchor(), "android", Instant.parse("2026-10-20T10:00:00Z"))
        val tombstone = ReviewCardFeedback.remove(created.id, "android", Instant.parse("2026-10-20T10:05:00Z"))!!
        assertTrue(tombstone.deleted)
        assertEquals(2L, tombstone.revision)
        assertEquals("2026-10-20T10:05:00.000Z", tombstone.payload.get("deleted_at").asString)
        assertEquals("kept", tombstone.comment) // the tombstone carries the full record
        assertEquals(emptyList<ReviewCardFeedback.Feedback>(), ReviewCardFeedback.forCard(cardId))
        val restored = ReviewCardFeedback.undo(tombstone, "android", Instant.parse("2026-10-20T10:05:02Z"))
        assertEquals(3L, restored.revision)
        assertFalse(restored.deleted)
        assertFalse(restored.payload.has("deleted_at"))
        assertEquals(listOf(restored), ReviewCardFeedback.forCard(cardId))
    }

    @Test fun undoWorksAfterSyncSettledTheTombstone() {
        val created = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "", anchor(), "android")
        val tombstone = ReviewCardFeedback.remove(created.id, "android")!!
        val operation = ReviewCardFeedback.pendingUploads().single().operationId
        ReviewCardFeedback.installServer(emptyList(), accepted = setOf(operation))
        assertNull(ReviewCardFeedback.find(created.id))
        val restored = ReviewCardFeedback.undo(tombstone, "android")
        assertEquals(tombstone.revision + 1, restored.revision)
        assertEquals(1, ReviewCardFeedback.pendingUploads().size)
    }

    // Change passage: a new record on the new passage, the old one deleted ----------------------

    private fun widerAnchor() = anchor().copy(
        exact = "the KL term keeps the policy close to the reference model",
        regions = listOf(ReviewCardFeedback.Region(7, 0.1765, 0.4116, 0.8256, 0.4290),
            ReviewCardFeedback.Region(7, 0.1765, 0.4290, 0.3537, 0.4460)),
    )

    @Test fun changingThePassageReplacesTheRecordInOneWrite() {
        val old = ReviewCardFeedback.create(cardId, Polarity.NEGATIVE, "Too vague.", anchor(), "android",
            Instant.parse("2026-11-09T10:00:00Z"))
        val stamp = ReviewVersionStamp("3".repeat(64), true)
        val replacement = ReviewCardFeedback.replacePassage(old.id, Polarity.POSITIVE, "  Clearer now.  ", widerAnchor(),
            "android", Instant.parse("2026-11-09T10:05:00Z"), version = stamp)
        val created = replacement.created
        // The new record: same card, the new quote and regions, the polarity and comment chosen in the editor.
        assertNotEquals(old.id, created.id)
        assertTrue(ReviewCardFeedback.validUuid4(created.id))
        assertEquals(cardId, created.cardId)
        assertEquals(widerAnchor().exact, created.exact)
        assertEquals(widerAnchor().regions, created.regions)
        assertEquals(Polarity.POSITIVE, created.polarity)
        assertEquals("Clearer now.", created.comment)
        assertEquals(1L, created.revision)
        assertEquals("2026-11-09T10:05:00.000Z", created.createdAt)
        assertEquals(stamp, ReviewVersionStamp.of(created.payload))
        // The old record is deleted with its anchor untouched (the anchor is immutable).
        val tombstone = replacement.tombstone
        assertEquals(old.id, tombstone.id)
        assertTrue(tombstone.deleted)
        assertEquals(2L, tombstone.revision)
        assertEquals(old.payload.get("anchor"), tombstone.payload.get("anchor"))
        assertEquals(listOf(created), ReviewCardFeedback.forCard(cardId))
        // Both writes are sent by the next Sync.
        assertEquals(setOf(old.id, created.id), ReviewCardFeedback.pendingUploads().map { it.entityId }.toSet())
    }

    @Test fun undoOfAPassageChangeBringsTheOldRecordBack() {
        val old = ReviewCardFeedback.create(cardId, Polarity.NEGATIVE, "Too vague.", anchor(), "android",
            Instant.parse("2026-11-09T10:00:00Z"))
        val replacement = ReviewCardFeedback.replacePassage(old.id, Polarity.NEGATIVE, "Too vague.", widerAnchor(),
            "android", Instant.parse("2026-11-09T10:05:00Z"))
        val restored = ReviewCardFeedback.undoReplacement(replacement, "android", Instant.parse("2026-11-09T10:05:03Z"))
        assertEquals(old.id, restored.id)
        assertFalse(restored.deleted)
        assertEquals(3L, restored.revision)
        assertEquals(old.payload.get("anchor"), restored.payload.get("anchor"))
        assertEquals(listOf(old.id), ReviewCardFeedback.forCard(cardId).map { it.id })
        val removed = ReviewCardFeedback.find(replacement.created.id)!!
        assertTrue(removed.deleted)
        assertEquals(2L, removed.revision)
    }

    @Test fun undoOfAPassageChangeAfterSyncSettledBothWrites() {
        val old = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "", anchor(), "android")
        val replacement = ReviewCardFeedback.replacePassage(old.id, Polarity.POSITIVE, "", widerAnchor(), "android")
        val operations = ReviewCardFeedback.pendingUploads().map { it.operationId }.toSet()
        ReviewCardFeedback.installServer(listOf(replacement.created.payload), accepted = operations)
        assertNull(ReviewCardFeedback.find(old.id))
        val restored = ReviewCardFeedback.undoReplacement(replacement, "android")
        assertEquals(old.revision + 2, restored.revision)
        assertEquals(listOf(old.id), ReviewCardFeedback.forCard(cardId).map { it.id })
        assertEquals(setOf(old.id, replacement.created.id), ReviewCardFeedback.pendingUploads().map { it.entityId }.toSet())
    }

    @Test fun aDeletedOrMissingHighlightKeepsNoNewPassage() {
        val old = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "", anchor(), "android")
        ReviewCardFeedback.remove(old.id, "android")
        val failures = listOf(old.id, "6c1d9f8e-0b3a-4e2f-8d7c-5a4b3c2d1e0f").map { id ->
            runCatching { ReviewCardFeedback.replacePassage(id, Polarity.POSITIVE, "", widerAnchor(), "android") }.exceptionOrNull()
        }
        assertTrue(failures.toString(), failures.all { it is IllegalArgumentException })
        // A passage that breaks the anchor rules changes nothing either.
        val live = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "", anchor(), "android")
        assertTrue(runCatching {
            ReviewCardFeedback.replacePassage(live.id, Polarity.POSITIVE, "", widerAnchor().copy(exact = " "), "android")
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(live.id), ReviewCardFeedback.forCard(cardId).map { it.id })
    }

    // review_version (spec thoughts-feedback-version-stamps) ----------------------------------

    @Test fun createAndEditRecordThePdfVersionAtTheTopLevel() {
        val first = ReviewVersionStamp("1".repeat(64), true)
        val created = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "", anchor(), "android",
            Instant.parse("2026-10-22T10:00:00Z"), version = first)
        assertEquals(first, ReviewVersionStamp.of(created.payload))
        assertEquals(first.toJson(), ReviewCardFeedback.pendingUploads().single().payload.get("review_version"))
        // An edit on a newer build replaces the stamp; one whose version is unknown keeps the last.
        val second = ReviewVersionStamp("2".repeat(64), true)
        val edited = ReviewCardFeedback.edit(created.id, Polarity.NEGATIVE, "Wrong sign", "android",
            Instant.parse("2026-10-22T10:01:00Z"), version = second)!!
        assertEquals(second, ReviewVersionStamp.of(edited.payload))
        // An edit that changes nothing writes nothing, stamp included.
        assertNull(ReviewCardFeedback.edit(created.id, Polarity.NEGATIVE, "Wrong sign", "android", version = first))
        // Delete and Undo are not new feedback: they keep the record's stamp.
        val tombstone = ReviewCardFeedback.remove(created.id, "android", Instant.parse("2026-10-22T10:03:00Z"))!!
        assertEquals(second, ReviewVersionStamp.of(tombstone.payload))
        val restored = ReviewCardFeedback.undo(tombstone, "android", Instant.parse("2026-10-22T10:03:01Z"))
        assertEquals(second, ReviewVersionStamp.of(restored.payload))
        // An edit on an unknown version removes the stamp: it would name a version the edit was not made on.
        val unknown = ReviewCardFeedback.edit(created.id, Polarity.NEGATIVE, "Wrong sign!", "android",
            Instant.parse("2026-10-22T10:04:00Z"))!!
        assertFalse(unknown.payload.has("review_version"))
    }

    @Test fun withoutAVersionTheFieldIsLeftOut() {
        val created = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "", anchor(), "android")
        assertFalse(created.payload.has("review_version"))
    }

    @Test fun aStampFromAnotherClientSurvivesStorageDeleteAndUndoAndAnEditReplacesIt() {
        val stamp = ReviewVersionStamp("3".repeat(64), false)
        val server = JsonParser.parseString(example).asJsonObject.apply { add("review_version", stamp.toJson()) }
        ReviewCardFeedback.installServer(listOf(server))
        assertEquals(stamp, ReviewVersionStamp.of(ReviewCardFeedback.find(feedbackId)!!.payload))
        val tombstone = ReviewCardFeedback.remove(feedbackId, "android")!!
        assertEquals(stamp, ReviewVersionStamp.of(tombstone.payload))
        assertEquals(stamp, ReviewVersionStamp.of(ReviewCardFeedback.undo(tombstone, "android").payload))
        val newer = ReviewVersionStamp("4".repeat(64), true)
        val edited = ReviewCardFeedback.edit(feedbackId, Polarity.POSITIVE, "fine", "android", version = newer)!!
        assertEquals(newer, ReviewVersionStamp.of(edited.payload))
        assertEquals("S3", edited.payload.get("study_label").asString)
    }

    // Installing the server's records --------------------------------------------------------

    @Test fun pendingWritesStayUntilTheServerHoldsThemOrAWinner() {
        val created = ReviewCardFeedback.create(cardId, Polarity.POSITIVE, "", anchor(), "android", Instant.parse("2026-10-20T10:00:00Z"))
        // Not sent yet (no capability): an empty server keeps it.
        ReviewCardFeedback.installServer(emptyList())
        assertEquals(created, ReviewCardFeedback.find(created.id))
        // Accepted and listed: settled.
        val operation = ReviewCardFeedback.pendingUploads().single().operationId
        ReviewCardFeedback.installServer(listOf(created.payload), accepted = setOf(operation))
        assertEquals(emptyList<ReviewCardFeedback.Upload>(), ReviewCardFeedback.pendingUploads())
        // An offline edit at rev 2 loses to a deletion at rev 2 elsewhere: superseded, then gone.
        ReviewCardFeedback.edit(created.id, Polarity.NEGATIVE, "late", "android")
        val edit = ReviewCardFeedback.pendingUploads().single().operationId
        ReviewCardFeedback.installServer(emptyList(), superseded = setOf(edit))
        assertNull(ReviewCardFeedback.find(created.id))
    }

    @Test fun aRecordDeletedOnTheServerGoesAndANewOneArrives() {
        val remote = parse(example)
        ReviewCardFeedback.installServer(listOf(remote.payload))
        assertEquals(listOf(remote), ReviewCardFeedback.forCard(cardId))
        ReviewCardFeedback.installServer(emptyList())
        assertEquals(emptyList<ReviewCardFeedback.Feedback>(), ReviewCardFeedback.all())
    }

    @Test fun aWinningServerWriteReplacesAPendingLocalOne() {
        ReviewCardFeedback.installServer(listOf(parse(example).payload))
        ReviewCardFeedback.edit(feedbackId, Polarity.POSITIVE, "mine", "android", Instant.parse("2026-10-20T11:00:00Z"))
        val theirs = record(2, "2026-10-20T11:30:00.000Z", "mac-okular", comment = "theirs")
        ReviewCardFeedback.installServer(listOf(theirs.payload))
        assertEquals("theirs", ReviewCardFeedback.find(feedbackId)!!.comment)
        assertEquals(emptyList<ReviewCardFeedback.Upload>(), ReviewCardFeedback.pendingUploads())
    }

    @Test fun creationRefusesAnchorsTheServerWouldReject() {
        val bad = listOf(
            anchor().copy(exact = " "),
            anchor().copy(documentId = "not-a-uuid"),
            anchor().copy(pdfSha256 = "ABC"),
            anchor().copy(regions = emptyList()),
            anchor().copy(regions = listOf(ReviewCardFeedback.Region(0, 0.1, 0.1, 0.2, 0.2))),
            anchor().copy(regions = listOf(ReviewCardFeedback.Region(1, 0.3, 0.1, 0.2, 0.2))),
        )
        bad.forEach { assertTrue(it.toString(), ReviewCardFeedback.anchorProblem(it) != null) }
        assertNull(ReviewCardFeedback.anchorProblem(anchor()))
    }
}

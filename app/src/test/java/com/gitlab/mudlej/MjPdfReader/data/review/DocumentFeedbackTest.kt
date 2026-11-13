// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Polarity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * thoughts-server protocol/document-feedback.md, Android conformance: payload round-trip with
 * unknown fields, record rules (§1), local merge and sticky delete (§3), create / edit / delete /
 * undo revisions, installing the server's records, drawings (§10) and text boxes (§11).
 */
class DocumentFeedbackTest {
    private lateinit var directory: File
    private val previousFile = DocumentFeedback.file
    private val previousCardFile = ReviewCardFeedback.file
    private val feedbackId = "9d2f4a51-7c0e-4b8a-a6f1-3e5b2c7d8e90"
    private val documentId = "c1e62ecc-2dfb-4259-97cc-e319b1186848"
    private val otherDocumentId = "0b5c4c4e-7a8e-4d7b-9a51-1f1f2f3f4f5f"
    private val pdfSha = "f4c8827551095a63ce58c6b1e2c5946014b7b3f62d0f96a780a2931f601a561e"

    /** The §1 example record, with unknown fields at the top level, in the anchor and in a region. */
    private val example = """{"schema_version":1,"feedback_id":"$feedbackId","polarity":"positive","comment":"",""" +
        """"anchor":{"version":1,"quote":{"exact":"The picture draws each route in the plane of the two weights",""" +
        """"prefix":"","suffix":": the horizontal position is a(t)"},"pdf":{"document_id":"$documentId",""" +
        """"pdf_sha256":"$pdfSha","regions":[{"page":1,"rect":[0.0330,0.5090,0.8930,0.5200],"glyphs":"future"}]},""" +
        """"anchor_extra":{"k":[1,2.50]}},"revision":1,"created_at":"2026-10-28T08:52:03.512Z",""" +
        """"updated_at":"2026-10-28T08:52:03.512Z","created_origin_id":"android-cph2719","origin_id":"android-cph2719",""" +
        """"deleted":false,"study_label":"S3"}"""

    @Before fun setUp() {
        directory = Files.createTempDirectory("document-feedback").toFile()
        DocumentFeedback.file = { File(directory, "document-feedback.json") }
        ReviewCardFeedback.file = { File(directory, "card-feedback.json") }
    }

    @After fun tearDown() {
        DocumentFeedback.file = previousFile
        ReviewCardFeedback.file = previousCardFile
        directory.deleteRecursively()
    }

    private fun parse(json: String) = DocumentFeedback.parse(JsonParser.parseString(json).asJsonObject)!!

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
    }.let { DocumentFeedback.parse(it)!! }

    private fun anchor(document: String = documentId) = ReviewCardFeedback.NewAnchor(
        "The picture draws each route", "", " in the plane", document, pdfSha,
        listOf(ReviewCardFeedback.Region(1, 0.033, 0.509, 0.893, 0.52)),
    )

    // Codec -----------------------------------------------------------------------------------

    @Test fun exampleRecordRoundTripsWithUnknownFields() {
        val feedback = parse(example)
        assertEquals(example, feedback.payload.toString())
        assertEquals(Polarity.POSITIVE, feedback.polarity)
        assertEquals("", feedback.cardId)
        assertEquals(documentId, feedback.pdfDocumentId)
        assertEquals(pdfSha, feedback.pdfSha256)
        assertEquals(listOf(ReviewCardFeedback.Region(1, 0.0330, 0.5090, 0.8930, 0.5200)), feedback.regions)
        // Stored and read back, then rewritten by an edit: the unknown fields survive.
        DocumentFeedback.installServer(listOf(feedback.payload))
        assertEquals(example, DocumentFeedback.find(feedbackId)!!.payload.toString())
        val edited = DocumentFeedback.edit(feedbackId, Polarity.NEGATIVE, "ok", "android-test", Instant.parse("2026-10-28T09:00:00Z"))!!
        val payload = edited.payload
        assertEquals("S3", payload.get("study_label").asString)
        assertEquals("future", payload.getAsJsonObject("anchor").getAsJsonObject("pdf").getAsJsonArray("regions")[0]
            .asJsonObject.get("glyphs").asString)
        assertEquals("""{"k":[1,2.50]}""", payload.getAsJsonObject("anchor").get("anchor_extra").toString())
        assertFalse(payload.has("card_id"))
        // The upload carries the record and its own entity type in the operation id.
        val upload = DocumentFeedback.pendingUploads().single()
        assertEquals(feedbackId, upload.entityId)
        assertEquals(1L, upload.baseRevision)
        assertEquals(payload, upload.payload)
        assertNotEquals(ReviewCardFeedback.uploadOf(edited).operationId, upload.operationId)
    }

    @Test fun recordsBreakingTheRulesAreRefused() {
        fun mutated(edit: JsonObject.() -> Unit) = DocumentFeedback.parse(JsonParser.parseString(example).asJsonObject.apply(edit))
        // Card feedback is review_card_feedback, never document feedback.
        assertNull(mutated { addProperty("card_id", "0072b138-d3fe-416a-83e7-1407058f0e2b") })
        assertNull(mutated { getAsJsonObject("anchor").remove("pdf") })
        assertNull(mutated { getAsJsonObject("anchor").getAsJsonObject("pdf").remove("regions") })
        assertNull(mutated { getAsJsonObject("anchor").getAsJsonObject("pdf").add("regions", com.google.gson.JsonArray()) })
        assertNull(mutated { getAsJsonObject("anchor").getAsJsonObject("pdf").remove("document_id") })
        assertNull(mutated { addProperty("view", "html") })
        assertNull(mutated { addProperty("polarity", "neutral") })
        assertNull(mutated { addProperty("feedback_id", "not-a-uuid") })
        assertNull(mutated { addProperty("feedback_id", feedbackId.uppercase()) })
        assertNull(mutated { getAsJsonObject("anchor").getAsJsonObject("quote").addProperty("exact", "  ") })
        assertTrue(mutated { addProperty("view", "pdf") } != null)
        // And card feedback does not read a document record.
        assertNull(ReviewCardFeedback.parse(JsonParser.parseString(example).asJsonObject))
    }

    // Merge (spec §3 = card feedback §5) --------------------------------------------------------

    @Test fun higherRevisionWinsAndEqualRevisionsGoToTheLaterUpdate() {
        val a = record(2, "2026-10-28T11:00:00.000Z", "mac-okular", comment = "A")
        val b = record(2, "2026-10-28T11:30:00.000Z", "android", polarity = "negative")
        assertEquals(b, DocumentFeedback.merge(DocumentFeedback.merge(null, a), b))
        assertEquals(b, DocumentFeedback.merge(DocumentFeedback.merge(null, b), a))
        assertTrue(DocumentFeedback.wins(record(3, "2026-10-28T09:00:00.000Z"), b))
        assertFalse(DocumentFeedback.wins(record(1, "2026-10-29T00:00:00.000Z"), b)) // stale: superseded
    }

    @Test fun deleteIsStickyInEveryArrivalOrderAndUndoRestores() {
        val deleted = record(2, "2026-10-28T11:00:00.000Z", deleted = true)
        for (time in listOf("2026-10-28T10:00:00.000Z", "2026-10-28T11:00:00.000Z", "2026-10-28T12:00:00.000Z")) {
            val edit = record(2, time, origin = "zzz", comment = "offline edit")
            assertEquals(time, deleted, DocumentFeedback.merge(DocumentFeedback.merge(null, edit), deleted))
            assertEquals(time, deleted, DocumentFeedback.merge(DocumentFeedback.merge(null, deleted), edit))
        }
        val undo = record(3, "2026-10-28T11:00:05.000Z")
        assertEquals(undo, DocumentFeedback.merge(deleted, undo))
    }

    @Test fun mergeKeepsTheFieldsTheFirstWriteFixed() {
        val stored = parse(example)
        val incoming = record(2, "2026-10-28T11:00:00.000Z").payload.deepCopy().apply {
            addProperty("created_at", "2030-01-01T00:00:00.000Z")
            addProperty("created_origin_id", "intruder")
            addProperty("view", "pdf")
            getAsJsonObject("anchor").getAsJsonObject("quote").addProperty("exact", "something else")
        }.let { DocumentFeedback.parse(it)!! }
        val merged = DocumentFeedback.merge(stored, incoming)
        assertEquals(2L, merged.revision)
        assertEquals("2026-10-28T08:52:03.512Z", merged.createdAt)
        assertEquals("android-cph2719", merged.createdOriginId)
        assertEquals(stored.payload.get("anchor"), merged.payload.get("anchor"))
        assertFalse(merged.payload.has("view"))
    }

    // Create / edit / delete / undo --------------------------------------------------------------

    @Test fun likeCreatesPositiveDocumentFeedbackWithoutACard() {
        val now = Instant.parse("2026-10-28T08:52:03.512345Z")
        val liked = DocumentFeedback.create(Polarity.POSITIVE, "", anchor(), "android-test", now)
        assertTrue(ReviewCardFeedback.validUuid4(liked.id))
        val payload = liked.payload
        assertEquals(liked.id, payload.get("feedback_id").asString)
        assertFalse(payload.has("card_id"))
        assertFalse(payload.has("view"))
        assertEquals(1, payload.get("schema_version").asInt)
        assertEquals("positive", payload.get("polarity").asString)
        assertEquals("", payload.get("comment").asString)
        assertEquals(1L, liked.revision)
        assertEquals("2026-10-28T08:52:03.512Z", liked.createdAt)
        assertEquals(liked.createdAt, liked.updatedAt)
        assertEquals("android-test", liked.createdOriginId)
        assertFalse(liked.deleted)
        assertFalse(payload.has("deleted_at"))
        val pdf = payload.getAsJsonObject("anchor").getAsJsonObject("pdf")
        assertEquals(documentId, pdf.get("document_id").asString)
        assertEquals(pdfSha, pdf.get("pdf_sha256").asString)
        assertEquals(1, pdf.getAsJsonArray("regions").size())
        assertNull(DocumentFeedback.pendingUploads().single().baseRevision)
        // The store reads it back as a valid record, and the card store is untouched.
        assertEquals(listOf(liked), DocumentFeedback.forDocument(documentId))
        assertEquals(emptyList<ReviewCardFeedback.Feedback>(), ReviewCardFeedback.all())
    }

    @Test fun recordsBelongToTheirDocument() {
        val mine = DocumentFeedback.create(Polarity.POSITIVE, "", anchor(), "android", Instant.parse("2026-10-28T10:00:00Z"))
        val second = DocumentFeedback.create(Polarity.NEGATIVE, "", anchor(), "android", Instant.parse("2026-10-28T10:01:00Z"))
        DocumentFeedback.create(Polarity.NEGATIVE, "", anchor(otherDocumentId), "android")
        assertNotEquals(mine.id, second.id)
        assertEquals(listOf(mine.id, second.id), DocumentFeedback.forDocument(documentId).map { it.id })
        assertEquals(listOf(mine.id, second.id), DocumentFeedback.forDocument(documentId.uppercase()).map { it.id })
        assertEquals(1, DocumentFeedback.forDocument(otherDocumentId).size)
    }

    @Test fun editIncrementsTheRevisionOnlyWhenSomethingChanged() {
        val created = DocumentFeedback.create(Polarity.POSITIVE, "", anchor(), "android", Instant.parse("2026-10-28T10:00:00Z"))
        assertNull(DocumentFeedback.edit(created.id, Polarity.POSITIVE, "   ", "android", Instant.parse("2026-10-28T10:01:00Z")))
        val edited = DocumentFeedback.edit(created.id, Polarity.NEGATIVE, "  Too vague.\n", "mac", Instant.parse("2026-10-28T10:02:00Z"))!!
        assertEquals(2L, edited.revision)
        assertEquals("Too vague.", edited.comment)
        assertEquals(created.createdAt, edited.createdAt)
        assertEquals("2026-10-28T10:02:00.000Z", edited.updatedAt)
        assertEquals("android", edited.createdOriginId)
        assertEquals("mac", edited.originId)
        assertEquals(created.payload.get("anchor"), edited.payload.get("anchor"))
        // Only the latest revision is pending.
        assertEquals(1L, DocumentFeedback.pendingUploads().single().baseRevision)
    }

    @Test fun deleteThenUndoGivesRevisionPlusTwoNotDeleted() {
        val created = DocumentFeedback.create(Polarity.POSITIVE, "kept", anchor(), "android", Instant.parse("2026-10-28T10:00:00Z"))
        val tombstone = DocumentFeedback.remove(created.id, "android", Instant.parse("2026-10-28T10:05:00Z"))!!
        assertTrue(tombstone.deleted)
        assertEquals(2L, tombstone.revision)
        assertEquals("2026-10-28T10:05:00.000Z", tombstone.payload.get("deleted_at").asString)
        assertEquals("kept", tombstone.comment) // the tombstone carries the full record
        assertEquals(emptyList<ReviewCardFeedback.Feedback>(), DocumentFeedback.forDocument(documentId))
        val restored = DocumentFeedback.undo(tombstone, "android", Instant.parse("2026-10-28T10:05:02Z"))
        assertEquals(3L, restored.revision)
        assertFalse(restored.deleted)
        assertFalse(restored.payload.has("deleted_at"))
        assertEquals(listOf(restored), DocumentFeedback.forDocument(documentId))
    }

    @Test fun undoWorksAfterSyncSettledTheTombstone() {
        val created = DocumentFeedback.create(Polarity.POSITIVE, "", anchor(), "android")
        val tombstone = DocumentFeedback.remove(created.id, "android")!!
        val operation = DocumentFeedback.pendingUploads().single().operationId
        DocumentFeedback.installServer(emptyList(), accepted = setOf(operation))
        assertNull(DocumentFeedback.find(created.id))
        val restored = DocumentFeedback.undo(tombstone, "android")
        assertEquals(tombstone.revision + 1, restored.revision)
        assertEquals(1, DocumentFeedback.pendingUploads().size)
    }

    @Test fun createAndEditRecordThePdfVersion() {
        val stamp = ReviewVersionStamp(pdfSha, true)
        val created = DocumentFeedback.create(Polarity.POSITIVE, "", anchor(), "android", version = stamp)
        assertEquals(stamp, ReviewVersionStamp.of(created.payload))
        val newer = ReviewVersionStamp("2".repeat(64), true)
        val edited = DocumentFeedback.edit(created.id, Polarity.NEGATIVE, "x", "android", version = newer)!!
        assertEquals(newer, ReviewVersionStamp.of(edited.payload))
        assertFalse(DocumentFeedback.create(Polarity.POSITIVE, "", anchor(), "android").payload.has("review_version"))
    }

    @Test fun creationRefusesAnchorsTheServerWouldReject() {
        val bad = listOf(
            anchor().copy(exact = " "),
            anchor().copy(documentId = "not-a-uuid"),
            anchor().copy(pdfSha256 = "ABC"),
            anchor().copy(regions = emptyList()),
            anchor().copy(regions = List(201) { ReviewCardFeedback.Region(1, 0.1, 0.1, 0.2, 0.2) }),
            anchor().copy(regions = listOf(ReviewCardFeedback.Region(0, 0.1, 0.1, 0.2, 0.2))),
            anchor().copy(htmlEditionSha256 = pdfSha),
        )
        bad.forEach { assertTrue(it.toString(), DocumentFeedback.anchorProblem(it) != null) }
        assertNull(DocumentFeedback.anchorProblem(anchor()))
        assertEquals(0, DocumentFeedback.all().size)
    }

    // Installing the server's records ------------------------------------------------------------

    @Test fun recordsMadeBeforeServerSupportStayQueuedAndShown() {
        val created = DocumentFeedback.create(Polarity.POSITIVE, "", anchor(), "android", Instant.parse("2026-10-28T10:00:00Z"))
        // Sync without the capability: nothing was sent, the server lists nothing.
        repeat(2) { DocumentFeedback.installServer(emptyList()) }
        assertEquals(listOf(created), DocumentFeedback.forDocument(documentId))
        assertEquals(1, DocumentFeedback.pendingUploads().size)
        // Accepted and listed: settled.
        val operation = DocumentFeedback.pendingUploads().single().operationId
        DocumentFeedback.installServer(listOf(created.payload), accepted = setOf(operation))
        assertEquals(emptyList<ReviewCardFeedback.Upload>(), DocumentFeedback.pendingUploads())
        assertEquals(listOf(created), DocumentFeedback.forDocument(documentId))
        // Known to the server, then no longer listed: deleted elsewhere.
        DocumentFeedback.installServer(emptyList())
        assertNull(DocumentFeedback.find(created.id))
    }

    @Test fun aLocalEditOvertakenByTheServersDeleteIsShownDeleted() {
        DocumentFeedback.installServer(listOf(parse(example).payload))
        DocumentFeedback.edit(feedbackId, Polarity.NEGATIVE, "mine", "android", Instant.parse("2026-10-28T12:00:00Z"))
        val superseded = DocumentFeedback.pendingUploads().single().operationId
        DocumentFeedback.installServer(emptyList(), superseded = setOf(superseded))
        assertNull(DocumentFeedback.find(feedbackId))
        // Another device's Undo (rev 3) brings it back.
        val undo = record(3, "2026-10-28T12:05:00.000Z", origin = "mac-okular")
        DocumentFeedback.installServer(listOf(undo.payload))
        assertEquals(listOf(undo), DocumentFeedback.forDocument(documentId))
    }

    @Test fun aWinningServerWriteReplacesAPendingLocalOneAndALosingOneStays() {
        DocumentFeedback.installServer(listOf(parse(example).payload))
        DocumentFeedback.edit(feedbackId, Polarity.POSITIVE, "mine", "android", Instant.parse("2026-10-28T11:00:00Z"))
        // An older server record does not replace the pending edit.
        DocumentFeedback.installServer(listOf(parse(example).payload))
        assertEquals("mine", DocumentFeedback.find(feedbackId)!!.comment)
        assertEquals(1, DocumentFeedback.pendingUploads().size)
        val theirs = record(2, "2026-10-28T11:30:00.000Z", "mac-okular", comment = "theirs")
        DocumentFeedback.installServer(listOf(theirs.payload))
        assertEquals("theirs", DocumentFeedback.find(feedbackId)!!.comment)
        assertEquals(emptyList<ReviewCardFeedback.Upload>(), DocumentFeedback.pendingUploads())
    }

    @Test fun serverRecordsThatAreNotDocumentFeedbackAreIgnored() {
        val card = JsonParser.parseString(example).asJsonObject.apply { addProperty("card_id", "some-card") }
        DocumentFeedback.installServer(listOf(card))
        assertEquals(emptyList<ReviewCardFeedback.Feedback>(), DocumentFeedback.all())
    }

    // Kinds and versions (spec §9) ----------------------------------------------------------------

    private val otherPdf = "a".repeat(64)
    private val neutral = ReviewCardFeedback.Polarity.NEUTRAL

    @Test fun aQuestionIsNeutralWithAKindAndKeepsThemThroughARoundTrip() {
        val created = DocumentFeedback.create(neutral, "Why this bound?", anchor(), "mac-okular", kind = ReviewCardFeedback.Kind.QUESTION)
        assertEquals(ReviewCardFeedback.Kind.QUESTION, created.kind)
        assertEquals("neutral", created.payload.get("polarity").asString)
        assertEquals("question", created.payload.get("kind").asString)
        assertEquals(created, DocumentFeedback.parse(created.payload))
        // The two go together, and a card never reads one.
        fun mutated(edit: JsonObject.() -> Unit) = DocumentFeedback.parse(created.payload.deepCopy().apply(edit))
        assertNull(mutated { remove("kind") })
        assertNull(mutated { addProperty("polarity", "positive") })
        assertNull(mutated { addProperty("kind", "idea") })
        assertNull(ReviewCardFeedback.parse(created.payload.deepCopy().apply { addProperty("card_id", "card") }))
    }

    @Test fun aLikeAndAQuestionShowOnTheirVersionAndACommentOnEveryVersionUntilResolved() {
        val like = DocumentFeedback.create(ReviewCardFeedback.Polarity.POSITIVE, "", anchor(), "mac-okular")
        assertTrue(DocumentFeedback.shownOn(like, pdfSha))
        assertTrue(DocumentFeedback.shownOn(like, pdfSha.uppercase()))
        assertFalse(DocumentFeedback.shownOn(like, otherPdf))
        // A question is tied to the version it was asked on, as a like is, and hides once resolved.
        val question = DocumentFeedback.create(neutral, "Why?", anchor(), "mac-okular", kind = ReviewCardFeedback.Kind.QUESTION)
        assertTrue(DocumentFeedback.shownOn(question, pdfSha))
        assertFalse(DocumentFeedback.shownOn(question, otherPdf))
        assertTrue(DocumentFeedback.offeredAsEarlier(question, otherPdf))
        val closed = DocumentFeedback.resolve(question.id, true, "mac-okular")!!
        assertEquals(question.revision + 1, closed.revision)
        assertFalse(DocumentFeedback.shownOn(closed, pdfSha))
        assertNull(DocumentFeedback.resolve(question.id, true, "mac-okular")) // no new revision
        val open = DocumentFeedback.resolve(question.id, false, "mac-okular")!!
        assertNull(open.resolvedAt)
        assertTrue(DocumentFeedback.shownOn(open, pdfSha))
        assertFalse(DocumentFeedback.shownOn(open, otherPdf))
        // A comment follows the text on every version until it is resolved.
        val comment = DocumentFeedback.create(neutral, "Fix this.", anchor(), "mac-okular", kind = ReviewCardFeedback.Kind.OTHER)
        assertTrue(DocumentFeedback.shownOn(comment, pdfSha))
        assertTrue(DocumentFeedback.shownOn(comment, otherPdf))
        assertFalse(DocumentFeedback.offeredAsEarlier(comment, otherPdf))
        val done = DocumentFeedback.resolve(comment.id, true, "mac-okular")!!
        assertFalse(DocumentFeedback.shownOn(done, pdfSha))
    }

    @Test fun aCommentCanChangeKindAndALikeHasNoResolution() {
        val other = DocumentFeedback.create(neutral, "Fix this.", anchor(), "mac-okular", kind = ReviewCardFeedback.Kind.OTHER)
        DocumentFeedback.resolve(other.id, true, "mac-okular")
        val liked = DocumentFeedback.edit(other.id, ReviewCardFeedback.Polarity.POSITIVE, "Fix this.", "mac-okular")!!
        assertNull(liked.kind)
        assertNull(liked.resolvedAt)
        assertEquals("positive", liked.payload.get("polarity").asString)
        val like = DocumentFeedback.create(ReviewCardFeedback.Polarity.POSITIVE, "", anchor(), "mac-okular")
        assertThrows(IllegalArgumentException::class.java) { DocumentFeedback.resolve(like.id, true, "mac-okular") }
        assertThrows(IllegalArgumentException::class.java) { DocumentFeedback.edit(like.id, neutral, "x", "mac-okular") }
    }

    // Change passage: a new record on the new passage, the old one deleted ----------------------

    private fun widerAnchor() = anchor().copy(
        exact = "The picture draws each route in the plane",
        regions = listOf(ReviewCardFeedback.Region(1, 0.033, 0.509, 0.893, 0.52), ReviewCardFeedback.Region(1, 0.033, 0.52, 0.4, 0.531)),
    )

    @Test fun changingThePassageReplacesTheRecordInOneWrite() {
        val old = DocumentFeedback.create(Polarity.NEGATIVE, "Too vague.", anchor(), "android", Instant.parse("2026-11-09T10:00:00Z"))
        val replacement = DocumentFeedback.replacePassage(old.id, Polarity.NEGATIVE, " Still vague. ", widerAnchor(), "android",
            Instant.parse("2026-11-09T10:05:00Z"))
        val created = replacement.created
        assertNotEquals(old.id, created.id)
        assertTrue(ReviewCardFeedback.validUuid4(created.id))
        assertEquals(widerAnchor().exact, created.exact)
        assertEquals(widerAnchor().regions, created.regions)
        assertEquals(documentId, created.pdfDocumentId)
        assertEquals(Polarity.NEGATIVE, created.polarity)
        assertEquals("Still vague.", created.comment)
        assertFalse(created.payload.has("card_id"))
        assertEquals(created, DocumentFeedback.parse(created.payload))
        val tombstone = replacement.tombstone
        assertTrue(tombstone.deleted)
        assertEquals(2L, tombstone.revision)
        assertEquals(old.payload.get("anchor"), tombstone.payload.get("anchor"))
        assertEquals(listOf(created), DocumentFeedback.forDocument(documentId))
        assertEquals(setOf(old.id, created.id), DocumentFeedback.pendingUploads().map { it.entityId }.toSet())
    }

    @Test fun aQuestionKeepsItsKindAndResolutionOnItsNewPassage() {
        val question = DocumentFeedback.create(neutral, "Why this bound?", anchor(), "android", kind = ReviewCardFeedback.Kind.QUESTION)
        val closed = DocumentFeedback.resolve(question.id, true, "android")!!
        val moved = DocumentFeedback.replacePassage(question.id, neutral, "Why this bound?", widerAnchor(), "android",
            kind = ReviewCardFeedback.Kind.QUESTION).created
        assertEquals(ReviewCardFeedback.Kind.QUESTION, moved.kind)
        assertEquals(closed.resolvedAt, moved.resolvedAt)
        // A kind goes with a neutral polarity, here as everywhere.
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFeedback.replacePassage(moved.id, Polarity.POSITIVE, "", anchor(), "android", kind = ReviewCardFeedback.Kind.QUESTION)
        }
    }

    @Test fun undoOfAPassageChangeBringsTheOldRecordBack() {
        val old = DocumentFeedback.create(Polarity.POSITIVE, "", anchor(), "android", Instant.parse("2026-11-09T10:00:00Z"))
        val replacement = DocumentFeedback.replacePassage(old.id, Polarity.POSITIVE, "", widerAnchor(), "android",
            Instant.parse("2026-11-09T10:05:00Z"))
        val restored = DocumentFeedback.undoReplacement(replacement, "android", Instant.parse("2026-11-09T10:05:03Z"))
        assertEquals(old.id, restored.id)
        assertFalse(restored.deleted)
        assertEquals(3L, restored.revision)
        assertEquals(listOf(old.id), DocumentFeedback.forDocument(documentId).map { it.id })
        assertTrue(DocumentFeedback.find(replacement.created.id)!!.deleted)
        // After Sync settled both writes, Undo still restores the old record.
        val again = DocumentFeedback.replacePassage(old.id, Polarity.POSITIVE, "", widerAnchor(), "android")
        DocumentFeedback.installServer(listOf(again.created.payload), accepted = DocumentFeedback.pendingUploads().map { it.operationId }.toSet())
        assertNull(DocumentFeedback.find(old.id))
        assertEquals(old.id, DocumentFeedback.undoReplacement(again, "android").id)
        assertEquals(listOf(old.id), DocumentFeedback.forDocument(documentId).map { it.id })
    }

    @Test fun drawingsAndDeletedRecordsKeepNoNewPassage() {
        DocumentFeedback.installServer(listOf(drawing()))
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFeedback.replacePassage(feedbackId, neutral, "", widerAnchor(), "android", kind = ReviewCardFeedback.Kind.OTHER)
        }
        val gone = DocumentFeedback.create(Polarity.POSITIVE, "", anchor(), "android")
        DocumentFeedback.remove(gone.id, "android")
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFeedback.replacePassage(gone.id, Polarity.POSITIVE, "", widerAnchor(), "android")
        }
    }

    // Drawings (spec §10) -----------------------------------------------------------------------

    private val strokes = """[{"points":[[0.2104,0.3312],[0.2231,0.3398],[0.25,0.5]],"pressure":"future"},{"points":[[1,0]]}]"""

    /** The §10 example, as the Mac writes it. */
    private fun drawing(sha: String = pdfSha, drawingJson: String = """{"strokes":$strokes}""") = JsonParser.parseString(
        """{"schema_version":1,"feedback_id":"$feedbackId","polarity":"neutral","kind":"drawing","comment":"",""" +
            """"anchor":{"version":1,"quote":{"exact":"Drawing","prefix":"","suffix":""},"pdf":{"document_id":"$documentId",""" +
            """"pdf_sha256":"$sha","regions":[{"page":3,"rect":[0,0,1,1]}]}},"drawing":$drawingJson,"view":"pdf","revision":1,""" +
            """"created_at":"2026-11-05T08:52:03.512Z","updated_at":"2026-11-05T08:52:03.512Z",""" +
            """"created_origin_id":"mac-okular","origin_id":"mac-okular","deleted":false}""",
    ).asJsonObject

    @Test fun aDrawingIsReadWithItsStrokes() {
        val record = DocumentFeedback.parse(drawing())!!
        assertTrue(record.isDrawing)
        assertEquals(ReviewCardFeedback.Kind.DRAWING, record.kind)
        assertEquals(Polarity.NEUTRAL, record.polarity)
        assertEquals(3, record.regions.single().page)
        assertEquals(
            listOf(listOf(0.2104f, 0.3312f, 0.2231f, 0.3398f, 0.25f, 0.5f), listOf(1f, 0f)),
            record.strokes.map { it.toList() },
        )
        // Stored and read back whole, with the fields this client does not know.
        DocumentFeedback.installServer(listOf(drawing()))
        assertEquals(drawing(), DocumentFeedback.find(feedbackId)!!.payload)
        assertEquals(2, DocumentFeedback.forDocument(documentId).single().strokes.size)
    }

    @Test fun aDrawingBreakingTheRulesIsRefused() {
        fun points(json: String) = """{"strokes":[{"points":$json}]}"""
        fun parsed(json: String) = DocumentFeedback.parse(drawing(drawingJson = json))
        assertNull(parsed("null"))
        assertNull(parsed("""{"strokes":[]}"""))
        assertNull(parsed(points("[]")))
        assertNull(parsed(points("[[0.1]]")))
        assertNull(parsed(points("[[0.1,0.2,0.3]]")))
        assertNull(parsed(points("[[0.1,1.2]]")))
        assertNull(parsed(points("[[-0.1,0.2]]")))
        assertNull(parsed(points("""[["a",0.2]]""")))
        val one = """{"points":[[0.1,0.2]]}"""
        assertNull(parsed("""{"strokes":[${List(201) { one }.joinToString(",")}]}"""))
        assertNull(parsed(points("[${List(5001) { "[0.1,0.2]" }.joinToString(",")}]")))
        assertEquals(200, parsed("""{"strokes":[${List(200) { one }.joinToString(",")}]}""")!!.strokes.size)
        assertEquals(10000, parsed(points("[${List(5000) { "[0.1,0.2]" }.joinToString(",")}]"))!!.strokes.single().size)
        assertNull(DocumentFeedback.parse(drawing().apply { remove("drawing") }))
        assertNull(DocumentFeedback.parse(drawing().apply { addProperty("polarity", "positive") }))
        assertNull(DocumentFeedback.parse(drawing().apply { addProperty("view", "html_attachment") }))
        // A drawing is neutral with a kind, so card feedback never reads one.
        assertNull(ReviewCardFeedback.parse(drawing().apply { addProperty("card_id", "card") }))
    }

    @Test fun aDrawingShowsOnlyOnItsVersion() {
        val record = DocumentFeedback.parse(drawing())!!
        assertTrue(DocumentFeedback.shownOn(record, pdfSha))
        assertTrue(DocumentFeedback.shownOn(record, pdfSha.uppercase()))
        assertFalse(DocumentFeedback.shownOn(record, otherPdf))
        // Drawn nowhere else, so "Show" of the earlier versions has nothing to draw for it.
        assertFalse(DocumentFeedback.offeredAsEarlier(record, otherPdf))
        assertFalse(DocumentFeedback.offeredAsEarlier(record, pdfSha))
        // resolved_at is not a drawing's, and does not hide it.
        val stamped = DocumentFeedback.parse(drawing().apply { addProperty("resolved_at", "2026-11-05T09:00:00.000Z") })!!
        assertTrue(DocumentFeedback.shownOn(stamped, pdfSha))
    }

    @Test fun earlierVersionsStillOfferEverythingElse() {
        val like = DocumentFeedback.create(ReviewCardFeedback.Polarity.POSITIVE, "", anchor(), "mac-okular")
        assertTrue(DocumentFeedback.offeredAsEarlier(like, otherPdf))
        assertFalse(DocumentFeedback.offeredAsEarlier(like, pdfSha))
        // A question is tied to its version, as a like is.
        val question = DocumentFeedback.create(neutral, "Why?", anchor(), "mac-okular", kind = ReviewCardFeedback.Kind.QUESTION)
        assertTrue(DocumentFeedback.offeredAsEarlier(question, otherPdf))
        assertFalse(DocumentFeedback.offeredAsEarlier(question, pdfSha))
        // A comment shows on every version until it is resolved; then it is one of the earlier ones.
        val comment = DocumentFeedback.create(neutral, "Fix this.", anchor(), "mac-okular", kind = ReviewCardFeedback.Kind.OTHER)
        assertFalse(DocumentFeedback.offeredAsEarlier(comment, otherPdf))
        assertTrue(DocumentFeedback.offeredAsEarlier(DocumentFeedback.resolve(comment.id, true, "mac-okular")!!, otherPdf))
    }

    @Test fun aDrawingIsNotResolvedEditedOrMadeHere() {
        DocumentFeedback.installServer(listOf(drawing()))
        assertThrows(IllegalArgumentException::class.java) { DocumentFeedback.resolve(feedbackId, true, "android") }
        assertThrows(IllegalArgumentException::class.java) { DocumentFeedback.edit(feedbackId, Polarity.POSITIVE, "x", "android") }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFeedback.edit(feedbackId, neutral, "x", "android", kind = ReviewCardFeedback.Kind.QUESTION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFeedback.edit(feedbackId, neutral, "x", "android", kind = ReviewCardFeedback.Kind.DRAWING)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFeedback.create(neutral, "", anchor(), "android", kind = ReviewCardFeedback.Kind.DRAWING)
        }
        assertEquals(emptyList<ReviewCardFeedback.Upload>(), DocumentFeedback.pendingUploads())
    }

    @Test fun aDrawingThatGrewOnTheMacReplacesTheStoredOne() {
        DocumentFeedback.installServer(listOf(drawing()))
        val grown = drawing(drawingJson = """{"strokes":[{"points":[[0.1,0.1]]},{"points":[[0.2,0.2]]},{"points":[[0.3,0.3]]}]}""").apply {
            addProperty("revision", 2)
            addProperty("updated_at", "2026-11-05T09:00:00.000Z")
        }
        DocumentFeedback.installServer(listOf(grown))
        assertEquals(3, DocumentFeedback.find(feedbackId)!!.strokes.size)
        assertEquals(3, DocumentFeedback.merge(DocumentFeedback.parse(drawing()), DocumentFeedback.parse(grown)!!).strokes.size)
    }

    // Text boxes (spec §11) ----------------------------------------------------------------------

    private val textBoxJson = """{"rect":[0.1204,0.3312,0.3518,0.3501],"font_size":0.0145,"align":"future"}"""

    /** The §11 example, as the Mac writes it, with a line break and a field this client does not know. */
    private fun textBox(
        sha: String = pdfSha,
        boxJson: String = textBoxJson,
        comment: String = "Why not the median?\\nOr the mode.",
    ) = JsonParser.parseString(
        """{"schema_version":1,"feedback_id":"$feedbackId","polarity":"neutral","kind":"text","comment":"$comment",""" +
            """"anchor":{"version":1,"quote":{"exact":"Text","prefix":"","suffix":""},"pdf":{"document_id":"$documentId",""" +
            """"pdf_sha256":"$sha","regions":[{"page":3,"rect":[0,0,1,1]}]}},"text_box":$boxJson,"view":"pdf","revision":1,""" +
            """"created_at":"2026-11-09T08:52:03.512Z","updated_at":"2026-11-09T08:52:03.512Z",""" +
            """"created_origin_id":"mac-okular","origin_id":"mac-okular","deleted":false}""",
    ).asJsonObject

    @Test fun aTextBoxIsReadWithItsPlaceAndText() {
        val record = DocumentFeedback.parse(textBox())!!
        assertTrue(record.isTextBox)
        assertTrue(record.isPageMark)
        assertFalse(record.isDrawing)
        assertEquals(ReviewCardFeedback.Kind.TEXT, record.kind)
        assertEquals(Polarity.NEUTRAL, record.polarity)
        assertEquals("Why not the median?\nOr the mode.", record.comment)
        assertEquals(3, record.regions.single().page)
        assertEquals(DocumentFeedback.TextBox(0.1204f, 0.3312f, 0.3518f, 0.3501f, 0.0145f), record.textBox)
        assertEquals(emptyList<FloatArray>(), record.strokes)
        // Stored and read back whole, with the fields this client does not know, inside text_box too.
        DocumentFeedback.installServer(listOf(textBox()))
        assertEquals(textBox(), DocumentFeedback.find(feedbackId)!!.payload)
        assertEquals("future", DocumentFeedback.find(feedbackId)!!.payload.getAsJsonObject("text_box").get("align").asString)
        assertEquals(0.0145f, DocumentFeedback.forDocument(documentId).single().textBox!!.fontSize)
        // Other records have no text box.
        assertNull(parse(example).textBox)
        assertFalse(parse(example).isPageMark)
    }

    @Test fun aTextBoxOnTheEdgesOfTheRulesIsRead() {
        fun parsed(json: String) = DocumentFeedback.parse(textBox(boxJson = json))
        assertEquals(DocumentFeedback.TextBox(0f, 0f, 1f, 1f, 0.004f), parsed("""{"rect":[0,0,1,1],"font_size":0.004}""")!!.textBox)
        assertEquals(0.2f, parsed("""{"rect":[0,0,1,1],"font_size":0.2}""")!!.textBox!!.fontSize)
        assertEquals("x", DocumentFeedback.parse(textBox(comment = "x"))!!.comment)
        assertEquals("  x\n", DocumentFeedback.parse(textBox(comment = "  x\\n"))!!.comment)
    }

    @Test fun aTextBoxBreakingTheRulesIsRefused() {
        fun parsed(json: String) = DocumentFeedback.parse(textBox(boxJson = json))
        fun rect(values: String) = parsed("""{"rect":$values,"font_size":0.0145}""")
        fun size(value: String) = parsed("""{"rect":[0.1,0.1,0.3,0.2],"font_size":$value}""")
        assertNull(parsed("null"))
        assertNull(parsed("[]"))
        assertNull(parsed("{}"))
        assertNull(parsed("""{"font_size":0.0145}"""))
        assertNull(rect("[0.1,0.1,0.3]"))
        assertNull(rect("[0.1,0.1,0.3,0.2,0.5]"))
        assertNull(rect("[0.3,0.1,0.3,0.2]")) // left == right
        assertNull(rect("[0.4,0.1,0.3,0.2]")) // left > right
        assertNull(rect("[0.1,0.2,0.3,0.2]")) // top == bottom
        assertNull(rect("[0.1,0.3,0.3,0.2]")) // top > bottom
        assertNull(rect("[-0.1,0.1,0.3,0.2]"))
        assertNull(rect("[0.1,-0.1,0.3,0.2]"))
        assertNull(rect("[0.1,0.1,1.1,0.2]"))
        assertNull(rect("[0.1,0.1,0.3,1.1]"))
        assertNull(rect("""["0.1",0.1,0.3,0.2]"""))
        assertNull(rect("""[null,0.1,0.3,0.2]"""))
        assertNull(rect("""{"left":0.1}"""))
        assertNull(size("0.0039"))
        assertNull(size("0.2001"))
        assertNull(size("0"))
        assertNull(size("-0.01"))
        assertNull(size("\"0.0145\""))
        assertNull(size("null"))
        assertNull(parsed("""{"rect":[0.1,0.1,0.3,0.2]}"""))
        assertNull(DocumentFeedback.parse(textBox().apply { remove("text_box") }))
        // The text is the comment: there is no empty text box.
        assertNull(DocumentFeedback.parse(textBox(comment = "")))
        assertNull(DocumentFeedback.parse(textBox(comment = " \\n\\t ")))
        assertNull(DocumentFeedback.parse(textBox().apply { remove("comment") }))
        // A text box is not resolved.
        assertNull(DocumentFeedback.parse(textBox().apply { addProperty("resolved_at", "2026-11-09T09:00:00.000Z") }))
        assertNull(DocumentFeedback.parse(textBox().apply { addProperty("polarity", "positive") }))
        assertNull(DocumentFeedback.parse(textBox().apply { addProperty("view", "html_attachment") }))
        // Only a text box has a place: a like, a question or a drawing with one is refused.
        val box = JsonParser.parseString(textBoxJson)
        assertNull(DocumentFeedback.parse(parse(example).payload.deepCopy().apply { add("text_box", box) }))
        assertNull(DocumentFeedback.parse(textBox().apply { addProperty("kind", "question") }))
        assertNull(DocumentFeedback.parse(drawing().apply { add("text_box", box) }))
        // ...while a null place is no place.
        assertTrue(DocumentFeedback.parse(parse(example).payload.deepCopy().apply { add("text_box", com.google.gson.JsonNull.INSTANCE) }) != null)
        // A text box is neutral with a kind, so card feedback never reads one.
        assertNull(ReviewCardFeedback.parse(textBox().apply { addProperty("card_id", "card") }))
        // Refused records are dropped when the server's records are installed.
        DocumentFeedback.installServer(listOf(textBox(comment = ""), textBox(boxJson = "null")))
        assertEquals(emptyList<ReviewCardFeedback.Feedback>(), DocumentFeedback.all())
    }

    @Test fun aTextBoxShowsOnlyOnItsVersion() {
        val record = DocumentFeedback.parse(textBox())!!
        assertTrue(DocumentFeedback.shownOn(record, pdfSha))
        assertTrue(DocumentFeedback.shownOn(record, pdfSha.uppercase()))
        assertFalse(DocumentFeedback.shownOn(record, otherPdf))
        // Drawn nowhere else, so "Show" of the earlier versions has nothing to draw for it.
        assertFalse(DocumentFeedback.offeredAsEarlier(record, otherPdf))
        assertFalse(DocumentFeedback.offeredAsEarlier(record, pdfSha))
    }

    @Test fun aTextBoxIsNotResolvedEditedOrMadeHere() {
        DocumentFeedback.installServer(listOf(textBox()))
        assertThrows(IllegalArgumentException::class.java) { DocumentFeedback.resolve(feedbackId, true, "android") }
        assertThrows(IllegalArgumentException::class.java) { DocumentFeedback.edit(feedbackId, Polarity.POSITIVE, "x", "android") }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFeedback.edit(feedbackId, neutral, "x", "android", kind = ReviewCardFeedback.Kind.OTHER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFeedback.edit(feedbackId, neutral, "x", "android", kind = ReviewCardFeedback.Kind.TEXT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFeedback.create(neutral, "x", anchor(), "android", kind = ReviewCardFeedback.Kind.TEXT)
        }
        // Nor can another record become one.
        val question = DocumentFeedback.create(neutral, "Why?", anchor(), "android", kind = ReviewCardFeedback.Kind.QUESTION)
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFeedback.edit(question.id, neutral, "Why?", "android", kind = ReviewCardFeedback.Kind.TEXT)
        }
        assertEquals(listOf(question.id), DocumentFeedback.pendingUploads().map { it.entityId })
        assertEquals(textBox(), DocumentFeedback.find(feedbackId)!!.payload)
    }

    @Test fun aTextBoxMovedOrEditedOnTheMacReplacesTheStoredOne() {
        DocumentFeedback.installServer(listOf(textBox()))
        val moved = textBox(boxJson = """{"rect":[0.5,0.6,0.7,0.65],"font_size":0.02,"align":"future"}""", comment = "Or the mode.").apply {
            addProperty("revision", 2)
            addProperty("updated_at", "2026-11-09T09:00:00.000Z")
        }
        DocumentFeedback.installServer(listOf(moved))
        assertEquals(DocumentFeedback.TextBox(0.5f, 0.6f, 0.7f, 0.65f, 0.02f), DocumentFeedback.find(feedbackId)!!.textBox)
        assertEquals("Or the mode.", DocumentFeedback.find(feedbackId)!!.comment)
        val merged = DocumentFeedback.merge(DocumentFeedback.parse(textBox()), DocumentFeedback.parse(moved)!!)
        assertEquals(0.5f, merged.textBox!!.left)
        // The anchor stays as first written: it only names the page.
        assertEquals(3, merged.regions.single().page)
        // Deleted on the Mac: gone here.
        DocumentFeedback.installServer(emptyList())
        assertNull(DocumentFeedback.find(feedbackId))
    }

    @Test fun feedbackMadeInAnHtmlAttachmentIsSkippedOnThePhone() {
        val attachment = JsonParser.parseString(example).asJsonObject.apply {
            addProperty("view", "html_attachment")
            getAsJsonObject("anchor").getAsJsonObject("pdf").add("regions", com.google.gson.JsonArray())
        }
        DocumentFeedback.installServer(listOf(attachment))
        assertEquals(emptyList<ReviewCardFeedback.Feedback>(), DocumentFeedback.all())
    }
}

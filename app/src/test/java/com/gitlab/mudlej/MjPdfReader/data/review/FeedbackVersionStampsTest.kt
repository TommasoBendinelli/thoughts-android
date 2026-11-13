// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.annotation.canonicalCommentForAnnotation
import com.gitlab.mudlej.MjPdfReader.data.annotation.logicalCommentMutationPayload
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.logicalCommentReplyPayload
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** review_version on logical comments (PDF and source comments, highlights, replies): create and edit, never delete. */
class FeedbackVersionStampsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val library = "a".repeat(64)
    private val viewed = "b".repeat(64)
    private val documentId = "c1e62ecc-2dfb-4259-97cc-e319b1186848"
    private val lookups = mutableListOf<Pair<String, String>>()

    /** The Library holds paper.pdf (source-built, vouched hash [library]); nothing else. */
    private fun stamper(): ReviewVersionStamper {
        val paper = temporary.root.resolve("paper.pdf").apply { if (!isFile) writeText("%PDF") }
        return ReviewVersionStamper({ null }, { library }, { _, _ -> false }, { error("no hashing expected") },
            documentFile = { id, stem -> lookups += id to stem; paper.takeIf { stem == "paper" || id == documentId } },
            librarySourceBuilt = { file -> file.name == "paper.pdf" })
    }

    private fun stampOf(payload: JsonObject) = ReviewVersionStamp.of(payload)

    /** A new top-level comment on paper.pdf as the reader saves it, with a field this client does not know. */
    private fun created(): JsonObject {
        val raw = JsonParser.parseString("""{"id":"root","page":4,"rect":[100,300,120,280],"contents":"Why?",""" +
            """"updated_at":"2026-10-22T10:00:00Z","deleted":false,"document_id":"$documentId","pdf_sha256":"$viewed"}""").asJsonObject
        return canonicalCommentForAnnotation(raw, "paper", null, null)!!.apply { addProperty("desktop_extra", "kept") }
    }

    @Test fun aNewCommentRecordsThePdfTheReaderShows() {
        val payload = FeedbackVersionStamps.stampLogicalComment(created(), stamper(), viewed, "paper.pdf")
        assertEquals(ReviewVersionStamp(viewed, true), stampOf(payload))
        // Same key and shape as review_event, at the top level.
        assertEquals(JsonParser.parseString("""{"pdf_sha256":"$viewed","source_built":true}"""), payload.get("review_version"))
        assertEquals("kept", payload.get("desktop_extra").asString)
    }

    @Test fun anEditElsewhereRecordsTheDocumentsCurrentVersionAndReplacesTheOldStamp() {
        val saved = FeedbackVersionStamps.stampLogicalComment(created(), stamper(), viewed, "paper.pdf")
        // The comment list edits it later, away from the reader: the comment's own document is looked up.
        val edit = logicalCommentMutationPayload(saved, "root", deleted = false, contents = "Why not?")!!
        assertEquals(ReviewVersionStamp(viewed, true), stampOf(edit)) // the edit path keeps fields it does not know
        FeedbackVersionStamps.stampLogicalComment(edit, stamper())
        assertEquals(ReviewVersionStamp(library, true), stampOf(edit))
        assertEquals(documentId to "paper", lookups.last())
        assertEquals("kept", edit.get("desktop_extra").asString)
    }

    @Test fun aReplyRecordsTheVersionOfItsThreadsDocument() {
        val root = created()
        val reply = logicalCommentReplyPayload(root, "reply-1", "Because.", "Me", "2026-10-22T10:05:00Z")!!
        FeedbackVersionStamps.stampLogicalComment(reply, stamper(), pdfFilename = "project.thoughts-project")
        assertEquals(ReviewVersionStamp(library, true), stampOf(reply))
    }

    @Test fun deletionsAreNotStampedAndKeepTheirOldStamp() {
        val saved = FeedbackVersionStamps.stampLogicalComment(created(), stamper(), viewed, "paper.pdf")
        val deleted = logicalCommentMutationPayload(saved, "root", deleted = true)!!
        FeedbackVersionStamps.stampLogicalComment(deleted, stamper(), library, "paper.pdf")
        assertEquals(ReviewVersionStamp(viewed, true), stampOf(deleted))
        val fresh = created().apply { addProperty("deleted", true) }
        FeedbackVersionStamps.stampLogicalComment(fresh, stamper(), viewed, "paper.pdf")
        assertFalse(fresh.has("review_version"))
    }

    @Test fun aStandaloneNoteIsAboutNoDocument() {
        val note = JsonParser.parseString("""{"schema_version":1,"comment_id":"n","thread_root_id":"n","parent_id":null,""" +
            """"subtype":"Note","contents":"Buy milk","origin":null,"scope":{"state":"standalone"},"deleted":false}""").asJsonObject
        FeedbackVersionStamps.stampLogicalComment(note, stamper(), viewed, "paper.pdf")
        assertFalse(note.has("review_version"))
    }

    @Test fun anUnknownVersionIsLeftOutAndRemovesAnOlderStamp() {
        val other = created().apply {
            getAsJsonObject("origin").addProperty("collection", "elsewhere")
            getAsJsonObject("origin").addProperty("document_id", "")
        }
        FeedbackVersionStamps.stampLogicalComment(other, stamper())
        assertFalse(other.has("review_version"))
        // An edit on a version that is not known: the old stamp would name a version the edit was not made on.
        val stamped = other.deepCopy().apply { add("review_version", ReviewVersionStamp(viewed, false).toJson()) }
        FeedbackVersionStamps.stampLogicalComment(stamped, stamper())
        assertFalse(stamped.has("review_version"))
        val noLibrary = other.deepCopy().apply { add("review_version", ReviewVersionStamp(viewed, false).toJson()) }
        FeedbackVersionStamps.stampLogicalComment(noLibrary, null)
        assertFalse(noLibrary.has("review_version"))
        assertEquals("kept", noLibrary.get("desktop_extra").asString)
    }

    @Test fun aSourceCommentOutsideTheLibraryIndexCountsAsSourceBuilt() {
        val linked = created().apply {
            getAsJsonObject("origin").addProperty("collection", "notes")
            getAsJsonObject("origin").addProperty("document_id", "")
            add("scope", JsonParser.parseString("""{"state":"source_linked","anchor_id":"x"}"""))
        }
        FeedbackVersionStamps.stampLogicalComment(linked, stamper(), viewed, "notes.pdf")
        assertEquals(ReviewVersionStamp(viewed, true), stampOf(linked))
        val pdfOnly = created().apply {
            getAsJsonObject("origin").addProperty("collection", "notes")
            getAsJsonObject("origin").addProperty("document_id", "")
        }
        FeedbackVersionStamps.stampLogicalComment(pdfOnly, stamper(), viewed, "notes.pdf")
        assertEquals(ReviewVersionStamp(viewed, false), stampOf(pdfOnly))
    }

    @Test fun anEditOfAnUnknownCommentShapeNeverThrows() {
        val odd = JsonParser.parseString("""{"origin":"not an object","scope":[1],"deleted":"maybe"}""").asJsonObject
        FeedbackVersionStamps.stampLogicalComment(odd, stamper())
        assertNull(stampOf(odd))
        assertTrue(File(temporary.root, "paper.pdf").isFile)
    }
}

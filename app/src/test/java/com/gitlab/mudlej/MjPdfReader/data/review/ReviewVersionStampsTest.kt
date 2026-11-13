// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.graphics.RectF
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewVersionStampsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @After fun forget() = ReviewVersionStamper.forgetHashesForTesting()

    private val card = ReviewCard("c", "approved", "paper", "a".repeat(64), 1, "b".repeat(64), "q", RectF(), null, "")

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun theLibrarysVouchedHashIsUsedWithoutReadingThePdf() {
        val pdf = temporary.newFile("paper.pdf").apply { writeText("%PDF built") }
        var hashed = 0
        val stamper = ReviewVersionStamper({ pdf }, { "C".repeat(64) }, { _, _ -> true }, { hashed++; null })
        assertEquals(ReviewVersionStamp("c".repeat(64), true), stamper.stamp(card))
        assertEquals(0, hashed)
    }

    @Test fun anotherPdfIsHashedOnceUntilItChanges() {
        val pdf = temporary.newFile("import.pdf").apply { writeText("%PDF one") }
        var hashed = 0
        val stamper = ReviewVersionStamper({ pdf }, { null }, { _, _ -> false }, { file -> hashed++; com.gitlab.mudlej.MjPdfReader.core.io.computeSha256(file) })
        assertEquals(ReviewVersionStamp(sha("%PDF one".toByteArray()), false), stamper.stamp(card))
        assertEquals(ReviewVersionStamp(sha("%PDF one".toByteArray()), false), stamper.stamp(card))
        assertEquals(1, hashed)
        pdf.writeText("%PDF two, longer")
        pdf.setLastModified(pdf.lastModified() + 5_000)
        assertEquals(sha("%PDF two, longer".toByteArray()), stamper.stamp(card)?.pdfSha256)
        assertEquals(2, hashed)
    }

    @Test fun aCardDrawnOnTheDisplayedPdfUsesItsHash() {
        var hashed = 0
        val pdf = temporary.newFile("shown.pdf").apply { writeText("%PDF") }
        val stamper = ReviewVersionStamper({ pdf }, { null }, { _, _ -> true }, { hashed++; null })
        assertEquals(ReviewVersionStamp("f".repeat(64), true), stamper.stamp(card, "F".repeat(64)))
        assertEquals(0, hashed)
        // Not on the phone: the displayed hash, source-built from the card's own links.
        assertEquals(ReviewVersionStamp("f".repeat(64), false),
            ReviewVersionStamper({ null }, { null }, { _, _ -> true }).stamp(card, "f".repeat(64)))
        assertNull(ReviewVersionStamper({ null }, { null }, { _, _ -> true }).stamp(card, "0".repeat(64)))
    }

    @Test fun noPdfOrAFailingHashMeansNoStamp() {
        assertNull(ReviewVersionStamper({ null }, { "c".repeat(64) }, { _, _ -> true }).stamp(card))
        assertNull(ReviewVersionStamper({ File(temporary.root, "missing.pdf") }, { "c".repeat(64) }, { _, _ -> true }).stamp(card))
        val pdf = temporary.newFile("broken.pdf")
        assertNull(ReviewVersionStamper({ pdf }, { null }, { _, _ -> true }, { error("unreadable") }).stamp(card))
        assertNull(ReviewVersionStamper({ error("no library") }, { null }, { _, _ -> true }).stamp(card))
    }

    @Test fun theEventFieldRoundTrips() {
        val stamp = ReviewVersionStamp("d".repeat(64), true)
        assertEquals(JsonParser.parseString("""{"pdf_sha256":"${"d".repeat(64)}","source_built":true}"""), stamp.toJson())
        val event = JsonParser.parseString("""{"review_version":${stamp.toJson()}}""").asJsonObject
        assertEquals(stamp, ReviewVersionStamp.of(event))
        assertNull(ReviewVersionStamp.of(JsonParser.parseString("""{"review_version":{"pdf_sha256":"x"}}""").asJsonObject))
        assertNull(ReviewVersionStamp.of(JsonParser.parseString("{}").asJsonObject))
    }
    // Feedback on a document (comments, replies): the same stamp, worked out for the document's PDF.

    @Test fun aDocumentsViewedHashIsUsedAsItIsWithoutReadingThePdf() {
        val pdf = temporary.newFile("paper.pdf").apply { writeText("%PDF built") }
        var hashed = 0
        var trusted = 0
        val stamper = ReviewVersionStamper({ null }, { trusted++; "c".repeat(64) }, { _, _ -> false }, { hashed++; null },
            documentFile = { _, stem -> pdf.takeIf { stem == "paper" } }, librarySourceBuilt = { true })
        assertEquals(ReviewVersionStamp("e".repeat(64), true), stamper.stampDocument(null, "paper", "E".repeat(64)))
        assertEquals(0, hashed)
        assertEquals(0, trusted)
        // A PDF outside the Library: the viewed hash, and the caller's fallback for the kind.
        assertEquals(ReviewVersionStamp("e".repeat(64), true), stamper.stampDocument(null, "elsewhere", "e".repeat(64), sourceBuiltFallback = true))
    }

    @Test fun aDocumentWithoutAViewedHashGoesThroughTheTrustedThenCachedPath() {
        val pdf = temporary.newFile("import.pdf").apply { writeText("%PDF one") }
        var hashed = 0
        var vouched: String? = "c".repeat(64)
        val ids = mutableListOf<String>()
        val stamper = ReviewVersionStamper({ null }, { vouched }, { _, _ -> false },
            { file -> hashed++; com.gitlab.mudlej.MjPdfReader.core.io.computeSha256(file) },
            documentFile = { id, stem -> ids += id; pdf.takeIf { id == "doc-1" || stem == "import" } }, librarySourceBuilt = { false })
        assertEquals(ReviewVersionStamp("c".repeat(64), false), stamper.stampDocument("doc-1", ""))
        assertEquals(listOf("doc-1"), ids)
        vouched = null
        // The placeholder of comments whose PDF was unknown is no version.
        assertEquals(sha("%PDF one".toByteArray()), stamper.stampDocument(null, "import", "0".repeat(64))?.pdfSha256)
        assertEquals(sha("%PDF one".toByteArray()), stamper.stampDocument(null, "import")?.pdfSha256)
        assertEquals(1, hashed)
    }

    @Test fun aDocumentThatIsNotOnThePhoneHasNoStamp() {
        val stamper = ReviewVersionStamper({ null }, { "c".repeat(64) }, { _, _ -> true },
            documentFile = { _, _ -> File(temporary.root, "missing.pdf") })
        assertNull(stamper.stampDocument("doc", "missing"))
        assertNull(stamper.stampDocument(null, null, "not a hash"))
        assertNull(ReviewVersionStamper({ null }, { null }, { _, _ -> true }, documentFile = { _, _ -> error("no library") })
            .stampDocument("doc", "paper"))
    }
}

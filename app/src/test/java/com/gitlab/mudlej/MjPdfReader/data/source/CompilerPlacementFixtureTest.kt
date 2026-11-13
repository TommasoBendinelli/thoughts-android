// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.logicalCommentOverlaySpecs
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Same compiler-produced PDF/artifact consumed by the Desktop native-reader test. */
class CompilerPlacementFixtureTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/compiler-comments/$name")!!.readBytes()
    private fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    private val artifact get() = JsonParser.parseString(bytes("artifact.json").decodeToString()).asJsonObject
    private val expected get() = JsonParser.parseString(bytes("expectations.json").decodeToString()).asJsonObject
    private fun pdfs() = artifact.getAsJsonObject("pdf").let {
        mapOf("manual.pdf" to OfflineBundlePdf("manual.pdf", it.get("pdf_sha256").asString, it.get("page_count").asInt, emptyList()))
    }
    private fun roots(): List<OfflineLogicalComment> {
        val correspondences = artifact.getAsJsonArray("review_correspondences").map { parseReviewCorrespondence(it.asJsonObject, pdfs()) }.associateBy { it.itemId }
        return artifact.getAsJsonArray("logical_comments").map { value ->
            val p = value.asJsonObject
            val id = p.get("comment_id").asString
            val c = correspondences.getValue(id)
            OfflineLogicalComment(id, id, null, 1, false, "source_linked", c.sourceTargets.first().sourceFile,
                c.sourceTargets.first().lineStart, "manual", 0, p.toString(), c)
        }
    }

    @Test fun ingestsRealCompilerProofsAndKeepsEveryFragment() {
        assertEquals(expected.get("artifact_sha256").asString, sha(bytes("artifact.json")))
        assertEquals(expected.get("pdf_sha256").asString, sha(bytes("corpus.pdf")))
        assertEquals(expected.get("source_sha256").asString, sha(bytes("corpus.tex")))
        val all = artifact.getAsJsonArray("review_correspondences").map { parseReviewCorrespondence(it.asJsonObject, pdfs()) }
        assertTrue(all.all { it.locations.isNotEmpty() })
        expected.getAsJsonObject("point_pages").entrySet().forEach { (id, page) ->
            assertEquals(page.asInt, all.single { it.itemId == id }.locations.single().page)
        }
        assertEquals(setOf(1, 2, 3), all.single { it.itemId == "p1" }.locations.map { it.page }.toSet())
        val cell = roots().single { it.id == "cell" }
        assertEquals(2, cell.locations.size)
        val overlays = logicalCommentOverlaySpecs(listOf(cell), "manual.pdf", expected.get("pdf_sha256").asString)
        assertEquals(2, overlays.size)
        cell.locations.zip(overlays).forEach { (location, overlay) ->
            assertEquals(location.regions.single()[0], overlay.left)
            assertEquals(location.regions.single()[1], overlay.top)
        }
        assertTrue(logicalCommentOverlaySpecs(listOf(cell), "manual.pdf", "f".repeat(64)).isEmpty())
    }

    @Test fun latestEditsRepliesAndTombstonesPreserveCompilerGeometry() {
        val root = roots().single { it.id == "cell" }
        val edit = JsonParser.parseString(root.payloadJson).asJsonObject.apply {
            addProperty("revision", 8); addProperty("contents", "Latest local edit")
            remove("locations")
        }
        val reply = edit.deepCopy().apply {
            addProperty("comment_id", "reply"); addProperty("parent_id", "cell"); addProperty("revision", 1)
            addProperty("contents", "Reply from the other device")
        }
        val updated = OfflineOverviewSourceSnapshot(logicalComments = listOf(root)).withLogicalPayloads(listOf(edit, reply))
        assertEquals(2, updated.logicalComments.size)
        assertTrue(updated.logicalComments.all { it.locations == root.locations && it.correspondence == root.correspondence })
        assertEquals("Latest local edit", JsonParser.parseString(updated.logicalComments.single { it.id == "cell" }.payloadJson).asJsonObject.get("contents").asString)
        val deleted = edit.deepCopy().apply { addProperty("revision", 9); addProperty("deleted", true) }
        val removed = updated.withLogicalPayloads(listOf(deleted))
        assertTrue(removed.logicalComments.all { it.deleted })
        assertTrue(logicalCommentOverlaySpecs(removed.logicalComments, "manual.pdf", expected.get("pdf_sha256").asString).isEmpty())
    }

    @Test fun rejectsWrongPdfSourceAndMarkerAndSeparatesCoincidentPoints() {
        val base = artifact.getAsJsonArray("review_correspondences").first { it.asJsonObject.get("item_id").asString == "cell" }.asJsonObject
        for (field in listOf("pdf_sha256", "source_sha256")) {
            val bad = base.deepCopy()
            bad.getAsJsonArray("locations")[0].asJsonObject.getAsJsonObject("proof").addProperty(field, "f".repeat(64))
            assertThrows(IllegalArgumentException::class.java) { parseReviewCorrespondence(bad, pdfs()) }
        }
        val points = roots().filter { it.id in listOf("point-a", "point-b") }
        val overlays = logicalCommentOverlaySpecs(points.reversed(), "manual.pdf", expected.get("pdf_sha256").asString)
        assertEquals(2, overlays.size)
        assertEquals(overlays[0].sourceX, overlays[1].sourceX)
        assertEquals(overlays[0].sourceY, overlays[1].sourceY)
        assertNotEquals(overlays[0].left, overlays[1].left)
        assertEquals(overlays, logicalCommentOverlaySpecs(points, "manual.pdf", expected.get("pdf_sha256").asString))
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SourceAnnotationIndexTest {
    private fun index(source: String, path: String = "docs-test/tex/source/a.tex", markdown: Boolean = false): JsonObject {
        val scanned = SourceAnnotationMarkup.scan(source, markdown).apply { addProperty("project", "docs-test") }
        return JsonObject().apply {
            addProperty("schema_version", 1)
            add("files", JsonObject().apply { add(path, scanned) })
            add("projects", JsonObject().apply { addProperty("docs-test", true) })
        }
    }
    private fun locate(index: JsonObject, type: String, id: String) = SourceAnnotationIndex.locate(index, type, id, "docs-test")

    @Test fun allTypesAndInclusiveRanges() {
        val source = "\\CommentAnchor{note}{Text.}\n\\CommentHighlight{highlight}{Why.}{First\nsecond}\n" +
            "\\CommentStrikethrough{strike}{Why.}{Old}\n\\ReviewCardMaskStart{card}{0}%\nAnswer.\n\\ReviewCardMaskEnd{card}{0}%\n" +
            "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{proposal}{Why.}{Old\ntext}{New\ntext}\n"
        val index = index(source)
        for ((type, id) in listOf("comment" to "note", "highlight" to "highlight", "strikethrough" to "strike", "review_card" to "card", "proposal" to "proposal"))
            assertEquals("linked", locate(index, type, id).get("state").asString)
        assertEquals(1, locate(index, "comment", "note").get("end_line").asInt)
        assertEquals(3, locate(index, "highlight", "highlight").get("end_line").asInt)
        assertEquals(5, locate(index, "review_card", "card").get("start_line").asInt)
        assertEquals(7, locate(index, "review_card", "card").get("end_line").asInt)
        assertEquals(10, locate(index, "proposal", "proposal").get("end_line").asInt)
    }

    @Test fun movesDuplicatesAndIncompleteCoverage() {
        val moved = index("\\CommentAnchor{same}{Note.}", "docs-test/tex/source/b.tex")
        assertEquals("docs-test/tex/source/b.tex", locate(moved, "comment", "same").get("source_file").asString)
        val files = moved.getAsJsonObject("files")
        files.add("docs-test/tex/source/a.tex", files.entrySet().first().value.deepCopy())
        assertEquals("ambiguous", locate(moved, "comment", "same").get("state").asString)
        moved.add("projects", JsonObject())
        assertEquals("unavailable", locate(moved, "comment", "same").get("state").asString)
        assertEquals("missing", locate(index("Gone."), "comment", "same").get("state").asString)
        assertEquals("unavailable", SourceAnnotationIndex.locate(index("\\CommentAnchor{same}{Note.}"), "comment", "same", "another-project").get("state").asString)
    }

    @Test fun literalsBrokenMarkersAndMarkdownRanges() {
        val definitions = SourceAnnotationMarkup.scan("\\providecommand{\\ReviewCardMaskStart}[2]{}\n" +
            "\\providecommand{\\agentProposedEdit}[5][]{#5}\n\\def\\ReviewCardMaskEnd#1#2{}\n" +
            "\\newcommand{\\example}{\\ReviewCardMaskStart{example}{0}}\n\\CommentAnchor{live}{Text.}\n", false)
        assertTrue(definitions.get("valid").asBoolean)
        assertEquals(1, definitions.getAsJsonObject("locations").size())
        val literal = SourceAnnotationMarkup.scan("\\begin{DocCode}\n\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{example}{Why}{Old}{New}\n\\ReviewCardMaskStart{example}{0}\n\\end{DocCode}\n", false)
        assertTrue(literal.get("valid").asBoolean)
        assertEquals(0, literal.getAsJsonObject("locations").size())
        assertFalse(SourceAnnotationMarkup.scan("\\ReviewCardMaskStart{card}{0}\nAnswer.\n", false).get("valid").asBoolean)
        assertFalse(SourceAnnotationMarkup.scan("\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{broken}{Why}{Old}{", false).get("valid").asBoolean)
        val markdown = "<!-- thoughts:v1:meta {\"key\":\"comment:note:s1\",\"contents\":\"Note.\"} -->\n" +
            "<!-- thoughts:v1:start comment:note:s1 -->\nFirst.\nSecond.\n<!-- thoughts:v1:end comment:note:s1 -->\n"
        val location = locate(index(markdown, "docs-test/markdown/source/a.md", true), "comment", "note")
        assertEquals(2, location.get("start_line").asInt)
        assertEquals(5, location.get("end_line").asInt)
        assertEquals(0, SourceAnnotationMarkup.scan("```\n$markdown```\n", true).getAsJsonObject("locations").size())
    }

    @Test fun persistenceNeverInfersAProposalDecision() {
        val root = Files.createTempDirectory("annotation-index").toFile()
        try {
            val file = File(root, "source-location-index.json")
            val cache = SourceAnnotationIndex.at(file)
            cache.install(index("\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{proposal}{Private explanation.}{Old}{New}"))
            assertFalse(file.readText().contains("Private explanation"))
            assertEquals("linked", cache.locate("proposal", "proposal", "docs-test").get("state").asString)
            cache.invalidate()
            assertEquals("unavailable", cache.locate("proposal", "proposal", "docs-test").get("state").asString)
            cache.install(index("New"))
            val missing = cache.locate("proposal", "proposal", "docs-test")
            assertEquals("missing", missing.get("state").asString)
            assertFalse(missing.has("decision"))
        } finally { root.deleteRecursively() }
    }

    @Test fun cardPassagesExposeLocationsOnlyFromAValidProjectCheck() {
        val root = Files.createTempDirectory("card-source-locations").toFile()
        try {
            val cache = SourceAnnotationIndex.at(File(root, "source-location-index.json"))
            val source = "\\ReviewCardMaskStart{card}{0}%\nFirst.\n\\ReviewCardMaskEnd{card}{0}%\n" +
                "Gap.\n\\ReviewCardMaskStart{card}{1}%\nSecond.\n\\ReviewCardMaskEnd{card}{1}%\n"
            cache.install(index(source))
            // Two segments of one card in one file are its passages; the first one locates it.
            assertEquals("linked", cache.locate("review_card", "card", "docs-test").get("state").asString)
            assertEquals(listOf(1 to 3, 5 to 7), cache.locations("review_card", "card", "docs-test")?.map {
                it.get("start_line").asInt to it.get("end_line").asInt
            })
            cache.invalidate()
            assertNull(cache.locations("review_card", "card", "docs-test"))
        } finally { root.deleteRecursively() }
    }
    @Test fun restartRestoresReadOnlyLocationsAndReusesOnlyExactSourceBytes() {
        val root = Files.createTempDirectory("saved-source-index").toFile()
        try {
            val file = File(root, "source-location-index.json")
            val path = "docs-test/tex/source/a.tex"
            val source = "\\ReviewCardMaskStart{card}{0}%\nAnswer.\n\\ReviewCardMaskEnd{card}{0}%\n"
            val scanned = index(source)
            val cache = SourceAnnotationIndex.at(file)
            cache.install(scanned)
            val restarted = SourceAnnotationIndex.reloadForTesting(file)
            assertEquals("linked", restarted.rememberedLocation("review_card", "card", "docs-test").get("state").asString)
            assertEquals("unavailable", restarted.locate("review_card", "card", "docs-test").get("state").asString)
            assertNull(restarted.locations("review_card", "card", "docs-test"))
            assertEquals(cache.reviewEvidence("card", "docs-test"), restarted.reviewEvidence("card", "docs-test"))
            val digest = scanned.getAsJsonObject("files").getAsJsonObject(path).get("sha256").asString
            assertNotNull(restarted.cachedScan(path, digest))
            assertNull(restarted.cachedScan(path, "changed"))
            assertTrue(restarted.install(scanned))
            assertEquals("linked", restarted.locate("review_card", "card", "docs-test").get("state").asString)
            restarted.invalidate()
            assertEquals("unavailable", restarted.rememberedLocation("review_card", "card", "docs-test").get("state").asString)
        } finally { root.deleteRecursively() }
    }

    @Test fun watchersUseRecordedDependenciesOnceAndRetainMissingPaths() {
        val shared = OfflineBundleInput("shared.tex", "docs-test/shared.tex", "hash")
        val pdfs = listOf("a", "b").associate { name ->
            "$name.pdf" to OfflineBundlePdf("$name.pdf", "hash", 1, listOf(
                OfflineBundleSegment(1, 1, true, "docs-test/$name.md", null, null, listOf(shared,
                    OfflineBundleInput("style.sty", "docs-test/style.sty", "hash")))
            ))
        }
        val bundle = OfflineSourceBundle("bundle", pdfs,
            mapOf("docs-test/unbuilt.tex" to OfflineBundleSource("docs-test/unbuilt.tex", "hash", "hash", 1)),
            emptyMap(), emptyMap(), emptyMap(), emptySet())
        assertEquals(setOf("docs-test/a.md", "docs-test/b.md", "docs-test/shared.tex"), bundle.trackedSourcePaths().toSet())
        assertEquals(3, bundle.trackedSourcePaths().size)
    }

    @Test fun severalSegmentsOfOneCardInOneFileAreLinkedAtTheFirstPassage() {
        val source = "\\ReviewCardMaskStart{card}{1}%\nLower.\n\\ReviewCardMaskEnd{card}{1}%\n" +
            "Middle.\n\\ReviewCardMaskStart{card}{0}%\nUpper.\n\\ReviewCardMaskEnd{card}{0}%\n"
        val location = locate(index(source), "review_card", "card")
        assertEquals("linked", location.get("state").asString)
        assertEquals(1, location.get("start_line").asInt)
        assertEquals(2, location.get("segment_count").asInt)
        // The same card in two files is still ambiguous.
        val twoFiles = index(source)
        val files = twoFiles.getAsJsonObject("files")
        files.add("docs-test/tex/source/b.tex", files.entrySet().first().value.deepCopy())
        assertEquals("ambiguous", locate(twoFiles, "review_card", "card").get("state").asString)
    }
}

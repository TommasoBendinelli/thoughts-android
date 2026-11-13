// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundleInput
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundlePdf
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundleSegment
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewDocumentNamesTest {
    private val catalogue = JsonParser.parseString("""[{"documents":[
        {"filename":"template.pdf","source_file":"docs/tex/source/thoughts_manual.tex","name":"thoughts manual","sha256":"current","start_page":1,"page_count":6},
        {"filename":"template.pdf","source_file":"docs/tex/source/thoughts_internals.tex","name":"thoughts internals","sha256":"current","start_page":7,"page_count":8}
    ]}]""").asJsonArray
    private fun segment(name: String, start: Int, count: Int) = OfflineBundleSegment(
        start, count, true, "docs/tex/source/thoughts_$name.tex", null, null,
        listOf(OfflineBundleInput("", "docs/tex/fragments/$name/topic.tex", "hash")),
    )
    private val names = ReviewDocumentNames(catalogue, mapOf("template.pdf" to OfflineBundlePdf(
        "template.pdf", "current", 14, listOf(segment("manual", 1, 6), segment("internals", 7, 8)),
    )), emptyMap())
    private fun card(page: Int, hash: String, sources: List<String> = emptyList()) = ReviewCard(
        id = "card", status = "approved", pdfStem = "template", pdfSha256 = hash,
        page = page, pageVisualSha256 = "visual", question = "Question", rect = RectF(),
        schedule = null, reanchorState = "needs_attention", sourceFiles = sources,
    )

    @Test fun `project names come from library membership rather than PDF stems`() {
        val projects = com.google.gson.JsonParser.parseString("""[
            {"id":"project-id","name":"Study project","target":"template.pdf","documents":[]}
        ]""").asJsonArray
        val resolver = ReviewDocumentNames(projects, emptyMap(), emptyMap())
        assertEquals("Study project", resolver.projectName(card(1, "old")))
        assertEquals("", ReviewDocumentNames(com.google.gson.JsonArray(), emptyMap(), emptyMap()).projectName(card(1, "old")))
    }

    @Test fun `indexed names retain ordered distinct memberships and fallback IDs`() {
        val projects = JsonParser.parseString("""[
            {"id":"a","name":"Shared","target":"template.pdf","documents":[{"filename":"template.pdf","name":"First"}]},
            {"id":"b","name":"Shared","documents":[{"filename":"template.pdf"}]},
            {"id":"fallback","documents":[{"filename":"template.pdf"}]},
            {"id":"unrelated","target":"another.pdf","documents":[{"filename":"another.pdf","name":"Wrong"}]}
        ]""").asJsonArray
        val resolver = ReviewDocumentNames(projects, emptyMap(), emptyMap())
        assertEquals("Shared\nfallback", resolver.projectName(card(1, "old")))
        assertEquals("template.pdf", resolver.name(card(1, "old")))
    }

    @Test fun `project choices retain stable IDs and shared PDF membership`() {
        val projects = JsonParser.parseString("""[
            {"id":"first","name":"Shared","target":"template.pdf","documents":[]},
            {"id":"second","name":"Shared","documents":[{"filename":"template.pdf"}]},
            {"id":"unused","name":"Unrelated","target":"other.pdf","documents":[]}
        ]""").asJsonArray
        val choices = ReviewDocumentNames(projects, emptyMap(), emptyMap())
            .projectsFor(setOf("template.pdf"))
        assertEquals(listOf("first", "second"), choices.map { it.id })
        assertEquals(setOf("template.pdf"), choices[0].filenames)
        assertEquals(setOf("template.pdf"), choices[1].filenames)
    }

    @Test fun `stale card uses source membership despite changed page boundaries`() {
        assertEquals("thoughts internals", names.name(card(2, "old", listOf("docs/tex/fragments/internals/topic.tex"))))
        assertEquals("thoughts manual", names.name(card(9, "old", listOf("docs/tex/fragments/manual/topic.tex"))))
    }
    @Test fun `current unanchored cards resolve both document boundaries`() {
        assertEquals("thoughts manual", names.name(card(6, "current")))
        assertEquals("thoughts internals", names.name(card(7, "current")))
    }
    @Test fun `stale page alone must not assign a current document`() {
        assertEquals("template.pdf", names.name(card(9, "old")))
    }
    @Test fun `removed document retains its source name instead of another document at same page`() {
        assertEquals("thoughts markdown design", names.name(card(14, "old", listOf("docs/tex/source/thoughts_markdown_design.tex"))))
    }
    @Test fun `removed default folder document retains its file name`() {
        val folder = ".thoughts/projects/${"a".repeat(64)}/documents"
        assertEquals("weekly plan", names.name(card(14, "old", listOf("$folder/notes/weekly_plan.md"))))
        // A first segment alone is not a default folder.
        assertEquals("template.pdf", names.name(card(14, "old", listOf(".thoughts/notes/weekly_plan.md"))))
    }
    @Test fun `catalogue name takes precedence over derived source title`() {
        val renamed = catalogue.deepCopy()
        renamed[0].asJsonObject.getAsJsonArray("documents")[0].asJsonObject.addProperty("name", "Renamed manual")
        val resolver = ReviewDocumentNames(renamed, emptyMap(), emptyMap())
        assertEquals("Renamed manual", resolver.name(card(1, "old", listOf("docs/tex/source/thoughts_manual.tex"))))
    }
}

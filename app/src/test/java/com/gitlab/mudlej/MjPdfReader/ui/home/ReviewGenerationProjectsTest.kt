// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ReviewGenerationProjectsTest {
    @Test fun renamedProjectUsesCompleteTargetAndChapterSelectsItsParent() {
        val catalogue = JsonParser.parseString("""[
            {"id":"source:docs-concepts","name":"My concepts","target":"concepts.pdf",
             "documents":[{"filename":"chapter.pdf"},{"filename":"reference.pdf"}]},
            {"id":"empty","name":"Empty","target":"","documents":[]},
            {"id":"stale","name":"Stale","target":"old.pdf","documents":[]}
        ]""").asJsonArray
        val choices = ReviewGenerationProjects.choices(catalogue,
            mapOf("concepts.pdf" to "current", "chapter.pdf" to "chapter", "reference.pdf" to "reference"))
        assertEquals(1, choices.size)
        val choice = choices.single()
        assertEquals("My concepts", choice.toString())
        assertEquals("concepts.pdf", choice.filename)
        assertEquals(listOf("chapter.pdf", "concepts.pdf", "reference.pdf"), choice.basicDocumentFilenames)
        assertTrue(choice.containsPdf("chapter.pdf"))
        assertFalse(choice.containsPdf(null))
    }

    @Test fun publicationsWithoutProjectsDoNotCreateReviewChoices() {
        assertTrue(ReviewGenerationProjects.choices(com.google.gson.JsonArray(),
            mapOf("paper.pdf" to "current")).isEmpty())
    }

    @Test fun identicalProjectNamesRetainDistinctTargets() {
        val catalogue = JsonParser.parseString("""[
            {"id":"one","name":"Research","target":"one.pdf","documents":[]},
            {"id":"two","name":"Research","target":"two.pdf","documents":[]}
        ]""").asJsonArray
        val choices = ReviewGenerationProjects.choices(catalogue, mapOf("one.pdf" to "a", "two.pdf" to "b"))
        assertEquals(2, choices.map { it.label }.toSet().size)
        assertEquals(listOf("one.pdf", "two.pdf"), choices.map { it.filename })
    }
}

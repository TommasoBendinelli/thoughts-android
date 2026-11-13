// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ReviewProjectMembershipTest {
    @Test fun importedDocumentsAndCombinedTargetShareLibraryIdentity() {
        val projects = JsonParser.parseString("""[
          {"id":"course","target":"project_course.pdf","documents":[
            {"filename":"lecture.pdf"},{"filename":"exercise.pdf"}]},
          {"id":"other","target":"other.pdf","documents":[]}
        ]""").asJsonArray
        assertEquals(mapOf("project_course.pdf" to "course", "lecture.pdf" to "course",
            "exercise.pdf" to "course", "other.pdf" to "other"), reviewProjectsByFilename(projects))
    }

    @Test fun ambiguousDocumentsDoNotSilentlySelectOneProject() {
        val projects = JsonParser.parseString("""[
          {"id":"a","target":"a.pdf","documents":[{"filename":"shared.pdf"}]},
          {"id":"b","target":"b.pdf","documents":[{"filename":"shared.pdf"}]}
        ]""").asJsonArray
        assertEquals(mapOf("a.pdf" to "a", "b.pdf" to "b"), reviewProjectsByFilename(projects))
    }

    @Test fun missingCatalogueDoesNotInferProjectsFromPublications() {
        assertTrue(reviewProjectsByFilename(com.google.gson.JsonArray()).isEmpty())
    }
}

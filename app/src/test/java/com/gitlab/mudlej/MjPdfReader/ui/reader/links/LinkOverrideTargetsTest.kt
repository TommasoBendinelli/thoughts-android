// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.links

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkOverrideTargetsTest {
    @Test fun targetsExcludeTheSourceAndArchivedDocumentsAndCarryTheirProject() {
        val targets = linkOverrideTargets(
            documentIdsByFilename = linkedMapOf(
                "source.pdf" to "src", "fast.pdf" to "fast", "agents.pdf" to "agents", "old.pdf" to "old", "copy.pdf" to "fast",
            ),
            displayNamesByFilename = mapOf("fast.pdf" to " FAST: Efficient Action Tokenization ", "agents.pdf" to ""),
            projectsByDocumentId = mapOf("fast" to "Robotics"),
            archivedFilenames = setOf("old.pdf"),
            sourceDocumentId = "src",
        )
        assertEquals(
            listOf(LinkOverrideTarget("agents", "agents", ""), LinkOverrideTarget("fast", "FAST: Efficient Action Tokenization", "Robotics")),
            targets,
        )
    }

    @Test fun projectNamesComeFromTheCatalogueDocuments() {
        val projects = JsonParser.parseString(
            """[{"name":"Robotics","documents":[{"id":"a"},{"document_id":"b","id":"x"}]},
               {"name":"Other","documents":[{"id":"a"},{"document_id":"c"}]},
               {"name":"","documents":[{"id":"d"}]}, 3]""",
        ).asJsonArray
        assertEquals(mapOf("a" to "Robotics", "b" to "Robotics", "c" to "Other"), projectNamesByDocumentId(projects))
    }
}

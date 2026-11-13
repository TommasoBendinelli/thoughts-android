// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.agent

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.tbend.agent.AgentError

class AgentLibraryTest {
    private val catalogue = JsonParser.parseString(
        """
        [
          {"id": "p-lmlm", "name": "LMLM", "documents": [{"document_id": "id-a"}, {"id": "id-b"}, {"name": "no id"}]},
          {"id": "p-apps", "name": "Apps", "documents": [{"document_id": "id-c"}]},
          {"name": "no id"},
          {"id": "p-empty"}
        ]
        """,
    ).asJsonArray

    private val projects = agentProjects(catalogue)

    private val documents = agentLibraryDocuments(
        documentIdsByFilename = linkedMapOf(
            "fact_masks.pdf" to "id-a",
            "moe.pdf" to "id-b",
            "piano_cover.pdf" to "id-c",
            "old.pdf" to "id-d",
            "copy.pdf" to "id-a",
        ),
        displayNamesByFilename = mapOf("fact_masks.pdf" to "Fact masks", "piano_cover.pdf" to "Piano cover  "),
        projects = projects,
        archivedFilenames = setOf("old.pdf"),
    )

    @Test fun projectsCarryTheirDocumentIdsSortedByName() {
        assertEquals(listOf("Apps", "LMLM", "p-empty"), projects.map { it.name })
        assertEquals(listOf("id-a", "id-b"), projects.first { it.id == "p-lmlm" }.documentIds)
        assertTrue(projects.first { it.id == "p-empty" }.documentIds.isEmpty())
    }

    @Test fun documentsAreLabelledLikeTheLinkPicker() {
        // One entry per id; display name, else the filename without .pdf; sorted by title.
        assertEquals(listOf("Fact masks", "moe", "old", "Piano cover"), documents.map { it.title })
        val moe = documents.first { it.id == "id-b" }
        assertEquals("LMLM", moe.project)
        assertEquals("p-lmlm", moe.projectId)
        assertEquals("moe.pdf", moe.filename)
        val old = documents.first { it.id == "id-d" }
        assertTrue(old.archived)
        assertEquals("", old.project)
        assertEquals("old", old.label())
        assertEquals("Piano cover (Apps)", documents.first { it.id == "id-c" }.label())
    }

    @Test fun foldingIgnoresAccentsAndCase() {
        assertEquals("cafe creme", agentFold("Café CRÈME"))
        assertTrue(agentMatches("Café crème notes", "creme CAFE"))
        assertFalse(agentMatches("Café crème", "tea"))
        assertTrue(agentMatches("anything", "  "))
        assertEquals(listOf("piano", "cover"), agentWords("piano_cover"))
    }

    @Test fun listingFiltersByWordsProjectAndArchive() {
        assertEquals(listOf("Fact masks", "moe", "Piano cover"), filterAgentDocuments(documents, "", "", false).map { it.title })
        assertEquals(4, filterAgentDocuments(documents, "", "", true).size)
        // Words of the title or of the filename.
        assertEquals(listOf("id-c"), filterAgentDocuments(documents, "piano cover", "", false).map { it.id })
        assertEquals(listOf("id-a"), filterAgentDocuments(documents, "fact_masks", "", false).map { it.id })
        // Project by name words or by id.
        assertEquals(listOf("id-a", "id-b"), filterAgentDocuments(documents, "", "lmlm", false).map { it.id })
        assertEquals(listOf("id-c"), filterAgentDocuments(documents, "", "p-apps", false).map { it.id })
        assertTrue(filterAgentDocuments(documents, "", "nowhere", false).isEmpty())
    }

    private fun pick(id: String = "", query: String = "", items: List<AgentDocument> = documents) = agentPick(
        items, id, query, idOf = AgentDocument::id, titleOf = AgentDocument::title,
        searchText = { "${it.title} ${it.filename}" }, describe = { it.label() },
        kind = "document", listCommand = "list_documents",
    )

    private fun pickError(id: String = "", query: String = ""): String = try {
        pick(id, query)
        fail("expected an AgentError")
        ""
    } catch (error: AgentError) {
        error.message.orEmpty()
    }

    @Test fun pickingByIdOrWords() {
        assertEquals("id-b", pick(id = "id-b").id)
        assertEquals("id-c", pick(query = "PIANO").id)
        assertEquals("id-a", pick(query = "masks").id)
        assertTrue(pickError(id = "nope").contains("no document has the id 'nope'; use list_documents"))
        assertTrue(pickError(query = "zebra").contains("no document matches 'zebra'"))
        assertTrue(pickError().contains("give an id or a query"))
    }

    @Test fun anExactTitleWinsAndAmbiguityIsNamed() {
        val items = documents + AgentDocument("id-e", "Fact masks v2", "fact_masks_v2.pdf", "", "", false)
        assertEquals("id-a", pick(query = "fact masks", items = items).id)
        val message = try {
            pick(query = "fact", items = items)
            ""
        } catch (error: AgentError) {
            error.message.orEmpty()
        }
        assertTrue(message, message.startsWith("2 documents match 'fact': Fact masks (LMLM) (id id-a); Fact masks v2 (id id-e)"))
        val first = agentPick(items, "", "fact", idOf = AgentDocument::id, titleOf = AgentDocument::title,
            kind = "note", listCommand = "list_notes", firstWhenAmbiguous = true)
        assertEquals("id-a", first.id)
    }

    @Test fun pagesAreOneBased() {
        assertNull(agentPageIndex(null))
        assertEquals(0, agentPageIndex(1))
        assertEquals(11, agentPageIndex(12))
        try {
            agentPageIndex(0)
            fail("expected an AgentError")
        } catch (error: AgentError) {
            assertTrue(error.message.orEmpty().contains("1-based"))
        }
    }
}

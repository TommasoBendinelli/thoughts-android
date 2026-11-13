// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibraryRenameDocumentTest {
    private fun project() = JsonParser.parseString("""{
      "id":"project-a", "target":"",
      "documents":[
        {"id":"docs-paper/tex/source/main.tex","document_id":"doc-main","name":"main","filename":"paper_main.pdf"},
        {"id":"other.pdf","document_id":"doc-other","name":"other","filename":"other.pdf"}
      ],
      "project_configuration": {"default_folder_id":"default","documents":[],"source_folders":[]}
    }""").asJsonObject

    @Test fun `a document keeps its file and identity and gets the new name`() {
        val project = project()
        renameLibraryDocument(project, "doc-main", "Overview")
        val document = project.getAsJsonArray("documents")[0].asJsonObject
        assertEquals("Overview", document.get("name").asString)
        assertEquals("paper_main.pdf", document.get("filename").asString)
        assertEquals("doc-main", document.get("document_id").asString)
        assertEquals("other", project.getAsJsonArray("documents")[1].asJsonObject.get("name").asString)
        val names = project.getAsJsonObject("project_configuration").getAsJsonObject("document_names")
        assertEquals("Overview", names.get("doc-main").asString)
        assertEquals(1, names.size())
    }

    @Test fun `a document is found by its Library id too`() {
        val project = project()
        renameLibraryDocument(project, "docs-paper/tex/source/main.tex", "Overview")
        assertEquals("Overview", project.getAsJsonObject("project_configuration")
            .getAsJsonObject("document_names").get("doc-main").asString)
    }

    @Test fun `a document no longer in the project changes nothing`() {
        val project = project()
        renameLibraryDocument(project, "missing", "Overview")
        assertEquals(project(), project)
        assertFalse(project.getAsJsonObject("project_configuration").has("document_names"))
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIncludeSourceTest {
    private val folder = ".thoughts/projects/${"a".repeat(64)}/documents"
    private val other = ".thoughts/projects/${"b".repeat(64)}/documents"
    private fun project() = JsonParser.parseString("""{
      "id":"project-a", "target":"", "documents":[],
      "project_configuration": {
        "default_folder_id":"default", "rendering":{}, "documents":[],
        "source_folders":[
          {"folder_id":"default","path":"$folder"},
          {"folder_id":"connected","path":"docs-paper"}
        ]
      }
    }""").asJsonObject
    private fun op(path: String) = JsonObject().apply {
        addProperty("action", "include_source"); addProperty("source_file", path); addProperty("new_document_id", "new-doc")
    }

    @Test fun `only document candidates of this Project are offered`() {
        val project = project()
        for (path in listOf("$folder/notes.md", "$folder/notes/a.markdown", "$folder/paper/main.tex", "docs-paper/tex/source/main.tex", "docs-paper/notes.md"))
            assertTrue(path, isIncludableSource(project, path))
        for (path in listOf("$folder/thoughts-project.json", "$folder/imported.pdf", "$folder/figure.png", "$folder/style.sty",
            "$other/notes.md", "docs-detached/notes.md", ".thoughts/notes.md", "docs-paper/figure.pdf"))
            assertFalse(path, isIncludableSource(project, path))
        assertThrows(IllegalArgumentException::class.java) { requireIncludableSource(project, "$folder/thoughts-project.json") }
        assertThrows(IllegalArgumentException::class.java) { requireIncludableSource(project, "$other/notes.md") }
        requireIncludableSource(project, "$folder/notes/a.md")
        assertTrue(inDefaultFolder(project, "$folder/notes/a.md"))
        assertFalse(inDefaultFolder(project, "docs-paper/notes.md"))
        assertFalse(inDefaultFolder(project, "$other/notes.md"))
    }

    @Test fun `a default folder Markdown document is declared with its real path and output`() {
        val project = project()
        includeDefaultFolderDocument(project, op("$folder/notes/a.md"))
        val configuration = project.getAsJsonObject("project_configuration")
        val declaration = configuration.getAsJsonArray("documents").single().asJsonObject
        assertEquals(JsonParser.parseString(
            """{"document_id":"new-doc","source":{"folder_id":"default","path":"notes/a.md"},"format":"markdown","included":true}"""
        ), declaration)
        // Spec Rule 2 vector: SHA-256 over "<owner>/<relative>".
        val output = "document_78dafde3991335de51b3.pdf"
        assertEquals(output, defaultFolderDocumentOutput("$folder/notes/a.md"))
        assertEquals(output, configuration.getAsJsonObject("rendering").getAsJsonObject("new-doc").get("output").asString)
        val document = project.getAsJsonArray("documents").single().asJsonObject
        assertEquals("$folder/notes/a.md", document.get("id").asString)
        assertEquals("$folder/notes/a.md", document.get("source_file").asString)
        assertEquals(folder, document.get("source_project").asString)
        assertEquals(output, document.get("filename").asString)
        assertEquals("a", document.get("name").asString)
        assertEquals(0, document.get("start_page").asInt)
        // Adding it again keeps one declaration.
        includeDefaultFolderDocument(project, op("$folder/notes/a.md").apply { addProperty("new_document_id", "again") })
        assertEquals(1, configuration.getAsJsonArray("documents").size())
        assertEquals(1, project.getAsJsonArray("documents").size())
    }

    @Test fun `a default folder LaTeX document keeps its own path`() {
        val project = project()
        includeDefaultFolderDocument(project, op("$folder/paper/main.tex"))
        val configuration = project.getAsJsonObject("project_configuration")
        val declaration = configuration.getAsJsonArray("documents").single().asJsonObject
        assertEquals("latex", declaration.get("format").asString)
        assertEquals("paper/main.tex", declaration.getAsJsonObject("source").get("path").asString)
        assertEquals("document_8bc44f39b6a4a6570fa5.pdf",
            configuration.getAsJsonObject("rendering").getAsJsonObject("new-doc").get("output").asString)
        assertFalse(project.toString().contains("tex/source"))
    }

    @Test fun `a renamed default folder declares the same document and output`() {
        val renamed = folder.removeSuffix("/documents") + "/default"
        val project = JsonParser.parseString(project().toString().replace(folder, renamed)).asJsonObject
        assertTrue(isIncludableSource(project, "$renamed/notes/a.md"))
        assertFalse(isIncludableSource(project, "$folder/notes/a.md"))
        includeDefaultFolderDocument(project, op("$renamed/notes/a.md"))
        val configuration = project.getAsJsonObject("project_configuration")
        assertEquals("notes/a.md", configuration.getAsJsonArray("documents").single().asJsonObject
            .getAsJsonObject("source").get("path").asString)
        // Rule 4: the Library PDF keeps its name across the rename.
        assertEquals("document_78dafde3991335de51b3.pdf",
            configuration.getAsJsonObject("rendering").getAsJsonObject("new-doc").get("output").asString)
        assertEquals(renamed, project.getAsJsonArray("documents").single().asJsonObject.get("source_project").asString)
    }

    @Test fun `two Projects with notes md get distinct outputs`() {
        assertEquals("document_d22ec810b56f6abf9dac.pdf", defaultFolderDocumentOutput("$folder/notes.md"))
        assertEquals("document_ecd1a07df1c1e085f643.pdf", defaultFolderDocumentOutput("$other/notes.md"))
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibrarySourceFoldersTest {
    @Test
    fun cachedCategoriesBecomeConfiguredRootsWithRebasedPaths() {
        val project = JsonParser.parseString("""{
          "project_configuration": {
            "default_folder_id":"default", "documents":[],
            "source_folders":[
              {"folder_id":"default","path":".thoughts/projects/test/documents"},
              {"folder_id":"mixed","path":"docs-mixed"},
              {"folder_id":"empty","path":"docs-empty"}
            ]
          },
          "source_folders":[{"project":"docs-mixed","folder":"My sources"}],
          "sources":[
            {"id":"docs-mixed","name":"Project files","files":[
              {"id":"docs-mixed/.ThoughtIgnore","name":".ThoughtIgnore"}]},
            {"id":"docs-mixed/markdown","name":"Markdown sources","files":[
              {"id":"docs-mixed/markdown/source/note.md","name":"note.md","relative_path":"source/note.md"}]},
            {"id":"docs-mixed/tex","name":"Project sources","files":[
              {"id":"docs-mixed/tex/source/main.tex","name":"main.tex"}]},
            {"id":"docs-mixed/tex/support/remote","kind":"overleaf","files":[
              {"id":"docs-mixed/tex/source/main.tex","name":"main.tex"}]},
            {"id":"docs-detached","files":[{"id":"docs-detached/stray.md"}]}
          ]
        }""").asJsonObject
        val original = project.deepCopy()
        val locations = librarySourceFolders(project).map { it.asJsonObject }
        assertEquals(listOf("default", "mixed", "empty"), locations.map { it.get("folder_id").asString })
        assertEquals(listOf("Default source folder", "My sources", "docs-empty"), locations.map { it.get("name").asString })
        val files = locations[1].getAsJsonArray("files").map { it.asJsonObject }
        assertEquals(listOf(".ThoughtIgnore", "markdown/source/note.md", "tex/source/main.tex"), files.map { it.get("relative_path").asString })
        assertEquals(0, locations[2].getAsJsonArray("files").size())
        assertEquals(original, project)
        project.add("sources", librarySourceFolders(project))
        assertEquals(project.get("sources"), librarySourceFolders(project))
    }

    @Test
    fun defaultFolderIncludesPendingAndExcludedPdfsBeforeSync() {
        val project = JsonParser.parseString("""{
          "project_configuration": {
            "default_folder_id":"default",
            "source_folders":[{"folder_id":"default","path":".thoughts/projects/test/documents"}],
            "documents":[
              {"document_id":"pending","format":"pdf","included":true,"source":{"folder_id":"default","path":"pending.pdf"}},
              {"document_id":"excluded","format":"pdf","included":false,"source":{"folder_id":"default","path":"excluded.pdf"}}
            ]
          },
          "documents":[{"document_id":"pending","size_bytes":123,"local_path":"/local/pending.pdf"}],
          "sources":[]
        }""").asJsonObject
        val roots = librarySourceFolders(project)
        assertEquals(1, roots.size())
        val files = roots[0].asJsonObject.getAsJsonArray("files").map { it.asJsonObject }
        assertEquals(listOf("thoughts-project.json", "pending.pdf", "excluded.pdf"), files.map { it.get("relative_path").asString })
        assertEquals(123, files[1].get("size_bytes").asInt)
        assertEquals("/local/pending.pdf", files[1].get("local_path").asString)
        assertFalse(files[2].has("local_path"))
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSourceFoldersTest {
    @Test fun connectedFoldersComeFromStableProjectConfiguration() {
        val project = JsonParser.parseString("""{
            "id":"new:models", "source_project":"",
            "project_configuration":{
                "default_folder_id":"default-id",
                "source_folders":[
                    {"folder_id":"default-id","path":".thoughts/projects/default/documents"},
                    {"folder_id":"model-id","path":"docs-models"},
                    {"folder_id":"notes-id","path":"docs-notes"}
                ]
            }
        }""").asJsonObject

        assertEquals(listOf("docs-models", "docs-notes"), connectedSourceProjects(project))
        assertEquals(".thoughts/projects/default/documents", defaultSourceFolder(project))
    }

    @Test fun legacyCatalogueDoesNotInferConnectedFolders() {
        val project = JsonParser.parseString("""{
            "id":"new:older", "source_project":"docs-main",
            "source_folders":[{"project":"docs-notes"}]
        }""").asJsonObject

        assertEquals(emptyList<String>(), connectedSourceProjects(project))
    }
}

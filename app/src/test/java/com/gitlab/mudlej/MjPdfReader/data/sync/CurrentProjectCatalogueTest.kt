// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class CurrentProjectCatalogueTest {
    @Test fun unpublishedDocumentsAreThoseUnpublishedAndPublishedNowhere() {
        fun doc(id: String) = JsonObject().apply { addProperty("document_id", id) }
        fun declared(id: String, included: Boolean) = doc(id).apply { addProperty("included", included) }
        val projects = JsonArray().apply {
            add(JsonObject().apply {
                add("documents", JsonArray().apply { add(doc("published")) })
                add("unpublished_documents", JsonArray().apply { add(doc("notes")); add(doc("shared")) })
            })
            // Published in another Project: its cards are still reviewed.
            add(JsonObject().apply { add("documents", JsonArray().apply { add(doc("shared")) }) })
            // Known only from the configuration's declarations.
            add(JsonObject().apply {
                add("documents", JsonArray())
                add("project_configuration", JsonObject().apply {
                    add("documents", JsonArray().apply { add(declared("draft", false)); add(declared("kept", true)) })
                })
            })
        }
        assertEquals(setOf("notes", "draft"), unpublishedDocumentIds(projects))
        assertEquals(emptySet<String>(), unpublishedDocumentIds(JsonArray()))
    }

    @Test fun cachedSyntheticProjectsDoNotBecomeCurrentProjects() {
        val cached = JsonArray().apply { add(JsonObject().apply {
            addProperty("id", "pdf:paper.pdf"); addProperty("name", "Paper")
            add("documents", JsonArray())
        }) }
        assertTrue(currentManifestProjects(cached).isEmpty)
        assertEquals(1, cached.size())
    }

    @Test fun manifestControlsNameMembershipAndOrder() {
        val configuration = emptyProjectConfiguration("new:test", "Authoritative")
        val documents = JsonArray()
        for (id in listOf("first", "second", "extra")) documents.add(JsonObject().apply {
            addProperty("document_id", id); addProperty("name", id)
        })
        for (id in listOf("second", "first")) configuration.getAsJsonArray("documents").add(JsonObject().apply {
            addProperty("document_id", id); addProperty("included", id == "second")
        })
        val cached = JsonArray().apply { add(JsonObject().apply {
            addProperty("id", "new:test"); addProperty("name", "Stale")
            add("project_configuration", configuration); add("documents", documents)
        }) }
        val current = currentManifestProjects(cached).single().asJsonObject
        assertEquals("Authoritative", current.get("name").asString)
        assertEquals(listOf("second"), current.getAsJsonArray("documents").map { it.asJsonObject.get("document_id").asString })
        assertEquals(3, documents.size())
    }

    @Test fun obsoleteConfigurationIsRejected() {
        val configuration = emptyProjectConfiguration("new:test", "Project")
        configuration.addProperty("schema_version", 4)
        val cached = JsonArray().apply { add(JsonObject().apply {
            addProperty("id", "new:test"); add("project_configuration", configuration); add("documents", JsonArray())
        }) }
        assertThrows(IllegalArgumentException::class.java) { currentManifestProjects(cached) }
    }

    @Test fun explicitlyCreatedProjectHasStableFolderIdentity() {
        assertEquals(emptyProjectConfiguration("new:test", "Project"), emptyProjectConfiguration("new:test", "Project"))
        assertNotEquals(emptyProjectConfiguration("new:other", "Project").get("default_folder_id"),
            emptyProjectConfiguration("new:test", "Project").get("default_folder_id"))
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LibraryProjectStoreInstrumentedTest {
    @Test fun queuedChangesFollowTheRenamedDefaultFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-store-default-rename").apply { deleteRecursively(); mkdirs() }
        val projectId = "new:default-rename"
        val key = com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.projectKey(projectId)
        val legacy = ".thoughts/projects/$key/documents"
        val renamed = ".thoughts/projects/$key/default"
        try {
            val project = JsonParser.parseString("""{
                "id":"$projectId", "revision":3, "name":"Notes", "target":"", "documents":[], "sources":[],
                "project_configuration":{
                    "schema_version":5, "kind":"thoughts-project", "id":"$projectId", "name":"Notes",
                    "default_folder_id":"f", "source_folders":[{"folder_id":"f","path":"$renamed"}],
                    "documents":[], "rendering":{}, "document_names":{}
                }
            }""").asJsonObject
            File(root, "state.json").writeText(JsonObject().apply {
                add("projects", com.google.gson.JsonArray().apply { add(project) })
                add("pending", JsonParser.parseString("""[
                    {"operation_id":"o1","project_id":"$projectId","action":"include_source","source_file":"$legacy/notes.md","new_document_id":"d1"},
                    {"operation_id":"o2","project_id":"$projectId","action":"include_source","source_file":"docs-gone/a.md","new_document_id":"d2"},
                    {"operation_id":"o3","project_id":"new:created","action":"create","name":"Created"}
                ]"""))
            }.toString())
            val store = LibraryProjectStore(context, root)
            // A queued change that no longer fits does not hide the catalogue.
            val projects = store.projects().map { it.asJsonObject }
            val notes = projects.single { it.get("id").asString == projectId }
            assertEquals("$renamed/notes.md", notes.getAsJsonArray("documents").single().asJsonObject.get("source_file").asString)
            assertEquals("document_" + java.security.MessageDigest.getInstance("SHA-256").digest("$legacy/notes.md".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(20) + ".pdf",
                notes.getAsJsonArray("documents").single().asJsonObject.get("filename").asString)
            // Rule 5: a Project created in a renamed Library gets a `default` folder.
            val created = projects.single { it.get("id").asString == "new:created" }
            assertEquals(com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.path("new:created", "default"),
                com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.configuredDefaultFolder(created.getAsJsonObject("project_configuration")))
            // The local migration rewrites the stored change once.
            assertTrue(store.rewriteLegacyDefaultFolder())
            assertFalse(store.rewriteLegacyDefaultFolder())
            val state = JsonParser.parseString(File(root, "state.json").readText()).asJsonObject
            assertEquals("$renamed/notes.md", state.getAsJsonArray("pending")[0].asJsonObject.get("source_file").asString)
            assertTrue(com.gitlab.mudlej.MjPdfReader.data.sync.DefaultFolderMigration.fedoraUsesDefault(store.storedCatalogue()))
        } finally { root.deleteRecursively() }
    }

    @Test fun queuedChangesFollowRenamedSourceFolders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-store-source-folder-names").apply { deleteRecursively(); mkdirs() }
        val projectId = "new:source-folder-names"
        val old = "docs-template"
        val renamed = "docs-template-c8ee33"
        val names = com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames
        try {
            // Fedora's catalogue after its rename, M, and changes queued before it.
            val project = JsonParser.parseString("""{
                "id":"$projectId", "revision":3, "name":"Notes", "target":"notes.thoughts-project", "documents":[], "sources":[],
                "source_folders":[{"project":"$renamed","folder":"Template"}],
                "project_configuration":{
                    "schema_version":5, "kind":"thoughts-project", "id":"$projectId", "name":"Notes",
                    "default_folder_id":"d", "source_folders":[
                        {"folder_id":"d","path":".thoughts/projects/${"a".repeat(64)}/default"},
                        {"folder_id":"f","path":"$renamed"}],
                    "documents":[], "rendering":{}, "document_names":{}
                }
            }""").asJsonObject
            File(root, "state.json").writeText(JsonObject().apply {
                add("projects", com.google.gson.JsonArray().apply { add(project) })
                add("source_folder_renames", JsonParser.parseString("""[{"folder_id":"f","old":"$old","new":"$renamed"}]"""))
                add("pending", JsonParser.parseString("""[
                    {"operation_id":"o1","project_id":"$projectId","action":"include_source","source_file":"$old/tex/notes.tex","new_document_id":"d1"},
                    {"operation_id":"o2","project_id":"$projectId","action":"import_html","source_project":"$old","filename":"p.html"}
                ]"""))
            }.toString())
            val store = LibraryProjectStore(context, root)
            val renames = store.storedSourceFolderRenames()
            assertEquals(listOf(com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames.Rename("f", old, renamed)), renames)
            // Once this phone moved its folders, the queued change names the new folder.
            names.installLocal(names.toMap(renames))
            val document = store.projects().single().asJsonObject.getAsJsonArray("documents").single().asJsonObject
            assertEquals("$renamed/tex/notes.tex", document.get("source_file").asString)
            assertTrue(document.get("id").asString.startsWith("$renamed/tex/source/document_"))
            // The local migration rewrites the stored changes once and keeps M.
            assertTrue(store.rewriteSourceFolderNames(names.toMap(renames)))
            assertFalse(store.rewriteSourceFolderNames(names.toMap(renames)))
            val state = JsonParser.parseString(File(root, "state.json").readText()).asJsonObject
            assertEquals("$renamed/tex/notes.tex", state.getAsJsonArray("pending")[0].asJsonObject.get("source_file").asString)
            assertEquals(renamed, state.getAsJsonArray("pending")[1].asJsonObject.get("source_project").asString)
            assertEquals(renames, LibraryProjectStore(context, root).storedSourceFolderRenames())
        } finally {
            names.installLocal(emptyMap())
            root.deleteRecursively()
        }
    }

    @Test fun emptyProjectAndOrderedMutationsSurviveRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-store-test").apply { deleteRecursively(); mkdirs() }
        try {
            val project = JsonObject().apply { addProperty("id", "new:test"); addProperty("revision", 0) }
            val store = LibraryProjectStore(context, root)
            store.enqueue("create", project, JsonObject().apply { addProperty("name", "Reading") })
            var reopened = LibraryProjectStore(context, root)
            var current = reopened.projects().single().asJsonObject
            assertEquals("Reading", current.get("name").asString)
            assertEquals(0, current.getAsJsonArray("documents").size())
            reopened.enqueue("rename", current, JsonObject().apply { addProperty("name", "Papers") })
            reopened = LibraryProjectStore(context, root)
            current = reopened.projects().single().asJsonObject
            assertEquals("Papers", current.get("name").asString)
            assertEquals(2, current.get("revision").asInt)
            reopened.enqueue("remove", current)
            assertEquals(0, LibraryProjectStore(context, root).projects().size())
            assertEquals(3, reopened.pendingCount())
        } finally { root.deleteRecursively() }
    }

    @Test fun identicalPdfImportsBelongToIndependentProjects() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ThoughtsLocalStore.install(context)
        val run = java.util.UUID.randomUUID().toString()
        val root = File(context.cacheDir, "project-import-$run").apply { mkdirs() }
        val ids = listOf("new:$run-a", "new:$run-b")
        try {
            val input = File(root, "paper.pdf").apply { writeText("%PDF-1.4\n% independent project import fixture\n") }
            val store = LibraryProjectStore(context, root)
            for (id in ids) {
                val project = JsonObject().apply { addProperty("id", id); addProperty("revision", 0) }
                store.enqueue("create", project, JsonObject().apply { addProperty("name", id) })
                store.stageImport(project, input, "Paper.pdf")
            }
            val documents = LibraryProjectStore(context, root).projects().map { it.asJsonObject.getAsJsonArray("documents").single().asJsonObject }
            assertNotEquals(documents[0].get("document_id"), documents[1].get("document_id"))
            assertNotEquals(documents[0].get("filename"), documents[1].get("filename"))
            for ((index, document) in documents.withIndex()) {
                val file = File(document.get("local_path").asString)
                assertEquals(ThoughtsLocalStore.projectDocuments(ids[index]), file.parentFile)
                assertArrayEquals(input.readBytes(), file.readBytes())
            }
        } finally {
            root.deleteRecursively()
            ids.forEach { ThoughtsLocalStore.projectDocuments(it).parentFile?.deleteRecursively() }
        }
    }

    @Test fun pendingImportUsesProjectDefaultFolderAndFourManifestFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ThoughtsLocalStore.install(context)
        val projectId = "new:" + java.util.UUID.randomUUID()
        val folderId = java.util.UUID.randomUUID().toString()
        val root = File(context.cacheDir, "project-import-configuration-$projectId").apply { mkdirs() }
        val managed = ThoughtsLocalStore.projectDocuments(projectId)
        try {
            val project = JsonParser.parseString("""{
                "id":"$projectId", "revision":1, "name":"Papers", "source_project":"",
                "documents":[], "sources":[],
                "project_configuration":{
                    "schema_version":5, "kind":"thoughts-project", "id":"$projectId", "name":"Papers",
                    "default_folder_id":"$folderId",
                    "source_folders":[{"folder_id":"$folderId","path":".thoughts/projects/${requireNotNull(managed.parentFile).name}/documents","document_id":"${java.util.UUID.randomUUID()}"}],
                    "documents":[], "rendering":{}, "document_names":{}
                }
            }""").asJsonObject
            File(root, "state.json").writeText(JsonObject().apply {
                add("projects", com.google.gson.JsonArray().apply { add(project) })
                add("pending", com.google.gson.JsonArray())
                addProperty("downloaded", true)
            }.toString())
            val input = File(root, "paper.pdf").apply { writeText("%PDF-1.4\n% fixture\n") }
            val store = LibraryProjectStore(context, root)
            store.stageImport(project, input, "Paper.pdf")

            val pending = store.projects().single().asJsonObject
            val document = pending.getAsJsonArray("documents").single().asJsonObject
            val declaration = pending.getAsJsonObject("project_configuration")
                .getAsJsonArray("documents").single().asJsonObject
            assertEquals(document.get("document_id"), document.get("id"))
            assertEquals(defaultSourceFolder(project) + "/" + document.get("filename").asString,
                document.get("managed_path").asString)
            assertEquals(setOf("document_id", "source", "format", "included"), declaration.keySet())
            assertEquals(folderId, declaration.getAsJsonObject("source").get("folder_id").asString)
            assertEquals(document.get("filename"), declaration.getAsJsonObject("source").get("path"))
            assertEquals("pdf", declaration.get("format").asString)
            assertTrue(declaration.get("included").asBoolean)
        } finally {
            root.deleteRecursively()
            managed.parentFile?.deleteRecursively()
        }
    }

    @Test fun corruptQueueIsPreserved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-store-corruption-test").apply { deleteRecursively(); mkdirs() }
        try {
            val file = File(root, "state.json").apply { writeText("not json") }
            val project = JsonObject().apply { addProperty("id", "new:test") }
            assertTrue(runCatching { LibraryProjectStore(context, root).enqueue("remove", project) }.isFailure)
            assertEquals("not json", file.readText())
        } finally { root.deleteRecursively() }
    }
}

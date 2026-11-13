// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames
import com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames.Rename
import com.gitlab.mudlej.MjPdfReader.data.source.followSourceFolderNames
import com.gitlab.mudlej.MjPdfReader.ui.links.HtmlAttachmentLink
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.tbend.thoughts.sdk.LibrarySnapshot
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/** The SQL the migration runs, on a real SQLite over JDBC with the app's exported Room schema. */
private class JdbcSourceFolderSql(val connection: Connection) : DefaultFolderSql {
    override fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        try { return block().also { connection.commit() } }
        catch (error: Throwable) { connection.rollback(); throw error }
        finally { connection.autoCommit = true }
    }

    override fun query(sql: String, args: Array<Any?>): List<List<Any?>> = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getObject(it).let { value -> if (value is Int) value.toLong() else value } }) }
        }
    }

    override fun update(sql: String, args: Array<Any?>): Int = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

    fun exec(sql: String, vararg args: Any?) { update(sql, arrayOf(*args)) }
    fun one(sql: String, vararg args: Any?): Any? = query(sql, arrayOf(*args)).singleOrNull()?.get(0)
}

class SourceFolderNamesMigrationTest {
    private val old = "docs-template"
    private val new = "docs-template-c8ee33"
    private val importOld = "docs-import-1ed79cc6" + "a".repeat(24)
    private val importNew = "docs-benchmarks-1ed79c"
    private val renames = listOf(Rename("f1", old, new), Rename("f2", importOld, importNew))
    private lateinit var root: File
    private lateinit var files: File
    private lateinit var working: File
    private lateinit var staging: File
    private lateinit var sql: JdbcSourceFolderSql
    private lateinit var projectState: File

    private val sourceEditor get() = File(files, "source-editor")
    private val offline get() = File(sourceEditor, "offline")
    private val proposalReview get() = File(files, "proposal-review")
    private val requests get() = File(files, "thoughts/source-requests")
    private val journal get() = File(root, "no-backup/thoughts-sync/source-folder-names-migration.json")

    private fun sha(text: String) = DefaultFolderMigration.sha256(text)
    private fun write(file: File, text: String) = file.apply { parentFile!!.mkdirs(); writeText(text) }
    private fun json(file: File) = JsonParser.parseString(file.readText()).asJsonObject

    @Before fun setUp() {
        SourceFolderNames.installLocal(emptyMap())
        root = Files.createTempDirectory("source-folder-names-migration").toFile()
        files = File(root, "files")
        working = File(root, "Documents/Thoughts Sources")
        staging = File(files, "thoughts")
        projectState = File(root, "no-backup/thoughts-sync/projects/state.json")
        sql = JdbcSourceFolderSql(DriverManager.getConnection("jdbc:sqlite::memory:"))
        val schema = JsonParser.parseString(File("schemas/com.gitlab.mudlej.MjPdfReader.data.AppDatabase/35.json").readText())
            .asJsonObject.getAsJsonObject("database")
        for (entity in schema.getAsJsonArray("entities")) {
            val value = entity.asJsonObject
            sql.exec(value.get("createSql").asString.replace("\${TABLE_NAME}", value.get("tableName").asString))
        }
    }

    @After fun tearDown() {
        sql.connection.close()
        root.deleteRecursively()
        SourceFolderNames.installLocal(emptyMap())
    }

    private fun migration() = SourceFolderNamesMigration(
        DefaultFolderMigration.Layout(
            journal = journal,
            folderRoots = listOf(working, staging),
            sourceEditor = sourceEditor,
            proposalReview = proposalReview,
            jsonTrees = listOf(requests, File(files, "source-comment-snapshot.json"), File(staging, "reviews")),
        ),
        sql,
        listOf({ map ->
            if (projectState.isFile) rewriteLibraryProjectStateFolderNames(projectState.readText(), map)?.let { projectState.writeText(it.toString()) }
            Unit
        }),
    )

    /** A phone that edited a connected-folder source offline and queued work before Fedora renamed its folders. */
    private fun fixture() {
        val path = "$old/tex/notes.tex"
        // Working tree: a local unsynced edit, a git clone inside the folder, a look-alike folder.
        write(File(working, path), "edited on the phone, see $old/tex/other.tex\n")
        write(File(working, "$old/.git/HEAD"), "ref: refs/heads/main\n")
        write(File(working, "$importOld/markdown/a.md"), "imported\n")
        write(File(working, "docs-templates/a.tex"), "look-alike\n")
        write(File(staging, "$old/tex/support/html/page.html"), "<html></html>")
        // Source editor state named after the source path.
        write(File(sourceEditor, "drafts/${sha(path)}.json"), JsonObject().apply {
            addProperty("schema_version", 2); addProperty("source_file", path); addProperty("contents", "edited, see $old/tex/other.tex\n")
            addProperty("base_contents", "uses $old/x literally"); addProperty("request_id", "r1")
            add("claims", JsonParser.parseString("""[{"source_file":"$path","proposal_id":"p1"}]"""))
        }.toString())
        write(File(sourceEditor, "drafts/${sha("docs-templates/a.tex")}.json"), """{"source_file":"docs-templates/a.tex","contents":"x"}""")
        write(File(offline, "working/${sha(path)}.json"), """{"schema_version":3,"source_file":"$path","bundle_id":"${"b".repeat(64)}"}""")
        write(File(offline, "materialization/${sha(path)}.json"), """{"source_file":"$path","action":"merge"}""")
        write(File(offline, "merge-recoveries/${sha(path)}.local"), "older phone text")
        write(File(offline, "working-observations.json"), """{"files":{"$path":{"size_bytes":3}},"list":[{"source_file":"$importOld/markdown/a.md"}]}""")
        write(File(offline, "objects/${"c".repeat(64)}"), """{"sources":[{"path":"$path"}]}""")
        write(File(offline, "source-location-index.json"), """{"projects":{"$old":true},"files":{"$path":{}}}""")
        write(File(offline, "projects/${"d".repeat(64)}/source-open-index-v2.json"), """{"x":"$path"}""")
        write(File(sourceEditor, "snapshots/k.json"), """{"source_file":"$path"}""")
        write(File(proposalReview, "${sha(path)}.json"), """{"filename":"$path","format":2}""")
        write(File(proposalReview, "${sha(path)}.synced"), "synced text")
        write(File(proposalReview, "${sha(path)}.documents/${"e".repeat(64)}.txt"), "document")
        write(File(requests, "r1.json"), """{"request_id":"r1","source_file":"$path","contents":"edited $old"}""")
        write(File(files, "source-comment-snapshot.json"), """{"entries":{"x":"$path"},"scopes":["project:$old"]}""")
        // The Project catalogue as Fedora sent it before its rename, M, and changes queued before the rename.
        write(projectState, """{"projects":[{"id":"p","source_folders":[{"project":"$old","folder":"$old"}],
            "project_configuration":{"default_folder_id":"d","source_folders":[{"folder_id":"d","path":".thoughts/projects/${"a".repeat(64)}/default"},
            {"folder_id":"f1","path":"$old"}]}}],
            "source_folder_renames":${SourceFolderNames.toJson(renames)},
            "pending":[{"action":"include_source","project_id":"p","source_file":"$old/tex/new.tex","operation_id":"o1"},
            {"action":"import_html","project_id":"p","source_project":"$old","filename":"page.html","operation_id":"o2"}]}""")
        // Room rows.
        sql.exec("INSERT INTO ThoughtsSourceSnapshot VALUES (?, 7, 's', 'o', 'res')", path)
        sql.exec("INSERT INTO ThoughtsSourceSnapshot VALUES (?, 9, 's2', 'o2', 'res')", "$old/clash.tex")
        sql.exec("INSERT INTO ThoughtsSourceSnapshot VALUES (?, 10, 's3', 'o3', 'res')", "$new/clash.tex")
        sql.exec("INSERT INTO ThoughtsSourceSnapshot VALUES (?, 11, 's4', 'o4', 'res')", "docs-templates/a.tex")
        val comment = """{"comment_id":"c1","thread_root_id":"c1","scope":{"source_file":"$path"},"contents":"about $old"}"""
        sql.exec("INSERT INTO ThoughtsProjection VALUES ('logical_comment', 'comments', 'c1', 1, 0, ?, 5, '')", comment)
        sql.exec("INSERT INTO ThoughtsLocalCommentState VALUES ('c2', 'o', 1, 1, 0, ?)",
            """{"comment_id":"c2","thread_root_id":"c2","scope":{"source_file":"$importOld/markdown/a.md"}}""")
        sql.exec("INSERT INTO ThoughtsCommentScope VALUES (?, 'c1', 'projection')", "project:$old")
        sql.exec("INSERT INTO ThoughtsCommentScope VALUES ('comment:c1', 'c1', 'projection')")
        sql.exec("""INSERT INTO ThoughtsOutbox (operationId, deviceSequence, entityType, entityId, collection, clientTimestamp,
            payloadJson, createdAtEpochMillis, proposalSourceFile, failureDetail) VALUES ('op', 1, 'proposal_event', 'e', 'proposals', 't', ?, 0, ?, ?)""",
            """{"source_file":"$path","text":"a\n$old"}""", path, "Fedora said $old/tex/notes.tex is gone")
        sql.exec("INSERT INTO CommentMutationJournal VALUES ('j', ?)", """[{"scope":{"source_file":"$path"}}]""")
        sql.exec("INSERT INTO CurrentSourceAnnotation VALUES (?, 'proposal', 'x', 'g', '{}')", old)
        sql.exec("INSERT INTO ReviewMeta VALUES (1, 4)")
        sql.exec("INSERT INTO ReviewRecord VALUES ('reviews/incoming/c.json', 'card', 0, 'e', 'c', ?)",
            """{"source_files":["$path"],"answer":"x"}""")
        // A card made in an HTML attachment page, and a retire event (review events carry no paths).
        sql.exec("INSERT INTO ReviewRecord VALUES ('reviews/incoming/h.json', 'card', 0, 'e2', 'h', ?)",
            """{"primary":{"created_in":"html_attachment","html_attachment":{"path":"$old/tex/support/html/page.html","sha256":"${"f".repeat(64)}"},
            "text_quote":{"exact":"see $old","prefix":"","suffix":""},"question":"What is in $old?"}}""")
        write(File(staging, "reviews/outgoing/retire.json"), """{"card_id":"h","queue_action":"retire","source_file":"$path"}""")
        sql.exec("""INSERT INTO sync_events (runId, phase, state, attempt, message, errorCode, createdAtEpochMillis, origin,
            serverTimestampEpochMillis, receivedTimestampEpochMillis, component, buildId, metadataJson)
            VALUES ('run', 'p', 's', 1, ?, '', 0, 'phone', 0, 0, 'phone', '', '{}')""", "history names $path")
    }

    @Test fun `local state moves to Fedora's folder names`() {
        fixture()
        val path = "$old/tex/notes.tex"
        val renamed = "$new/tex/notes.tex"
        assertTrue(migration().needed(renames))
        val report = migration().run(renames)

        // Folders: one rename each in both roots, contents (and the git clone) untouched.
        assertFalse(File(working, old).exists())
        assertEquals("edited on the phone, see $old/tex/other.tex\n", File(working, renamed).readText())
        assertEquals("ref: refs/heads/main\n", File(working, "$new/.git/HEAD").readText())
        assertEquals("imported\n", File(working, "$importNew/markdown/a.md").readText())
        assertEquals("look-alike\n", File(working, "docs-templates/a.tex").readText())
        assertEquals("<html></html>", File(staging, "$new/tex/support/html/page.html").readText())
        assertEquals(3, report.renamedFolders)

        // Files named after the path follow it; source text is never rewritten.
        assertFalse(File(sourceEditor, "drafts/${sha(path)}.json").exists())
        val draft = json(File(sourceEditor, "drafts/${sha(renamed)}.json"))
        assertEquals(renamed, draft.get("source_file").asString)
        assertEquals("edited, see $old/tex/other.tex\n", draft.get("contents").asString)
        assertEquals("uses $old/x literally", draft.get("base_contents").asString)
        assertEquals(renamed, draft.getAsJsonArray("claims")[0].asJsonObject.get("source_file").asString)
        assertTrue(File(sourceEditor, "drafts/${sha("docs-templates/a.tex")}.json").isFile)
        assertEquals(renamed, json(File(offline, "working/${sha(renamed)}.json")).get("source_file").asString)
        assertEquals(renamed, json(File(offline, "materialization/${sha(renamed)}.json")).get("source_file").asString)
        assertEquals("older phone text", File(offline, "merge-recoveries/${sha(renamed)}.local").readText())
        assertFalse(File(offline, "merge-recoveries/${sha(path)}.local").exists())
        assertEquals(renamed, json(File(proposalReview, "${sha(renamed)}.json")).get("filename").asString)
        assertEquals("synced text", File(proposalReview, "${sha(renamed)}.synced").readText())
        assertTrue(File(proposalReview, "${sha(renamed)}.documents/${"e".repeat(64)}.txt").isFile)
        assertEquals(listOf("${sha(renamed)}.documents", "${sha(renamed)}.json", "${sha(renamed)}.synced"),
            proposalReview.list()!!.sorted())
        val observations = json(File(offline, "working-observations.json"))
        assertEquals(setOf(renamed), observations.getAsJsonObject("files").keySet())
        assertEquals("$importNew/markdown/a.md", observations.getAsJsonArray("list")[0].asJsonObject.get("source_file").asString)
        val request = json(File(requests, "r1.json"))
        assertEquals(renamed, request.get("source_file").asString)
        assertEquals("edited $old", request.get("contents").asString)
        val commentSnapshot = json(File(files, "source-comment-snapshot.json"))
        assertEquals(renamed, commentSnapshot.getAsJsonObject("entries").get("x").asString)
        assertEquals("project:$new", commentSnapshot.getAsJsonArray("scopes")[0].asString)
        // Content-addressed objects keep their bytes; caches keyed by path are gone.
        assertTrue(File(offline, "objects/${"c".repeat(64)}").readText().contains(path))
        assertFalse(File(offline, "source-location-index.json").exists())
        assertFalse(File(offline, "projects/${"d".repeat(64)}/source-open-index-v2.json").exists())
        assertFalse(File(sourceEditor, "snapshots").exists())

        // Project state: queued changes and the cached catalogue follow; M and folder labels stay.
        val state = json(projectState)
        val pending = state.getAsJsonArray("pending")
        assertEquals("$new/tex/new.tex", pending[0].asJsonObject.get("source_file").asString)
        assertEquals(new, pending[1].asJsonObject.get("source_project").asString)
        val project = state.getAsJsonArray("projects")[0].asJsonObject
        assertEquals(new, project.getAsJsonObject("project_configuration").getAsJsonArray("source_folders")[1].asJsonObject.get("path").asString)
        assertEquals(new, project.getAsJsonArray("source_folders")[0].asJsonObject.get("project").asString)
        assertEquals(old, project.getAsJsonArray("source_folders")[0].asJsonObject.get("folder").asString)
        assertEquals(renames, SourceFolderNames.parse(state.get("source_folder_renames")))

        // Room: rows rewritten, a row Fedora already sent under the new path wins, history and prose are kept.
        assertEquals(7L, sql.one("SELECT revision FROM ThoughtsSourceSnapshot WHERE path = ?", renamed))
        assertEquals(10L, sql.one("SELECT revision FROM ThoughtsSourceSnapshot WHERE path = ?", "$new/clash.tex"))
        assertEquals(11L, sql.one("SELECT revision FROM ThoughtsSourceSnapshot WHERE path = ?", "docs-templates/a.tex"))
        assertEquals(0L, sql.one("SELECT COUNT(*) FROM ThoughtsSourceSnapshot WHERE path LIKE 'docs-template/%'"))
        val projection = JsonParser.parseString(sql.one("SELECT payloadJson FROM ThoughtsProjection WHERE entityId = 'c1'") as String).asJsonObject
        assertEquals(renamed, projection.getAsJsonObject("scope").get("source_file").asString)
        assertEquals("about $old", projection.get("contents").asString)
        assertTrue((sql.one("SELECT payloadJson FROM ThoughtsLocalCommentState") as String).contains("$importNew/markdown/a.md"))
        assertEquals(renamed, sql.one("SELECT proposalSourceFile FROM ThoughtsOutbox"))
        val outbox = JsonParser.parseString(sql.one("SELECT payloadJson FROM ThoughtsOutbox") as String).asJsonObject
        assertEquals(renamed, outbox.get("source_file").asString)
        assertEquals("a\n$old", outbox.get("text").asString)
        assertEquals("Fedora said $old/tex/notes.tex is gone", sql.one("SELECT failureDetail FROM ThoughtsOutbox"))
        assertTrue((sql.one("SELECT payloadJson FROM CommentMutationJournal") as String).contains(renamed))
        assertEquals(0L, sql.one("SELECT COUNT(*) FROM CurrentSourceAnnotation"))
        assertTrue((sql.one("SELECT payload FROM ReviewRecord WHERE cardId = 'c'") as String).contains(renamed))
        val attachmentCard = JsonParser.parseString(sql.one("SELECT payload FROM ReviewRecord WHERE cardId = 'h'") as String)
            .asJsonObject.getAsJsonObject("primary")
        assertEquals("$new/tex/support/html/page.html", attachmentCard.getAsJsonObject("html_attachment").get("path").asString)
        assertEquals("$new/tex/support/html/page.html",
            com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards.Anchor.from(attachmentCard)?.path)
        assertEquals(renamed, json(File(staging, "reviews/outgoing/retire.json")).get("source_file").asString)
        assertEquals(5L, sql.one("SELECT generation FROM ReviewMeta"))
        assertTrue((sql.one("SELECT message FROM sync_events") as String).contains(path))
        assertEquals(
            setOf("comment:c1|c1|projection", "project:$new|c1|projection", "comment:c2|c2|local", "project:$importNew|c2|local"),
            sql.query("SELECT scopeKey, entityId, recordSource FROM ThoughtsCommentScope").map { it.joinToString("|") }.toSet(),
        )

        // From now on old spellings are read in the new one.
        assertEquals(mapOf(old to new, importOld to importNew), SourceFolderNames.localRenames)
        assertEquals("$new/tex/a.tex", queuedSourceFile(JsonObject(), "$old/tex/a.tex"))
        assertEquals("$new/tex/support/html/page.html", HtmlAttachmentLink.parse("thoughts-html:$old/tex/support/html/page.html"))
        val journalState = json(journal)
        assertEquals(SourceFolderNamesMigration.PHASE_DONE, journalState.get("phase").asString)
        assertEquals(renames, SourceFolderNames.parse(journalState.get("applied")))
        assertFalse(migration().needed(renames))

        // A second run is a no-op; a new process reads the applied renames from the journal.
        assertEquals(SourceFolderNamesMigration.Report(), migration().run(renames))
        SourceFolderNames.installLocal(emptyMap())
        SourceFolderNamesMigration.installFrom(journal)
        assertEquals(mapOf(old to new, importOld to importNew), SourceFolderNames.localRenames)
    }

    @Test fun `a legacy source-docs Project keeps its id and default folder while its folder is renamed`() {
        val id = "source:$old"
        val defaultFolder = com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.path(id, "default")
        val notes = "$defaultFolder/notes.tex"
        val connected = "$old/tex/main.tex"
        write(File(working, notes), "default folder of $id\n")
        write(File(working, connected), "connected\n")
        write(File(staging, "$defaultFolder/scan_0123456789ab_${com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.projectKey(id).take(8)}.pdf"), "%PDF")
        write(File(sourceEditor, "drafts/${sha(notes)}.json"), """{"source_file":"$notes","project_id":"$id","contents":"x"}""")
        write(File(sourceEditor, "drafts/${sha(connected)}.json"), """{"source_file":"$connected","project_id":"$id","contents":"y"}""")
        write(projectState, """{"projects":[{"id":"$id","name":"Template","source_folders":[{"project":"$old","folder":"$old"}],
            "project_configuration":{"id":"$id","default_folder_id":"d","source_folders":[
            {"folder_id":"d","path":"$defaultFolder"},{"folder_id":"f1","path":"$old"}]}}],
            "source_folder_renames":${SourceFolderNames.toJson(renames)},
            "pending":[{"action":"include_source","project_id":"$id","source_file":"$connected","operation_id":"o1"}]}""")
        sql.exec("INSERT INTO ThoughtsProjection VALUES ('library_project', 'projects', ?, 1, 0, ?, 5, '')", id,
            """{"id":"$id","source_folders":[{"project":"$old"}],"reading":"$id"}""")
        sql.exec("INSERT INTO ThoughtsCommentScope VALUES (?, 'c9', 'projection')", "project:$old")
        sql.exec("INSERT INTO ReviewRecord VALUES ('reviews/incoming/l.json', 'card', 0, 'e3', 'l', ?)",
            """{"project_id":"$id","source_files":["$connected","$notes"]}""")

        migration().run(renames)

        // The connected folder is renamed; the default folder (sha256 of the unchanged id) stays where it is.
        assertEquals("connected\n", File(working, "$new/tex/main.tex").readText())
        assertFalse(File(working, old).exists())
        assertEquals("default folder of $id\n", File(working, notes).readText())
        assertTrue(File(staging, defaultFolder).listFiles()!!.single().name.endsWith("_${com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.projectKey(id).take(8)}.pdf"))
        // Files named after a default-folder path keep their name; ids inside every store stay.
        assertEquals(id, json(File(sourceEditor, "drafts/${sha(notes)}.json")).get("project_id").asString)
        val draft = json(File(sourceEditor, "drafts/${sha("$new/tex/main.tex")}.json"))
        assertEquals("$new/tex/main.tex", draft.get("source_file").asString)
        assertEquals(id, draft.get("project_id").asString)
        val project = json(projectState).getAsJsonArray("projects")[0].asJsonObject
        assertEquals(id, project.get("id").asString)
        val configuration = project.getAsJsonObject("project_configuration")
        assertEquals(id, configuration.get("id").asString)
        assertEquals(defaultFolder, configuration.getAsJsonArray("source_folders")[0].asJsonObject.get("path").asString)
        assertEquals(new, configuration.getAsJsonArray("source_folders")[1].asJsonObject.get("path").asString)
        assertEquals(defaultFolder, defaultSourceFolder(project))
        val pending = json(projectState).getAsJsonArray("pending")[0].asJsonObject
        assertEquals(id, pending.get("project_id").asString)
        assertEquals("$new/tex/main.tex", pending.get("source_file").asString)
        // Room: the Project's row keeps its id (primary key and payload), folders follow.
        val row = sql.query("SELECT entityId, payloadJson FROM ThoughtsProjection WHERE entityType = 'library_project'").single()
        assertEquals(id, row[0])
        val payload = JsonParser.parseString(row[1] as String).asJsonObject
        assertEquals(id, payload.get("id").asString)
        assertEquals(id, payload.get("reading").asString)
        assertEquals(new, payload.getAsJsonArray("source_folders")[0].asJsonObject.get("project").asString)
        val card = JsonParser.parseString(sql.one("SELECT payload FROM ReviewRecord WHERE cardId = 'l'") as String).asJsonObject
        assertEquals(id, card.get("project_id").asString)
        assertEquals(listOf("$new/tex/main.tex", notes), card.getAsJsonArray("source_files").map { it.asString })
        assertEquals(0L, sql.one("SELECT COUNT(*) FROM ThoughtsCommentScope WHERE scopeKey = ?", "project:$old"))
    }

    @Test fun `an interrupted run continues from the journal with the renames it started`() {
        fixture()
        val path = "$old/tex/notes.tex"
        val renamed = "$new/tex/notes.tex"
        // As if a run died after moving one folder and writing the new working state but before deleting the old.
        File(working, old).renameTo(File(working, new))
        write(journal, """{"schema_version":1,"phase":"moving","started_at":"2026-10-27T10:00:00Z",
            "renames":${SourceFolderNames.toJson(renames)},"applied":[]}""")
        val stale = File(offline, "working/${sha(path)}.json")
        write(File(offline, "working/${sha(renamed)}.json"),
            SourceFolderNames.rewriteJsonValues(JsonParser.parseString(stale.readText()), SourceFolderNames.toMap(renames)).toString())
        val migration = migration()
        assertTrue(migration.interrupted())
        // Resumed at start with no catalogue M at hand: the journal's renames are applied.
        assertTrue(migration.needed(emptyList()))
        SourceFolderNamesMigration.installFrom(journal)
        assertEquals(new, SourceFolderNames.followLocal(old))
        migration.run(emptyList())
        assertFalse(stale.exists())
        assertEquals(renamed, json(File(offline, "working/${sha(renamed)}.json")).get("source_file").asString)
        assertEquals("imported\n", File(working, "$importNew/markdown/a.md").readText())
        assertEquals("2026-10-27T10:00:00Z", json(journal).get("started_at").asString)
        assertFalse(migration.interrupted())
        assertEquals(renames, migration.applied())
    }

    @Test fun `with both folders identical files merge and differing ones stop the migration`() {
        write(File(working, "$old/same.tex"), "same")
        write(File(working, "$new/same.tex"), "same")
        write(File(working, "$old/only-old.tex"), "old")
        write(File(working, "$old/edited.tex"), "phone edit")
        write(File(working, "$new/edited.tex"), "Fedora version")
        val error = assertThrows(SourceFolderNamesMigration.Problem::class.java) { migration().run(renames) }
        assertTrue(error.message!!, error.message!!.contains("edited.tex"))
        assertEquals("old", File(working, "$new/only-old.tex").readText())
        assertFalse(File(working, "$old/same.tex").exists())
        assertEquals("phone edit", File(working, "$old/edited.tex").readText())
        assertEquals("Fedora version", File(working, "$new/edited.tex").readText())
        assertTrue(migration().interrupted())
        assertEquals(1, json(journal).getAsJsonArray("problems").size())

        // The user keeps one copy; the next run finishes.
        File(working, "$old/edited.tex").delete()
        val report = migration().run(renames)
        assertEquals(1, report.movedFolders)
        assertFalse(File(working, old).exists())
        assertFalse(migration().interrupted())
    }

    @Test fun `only renames not applied yet run, and a contradicting map stops`() {
        assertNull(migration().phase())
        assertFalse(migration().needed(emptyList()))
        assertEquals(SourceFolderNamesMigration.Report(), migration().run(renames.take(1)))
        assertEquals(renames.take(1), migration().applied())
        assertFalse(migration().needed(renames.take(1)))
        assertTrue(migration().needed(renames))
        assertEquals(listOf(renames[1]), migration().pending(renames))
        migration().run(renames)
        assertEquals(renames, migration().applied())
        assertThrows(SourceFolderNamesMigration.Problem::class.java) {
            migration().run(listOf(Rename("f1", old, "docs-template-ffffff")))
        }
    }

    @Test fun `a bundle built before Fedora's rename is read in the new spelling`() {
        val before = JsonParser.parseString("""{"sources":[{"path":"$old/tex/a.tex"}],
            "pdfs":[{"filename":"template_a.pdf","synctex_path":"/build/$old/tex/a.tex","placement_sources":["$old/tex/a.tex"]}]}""").asJsonObject
        assertSame(before, followSourceFolderNames(before))
        SourceFolderNames.installLocal(SourceFolderNames.toMap(renames))
        val read = followSourceFolderNames(before)
        assertEquals("$new/tex/a.tex", read.getAsJsonArray("sources")[0].asJsonObject.get("path").asString)
        val pdf = read.getAsJsonArray("pdfs")[0].asJsonObject
        assertEquals("template_a.pdf", pdf.get("filename").asString)
        assertEquals("/build/$old/tex/a.tex", pdf.get("synctex_path").asString)
        assertEquals("$new/tex/a.tex", pdf.getAsJsonArray("placement_sources")[0].asString)
        // A bundle Fedora built after its rename is read as it is.
        val after = JsonParser.parseString("""{"sources":[{"path":"$new/tex/a.tex"},{"path":"docs-other/x.tex"}],"note":"$old"}""").asJsonObject
        assertSame(after, followSourceFolderNames(after))
    }

    @Test fun `a snapshot naming a folder nobody knows asks Fedora for M`() {
        fun snapshot(vararg projects: String) = Gson().fromJson(
            """{"publications":[${projects.joinToString { """{"generation_id":"g","document_id":"d","filename":"x.pdf","sha256":"s","size_bytes":1,
                "project":"$it","source_frontier":0,"comment_frontier":0,"annotation_frontier":0,"metadata":{}}""" }}],
                "latest_server_sequence":1,"source_index_sha256":null}""",
            LibrarySnapshot::class.java,
        )
        val catalogue = JsonParser.parseString("""[{"id":"p","project_configuration":{"source_folders":[{"folder_id":"f1","path":"$old"}]}}]""").asJsonArray
        assertEquals(emptySet<String>(), unknownSnapshotFolders(snapshot(old, ".thoughts/projects/${"a".repeat(64)}/default", ""), catalogue, emptyList()))
        assertEquals(setOf(new), unknownSnapshotFolders(snapshot(new, old), catalogue, emptyList()))
        assertEquals(emptySet<String>(), unknownSnapshotFolders(snapshot(new), catalogue, renames))
    }

    @Test fun `stored values are rewritten field by field`() {
        val map = SourceFolderNames.toMap(renames)
        assertEquals("$new/a.tex", SourceFolderNamesMigration.rewriteColumnValue("$old/a.tex", map))
        assertEquals("line\n$old/a.tex", SourceFolderNamesMigration.rewriteColumnValue("line\n$old/a.tex", map))
        assertEquals("""{"path":"$new/a.tex","contents":"$old"}""",
            SourceFolderNamesMigration.rewriteColumnValue("""{"path":"$old/a.tex","contents":"$old"}""", map))
        assertEquals("[\"project:$new\"]", SourceFolderNamesMigration.rewriteColumnValue("[\"project:$old\"]", map))
        assertEquals("{not json $new", SourceFolderNamesMigration.rewriteColumnValue("{not json $old", map))
    }
}

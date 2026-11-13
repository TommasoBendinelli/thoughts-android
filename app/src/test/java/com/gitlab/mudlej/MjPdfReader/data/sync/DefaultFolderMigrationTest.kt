// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/** The SQL the migration runs, on a real SQLite over JDBC with the app's exported Room schema. */
private class JdbcDefaultFolderSql(val connection: Connection) : DefaultFolderSql {
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

class DefaultFolderMigrationTest {
    private val h = "a".repeat(64)
    private val legacy = ".thoughts/projects/$h/documents"
    private val canonical = ".thoughts/projects/$h/default"
    private lateinit var root: File
    private lateinit var files: File
    private lateinit var working: File
    private lateinit var staging: File
    private lateinit var sql: JdbcDefaultFolderSql
    private lateinit var projectState: File

    private val sourceEditor get() = File(files, "source-editor")
    private val offline get() = File(sourceEditor, "offline")
    private val proposalReview get() = File(files, "proposal-review")
    private val requests get() = File(files, "thoughts/source-requests")
    private val journal get() = File(root, "no-backup/thoughts-sync/default-folder-migration.json")

    private fun sha(text: String) = DefaultFolderMigration.sha256(text)
    private fun write(file: File, text: String) = file.apply { parentFile.mkdirs(); writeText(text) }
    private fun json(file: File) = JsonParser.parseString(file.readText()).asJsonObject

    @Before fun setUp() {
        DefaultFolder.localStateMigrated = false
        root = Files.createTempDirectory("default-folder-migration").toFile()
        files = File(root, "files")
        working = File(root, "Documents/Thoughts Sources")
        staging = File(files, "thoughts")
        projectState = File(root, "no-backup/thoughts-sync/projects/state.json")
        sql = JdbcDefaultFolderSql(DriverManager.getConnection("jdbc:sqlite::memory:"))
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
        DefaultFolder.localStateMigrated = false
    }

    private fun migration() = DefaultFolderMigration(
        DefaultFolderMigration.Layout(
            journal = journal,
            folderRoots = listOf(working, staging),
            sourceEditor = sourceEditor,
            proposalReview = proposalReview,
            jsonTrees = listOf(requests, File(files, "source-comment-snapshot.json")),
        ),
        sql,
        listOf({
            if (projectState.isFile) rewriteLibraryProjectState(projectState.readText())?.let { projectState.writeText(it.toString()) }
            Unit
        }),
    )

    /** A phone that edited notes.tex offline, staged an import, and queued work before Fedora renamed its folders. */
    private fun fixture() {
        // Working tree: a local unsynced edit and a resource; staged PDF import.
        write(File(working, "$legacy/notes.tex"), "edited on the phone\n")
        write(File(working, "$legacy/figures/plot.pdf"), "%PDF-1.4 plot")
        write(File(working, "docs-paper/main.tex"), "connected\n")
        write(File(staging, "$legacy/scan_0123456789ab_12345678.pdf"), "%PDF-1.7 scan")
        // Source editor state named after the source path.
        val path = "$legacy/notes.tex"
        write(File(sourceEditor, "drafts/${sha(path)}.json"), JsonObject().apply {
            addProperty("schema_version", 2); addProperty("source_file", path); addProperty("contents", "edited on the phone\n")
            addProperty("base_contents", "uses $legacy/notes.tex literally"); addProperty("request_id", "r1")
            add("claims", JsonParser.parseString("""[{"source_file":"$path","proposal_id":"p1"}]"""))
        }.toString())
        write(File(sourceEditor, "drafts/${sha("docs-paper/main.tex")}.json"), """{"source_file":"docs-paper/main.tex","contents":"x"}""")
        write(File(offline, "working/${sha(path)}.json"), """{"schema_version":3,"source_file":"$path","bundle_id":"${"b".repeat(64)}"}""")
        write(File(offline, "materialization/${sha(path)}.json"), """{"source_file":"$path","action":"merge"}""")
        write(File(offline, "merge-recoveries/${sha(path)}.local"), "older phone text")
        write(File(offline, "working-observations.json"), """{"files":[{"source_file":"$path","size_bytes":3}]}""")
        write(File(offline, "objects/${"c".repeat(64)}"), """{"sources":[{"path":"$path"}]}""")
        write(File(offline, "source-location-index.json"), """{"files":{"$path":{}}}""")
        write(File(offline, "projects/${"d".repeat(64)}/source-open-index-v2.json"), """{"x":"$path"}""")
        write(File(sourceEditor, "snapshots/k.json"), """{"source_file":"$path"}""")
        write(File(proposalReview, "${sha(path)}.json"), """{"filename":"$path","format":2}""")
        write(File(proposalReview, "${sha(path)}.synced"), "synced text")
        write(File(proposalReview, "${sha(path)}.documents/${"e".repeat(64)}.txt"), "document")
        write(File(requests, "r1.json"), """{"request_id":"r1","source_file":"$path","contents":"edited"}""")
        write(File(files, "source-comment-snapshot.json"), """{"entries":{"x":"$path"}}""")
        // The Project catalogue (already from Fedora) and a change queued before the rename.
        write(projectState, """{"projects":[{"id":"p","project_configuration":{"default_folder_id":"f",
            "source_folders":[{"folder_id":"f","path":"$canonical"}]}}],
            "pending":[{"action":"include_source","project_id":"p","source_file":"$path","operation_id":"o1"},
            {"action":"import","project_id":"p","local_path":"${staging.absolutePath}/$legacy/scan_0123456789ab_12345678.pdf"}]}""")
        // Room rows.
        sql.exec("INSERT INTO ThoughtsSourceSnapshot VALUES (?, 7, 's', 'o', 'res')", path)
        sql.exec("INSERT INTO ThoughtsSourceSnapshot VALUES (?, 9, 's2', 'o2', 'res')", "$legacy/clash.tex")
        sql.exec("INSERT INTO ThoughtsSourceSnapshot VALUES (?, 10, 's3', 'o3', 'res')", "$canonical/clash.tex")
        val comment = """{"comment_id":"c1","thread_root_id":"c1","scope":{"source_file":"$path"}}"""
        sql.exec("INSERT INTO ThoughtsProjection VALUES ('logical_comment', 'comments', 'c1', 1, 0, ?, 5, '')", comment)
        sql.exec("INSERT INTO ThoughtsLocalCommentState VALUES ('c2', 'o', 1, 1, 0, ?)",
            """{"comment_id":"c2","thread_root_id":"c2","scope":{"source_file":"$path"}}""")
        sql.exec("INSERT INTO ThoughtsCommentScope VALUES (?, 'c1', 'projection')", "project:$legacy")
        sql.exec("INSERT INTO ThoughtsCommentScope VALUES ('comment:c1', 'c1', 'projection')")
        sql.exec("""INSERT INTO ThoughtsOutbox (operationId, deviceSequence, entityType, entityId, collection, clientTimestamp,
            payloadJson, createdAtEpochMillis, proposalSourceFile) VALUES ('op', 1, 'proposal_event', 'e', 'proposals', 't', ?, 0, ?)""",
            """{"source_file":"$path","text":"a\nb"}""", path)
        sql.exec("INSERT INTO CommentMutationJournal VALUES ('j', ?)", """[{"scope":{"source_file":"$path"}}]""")
        sql.exec("INSERT INTO CurrentSourceAnnotation VALUES (?, 'proposal', 'x', 'g', '{}')", legacy)
        sql.exec("INSERT INTO ReviewMeta VALUES (1, 4)")
        sql.exec("INSERT INTO ReviewRecord VALUES ('reviews/incoming/c.json', 'card', 0, 'e', 'c', ?)",
            """{"source_files":["$path"],"answer":"x"}""")
        sql.exec("""INSERT INTO sync_events (runId, phase, state, attempt, message, errorCode, createdAtEpochMillis, origin,
            serverTimestampEpochMillis, receivedTimestampEpochMillis, component, buildId, metadataJson)
            VALUES ('run', 'p', 's', 1, ?, '', 0, 'phone', 0, 0, 'phone', '', '{}')""", "history names $path")
    }

    @Test fun `local state moves to the default folder`() {
        fixture()
        val path = "$legacy/notes.tex"
        val renamed = "$canonical/notes.tex"
        val report = migration().run()

        // Folders: one rename each, contents untouched.
        assertFalse(File(working, legacy).exists())
        assertEquals("edited on the phone\n", File(working, "$canonical/notes.tex").readText())
        assertEquals("%PDF-1.4 plot", File(working, "$canonical/figures/plot.pdf").readText())
        assertEquals("%PDF-1.7 scan", File(staging, "$canonical/scan_0123456789ab_12345678.pdf").readText())
        assertEquals("connected\n", File(working, "docs-paper/main.tex").readText())
        assertEquals(2, report.movedFolders)

        // Files named after the path follow it; source text is never rewritten.
        assertFalse(File(sourceEditor, "drafts/${sha(path)}.json").exists())
        val draft = json(File(sourceEditor, "drafts/${sha(renamed)}.json"))
        assertEquals(renamed, draft.get("source_file").asString)
        assertEquals("uses $legacy/notes.tex literally", draft.get("base_contents").asString)
        assertEquals(renamed, draft.getAsJsonArray("claims")[0].asJsonObject.get("source_file").asString)
        assertTrue(File(sourceEditor, "drafts/${sha("docs-paper/main.tex")}.json").isFile)
        assertEquals(renamed, json(File(offline, "working/${sha(renamed)}.json")).get("source_file").asString)
        assertEquals(renamed, json(File(offline, "materialization/${sha(renamed)}.json")).get("source_file").asString)
        assertEquals("older phone text", File(offline, "merge-recoveries/${sha(renamed)}.local").readText())
        assertFalse(File(offline, "merge-recoveries/${sha(path)}.local").exists())
        assertEquals(renamed, json(File(proposalReview, "${sha(renamed)}.json")).get("filename").asString)
        assertEquals("synced text", File(proposalReview, "${sha(renamed)}.synced").readText())
        assertTrue(File(proposalReview, "${sha(renamed)}.documents/${"e".repeat(64)}.txt").isFile)
        assertEquals(listOf("${sha(renamed)}.documents", "${sha(renamed)}.json", "${sha(renamed)}.synced"),
            proposalReview.list()!!.sorted())
        assertTrue(File(offline, "working-observations.json").readText().contains(renamed))
        assertEquals(renamed, json(File(requests, "r1.json")).get("source_file").asString)
        assertTrue(File(files, "source-comment-snapshot.json").readText().contains(renamed))
        // Content-addressed objects keep their bytes; caches keyed by path are gone.
        assertTrue(File(offline, "objects/${"c".repeat(64)}").readText().contains(path))
        assertFalse(File(offline, "source-location-index.json").exists())
        assertFalse(File(offline, "projects/${"d".repeat(64)}/source-open-index-v2.json").exists())
        assertFalse(File(sourceEditor, "snapshots").exists())

        // Project state: the queued include_source and the staged import follow.
        val state = json(projectState)
        val pending = state.getAsJsonArray("pending")
        assertEquals(renamed, pending[0].asJsonObject.get("source_file").asString)
        assertEquals("${staging.absolutePath}/$canonical/scan_0123456789ab_12345678.pdf", pending[1].asJsonObject.get("local_path").asString)

        // Room: rows rewritten, a row Fedora already sent under the new path wins, history is kept.
        assertEquals(7L, sql.one("SELECT revision FROM ThoughtsSourceSnapshot WHERE path = ?", renamed))
        assertEquals(10L, sql.one("SELECT revision FROM ThoughtsSourceSnapshot WHERE path = ?", "$canonical/clash.tex"))
        assertEquals(0L, sql.one("SELECT COUNT(*) FROM ThoughtsSourceSnapshot WHERE path LIKE '%/documents/%'"))
        assertTrue((sql.one("SELECT payloadJson FROM ThoughtsProjection WHERE entityId = 'c1'") as String).contains(renamed))
        assertTrue((sql.one("SELECT payloadJson FROM ThoughtsLocalCommentState") as String).contains(renamed))
        assertEquals(renamed, sql.one("SELECT proposalSourceFile FROM ThoughtsOutbox"))
        assertTrue((sql.one("SELECT payloadJson FROM ThoughtsOutbox") as String).contains("\"source_file\":\"$renamed\""))
        assertTrue((sql.one("SELECT payloadJson FROM CommentMutationJournal") as String).contains(renamed))
        assertEquals(0L, sql.one("SELECT COUNT(*) FROM CurrentSourceAnnotation"))
        assertTrue((sql.one("SELECT payload FROM ReviewRecord") as String).contains(renamed))
        assertEquals(5L, sql.one("SELECT generation FROM ReviewMeta"))
        assertTrue((sql.one("SELECT message FROM sync_events") as String).contains(path))
        assertEquals(
            setOf("comment:c1|c1|projection", "project:$canonical|c1|projection", "comment:c2|c2|local", "project:$canonical|c2|local"),
            sql.query("SELECT scopeKey, entityId, recordSource FROM ThoughtsCommentScope").map { it.joinToString("|") }.toSet(),
        )

        assertTrue(DefaultFolder.localStateMigrated)
        assertEquals(DefaultFolderMigration.PHASE_DONE, json(journal).get("phase").asString)
        assertTrue(migration().done())

        // A second run is a no-op.
        assertEquals(DefaultFolderMigration.Report(), migration().run())
    }

    @Test fun `an interrupted run continues from the journal`() {
        fixture()
        val path = "$legacy/notes.tex"
        val renamed = "$canonical/notes.tex"
        // As if a run died after moving the folder and writing the new working state but before deleting the old.
        File(working, legacy).renameTo(File(working, canonical).apply { parentFile.mkdirs() })
        write(journal, """{"schema_version":1,"phase":"started","started_at":"2026-10-17T10:00:00Z"}""")
        val old = File(offline, "working/${sha(path)}.json")
        write(File(offline, "working/${sha(renamed)}.json"), DefaultFolder.rewriteJsonValues(JsonParser.parseString(old.readText())).toString())
        assertTrue(migration().interrupted())
        migration().run()
        assertFalse(old.exists())
        assertEquals(renamed, json(File(offline, "working/${sha(renamed)}.json")).get("source_file").asString)
        assertEquals("2026-10-17T10:00:00Z", json(journal).get("started_at").asString)
        assertTrue(migration().done())
        assertFalse(migration().interrupted())
    }

    @Test fun `with both folders identical files merge and differing ones stop the migration`() {
        write(File(working, "$legacy/same.tex"), "same")
        write(File(working, "$canonical/same.tex"), "same")
        write(File(working, "$legacy/only-old.tex"), "old")
        write(File(working, "$legacy/edited.tex"), "phone edit")
        write(File(working, "$canonical/edited.tex"), "Fedora version")
        val error = assertThrows(DefaultFolderMigration.Problem::class.java) { migration().run() }
        assertTrue(error.message!!, error.message!!.contains("edited.tex"))
        assertEquals("old", File(working, "$canonical/only-old.tex").readText())
        assertFalse(File(working, "$legacy/same.tex").exists())
        assertEquals("phone edit", File(working, "$legacy/edited.tex").readText())
        assertEquals("Fedora version", File(working, "$canonical/edited.tex").readText())
        assertTrue(migration().interrupted())
        assertEquals(1, json(journal).getAsJsonArray("problems").size())

        // The user keeps one copy; the next run finishes.
        File(working, "$legacy/edited.tex").delete()
        migration().run()
        assertFalse(File(working, legacy).exists())
        assertTrue(migration().done())
    }

    @Test fun `a phone without legacy state only records the run`() {
        assertNull(migration().phase())
        assertEquals(DefaultFolderMigration.Report(), migration().run())
        assertTrue(migration().done())
    }

    @Test fun `the trigger is a Fedora catalogue naming default`() {
        val catalogue = { path: String -> JsonParser.parseString("""[{"id":"p","project_configuration":{"default_folder_id":"f",
            "source_folders":[{"folder_id":"f","path":"$path"}]}}]""").asJsonArray }
        assertFalse(DefaultFolderMigration.fedoraUsesDefault(catalogue(legacy)))
        assertTrue(DefaultFolderMigration.fedoraUsesDefault(catalogue(canonical)))
        assertFalse(DefaultFolderMigration.fedoraUsesDefault(com.google.gson.JsonArray()))
    }

    @Test fun `a queued include_source names the folder as it is called now`() {
        val project = JsonParser.parseString("""{"id":"p","project_configuration":{"default_folder_id":"f",
            "source_folders":[{"folder_id":"f","path":"$canonical"},{"folder_id":"g","path":"docs-x"}]}}""").asJsonObject
        assertEquals("$canonical/a.md", queuedSourceFile(project, "$legacy/a.md"))
        assertEquals("$canonical/a.md", queuedSourceFile(project, "$canonical/a.md"))
        assertEquals("docs-x/a.md", queuedSourceFile(project, "docs-x/a.md"))
        val other = ".thoughts/projects/${"b".repeat(64)}/documents/a.md"
        assertEquals(other, queuedSourceFile(project, other))
        assertTrue(inDefaultFolder(project, queuedSourceFile(project, "$legacy/a.md")))
        assertNull(rewriteLibraryProjectState("""{"projects":[],"pending":[]}"""))
    }
}

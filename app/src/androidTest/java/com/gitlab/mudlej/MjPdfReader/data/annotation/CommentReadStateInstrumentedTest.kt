// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.graphics.RectF
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.AppDatabaseMigrations
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class CommentReadStateInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun migrationPreservesCanonicalTombstones() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${TestRunIdentity.id}-comment-migration"
        try {
            helper.createDatabase(name, 33).apply {
                execSQL("INSERT INTO ThoughtsProjection(entityType,collection,entityId,revision,deleted,payloadJson,serverSequence,proposalSourceFile) VALUES ('logical_comment','comments','kept',2,1,'{\"deleted\":true}',3,'')")
                close()
            }
            helper.runMigrationsAndValidate(name, 34, true, AppDatabaseMigrations.MIGRATION_33_TO_34).use { db ->
                db.query("SELECT entityId, revision, deleted FROM ThoughtsProjection").use {
                    assertTrue(it.moveToFirst()); assertEquals("kept", it.getString(0))
                    assertEquals(2, it.getInt(1)); assertEquals(1, it.getInt(2))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun importCheckpointDetectsChangedBytesEvenWithPreservedFileMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stem = "test-${TestRunIdentity.id}-import"
        val filename = "$stem.pdf"
        val hash = com.gitlab.mudlej.MjPdfReader.testing.registerTestPdf(context, filename)
        val pdf = java.io.File(context.filesDir, filename)
        val sidecar = CommentSidecarStore(context).sidecarFile(android.net.Uri.fromFile(pdf), filename)
        sidecar.parentFile!!.mkdirs()
        fun content(suffix: String) = """{"id":"$stem-$suffix","page":1,"subtype":"Text","contents":"Imported","rect":[40,100,60,80],"revision":1,"deleted":false,"annotation_pdf_sha256":"$hash"}
"""
        sidecar.writeText(content("a"))
        LogicalCommentStore(context).importCatalogue()
        val dao = AppDatabase.getInstance(context).commentReadStateDao()
        val first = dao.checkpoint(sidecar.absolutePath)
        assertEquals(64, first!!.length)
        val modified = sidecar.lastModified()
        val size = sidecar.length()
        sidecar.writeText(content("b"))
        check(sidecar.setLastModified(modified))
        assertEquals(size, sidecar.length())
        LogicalCommentStore(context).importCatalogue()
        assertNotEquals(first, dao.checkpoint(sidecar.absolutePath))
        val ids = LogicalCommentStore(context).catalogueSnapshot().logicalComments.map { it.id }
        assertTrue("$stem-a" in ids)
        assertTrue("$stem-b" in ids)
    }

    @Test fun databaseJournalSurvivesReopeningAndNeverReimportsRetiredMutations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "test-${TestRunIdentity.id}-journal"
        val filename = "$id.pdf"
        val hash = com.gitlab.mudlej.MjPdfReader.testing.registerTestPdf(context, filename)
        val now = Instant.now().toString()
        val store = LogicalCommentStore(context)
        store.edit(filename, hash, listOf(AnnotationEdit.AddPdfComment(
            0, id, RectF(1f, 20f, 10f, 10f), "Saved", "Tester", now, now)))
        val payload = JsonParser.parseString(store.snapshot(filename).logicalComments.single { it.id == id }.payloadJson).asJsonObject
        payload.addProperty("revision", payload.get("revision").asLong + 1)
        payload.addProperty("contents", "Recoverable local edit")
        val row = CommentMutationJournal(id, JsonArray().apply { add(payload) }.toString())
        val dao = AppDatabase.getInstance(context).commentReadStateDao()
        val marker = "$id-migration"
        dao.migrateJournals(listOf(row), marker)
        assertTrue(LogicalCommentStore(context).catalogue().metadataFor(id).searchableText.contains("Recoverable local edit"))
        // A retired legacy file left as recovery evidence must never resurrect its mutation.
        dao.removeJournal(id)
        dao.migrateJournals(listOf(row), marker)
        assertFalse(dao.journals().any { it.id == id })
        assertTrue(LogicalCommentStore(context).catalogue().metadataFor(id).searchableText.contains("Saved"))
        store.edit(filename, hash, listOf(AnnotationEdit.DeletePdfComment(0, 0, com.github.barteksc.pdfviewer.PDFView.PdfComment.SUBTYPE_TEXT, id)))
        assertEquals(0, LogicalCommentStore(context).catalogue().metadataFor(id).liveComments)
    }
}

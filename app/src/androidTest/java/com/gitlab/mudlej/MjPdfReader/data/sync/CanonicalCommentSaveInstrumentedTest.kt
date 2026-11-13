// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentDeliveryRecord
import com.gitlab.mudlej.MjPdfReader.data.render.PendingCommentDelivery
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.resolveStoredPdfComments
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only a uniquely named test database and temporary fixture PDF; no Sync. */
@RunWith(AndroidJUnit4::class)
class CanonicalCommentSaveInstrumentedTest {
    @Test fun savesUnlinkedThreadOfflineAndVerifiesItsOriginalPdfAfterReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val name = "comment-unification-$token.db"
        val filename = "comment-unification-$token.pdf"
        val pdf = File(context.cacheDir, filename)
        pdf.writeBytes(instrumentation.context.assets.open("compiler-comments/corpus.pdf").use { it.readBytes() })
        val hash = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes()).joinToString("") { "%02x".format(it) }
        DocumentIdentityStore().registerImport(filename, hash, pdf)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var database = open()
        try {
            var repository = FedoraRepository(context, database)
            fun raw(id: String, parent: String? = null) = JsonParser.parseString("""{
                "id":"$id","revision":1,"annotation_pdf_sha256":"$hash","page":1,
                "subtype":"Text","rect":[100,300,120,280],"contents":"Offline comment",
                "created":"2026-09-10T11:00:00Z","updated_at":"2026-09-10T11:00:00Z","deleted":false
            }""").asJsonObject.apply { parent?.let { addProperty("in_reply_to", it) } }
            val root = raw("root-$token")
            val reply = raw("reply-$token", "root-$token")
            fun batch(vararg rows: JsonObject) = PendingCommentDelivery(UUID.randomUUID().toString(), filename,
                rows.map { CommentDeliveryRecord(it.get("id").asString, it.get("revision").asInt, it.toString()) }, 0)
            val initial = batch(reply, root)
            repository.captureCommentBatches(listOf(initial))
            repository.captureCommentBatches(listOf(initial))
            assertEquals(2, database.thoughtsSyncDao().undelivered().size)
            assertTrue(database.thoughtsSyncDao().undelivered().all { it.entityType == "logical_comment" })
            val saved = repository.effectiveLogicalCommentPayloads().associateBy { it.get("comment_id").asString }
            val origin = saved.getValue("root-$token").getAsJsonObject("origin").deepCopy()
            assertEquals(hash, origin.get("pdf_sha256").asString)
            assertEquals(root.get("rect"), origin.getAsJsonObject("raw_pdf_geometry").get("rect"))
            assertEquals("root-$token", saved.getValue("reply-$token").get("thread_root_id").asString)
            root.addProperty("revision", 2)
            root.addProperty("contents", "Edited while unlinked")
            repository.captureCommentBatches(listOf(batch(root)))
            database.close()
            database = open()
            repository = FedoraRepository(context, database)
            val reopened = repository.effectiveLogicalCommentPayloads().single { it.get("comment_id").asString == "root-$token" }
            assertEquals("Edited while unlinked", reopened.get("contents").asString)
            assertEquals(origin, reopened.get("origin"))
            assertEquals(3, database.thoughtsSyncDao().undelivered().size)
            val logical = OfflineLogicalComment("root-$token", "root-$token", null, 2, false, "pdf_only", "", 0,
                filename.removeSuffix(".pdf"), 0, reopened.toString())
            val core = PdfiumCore(context)
            val native = core.newDocument(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY))
            try {
                assertEquals(3, core.getPageCount(native))
                core.openPage(native, 0)
                val size = core.getPageWidthPoint(native, 0).toFloat() to core.getPageHeightPoint(native, 0).toFloat()
                val placed = resolveStoredPdfComments(emptyList(), listOf(logical), filename, hash) { page -> if (page == 0) size else null }
                assertEquals(1, placed.size)
                assertEquals("Edited while unlinked", placed.single().contents)
                assertTrue(resolveStoredPdfComments(emptyList(), listOf(logical), filename, "b".repeat(64)) { size }.isEmpty())
            } finally { core.closeDocument(native) }
            root.addProperty("revision", 3)
            root.addProperty("deleted", true)
            repository.captureCommentBatches(listOf(batch(root)))
            assertTrue(repository.effectiveLogicalCommentPayloads().single { it.get("comment_id").asString == "root-$token" }.get("deleted").asBoolean)
        } finally {
            database.close()
            context.deleteDatabase(name)
            pdf.delete()
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.VerifiedPdfActivationStore
import com.gitlab.mudlej.MjPdfReader.data.render.sourceLinkedLogicalFile
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DocumentIdentityInstrumentedTest {
    @Test fun importedCardSurvivesRestartAndPublicationWithoutReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${TestRunIdentity.id}-identity.pdf"
        val imported = File(context.cacheDir, name)
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create())
            pdf.finishPage(page)
            imported.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        val sha = MessageDigest.getInstance("SHA-256").digest(imported.readBytes()).joinToString("") { "%02x".format(it) }
        val directory = PhoneRenderQueue.defaultCanonicalPdfDirectory().apply { mkdirs() }
        val identity = DocumentIdentityStore().registerImport(name, sha, imported)
        assertTrue(DocumentIdentityStore.validId(identity))
        assertEquals(identity, DocumentIdentityStore().identifier(name))
        assertEquals(imported, DocumentIdentityStore().fileForId(identity))
        val store = ReviewStore(context)
        val card = store.createManualCard(name.removeSuffix(".pdf"), sha, 1,
            RectF(.1f, .1f, .8f, .3f), "[TEST ${TestRunIdentity.id}] What is shown?", sourceAvailable = false).getOrThrow()
        assertEquals(identity, card.documentId)
        val reopened = ReviewStore(context).card(card.id)!!
        assertEquals(identity, reopened.documentId)
        assertEquals(imported, store.reviewPdfFile(reopened))
        val published = File(directory, name)
        imported.copyTo(published)
        File(directory, ".thoughts-library-index.json").writeText(JsonObject().apply {
            add("pdfs", JsonArray().apply { add(JsonObject().apply {
                addProperty("filename", name); addProperty("document_id", identity)
                addProperty("pdf_sha256", sha); addProperty("kind", "unlinked")
            }) })
        }.toString())
        assertEquals(published, DocumentIdentityStore().fileForId(identity))
        assertEquals(published, sourceLinkedLogicalFile(context, Uri.fromFile(imported)))
        assertEquals(published, store.reviewPdfFile(reopened))
        assertEquals(identity, ReviewStore(context).card(card.id)!!.documentId)
        val snapshot = SourceLinkedPdfIndexSnapshot(libraryPdfSha256ByFilename = mapOf(name to sha), generationCurrent = true)
        assertFalse(VerifiedPdfActivationStore(File(context.cacheDir, "identity-activation")).hasVerifiedUpdateReady(published, sha, snapshot))
        assertTrue(imported.isFile)
        assertTrue(published.delete())
        assertTrue(imported.delete())
        assertNull(store.reviewPdfFile(reopened))
    }
}

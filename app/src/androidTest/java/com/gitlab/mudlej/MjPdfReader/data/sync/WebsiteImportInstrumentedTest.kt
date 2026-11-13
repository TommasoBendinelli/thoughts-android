// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.importing.PaperWebsite
import com.gitlab.mudlej.MjPdfReader.data.importing.PaperPdfDownloader
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class WebsiteImportInstrumentedTest {
    @Test fun liveOpenReviewExampleDownloadsAndRenders() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveOpenReviewImport") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paper = PaperWebsite.resolve("https://openreview.net/pdf?id=iEmRSwdzyw&utm_source=chatgpt.com")
        val file = PaperPdfDownloader().download(paper, context.cacheDir)
        try {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                assertEquals(33, it.pageCount)
            }
        } finally { file.delete() }
    }

    @Test fun liveArxivDownloadsRenderAndPersistWithoutSync() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveWebsiteImport") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "website-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val store = LibraryProjectStore(context, File(root, "store"))
            val project = JsonObject().apply { addProperty("id", "new:website-test"); addProperty("revision", 0) }
            store.enqueue("create", project, JsonObject().apply { addProperty("name", "Website test") })
            listOf("https://arxiv.org/abs/1706.03762").forEach { url ->
                val paper = PaperWebsite.resolve(url)
                val file = PaperPdfDownloader().download(paper, root)
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                    assertTrue(it.pageCount > 0)
                }
                store.stageImport(store.projects().single().asJsonObject, file, paper.displayName)
                file.delete()
            }
            val reopened = LibraryProjectStore(context, File(root, "store"))
            val docs = reopened.projects().single().asJsonObject.getAsJsonArray("documents")
            assertEquals(1, docs.size())
            assertEquals(2, reopened.pendingCount())
            docs.forEach { doc ->
                val local = File(doc.asJsonObject.get("local_path").asString)
                assertTrue(local.isFile)
                assertEquals(64, doc.asJsonObject.get("sha256").asString.length)
            }
            val invalid = File(root, "invalid.pdf").apply { writeText("<html>Not a PDF</html>") }
            assertTrue(runCatching { reopened.stageImport(reopened.projects().single().asJsonObject, invalid, "Invalid") }.isFailure)
            assertEquals(2, reopened.pendingCount())
            assertEquals(1, File(root, "store").listFiles()!!.count { it.extension == "pdf" })
        } finally { root.deleteRecursively() }
    }
}

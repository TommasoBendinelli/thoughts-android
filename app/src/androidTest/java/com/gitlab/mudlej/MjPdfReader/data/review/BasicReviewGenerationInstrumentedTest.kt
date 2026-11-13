// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BasicReviewGenerationInstrumentedTest {
    @Test fun localPdfSnapsBetweenPassagesAndRetainsSnapshotAfterReplacement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "basic-generation-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(root, "paper.pdf")
            val pdf = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                    val paint = Paint().apply { textSize = 20f }
                    page.canvas.drawText("Upper answer ${index + 1}", 30f, 100f, paint)
                    page.canvas.drawText("Lower answer ${index + 1}", 30f, 400f, paint)
                    pdf.finishPage(page)
                }
                file.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            val store = ReviewStore(null, File(root, "cards"), File(root, "incoming"), File(root, "events"), File(root, "outgoing"))
            val sha = basicPdfSha256(file)
            val generated = generateBasicReviewDocument(file, sha, store)
            assertTrue(generated.errors.isEmpty())
            assertTrue(generated.added >= 4)
            val count = generated.added
            assertTrue(store.cards().all { card ->
                card.regions.groupBy { it.page }.values.sumOf { regions ->
                    (regions.maxOf { it.rect.bottom } - regions.minOf { it.rect.top }).toDouble()
                } <= .500001
            })
            assertEquals(count, store.cards().size)
            assertTrue(store.cards().all { it.approved && it.reanchorState == "succeeded" })
            assertEquals(2, store.cards().map { it.pageVisualSha256 }.distinct().size)
            assertEquals(BasicReviewResult(0, count), generateBasicReviewDocument(file, sha, store))
            assertTrue(runCatching { generateBasicReviewDocument(file, "a".repeat(64), store) }.isFailure)
            assertEquals(count, store.cards().size)
            assertFalse(File(root, "events").exists())
            val card = store.cards().first()
            file.writeText("Replaced PDF")
            assertEquals(sha, basicPdfSha256(requireNotNull(store.reviewPdfFile(card))))
            assertEquals(count, store.dueCards(canonicalPdfSha256ByFilename = mapOf("paper.pdf" to "d".repeat(64))).size)
        } finally { root.deleteRecursively() }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Pdfium render + WebP encode path of the warm cache, on the sandbox app's cache dir. */
@RunWith(AndroidJUnit4::class)
class PdfWarmCacheInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var pdfDir: File
    private val previewRoot get() = File(context.cacheDir, PdfWarmCache.PREVIEW_DIRECTORY)

    @Before
    fun setUp() {
        pdfDir = File(context.cacheDir, "warm-cache-test-pdfs").apply { deleteRecursively(); mkdirs() }
        previewRoot.deleteRecursively()
    }

    @After
    fun tearDown() {
        pdfDir.deleteRecursively()
        previewRoot.deleteRecursively()
    }

    private fun textPdf(name: String, pages: Int): Pair<File, String> {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 10f; isAntiAlias = true }
        repeat(pages) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, index + 1).create())
            for (line in 0 until 60) {
                page.canvas.drawText("$name page ${index + 1} line $line: the quick brown fox jumps over the lazy dog", 54f, 60f + line * 11.5f, paint)
            }
            document.finishPage(page)
        }
        val file = File(pdfDir, "$name.pdf")
        file.outputStream().use(document::writeTo)
        document.close()
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return file to sha
    }

    @Test
    fun secondRunRendersNothingAndPreviewsDecodeAtTheReaderBucket() {
        File(context.cacheDir, "previews").apply { mkdirs(); File(this, "legacy.webp").writeBytes(ByteArray(10)) }
        val pdfs = (0 until 6).map { textPdf("doc$it", pages = if (it % 2 == 0) 12 else 3) }

        val first = PdfWarmCache(context).warm(pdfs) { true }
        Log.i("ThoughtsSyncTiming", "test first " + first.logLine())
        assertTrue(first.previews.pagesRendered == 3 * 3 + 3 * PdfWarmCache.FIRST_PAGE_COUNT)
        assertTrue(first.previews.bytesHeld <= PdfWarmCache.WARM_BUDGET_BYTES)
        assertTrue(!File(context.cacheDir, "previews").exists())

        val second = PdfWarmCache(context).warm(pdfs) { true }
        Log.i("ThoughtsSyncTiming", "test second " + second.logLine())
        assertEquals(0, second.previews.pagesRendered)
        assertEquals(0, second.previews.documentsOpened)
        assertEquals(0, second.geometryWritten)

        val bucket = PdfWarmCache.previewBucket(context)
        val (pdf, sha) = pdfs.first()
        val preview = File(
            File(previewRoot, PdfWarmCache.previewDocumentKey(sha, pdf.length())),
            PdfWarmCache.previewFilename(0, bucket),
        )
        val bytes = preview.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(bucket, bitmap.width)
        bitmap.recycle()
    }

    @Test
    fun constraintsRequireChargingIdleAndBatteryNotLow() {
        val constraints = pdfWarmCacheConstraints()
        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresDeviceIdle())
        assertTrue(constraints.requiresBatteryNotLow())
    }
}

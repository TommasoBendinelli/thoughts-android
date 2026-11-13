// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.data.source.*
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.google.gson.JsonParser
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompilerCommentRenderingInstrumentedTest {
    @Test fun drawsHitsNavigatesAndExportsTheRealCompilerFixture() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
            "This conformance test is restricted to an isolated emulator."
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        fun bytes(name: String) = assets.open("compiler-comments/$name").use { it.readBytes() }
        fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        val expected = JsonParser.parseString(bytes("expectations.json").decodeToString()).asJsonObject
        val manifest = JsonParser.parseString(bytes("artifact.json").decodeToString()).asJsonObject
        val pdfHash = expected.get("pdf_sha256").asString
        assertEquals(pdfHash, sha(bytes("corpus.pdf")))
        val pdfs = mapOf("manual.pdf" to OfflineBundlePdf("manual.pdf", pdfHash, 3, emptyList()))
        val correspondence = manifest.getAsJsonArray("review_correspondences").map {
            parseReviewCorrespondence(it.asJsonObject, pdfs)
        }.associateBy { it.itemId }
        val logical = manifest.getAsJsonArray("logical_comments").map { element ->
            val payload = element.asJsonObject.deepCopy()
            val id = payload.get("comment_id").asString
            if (id == "cell") { payload.addProperty("contents", "Latest on Mobile"); payload.addProperty("revision", 4) }
            val c = correspondence.getValue(id)
            OfflineLogicalComment(id, id, null, payload.get("revision").asLong, false, "source_linked",
                c.sourceTargets.first().sourceFile, c.sourceTargets.first().lineStart, "manual", 0,
                payload.toString(), c)
        }
        val source = File(context.cacheDir, "compiler-conformance.pdf").apply { writeBytes(bytes("corpus.pdf")) }
        val output = File(context.getExternalFilesDir(null), "compiler-conformance-export.pdf")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(source), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var view: PDFView
            var loaded = false
            val deadline = SystemClock.uptimeMillis() + 15000
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    view = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)).pdfView
                    loaded = view.pageCount == 3
                }
                if (!loaded) SystemClock.sleep(100)
            }
            assertTrue("Fixture PDF loaded", loaded)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            val specs = logicalCommentOverlaySpecs(logical, "manual.pdf", pdfHash)
            val overlays = specs.map { PDFView.LogicalCommentOverlay(it.commentId, it.pageIndex,
                it.left, it.top, it.right, it.bottom, it.selected, it.isPoint, it.sourceX ?: Float.NaN, it.sourceY ?: Float.NaN) }
            scenario.onActivity {
                val resolved = resolveStoredPdfComments(emptyList(), logical, "manual.pdf", pdfHash) { page ->
                    view.getPagePointSize(page)?.let { it.width to it.height }
                }
                assertEquals(logical.size, resolved.size)
                val cell = resolved.single { it.id == "cell" }
                assertEquals(2, cell.fragments.single().rects.size)
                assertEquals("Latest on Mobile", cell.contents)
                view.setManagedPdfComments(managedPdfCommentRecords(resolved))
                view.setLogicalCommentOverlays(overlays)
                assertEquals(logical.size, view.allPdfComments.size)
                val bubbleMethod = PDFView::class.java.getDeclaredMethod("logicalPointMarker", PDFView.LogicalCommentOverlay::class.java).apply { isAccessible = true }
                fun bubbleRect(overlay: PDFView.LogicalCommentOverlay): RectF {
                    val bubble = bubbleMethod.invoke(view, overlay)!!
                    fun n(name: String) = bubble.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(bubble)
                    return RectF(n("left"), n("top"), n("right"), n("bottom"))
                }
                for (zoom in listOf(0.7f, 1f, 1.7f)) {
                    view.zoomTo(zoom); view.jumpTo(0, false)
                    val points = overlays.filter { it.isPoint && it.pageIndex == 0 }
                    val bounds = points.map(::bubbleRect)
                    bounds.forEachIndexed { index, bound -> bounds.drop(index + 1).forEach { other ->
                        assertFalse("Nearby comments have distinct bubbles at zoom $zoom", RectF.intersects(bound, other))
                    } }
                    points.zip(bounds).forEach { (overlay, bound) ->
                        assertEquals(overlay.commentId, view.logicalCommentAt(bound.centerX()+view.currentXOffset, bound.centerY()+view.currentYOffset))
                    }
                }
                view.zoomTo(1f); view.jumpTo(0, false)
                val nativeFile = PDFView::class.java.getDeclaredField("pdfFile").apply { isAccessible = true }.get(view)
                val nativeComments = nativeFile.javaClass.getDeclaredMethod("getPdfComments", Int::class.javaPrimitiveType).apply { isAccessible = true }
                fun nativeCounts() = (0 until view.pageCount).map { (nativeComments.invoke(nativeFile, it) as List<*>).size }
                val beforeExport = nativeCounts()
                assertTrue(view.saveAsCopy(output))
                assertEquals("Export must not add native annotations to the open reader", beforeExport, nativeCounts())
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(600)
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(context.getExternalFilesDir(null), "compiler-conformance-page1.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }; image.recycle()
            }
            scenario.onActivity { view.jumpTo(2, false); assertEquals(2, view.currentPage) }
            instrumentation.waitForIdleSync(); SystemClock.sleep(600)
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(context.getExternalFilesDir(null), "compiler-conformance-float.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }; image.recycle()
            }
            assertEquals("Opening and exporting preserves the immutable source PDF", pdfHash, sha(source.readBytes()))
        }
        val core = PdfiumCore(context)
        val exported = core.newDocument(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            assertEquals(3, core.getPageCount(exported))
            val first = core.getPdfComments(exported, 0)
            assertEquals("Latest on Mobile", first.single { it.name == "cell" }.contents)
            assertTrue(core.getPdfComments(exported, 2).any { it.name == "float" })
            core.openPage(exported, 0)
            val cellIndex = first.single { it.name == "cell" }.annotationIndex
            val cellRects = core.getHighlightAnnotations(exported, 0).filter { it.annotationIndex == cellIndex }.map { it.bounds }
            assertEquals("Both exported word rectangles survive reopening", 2, cellRects.size)
            core.openTextPage(exported, 0)
            val cellQuote = buildString {
                val box = FloatArray(4)
                for (index in 0 until core.textCountChars(exported, 0)) {
                    if (core.tightCharBox(exported, 0, index, box) && cellRects.any { rect ->
                        val x = (box[0]+box[2])/2; val y = (box[1]+box[3])/2
                        x >= rect.left && x <= rect.right && y >= rect.bottom && y <= rect.top
                    }) appendCodePoint(core.charUnicode(exported, 0, index))
                }
            }
            assertTrue("Exported geometry covers Target cell: $cellQuote", cellQuote.contains("Target") && cellQuote.contains("cell"))
        } finally { core.closeDocument(exported) }
    }
}

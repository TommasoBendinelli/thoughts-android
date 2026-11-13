// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Text boxes from the Mac (thoughts-server protocol/document-feedback.md §11), read off the screen:
 * the text is drawn in the neutral colour from the top left of its box, wraps at the box's width,
 * keeps its line breaks, is as large as its font size says and grows with the zoom, and nothing is
 * filled; a text box typed on another PDF is not drawn, even after "Show", and is not counted in the
 * offer; a tap on the box opens its text without the kinds, and a tap elsewhere on the page does not.
 * The screen is saved to /data/local/tmp/document-feedback-text-box-*.png for review.
 */
@RunWith(AndroidJUnit4::class)
class DocumentFeedbackTextBoxInstrumentedTest {
    private val documentRecordTime = "2026-11-09T08:52:03.512Z"
    private val comment = "Why not the median?\nOr the mode."
    /** Narrower than the first line, so it wraps there. */
    private val box = RectF(0.2f, 0.4f, 0.45f, 0.5f)
    private val fontSize = 0.025f

    private fun record(documentId: String, sha: String, exact: String, extra: String) = JsonParser.parseString(
        """{"schema_version":1,"feedback_id":"${UUID.randomUUID()}",""" +
            """"anchor":{"version":1,"quote":{"exact":"$exact","prefix":"","suffix":""},"pdf":{"document_id":"$documentId",""" +
            """"pdf_sha256":"$sha","regions":[{"page":1,"rect":[0,0,1,1]}]}},"revision":1,""" +
            """"created_at":"$documentRecordTime","updated_at":"$documentRecordTime",""" +
            """"created_origin_id":"mac-okular","origin_id":"mac-okular","deleted":false,$extra}""",
    ).asJsonObject

    private fun textBox(documentId: String, sha: String, text: String): JsonObject = record(documentId, sha, "Text",
        """"polarity":"neutral","kind":"text","view":"pdf","comment":${com.google.gson.JsonPrimitive(text)},""" +
            """"text_box":{"rect":[${box.left},${box.top},${box.right},${box.bottom}],"font_size":$fontSize}""")

    /** Rows of [pixels] that hold some, in clusters separated by empty rows: the lines of a text. */
    private fun lines(pixels: List<Pair<Int, Int>>): List<IntRange> {
        val rows = pixels.map { it.second }.toSortedSet()
        val clusters = mutableListOf<IntRange>()
        var start = -1
        var last = -1
        for (row in rows) {
            if (start < 0) start = row
            else if (row > last + 1) {
                clusters += start..last
                start = row
            }
            last = row
        }
        if (start >= 0) clusters += start..last
        return clusters
    }

    @Test fun theTextIsDrawnOnItsOwnPdfAndNowhereElse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "document-feedback-text-box-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val previousFeedbackFile = DocumentFeedback.file
        DocumentFeedback.file = { File(root, "document-feedback.json") }
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                page.canvas.drawText("A page to write on", 45f, 65f, Paint().apply { textSize = 18f; isAntiAlias = true })
                document.finishPage(page)
                pdf.parentFile!!.mkdirs()
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes())
                .joinToString("") { "%02x".format(it) }
            val documentId = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore()
                .registerImport("$stem.pdf", hash, pdf)
            val older = "0".repeat(63) + "1"
            // The text box; the same typed on another PDF; a like from that PDF, which is what the offer
            // of earlier versions has to count; and a text box without text, which is not one.
            val own = textBox(documentId, hash, comment)
            val elsewhere = textBox(documentId, older, "Typed on the older PDF")
            val like = record(documentId, older, "A page", """"polarity":"positive","comment":""""")
            val empty = textBox(documentId, hash, " ")
            DocumentFeedback.installServer(listOf(own, elsewhere, like, empty))
            val ownId = own.get("feedback_id").asString
            assertEquals(3, DocumentFeedback.forDocument(documentId).size)

            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(Uri.fromFile(pdf), "application/pdf")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var binding: ActivityMainBinding
                lateinit var feedback: DocumentFeedbackController
                scenario.onActivity { activity ->
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                    feedback = MainActivity::class.java.getDeclaredField("documentFeedbackController")
                        .apply { isAccessible = true }.get(activity) as DocumentFeedbackController
                }
                fun awaitUi(message: String, condition: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    var matched = false
                    while (!matched && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity { matched = condition() }
                        if (!matched) SystemClock.sleep(50)
                    }
                    if (!matched) fail(message)
                }
                fun drawn(): List<PDFView.ReviewFeedbackHighlight> {
                    var value = emptyList<PDFView.ReviewFeedbackHighlight>()
                    scenario.onActivity { value = binding.pdfView.visibleDocumentFeedback }
                    return value
                }
                fun texts(view: View): List<String> = when (view) {
                    is TextView -> listOf(view.text.toString())
                    is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                    else -> emptyList()
                }
                fun clickText(view: View, text: String): Boolean = when {
                    view is TextView && view.text.toString() == text && view.isClickable -> view.performClick()
                    view is ViewGroup -> (0 until view.childCount).any { clickText(view.getChildAt(it), text) }
                    else -> false
                }
                fun windowTexts(): List<String> {
                    var value = emptyList<String>()
                    scenario.onActivity { value = WindowInspector.getGlobalWindowViews().flatMap(::texts) }
                    return value
                }
                fun screen(name: String): Bitmap {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(1000)
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/document-feedback-text-box-$name.png").close()
                    SystemClock.sleep(700)
                    return instrumentation.uiAutomation.takeScreenshot().copy(Bitmap.Config.ARGB_8888, false)
                }
                val toDocument = PDFView::class.java.getDeclaredMethod("normalizedTopLeftRectToDocument",
                    Int::class.javaPrimitiveType, RectF::class.java).apply { isAccessible = true }
                fun onDocument(rect: RectF): RectF = toDocument.invoke(binding.pdfView, 0, rect) as RectF

                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
                awaitUi("Only this PDF's text box is drawn") { drawn().map { it.id } == listOf(ownId) }

                // One from earlier versions: the like. Show brings it, not the old text box.
                val offer = context.resources.getQuantityString(R.plurals.review_feedback_earlier, 1, 1)
                awaitUi("Earlier versions offered") { windowTexts().contains(offer) }
                scenario.onActivity {
                    assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, context.getString(R.string.review_feedback_show)) })
                }
                SystemClock.sleep(1000)
                assertFalse(drawn().map { it.id }.contains(elsewhere.get("feedback_id").asString))
                assertTrue(drawn().map { it.id }.contains(ownId))

                val highlight = drawn().single { it.id == ownId }
                assertTrue(highlight.neutral)
                assertFalse("The text is shown, so there is no comment dot", highlight.hasComment)
                assertEquals(comment, highlight.text)
                assertEquals(fontSize, highlight.fontSize)
                assertEquals(listOf(0), highlight.pages)
                assertEquals(box, highlight.rects.single())
                assertEquals(null, highlight.strokes)

                // Where the box and the page are on the screen.
                val neutral = 0x1565C0
                fun isText(pixel: Int) = abs(Color.red(pixel) - Color.red(neutral)) < 24 &&
                    abs(Color.green(pixel) - Color.green(neutral)) < 24 && abs(Color.blue(pixel) - Color.blue(neutral)) < 24
                var viewLeft = 0
                var viewTop = 0
                var viewBottom = 0
                var onScreen = RectF()
                var pageHeight = 0f
                scenario.onActivity {
                    val at = IntArray(2)
                    binding.pdfView.getLocationOnScreen(at)
                    viewLeft = at[0]
                    viewTop = at[1]
                    viewBottom = at[1] + binding.pdfView.height
                    onScreen = onDocument(box).apply {
                        offset(binding.pdfView.currentXOffset + viewLeft, binding.pdfView.currentYOffset + viewTop)
                    }
                    pageHeight = onDocument(RectF(0f, 0f, 1f, 1f)).height()
                }
                val fontPx = fontSize * pageHeight
                val shot = screen("1-reading")
                // Around the box, wide enough to see the text where it should not be.
                val rowsSearched = maxOf(viewTop, (onScreen.top - 3 * fontPx).toInt()) until
                    minOf(viewBottom, shot.height, (onScreen.top + 10 * fontPx).toInt())
                val columnsSearched = maxOf(0, (onScreen.left - 3 * fontPx).toInt()) until
                    minOf(shot.width, (onScreen.right + 6 * fontPx).toInt())
                val text = rowsSearched.flatMap { y ->
                    columnsSearched.filter { x -> isText(shot.getPixel(x, y)) }.map { x -> x to y }
                }
                assertTrue("The text is on the screen", text.size > 150)
                val left = text.minOf { it.first }
                val right = text.maxOf { it.first }
                val top = text.minOf { it.second }
                val bottom = text.maxOf { it.second }
                val where = "text $left,$top-$right,$bottom; box $onScreen; font $fontPx px"
                // From the top left of the box, wrapping at its width, and nowhere else on the screen.
                assertTrue(where, left >= onScreen.left - 3 && left <= onScreen.left + fontPx)
                assertTrue(where, top >= onScreen.top - 3 && top <= onScreen.top + fontPx * 0.6f)
                assertTrue(where, right <= onScreen.right + 3)
                assertTrue(where, bottom <= onScreen.top + 6 * fontPx)
                // Two lines of its own and at least one more where the first wraps, a font size apart.
                val rows = lines(text)
                assertTrue("$where; lines $rows", rows.size >= 3)
                val spacings = rows.zipWithNext { a, b -> b.first - a.first }.sorted()
                val spacing = spacings[spacings.size / 2]
                assertTrue("$where; lines $rows", spacing >= 0.9f * fontPx && spacing <= 1.6f * fontPx)
                assertTrue("Only the letters are painted: nothing is filled", text.size < (right - left) * (bottom - top) * 0.4f)

                // A tap in the box finds it, a tap elsewhere on the page does not.
                scenario.onActivity {
                    assertEquals(ownId, binding.pdfView.findDocumentFeedbackAt(onScreen.centerX() - viewLeft, onScreen.centerY() - viewTop))
                    assertEquals(ownId, binding.pdfView.findDocumentFeedbackAt(onScreen.left + 2 - viewLeft, onScreen.top + 2 - viewTop))
                    assertEquals(null, binding.pdfView.findDocumentFeedbackAt(onScreen.centerX() - viewLeft, onScreen.bottom + 8 * fontPx - viewTop))
                    assertEquals(null, binding.pdfView.findDocumentFeedbackAt(onScreen.right + 3 * fontPx - viewLeft, onScreen.centerY() - viewTop))
                }

                // At twice the zoom the text is twice as large and wraps at the same words.
                fun drawnAt(zoom: Float): List<Pair<Int, Int>> {
                    var pixels = emptyList<Pair<Int, Int>>()
                    scenario.onActivity {
                        val view = binding.pdfView
                        val previous = view.zoom
                        view.zoomTo(zoom)
                        try {
                            val area = onDocument(box)
                            val bitmap = Bitmap.createBitmap((area.width() * 2).toInt() + 20, (area.height() * 4).toInt() + 20, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.WHITE)
                            canvas.translate(10f - area.left, 10f - area.top)
                            PDFView::class.java.getDeclaredMethod("drawDocumentFeedback", Canvas::class.java, Set::class.java)
                                .apply { isAccessible = true }.invoke(view, canvas, setOf(0))
                            pixels = (0 until bitmap.height).flatMap { y ->
                                (0 until bitmap.width).filter { x -> isText(bitmap.getPixel(x, y)) }.map { x -> x to y }
                            }
                            bitmap.recycle()
                        } finally {
                            view.zoomTo(previous)
                            view.invalidate()
                        }
                    }
                    return pixels
                }
                var zoom = 1f
                scenario.onActivity { zoom = binding.pdfView.zoom }
                val near = drawnAt(zoom)
                val far = drawnAt(zoom * 2)
                val nearLines = lines(near)
                val farLines = lines(far)
                assertEquals("$nearLines vs $farLines", nearLines.size, farLines.size)
                val heightRatio = (far.maxOf { it.second } - far.minOf { it.second }).toFloat() /
                    (near.maxOf { it.second } - near.minOf { it.second })
                val widthRatio = (far.maxOf { it.first } - far.minOf { it.first }).toFloat() /
                    (near.maxOf { it.first } - near.minOf { it.first })
                assertTrue("height × $heightRatio", heightRatio in 1.85f..2.15f)
                assertTrue("width × $widthRatio", widthRatio in 1.85f..2.15f)

                // The tap opens the text with nothing to choose or change.
                scenario.onActivity { feedback.open(ownId) }
                awaitUi("Text box shown") { windowTexts().contains(context.getString(R.string.review_feedback_text_box)) }
                // The dialog's own window: the activity behind it holds hidden views with such words.
                var shownTexts = emptyList<String>()
                scenario.onActivity { shownTexts = texts(WindowInspector.getGlobalWindowViews().last()) }
                assertTrue(shownTexts.toString(), shownTexts.contains(context.getString(R.string.review_feedback_text_box)))
                assertTrue(shownTexts.toString(), shownTexts.any { it.startsWith(comment) })
                listOf(R.string.review_feedback_kind_question, R.string.review_feedback_kind_other, R.string.review_feedback_like,
                    R.string.review_feedback_dislike, R.string.review_feedback_resolve, R.string.review_feedback_save,
                    R.string.review_feedback_delete).forEach { id ->
                    assertFalse(context.getString(id), shownTexts.contains(context.getString(id)))
                }
                screen("2-tapped")
            }
        } finally {
            DocumentFeedback.file = previousFeedbackFile
            pdf.delete()
            root.deleteRecursively()
        }
    }
}

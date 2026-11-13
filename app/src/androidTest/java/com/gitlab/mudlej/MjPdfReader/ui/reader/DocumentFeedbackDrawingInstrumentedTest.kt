// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
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
 * Drawings from the Mac (thoughts-server protocol/document-feedback.md §10), read off the screen:
 * the strokes are drawn in the neutral colour and the whole-page region is not; a drawing made on
 * another PDF is not drawn, even after "Show", and is not counted in the offer; a tap on the line
 * opens the drawing without the kinds, and a tap inside a circled area stays with the page. The
 * screen is saved to /data/local/tmp/document-feedback-drawing-*.png for review.
 */
@RunWith(AndroidJUnit4::class)
class DocumentFeedbackDrawingInstrumentedTest {
    private val documentRecordTime = "2026-11-05T08:52:03.512Z"

    private fun record(documentId: String, sha: String, extra: String) = JsonParser.parseString(
        """{"schema_version":1,"feedback_id":"${UUID.randomUUID()}","comment":"",""" +
            """"anchor":{"version":1,"quote":{"exact":"Drawing","prefix":"","suffix":""},"pdf":{"document_id":"$documentId",""" +
            """"pdf_sha256":"$sha","regions":[{"page":1,"rect":[0,0,1,1]}]}},"revision":1,""" +
            """"created_at":"$documentRecordTime","updated_at":"$documentRecordTime",""" +
            """"created_origin_id":"mac-okular","origin_id":"mac-okular","deleted":false,$extra}""",
    ).asJsonObject

    private fun drawing(documentId: String, sha: String, strokes: String): JsonObject =
        record(documentId, sha, """"polarity":"neutral","kind":"drawing","view":"pdf","drawing":{"strokes":$strokes}""")

    @Test fun strokesAreDrawnOnTheirOwnPdfAndNowhereElse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "document-feedback-drawing-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val previousFeedbackFile = DocumentFeedback.file
        DocumentFeedback.file = { File(root, "document-feedback.json") }
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                page.canvas.drawText("A page to draw on", 45f, 65f, Paint().apply { textSize = 18f; isAntiAlias = true })
                document.finishPage(page)
                pdf.parentFile!!.mkdirs()
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes())
                .joinToString("") { "%02x".format(it) }
            val documentId = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore()
                .registerImport("$stem.pdf", hash, pdf)
            val older = "0".repeat(63) + "1"
            // A curve around the middle of the page and a dot; the same curve made on another PDF;
            // a like from that PDF, which is what the offer of earlier versions has to count.
            val circle = (0..72).joinToString(",") { step ->
                val angle = Math.toRadians(step * 5.0)
                "[%.4f,%.4f]".format(java.util.Locale.ROOT, 0.5 + 0.25 * Math.cos(angle), 0.5 + 0.15 * Math.sin(angle))
            }
            val own = drawing(documentId, hash, """[{"points":[$circle]},{"points":[[0.2,0.9]]}]""")
            val elsewhere = drawing(documentId, older, """[{"points":[[0.1,0.1],[0.9,0.1]]}]""")
            val like = record(documentId, older, """"polarity":"positive"""")
            DocumentFeedback.installServer(listOf(own, elsewhere, like))
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
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/document-feedback-drawing-$name.png").close()
                    SystemClock.sleep(700)
                    return instrumentation.uiAutomation.takeScreenshot().copy(Bitmap.Config.ARGB_8888, false)
                }

                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
                awaitUi("Only this PDF's drawing is drawn") { drawn().map { it.id } == listOf(ownId) }
                val highlight = drawn().single()
                assertTrue(highlight.neutral)
                assertEquals(2, highlight.strokes!!.size)
                assertEquals(listOf(0), highlight.pages)

                // Off the screen: the pencil's colour forms the curve and the dot, and fills nothing.
                val neutral = 0x1565C0
                fun isPencil(pixel: Int) = abs(Color.red(pixel) - Color.red(neutral)) < 24 &&
                    abs(Color.green(pixel) - Color.green(neutral)) < 24 && abs(Color.blue(pixel) - Color.blue(neutral)) < 24
                var viewLeft = 0
                var viewTop = 0
                var viewBottom = 0
                scenario.onActivity {
                    val at = IntArray(2)
                    binding.pdfView.getLocationOnScreen(at)
                    viewLeft = at[0]
                    viewTop = at[1]
                    viewBottom = at[1] + binding.pdfView.height
                }
                val shot = screen("1-reading")
                val pencil = (viewTop until minOf(viewBottom, shot.height)).flatMap { y ->
                    (0 until shot.width).filter { x -> isPencil(shot.getPixel(x, y)) }.map { x -> x to y }
                }
                assertTrue("The strokes are on the screen", pencil.size > 200)
                val left = pencil.minOf { it.first }
                val right = pencil.maxOf { it.first }
                val curve = pencil.filter { it.first > (left + right) / 2 - (right - left) / 3 }
                val top = curve.minOf { it.second }
                val bottom = curve.maxOf { it.second }
                val centre = (left + right) / 2 + (right - left) / 8 to (top + bottom) / 2
                assertTrue("The line is thin: nothing fills the page", pencil.size < (right - left) * (bottom - top) / 4)
                assertFalse("The middle of the circle is not painted", isPencil(shot.getPixel(centre.first, centre.second)))

                // A tap on the line finds it, a tap inside it stays with the page.
                val onLine = curve.first { it.second == top }
                scenario.onActivity {
                    assertEquals(ownId, binding.pdfView.findDocumentFeedbackAt((onLine.first - viewLeft).toFloat(), (onLine.second - viewTop).toFloat()))
                    assertEquals(null, binding.pdfView.findDocumentFeedbackAt((centre.first - viewLeft).toFloat(), (centre.second - viewTop).toFloat()))
                }

                // One from earlier versions: the like. Show brings it, not the old drawing.
                val offer = context.resources.getQuantityString(R.plurals.review_feedback_earlier, 1, 1)
                awaitUi("Earlier versions offered") { windowTexts().contains(offer) }
                scenario.onActivity {
                    assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, context.getString(R.string.review_feedback_show)) })
                }
                SystemClock.sleep(1000)
                assertFalse(drawn().map { it.id }.contains(elsewhere.get("feedback_id").asString))
                assertTrue(drawn().map { it.id }.contains(ownId))

                // The tap opens the drawing with nothing to choose or change.
                scenario.onActivity { feedback.open(ownId) }
                awaitUi("Drawing shown") { windowTexts().contains(context.getString(R.string.review_feedback_drawing)) }
                // The dialog's own window: the activity behind it holds hidden views with such words.
                var shownTexts = emptyList<String>()
                scenario.onActivity { shownTexts = texts(WindowInspector.getGlobalWindowViews().last()) }
                assertTrue(shownTexts.toString(), shownTexts.contains(context.getString(R.string.review_feedback_drawing)))
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

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.UiAutomation
import android.content.Intent
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
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.ReviewFeedbackActions
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Document feedback (thoughts-server protocol/document-feedback.md §5, Android conformance): liked
 * and disliked passages of a Library PDF are drawn in normal reading, from their regions on the
 * same PDF and from their quote on another version; during a review, except those in the card's
 * answer while it is masked or revealed, and all again once the review ends. Like on a selection creates a record
 * tied to the document and the displayed PDF; card feedback takes a selection in a revealed answer
 * instead; a highlight opens the editor, and Delete offers Undo. Screens are saved to
 * /data/local/tmp/document-feedback-*.png for review.
 */
@RunWith(AndroidJUnit4::class)
class DocumentFeedbackInstrumentedTest {
    @Test fun feedbackIsDrawnInReadingAndOutsideTheAnswerDuringAReview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "document-feedback-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val store = ReviewStore(null, File(root, "review"), File(root, "incoming"),
            File(root, "events"), File(root, "cards"))
        val previousFeedbackFile = DocumentFeedback.file
        val previousCardFeedbackFile = ReviewCardFeedback.file
        DocumentFeedback.file = { File(root, "document-feedback.json") }
        ReviewCardFeedback.file = { File(root, "card-feedback.json") }
        try {
            // Page 600 × 800 pt: a question line, two answer lines, a line below the answer.
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                val paint = Paint().apply { textSize = 18f; isAntiAlias = true }
                page.canvas.drawText("Question area above the answer", 45f, 65f, paint)
                page.canvas.drawText("The KL term keeps the policy close", 45f, 165f, paint)
                page.canvas.drawText("to the reference model at all times.", 45f, 195f, paint)
                page.canvas.drawText("Unrelated text below the answer area", 45f, 320f, paint)
                document.finishPage(page)
                pdf.parentFile!!.mkdirs()
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes())
                .joinToString("") { "%02x".format(it) }
            val documentId = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore()
                .registerImport("$stem.pdf", hash, pdf)
            // A liked passage made on this PDF, a disliked one made on an older version (found by
            // its quote), and one whose text is gone (orphaned: kept, not drawn).
            val exact = DocumentFeedback.create(ReviewCardFeedback.Polarity.POSITIVE, "",
                ReviewCardFeedback.NewAnchor("Question area", "", " above", documentId, hash,
                    listOf(ReviewCardFeedback.Region(1, .07, .06, .3, .085))), "test")
            val relocated = DocumentFeedback.create(ReviewCardFeedback.Polarity.NEGATIVE, "Too vague.",
                ReviewCardFeedback.NewAnchor("Unrelated text", "", " below", documentId, "0".repeat(63) + "1",
                    listOf(ReviewCardFeedback.Region(1, .07, .5, .3, .52))), "test")
            val orphan = DocumentFeedback.create(ReviewCardFeedback.Polarity.POSITIVE, "",
                ReviewCardFeedback.NewAnchor("the entropy bonus", "", "", documentId, "0".repeat(63) + "1",
                    listOf(ReviewCardFeedback.Region(1, .07, .5, .3, .52))), "test")
            // A card whose answer is the two middle lines.
            val cardId = UUID.randomUUID().toString()
            val question = "What keeps the policy close?"
            File(root, "incoming/cards/server/$cardId.json").apply {
                parentFile!!.mkdirs()
                writeText(JsonObject().apply {
                    addProperty("schema_version", 3)
                    addProperty("card_type", "visual_region")
                    addProperty("card_id", cardId)
                    addProperty("status", "approved")
                    addProperty("revision", 1)
                    addProperty("origin_id", "test-server")
                    add("primary", JsonObject().apply {
                        addProperty("pdf_stem", stem)
                        addProperty("pdf_sha256", hash)
                        addProperty("document_id", documentId)
                        addProperty("page", 1)
                        addProperty("page_visual_sha256", hash)
                        addProperty("question", question)
                        add("rect", JsonArray().apply { listOf(.05f, .17f, .9f, .27f).forEach { add(it) } })
                    })
                    add("reanchor", JsonObject().apply {
                        addProperty("state", "succeeded")
                        addProperty("strategy", "source_markers_v2")
                    })
                }.toString())
            }
            val card: ReviewCard = checkNotNull(store.card(cardId))
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
                fun screenshot(name: String) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/document-feedback-$name.png").close()
                    SystemClock.sleep(700)
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

                // Normal reading: the exact and the relocated passage are drawn; the orphan is not.
                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
                awaitUi("Feedback drawn in normal reading") { drawn().map { it.id }.toSet() == setOf(exact.id, relocated.id) }
                val dislike = drawn().single { it.id == relocated.id }
                assertFalse(dislike.positive)
                assertTrue(dislike.hasComment)
                assertEquals(listOf(0), dislike.pages)
                assertTrue(DocumentFeedback.find(orphan.id)?.deleted == false)
                screenshot("1-reading")

                // Any selection qualifies in normal reading; Like creates a record of this PDF at once.
                val line = PDFView.HighlightRequest(0, listOf(RectF(45f, 800f - 152f, 250f, 800f - 168f)), "The KL term keeps")
                scenario.onActivity { assertTrue(feedback.qualifies(line)) }
                scenario.onActivity { feedback.give(line, ReviewFeedbackActions.Action.LIKE) }
                awaitUi("Liked passage drawn") { drawn().size == 3 }
                val liked = DocumentFeedback.forDocument(documentId).single { it.exact == "The KL term keeps" }
                assertEquals(ReviewCardFeedback.Polarity.POSITIVE, liked.polarity)
                assertEquals("", liked.comment)
                assertEquals(hash, liked.pdfSha256)
                assertEquals(documentId, liked.pdfDocumentId)
                assertEquals("", liked.cardId)
                assertTrue(drawn().last().positive)
                assertEquals(1, DocumentFeedback.pendingUploads().count { it.entityId == liked.id })
                assertEquals(emptyList<ReviewCardFeedback.Feedback>(), ReviewCardFeedback.forCard(cardId))

                // The highlight opens the editor; Delete removes it at once and offers Undo.
                scenario.onActivity { feedback.open(liked.id) }
                awaitUi("Editor shown") { windowTexts().contains(context.getString(R.string.review_feedback_delete)) }
                screenshot("2-editor")
                scenario.onActivity {
                    assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, context.getString(R.string.review_feedback_delete)) })
                }
                awaitUi("Deleted") { DocumentFeedback.find(liked.id)?.deleted == true && drawn().size == 2 }
                awaitUi("Undo offered") { windowTexts().contains(context.getString(R.string.review_feedback_undo)) }
                scenario.onActivity {
                    assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, context.getString(R.string.review_feedback_undo)) })
                }
                awaitUi("Restored") { DocumentFeedback.find(liked.id)?.let { !it.deleted && it.revision == 3L } == true && drawn().size == 3 }

                // A review: the passages outside the answer stay drawn, masked or revealed; the liked
                // line in the answer is not (card feedback is shown there).
                lateinit var controller: ReviewSessionController
                scenario.onActivity { activity ->
                    controller = ReviewSessionController(activity, binding,
                        DocumentState(uri = Uri.fromFile(pdf), annotationPdfSha256 = hash, documentId = documentId), store)
                    MainActivity::class.java.getDeclaredField("reviewSessionController")
                        .apply { isAccessible = true }.set(activity, controller)
                    ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java,
                        ReviewInterval::class.java, Map::class.java).apply { isAccessible = true }
                        .invoke(controller, card, ReviewInterval.suggested(null, null), emptyMap<String, Int>())
                }
                awaitUi("Card shown") { binding.reviewQuestion.text.toString() == question }
                awaitUi("Answer's passage hidden while masked") { drawn().map { it.id }.toSet() == setOf(exact.id, relocated.id) }
                scenario.onActivity { assertEquals(null, binding.pdfView.findDocumentFeedbackAt(0f, 0f)) }
                screenshot("3-masked")
                scenario.onActivity { binding.reviewRevealButton.performClick() }
                SystemClock.sleep(500)
                assertEquals(setOf(exact.id, relocated.id), drawn().map { it.id }.toSet())
                // Card feedback takes a selection inside the revealed answer: one selection never creates both.
                scenario.onActivity { assertTrue(controller.feedbackActions.qualifies(line)) }
                screenshot("4-revealed")

                // The review ends: normal reading draws the document feedback again.
                scenario.onActivity {
                    ReviewSessionController::class.java.getDeclaredMethod("endReview").apply { isAccessible = true }.invoke(controller)
                }
                awaitUi("Drawn again after the review") { drawn().size == 3 }
                screenshot("5-after-review")
            }
        } finally {
            DocumentFeedback.file = previousFeedbackFile
            ReviewCardFeedback.file = previousCardFeedbackFile
            pdf.delete()
            root.deleteRecursively()
        }
    }

    /**
     * Change passage in normal reading: the editor selects the highlight's passage with handles,
     * Save passage on the same passage saves the editor's changes to the same record, a wider
     * passage replaces it with its kind and comment (Undo brings it back), and Cancel changes nothing.
     */
    @Test fun changePassageMovesADocumentHighlight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "document-feedback-passage-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val previousFeedbackFile = DocumentFeedback.file
        val previousCardFeedbackFile = ReviewCardFeedback.file
        DocumentFeedback.file = { File(root, "document-feedback.json") }
        ReviewCardFeedback.file = { File(root, "card-feedback.json") }
        val paint = Paint().apply { textSize = 18f; isAntiAlias = true }
        val firstLine = "The KL term keeps the policy close"
        fun lineRegion(text: String) = ReviewCardFeedback.Region(1, 45.0 / 600, (165.0 + paint.ascent()) / 800,
            (45.0 + paint.measureText(text)) / 600, (165.0 + paint.descent()) / 800)
        fun request(text: String) = PDFView.HighlightRequest(0,
            listOf(RectF(45f, 800f - (165f + paint.ascent()), 45f + paint.measureText(text), 800f - (165f + paint.descent()))), text)
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                page.canvas.drawText("Question area above the answer", 45f, 65f, paint)
                page.canvas.drawText(firstLine, 45f, 165f, paint)
                page.canvas.drawText("to the reference model at all times.", 45f, 195f, paint)
                document.finishPage(page)
                pdf.parentFile!!.mkdirs()
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes())
                .joinToString("") { "%02x".format(it) }
            val documentId = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore()
                .registerImport("$stem.pdf", hash, pdf)
            // A disliked passage with a comment, as in the reader: dashed outline and a dot.
            val narrow = DocumentFeedback.create(ReviewCardFeedback.Polarity.NEGATIVE, "Too vague.",
                ReviewCardFeedback.NewAnchor("The KL term keeps", "", " the policy close", documentId, hash,
                    listOf(lineRegion("The KL term keeps"))), "test")
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
                fun drawn(): List<String> {
                    var value = emptyList<String>()
                    scenario.onActivity { value = binding.pdfView.visibleDocumentFeedback.map { it.id } }
                    return value
                }
                fun screenshot(name: String) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/document-feedback-$name.png").close()
                    SystemClock.sleep(700)
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
                fun <T : View> windowView(viewId: Int): T? = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById<T>(viewId) }
                fun live() = DocumentFeedback.forDocument(documentId)
                fun changePassage(feedbackId: String, comment: String? = null) {
                    scenario.onActivity { feedback.open(feedbackId) }
                    awaitUi("Editor shown") { windowView<View>(R.id.reviewFeedbackChangePassage)?.isShown == true }
                    scenario.onActivity {
                        comment?.let { windowView<android.widget.EditText>(R.id.reviewFeedbackComment)!!.setText(it) }
                        assertTrue(windowView<View>(R.id.reviewFeedbackChangePassage)!!.performClick())
                    }
                    awaitUi("Passage selected") { feedback.isChangingPassage() && binding.pdfView.hasTextSelection() &&
                        binding.textSelectionSavePassageButton.isShown }
                }

                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
                awaitUi("Feedback drawn") { drawn() == listOf(narrow.id) }
                screenshot("10-passage-before")

                changePassage(narrow.id, comment = "Too vague, and too short.")
                scenario.onActivity {
                    assertEquals("The KL term keeps", binding.pdfView.selectedText)
                    assertEquals(View.GONE, binding.textSelectionCopyButton.visibility)
                    assertEquals(View.GONE, binding.textSelectionFeedbackDislikeButton.visibility)
                }
                assertEquals(emptyList<String>(), drawn())
                screenshot("11-passage-selected")

                // Save passage on the unchanged passage keeps the record and saves the new comment.
                scenario.onActivity { assertTrue(binding.textSelectionSavePassageButton.performClick()) }
                awaitUi("Same record, comment saved") { DocumentFeedback.find(narrow.id)?.comment == "Too vague, and too short." }
                awaitUi("Mode ended") { !feedback.isChangingPassage() && !binding.pdfView.hasTextSelection() && drawn() == listOf(narrow.id) }

                // The whole line: a new record with the same polarity and comment; the old one deleted.
                changePassage(narrow.id)
                scenario.onActivity {
                    feedback.savePassage(request(firstLine))
                    assertFalse(feedback.isChangingPassage())
                    binding.pdfView.clearTextSelection()
                }
                awaitUi("Replaced") { live().singleOrNull()?.exact == firstLine }
                val wide = live().single()
                assertEquals(ReviewCardFeedback.Polarity.NEGATIVE, wide.polarity)
                assertEquals("Too vague, and too short.", wide.comment)
                assertEquals(hash, wide.pdfSha256)
                assertTrue(DocumentFeedback.find(narrow.id)!!.deleted)
                assertEquals(setOf(narrow.id, wide.id), DocumentFeedback.pendingUploads().map { it.entityId }.toSet())
                awaitUi("New highlight drawn") { drawn() == listOf(wide.id) }
                awaitUi("Passage changed · Undo") { windowTexts().contains(context.getString(R.string.review_feedback_passage_changed)) }
                screenshot("12-passage-changed")

                scenario.onActivity {
                    assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, context.getString(R.string.review_feedback_undo)) })
                }
                awaitUi("Old passage restored") { live().map { it.id } == listOf(narrow.id) && drawn() == listOf(narrow.id) }
                assertTrue(DocumentFeedback.find(wide.id)!!.deleted)

                // Cancel changes nothing.
                changePassage(narrow.id)
                scenario.onActivity { assertTrue(binding.textSelectionCancelPassageButton.performClick()) }
                awaitUi("Cancelled") { !feedback.isChangingPassage() && !binding.pdfView.hasTextSelection() && drawn() == listOf(narrow.id) }
                assertEquals(listOf(narrow.id), live().map { it.id })
                assertEquals("The KL term keeps", live().single().exact)

                // There is no Save or Cancel: closing the editor (here with Back) saves it.
                scenario.onActivity { feedback.open(narrow.id) }
                awaitUi("Editor shown") { windowView<View>(R.id.reviewFeedbackDelete)?.isShown == true }
                scenario.onActivity {
                    assertFalse(WindowInspector.getGlobalWindowViews().flatMap(::texts).contains(context.getString(R.string.review_feedback_save)))
                    windowView<android.widget.EditText>(R.id.reviewFeedbackComment)!!.setText("Closed with Back.")
                }
                screenshot("13-editor")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                awaitUi("Saved on close") { DocumentFeedback.find(narrow.id)?.comment == "Closed with Back." }
                // A question needs its text: closed without it, nothing is saved and the reader is told.
                scenario.onActivity { feedback.open(narrow.id) }
                awaitUi("Editor shown again") { windowView<View>(R.id.reviewFeedbackDelete)?.isShown == true }
                scenario.onActivity {
                    assertTrue(windowView<View>(R.id.reviewFeedbackQuestion)!!.performClick())
                    windowView<android.widget.EditText>(R.id.reviewFeedbackComment)!!.setText("")
                }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                awaitUi("Not saved") { windowTexts().contains(context.getString(R.string.review_feedback_not_saved_needs_text)) }
                assertEquals(null, DocumentFeedback.find(narrow.id)?.kind)
                assertEquals("Closed with Back.", DocumentFeedback.find(narrow.id)?.comment)
            }
        } finally {
            DocumentFeedback.file = previousFeedbackFile
            ReviewCardFeedback.file = previousCardFeedbackFile
            pdf.delete()
            root.deleteRecursively()
        }
    }
}

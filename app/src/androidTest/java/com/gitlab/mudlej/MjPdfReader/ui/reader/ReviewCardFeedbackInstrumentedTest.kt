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
 * Review card feedback on the review screen (thoughts-server protocol/review-card-feedback.md,
 * conformance C5, C6, C8): highlights are drawn only after Reveal or Set knowledge and never while
 * the answer is masked or the review has ended; only selections inside the answer get Like /
 * Dislike / Comment…; feedback not found in the answer is listed under its heading and can be
 * deleted. Feedback is shared across the cards of a document: another card whose answer holds
 * the passage shows it ("From another card") and edits that same record; a card whose answer does
 * not hold it shows nothing. Screens are saved to /data/local/tmp/card-feedback-*.png for review.
 */
@RunWith(AndroidJUnit4::class)
class ReviewCardFeedbackInstrumentedTest {
    @Test fun feedbackIsDrawnOnlyWhileTheAnswerIsVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "review-card-feedback-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val store = ReviewStore(null, File(root, "review"), File(root, "incoming"),
            File(root, "events"), File(root, "cards"))
        val previousFeedbackFile = ReviewCardFeedback.file
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
            val id = UUID.randomUUID().toString()
            val question = "What keeps the policy close?"
            val answerRect = listOf(.05f, .17f, .9f, .27f)
            val record = JsonObject().apply {
                addProperty("schema_version", 3)
                addProperty("card_type", "visual_region")
                addProperty("card_id", id)
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
                    add("rect", JsonArray().apply { answerRect.forEach { add(it) } })
                })
                add("reanchor", JsonObject().apply {
                    addProperty("state", "succeeded")
                    addProperty("strategy", "source_markers_v2")
                })
            }
            File(root, "incoming/cards/server/$id.json").apply {
                parentFile!!.mkdirs()
                writeText(record.toString())
            }
            val card: ReviewCard = checkNotNull(store.card(id))
            // One disliked passage made on another PDF version, found again by its quote, and one whose
            // text is gone from the answer.
            val anchor = ReviewCardFeedback.NewAnchor("keeps the policy close", "The KL term ", "\nto the",
                documentId, "0".repeat(64), listOf(ReviewCardFeedback.Region(1, .1, .19, .5, .22)))
            ReviewCardFeedback.create(id, ReviewCardFeedback.Polarity.NEGATIVE, "Too vague.", anchor, "test")
            val orphan = ReviewCardFeedback.create(id, ReviewCardFeedback.Polarity.POSITIVE, "",
                anchor.copy(exact = "the entropy bonus", prefix = "", suffix = ""), "test")
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(Uri.fromFile(pdf), "application/pdf")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var binding: ActivityMainBinding
                lateinit var controller: ReviewSessionController
                scenario.onActivity { activity ->
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
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
                    scenario.onActivity { value = binding.pdfView.visibleReviewFeedback }
                    return value
                }
                fun screenshot(name: String) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/card-feedback-$name.png").close()
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

                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
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

                // C5: masked → nothing drawn or counted.
                SystemClock.sleep(500)
                assertEquals(emptyList<PDFView.ReviewFeedbackHighlight>(), drawn())
                scenario.onActivity { assertEquals(View.GONE, binding.reviewFeedbackSummary.visibility) }
                screenshot("1-masked")

                // C6 while masked: no feedback actions even inside the answer.
                val inside = PDFView.HighlightRequest(0, listOf(RectF(45f, 800f - 152f, 250f, 800f - 168f)),
                    "The KL term keeps")
                val outside = PDFView.HighlightRequest(0, listOf(RectF(45f, 800f - 307f, 250f, 800f - 323f)),
                    "Unrelated text")
                scenario.onActivity { assertFalse(controller.feedbackActions.qualifies(inside)) }

                // Reveal → the relocated disliked passage is drawn; the other is "not found".
                scenario.onActivity { binding.reviewRevealButton.performClick() }
                awaitUi("Feedback drawn after Reveal") { drawn().size == 1 }
                val highlight = drawn().single()
                assertFalse(highlight.positive)
                assertTrue(highlight.hasComment)
                scenario.onActivity {
                    assertEquals(View.VISIBLE, binding.reviewFeedbackSummary.visibility)
                    assertEquals("1 liked · 1 disliked (1 not found)", binding.reviewFeedbackSummary.text.toString())
                }
                screenshot("2-revealed-light")

                // C6: inside the answer qualifies, partly outside does not.
                scenario.onActivity {
                    assertTrue(controller.feedbackActions.qualifies(inside))
                    assertFalse(controller.feedbackActions.qualifies(outside))
                    assertFalse(controller.feedbackActions.qualifies(PDFView.HighlightRequest(
                        listOf(PDFView.HighlightFragment(0, listOf(RectF(45f, 800f - 152f, 250f, 800f - 168f)), "The KL"),
                            PDFView.HighlightFragment(0, listOf(RectF(45f, 800f - 307f, 250f, 800f - 323f)), "Unrelated")))))
                }
                // Like on the selection: saved at once, positive, empty comment, drawn on top.
                scenario.onActivity { controller.feedbackActions.give(inside, ReviewFeedbackActions.Action.LIKE) }
                awaitUi("Liked passage drawn") { drawn().size == 2 }
                val liked = ReviewCardFeedback.forCard(id).single { it.exact == "The KL term keeps" }
                assertEquals(ReviewCardFeedback.Polarity.POSITIVE, liked.polarity)
                assertEquals("", liked.comment)
                assertEquals(hash, liked.pdfSha256)
                assertEquals(documentId, liked.pdfDocumentId)
                assertTrue(drawn().last().positive)
                scenario.onActivity { binding.pdfView.setNightMode(true); binding.pdfView.invalidate() }
                awaitUi("Feedback kept in dark mode") { drawn().size == 2 }
                screenshot("3-revealed-dark")
                scenario.onActivity { binding.pdfView.setNightMode(false); binding.pdfView.invalidate() }

                // Hide → masked again, nothing drawn.
                scenario.onActivity { binding.reviewRevealButton.performClick() }
                awaitUi("Feedback hidden with the answer") { drawn().isEmpty() }
                scenario.onActivity { assertEquals(View.GONE, binding.reviewFeedbackSummary.visibility) }

                // Set knowledge reveals the answer, and the feedback with it.
                scenario.onActivity { binding.reviewSetKnowledgeButton.performClick() }
                awaitUi("Feedback drawn after Set knowledge") { drawn().size == 2 }

                // C8: the list shows the passage not found under its heading; delete it from its editor.
                scenario.onActivity { binding.reviewFeedbackSummary.performClick() }
                instrumentation.waitForIdleSync()
                awaitUi("Feedback list shown") {
                    WindowInspector.getGlobalWindowViews().flatMap(::texts)
                        .contains(context.getString(R.string.review_feedback_not_found))
                }
                screenshot("4-list")
                val orphanLine = windowTexts().single { it.contains("the entropy bonus") }
                scenario.onActivity { assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, orphanLine) }) }
                awaitUi("Editor shown") { WindowInspector.getGlobalWindowViews().flatMap(::texts).contains(context.getString(R.string.review_feedback_delete)) }
                // The chosen polarity is a filled segment in its colour, the other one outlined; TalkBack hears "checked".
                fun toggle(id: Int): com.google.android.material.button.MaterialButton =
                    WindowInspector.getGlobalWindowViews().firstNotNullOf { it.findViewById(id) }
                fun fill(button: com.google.android.material.button.MaterialButton) =
                    button.backgroundTintList!!.getColorForState(button.drawableState, android.graphics.Color.MAGENTA)
                val transparent = android.graphics.Color.TRANSPARENT
                // What is actually painted near the segment's start edge (between the corner and the icon).
                fun painted(button: com.google.android.material.button.MaterialButton): Int {
                    val bitmap = android.graphics.Bitmap.createBitmap(button.width, button.height, android.graphics.Bitmap.Config.ARGB_8888)
                    button.draw(android.graphics.Canvas(bitmap))
                    return bitmap.getPixel(button.width / 2, (button.height * 0.2f).toInt())
                }
                scenario.onActivity {
                    val like = toggle(R.id.reviewFeedbackLike)
                    val dislike = toggle(R.id.reviewFeedbackDislike)
                    assertTrue(like.isChecked)
                    assertFalse(dislike.isChecked)
                    assertEquals(context.getColor(R.color.review_feedback_positive), fill(like))
                    assertEquals(transparent, fill(dislike))
                    assertEquals(context.getColor(R.color.review_feedback_positive), painted(like))
                    assertEquals(transparent, painted(dislike))
                    assertEquals(context.getColor(R.color.review_feedback_on_selected), like.currentTextColor)
                    val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
                    like.onInitializeAccessibilityNodeInfo(info)
                    assertTrue(info.isCheckable && info.isChecked)
                }
                screenshot("5-editor-like")
                scenario.onActivity { toggle(R.id.reviewFeedbackDislike).performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val like = toggle(R.id.reviewFeedbackLike)
                    val dislike = toggle(R.id.reviewFeedbackDislike)
                    assertFalse(like.isChecked)
                    assertTrue(dislike.isChecked)
                    assertEquals(context.getColor(R.color.review_feedback_negative), fill(dislike))
                    assertEquals(transparent, fill(like))
                    assertEquals(context.getColor(R.color.review_feedback_negative), painted(dislike))
                }
                screenshot("5-editor-dislike")
                scenario.onActivity {
                    assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, context.getString(R.string.review_feedback_delete)) })
                }
                awaitUi("Orphan deleted") { ReviewCardFeedback.find(orphan.id)?.deleted == true }
                awaitUi("Count updated") { binding.reviewFeedbackSummary.text.toString() == "1 liked · 1 disliked" }
                assertEquals(2, drawn().size)
                screenshot("6-deleted-undo")

                // Ending the review (normal reading) leaves nothing drawn.
                scenario.onActivity {
                    ReviewSessionController::class.java.getDeclaredMethod("endReview").apply { isAccessible = true }.invoke(controller)
                }
                awaitUi("No feedback after the review") { drawn().isEmpty() && binding.reviewFeedbackSummary.visibility == View.GONE }
            }
        } finally {
            ReviewCardFeedback.file = previousFeedbackFile
            pdf.delete()
            root.deleteRecursively()
        }
    }

    @Test fun feedbackIsSharedWithTheCardsWhoseAnswerHoldsThePassage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "review-card-feedback-shared-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val store = ReviewStore(null, File(root, "review"), File(root, "incoming"),
            File(root, "events"), File(root, "cards"))
        val previousFeedbackFile = ReviewCardFeedback.file
        ReviewCardFeedback.file = { File(root, "card-feedback.json") }
        try {
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
            fun cardOn(question: String, answerRect: List<Float>): ReviewCard {
                val id = UUID.randomUUID().toString()
                val record = JsonObject().apply {
                    addProperty("schema_version", 3)
                    addProperty("card_type", "visual_region")
                    addProperty("card_id", id)
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
                        add("rect", JsonArray().apply { answerRect.forEach { add(it) } })
                    })
                    add("reanchor", JsonObject().apply {
                        addProperty("state", "succeeded")
                        addProperty("strategy", "source_markers_v2")
                    })
                }
                File(root, "incoming/cards/server/$id.json").apply {
                    parentFile!!.mkdirs()
                    writeText(record.toString())
                }
                return checkNotNull(store.card(id))
            }
            // Card A: both answer lines. Card B: only the first line. Card C: the line below.
            val cardA = cardOn("What keeps the policy close?", listOf(.05f, .17f, .9f, .27f))
            val cardB = cardOn("What does the KL term do?", listOf(.05f, .17f, .9f, .225f))
            val cardC = cardOn("What is below the answer?", listOf(.05f, .37f, .9f, .42f))
            // Made on card A: a liked passage of the first line on this PDF, and a disliked passage of
            // the second line on an older PDF version (found again only in an answer that holds it).
            val shared = ReviewCardFeedback.create(cardA.id, ReviewCardFeedback.Polarity.POSITIVE, "",
                ReviewCardFeedback.NewAnchor("The KL term keeps", "", " the policy close", documentId, hash,
                    listOf(ReviewCardFeedback.Region(1, .075, .19, .42, .21))), "test")
            val secondLine = ReviewCardFeedback.create(cardA.id, ReviewCardFeedback.Polarity.NEGATIVE, "Vague.",
                ReviewCardFeedback.NewAnchor("to the reference model", "policy close\n", " at all", documentId, "0".repeat(64),
                    listOf(ReviewCardFeedback.Region(1, .075, .23, .45, .25))), "test")
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(Uri.fromFile(pdf), "application/pdf")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var binding: ActivityMainBinding
                lateinit var controller: ReviewSessionController
                scenario.onActivity { activity ->
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
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
                    scenario.onActivity { value = binding.pdfView.visibleReviewFeedback }
                    return value
                }
                fun screenshot(name: String) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/card-feedback-$name.png").close()
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
                fun reveal(card: ReviewCard) {
                    scenario.onActivity {
                        ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java,
                            ReviewInterval::class.java, Map::class.java).apply { isAccessible = true }
                            .invoke(controller, card, ReviewInterval.suggested(null, null), emptyMap<String, Int>())
                    }
                    awaitUi("Card shown") { binding.reviewQuestion.text.toString() == card.question }
                    scenario.onActivity { binding.reviewRevealButton.performClick() }
                }
                val fromA = context.getString(R.string.review_feedback_from_another_card_question, cardA.question)

                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
                scenario.onActivity { activity ->
                    controller = ReviewSessionController(activity, binding,
                        DocumentState(uri = Uri.fromFile(pdf), annotationPdfSha256 = hash, documentId = documentId), store)
                    MainActivity::class.java.getDeclaredField("reviewSessionController")
                        .apply { isAccessible = true }.set(activity, controller)
                }

                // Card A, the home card: both records, as before.
                reveal(cardA)
                awaitUi("Card A's feedback drawn") { drawn().size == 2 }
                scenario.onActivity { assertEquals("1 liked · 1 disliked", binding.reviewFeedbackSummary.text.toString()) }

                // Card B holds the first line only: the liked passage is shared, the second line is not.
                reveal(cardB)
                awaitUi("Shared feedback drawn on card B") { drawn().map { it.id } == listOf(shared.id) }
                scenario.onActivity { assertEquals("1 liked", binding.reviewFeedbackSummary.text.toString()) }
                screenshot("7-shared-on-other-card")
                scenario.onActivity { binding.reviewFeedbackSummary.performClick() }
                awaitUi("List labels it") { windowTexts().any { it.contains("The KL term keeps") && it.contains(fromA) } }
                assertFalse(windowTexts().any { it.contains("to the reference model") })
                assertFalse(windowTexts().contains(context.getString(R.string.review_feedback_not_found)))
                screenshot("8-shared-list")
                val line = windowTexts().single { it.contains("The KL term keeps") }
                scenario.onActivity { assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, line) }) }
                awaitUi("Editor shown with its home card") { windowTexts().contains(fromA) &&
                    windowTexts().contains(context.getString(R.string.review_feedback_delete)) }

                // Editing it on card B edits the same record, which stays card A's. There is no Save: closing
                // the editor (here with Back) saves it.
                scenario.onActivity {
                    WindowInspector.getGlobalWindowViews().firstNotNullOf {
                        it.findViewById<android.widget.EditText>(R.id.reviewFeedbackComment)
                    }.setText("Shared comment")
                    assertFalse(WindowInspector.getGlobalWindowViews().flatMap(::texts).contains(context.getString(R.string.review_feedback_save)))
                }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                awaitUi("Same record edited") { ReviewCardFeedback.find(shared.id)?.comment == "Shared comment" }
                val edited = ReviewCardFeedback.find(shared.id)!!
                assertEquals(2L, edited.revision)
                assertEquals(cardA.id, edited.cardId)
                assertEquals(emptyList<ReviewCardFeedback.Feedback>(), ReviewCardFeedback.forCard(cardB.id))
                awaitUi("Comment dot drawn") { drawn().singleOrNull()?.hasComment == true }

                // Deleting it on card B deletes it for card A too; Undo brings it back.
                scenario.onActivity { controller.feedbackActions.open(shared.id) }
                awaitUi("Editor shown") { windowTexts().contains(context.getString(R.string.review_feedback_delete)) }
                scenario.onActivity {
                    assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, context.getString(R.string.review_feedback_delete)) })
                }
                awaitUi("Same record deleted") { ReviewCardFeedback.find(shared.id)?.deleted == true && drawn().isEmpty() }
                scenario.onActivity { assertEquals(View.GONE, binding.reviewFeedbackSummary.visibility) }
                assertEquals(listOf(secondLine.id), ReviewCardFeedback.forCard(cardA.id).map { it.id })
                awaitUi("Undo offered") { windowTexts().contains(context.getString(R.string.review_feedback_undo)) }
                scenario.onActivity {
                    assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, context.getString(R.string.review_feedback_undo)) })
                }
                awaitUi("Same record restored") { ReviewCardFeedback.find(shared.id)?.let { !it.deleted && it.revision == 4L } == true &&
                    drawn().map { it.id } == listOf(shared.id) }

                // Card C's answer holds neither passage: nothing is drawn, counted or listed.
                reveal(cardC)
                SystemClock.sleep(1_000)
                assertEquals(emptyList<PDFView.ReviewFeedbackHighlight>(), drawn())
                scenario.onActivity { assertEquals(View.GONE, binding.reviewFeedbackSummary.visibility) }
                screenshot("9-not-in-this-answer")
            }
        } finally {
            ReviewCardFeedback.file = previousFeedbackFile
            pdf.delete()
            root.deleteRecursively()
        }
    }

    /**
     * Change passage: the editor of an own highlight selects its passage with handles; the selection
     * offers only Save passage and Cancel; saving the same passage keeps the record, a new passage
     * replaces it (Undo brings it back), one outside the answer is refused, Cancel changes nothing.
     */
    @Test fun changePassageMovesAHighlightToTheSelectedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "review-card-feedback-passage-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val store = ReviewStore(null, File(root, "review"), File(root, "incoming"),
            File(root, "events"), File(root, "cards"))
        val previousFeedbackFile = ReviewCardFeedback.file
        ReviewCardFeedback.file = { File(root, "card-feedback.json") }
        val paint = Paint().apply { textSize = 18f; isAntiAlias = true }
        val firstLine = "The KL term keeps the policy close"
        // A passage's area as a selection gives it: its glyphs on the first answer line (baseline 165 pt).
        fun lineRegion(text: String) = ReviewCardFeedback.Region(1, 45.0 / 600, (165.0 + paint.ascent()) / 800,
            (45.0 + paint.measureText(text)) / 600, (165.0 + paint.descent()) / 800)
        fun request(text: String, baseline: Float = 165f) = PDFView.HighlightRequest(0,
            listOf(RectF(45f, 800f - (baseline + paint.ascent()), 45f + paint.measureText(text), 800f - (baseline + paint.descent()))), text)
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                page.canvas.drawText("Question area above the answer", 45f, 65f, paint)
                page.canvas.drawText(firstLine, 45f, 165f, paint)
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
            val id = UUID.randomUUID().toString()
            val question = "What keeps the policy close?"
            val record = JsonObject().apply {
                addProperty("schema_version", 3)
                addProperty("card_type", "visual_region")
                addProperty("card_id", id)
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
            }
            File(root, "incoming/cards/server/$id.json").apply {
                parentFile!!.mkdirs()
                writeText(record.toString())
            }
            val card: ReviewCard = checkNotNull(store.card(id))
            val narrow = ReviewCardFeedback.create(id, ReviewCardFeedback.Polarity.POSITIVE, "",
                ReviewCardFeedback.NewAnchor("The KL term keeps", "", " the policy close", documentId, hash,
                    listOf(lineRegion("The KL term keeps"))), "test")
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(Uri.fromFile(pdf), "application/pdf")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var binding: ActivityMainBinding
                lateinit var controller: ReviewSessionController
                scenario.onActivity { activity ->
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
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
                    scenario.onActivity { value = binding.pdfView.visibleReviewFeedback }
                    return value
                }
                fun screenshot(name: String) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/card-feedback-$name.png").close()
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
                fun live() = ReviewCardFeedback.forCard(id)
                /** Opens [feedbackId]'s editor, optionally sets its comment, and taps Change passage. */
                fun changePassage(feedbackId: String, comment: String? = null) {
                    scenario.onActivity { controller.feedbackActions.open(feedbackId) }
                    awaitUi("Editor shown") { windowView<View>(R.id.reviewFeedbackChangePassage)?.isShown == true }
                    scenario.onActivity {
                        comment?.let { windowView<android.widget.EditText>(R.id.reviewFeedbackComment)!!.setText(it) }
                        assertTrue(windowView<View>(R.id.reviewFeedbackChangePassage)!!.performClick())
                    }
                    awaitUi("Passage selected") { controller.feedbackActions.isChangingPassage() && binding.pdfView.hasTextSelection() &&
                        binding.textSelectionSavePassageButton.isShown }
                }

                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
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
                scenario.onActivity { binding.reviewRevealButton.performClick() }
                awaitUi("Feedback drawn") { drawn().map { it.id } == listOf(narrow.id) }

                // Change passage selects the highlight's own passage, with only Save passage and Cancel offered.
                changePassage(narrow.id, comment = "Too narrow.")
                scenario.onActivity {
                    assertEquals("The KL term keeps", binding.pdfView.selectedText)
                    assertEquals(View.VISIBLE, binding.textSelectionCancelPassageButton.visibility)
                    listOf(binding.textSelectionCopyButton, binding.textSelectionFeedbackLikeButton,
                        binding.textSelectionAddReviewButton, binding.textSelectionNoteButton).forEach {
                        assertEquals(View.GONE, it.visibility)
                    }
                }
                // The highlight gives way to the selection while it is changed.
                assertEquals(emptyList<PDFView.ReviewFeedbackHighlight>(), drawn())
                screenshot("10-change-passage-selected")

                // Save passage on the unchanged passage keeps the record and saves the edited comment.
                scenario.onActivity { assertTrue(binding.textSelectionSavePassageButton.performClick()) }
                awaitUi("Same record, comment saved") { ReviewCardFeedback.find(narrow.id)?.comment == "Too narrow." }
                awaitUi("Mode ended") { !controller.feedbackActions.isChangingPassage() && !binding.pdfView.hasTextSelection() }
                assertEquals(listOf(narrow.id), live().map { it.id })
                awaitUi("Highlight drawn again") { drawn().map { it.id } == listOf(narrow.id) }

                // A wider passage replaces the record; polarity and comment go with it.
                changePassage(narrow.id)
                scenario.onActivity { controller.feedbackActions.savePassage(request(firstLine)) }
                scenario.onActivity { assertFalse(controller.feedbackActions.isChangingPassage()) }
                scenario.onActivity { binding.pdfView.clearTextSelection() }
                awaitUi("Replaced") { live().singleOrNull()?.exact == firstLine }
                val wide = live().single()
                assertEquals(ReviewCardFeedback.Polarity.POSITIVE, wide.polarity)
                assertEquals("Too narrow.", wide.comment)
                assertEquals(hash, wide.pdfSha256)
                assertTrue(ReviewCardFeedback.find(narrow.id)!!.deleted)
                awaitUi("New highlight drawn") { drawn().map { it.id } == listOf(wide.id) }
                awaitUi("Passage changed · Undo") { windowTexts().contains(context.getString(R.string.review_feedback_passage_changed)) }
                screenshot("11-passage-changed")

                // Undo: the old passage is back, the new record deleted.
                scenario.onActivity {
                    assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickText(it, context.getString(R.string.review_feedback_undo)) })
                }
                awaitUi("Old passage restored") { live().map { it.id } == listOf(narrow.id) && drawn().map { it.id } == listOf(narrow.id) }
                assertTrue(ReviewCardFeedback.find(wide.id)!!.deleted)

                // A passage outside the answer is refused; the selection stays for another try.
                changePassage(narrow.id)
                scenario.onActivity { controller.feedbackActions.savePassage(request("Unrelated text", baseline = 320f)) }
                scenario.onActivity { assertTrue(controller.feedbackActions.isChangingPassage()) }
                awaitUi("Refusal shown") { windowTexts().contains(context.getString(R.string.review_feedback_passage_outside)) }

                // Cancel changes nothing.
                scenario.onActivity { assertTrue(binding.textSelectionCancelPassageButton.performClick()) }
                awaitUi("Cancelled") { !controller.feedbackActions.isChangingPassage() && !binding.pdfView.hasTextSelection() &&
                    !binding.textSelectionActionCard.isShown }
                awaitUi("Highlight drawn again after Cancel") { drawn().map { it.id } == listOf(narrow.id) }
                assertEquals(listOf(narrow.id), live().map { it.id })
                assertEquals(firstLine.take(17), live().single().exact)

                // Masking the answer ends a change in progress.
                changePassage(narrow.id)
                scenario.onActivity { binding.reviewRevealButton.performClick() }
                awaitUi("Mode ended with the answer") { !controller.feedbackActions.isChangingPassage() && !binding.pdfView.hasTextSelection() }
                assertEquals(listOf(narrow.id), live().map { it.id })
            }
        } finally {
            ReviewCardFeedback.file = previousFeedbackFile
            pdf.delete()
            root.deleteRecursively()
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MenuItem
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewSkipBannerInstrumentedTest {
    @Test fun lastCardShowsCompletionWithoutBannerAndMenuUndoRestoresCard() = checkSkip(false)

    @Test fun skipOpensNextCardWithoutBannerAndMenuUndoRestoresCard() = checkSkip(true)

    @Test fun consecutiveSkipsUndoInReverseOrder() = checkSkip(true, "skip")

    @Test fun documentSkipCompletesQueueAndUndoRestoresWholeGroup() = checkSkip(true, timingAction = "skip", wholeDocument = true)

    private fun checkSkip(hasNextCard: Boolean, nextAction: String? = null,
                          timingAction: String? = null, wholeDocument: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "review-skip-banner-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val store = ReviewStore(null, File(root, "review"), File(root, "incoming"),
            File(root, "events"), File(root, "cards"))
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                val paint = Paint().apply { textSize = 18f }
                page.canvas.drawText("Skip without a confirmation banner", 45f, 65f, paint)
                page.canvas.drawText("A skipped card can be restored with Undo.", 45f, 175f, paint)
                document.finishPage(page)
                pdf.parentFile!!.mkdirs()
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes())
                .joinToString("") { "%02x".format(it) }
            // Review sessions find a card's PDF through its permanent Library identity.
            val documentId = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore()
                .registerImport("$stem.pdf", hash, pdf)
            fun addCard(rect: RectF, question: String): ReviewCard {
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
                        add("rect", JsonArray().apply {
                            listOf(rect.left, rect.top, rect.right, rect.bottom).forEach { add(it) }
                        })
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
            val first = addCard(RectF(.05f, .15f, .9f, .25f),
                "What happens when this card is skipped?")
            val next = if (hasNextCard) addCard(RectF(.05f, .35f, .9f, .45f),
                "The next question appears directly after Skip.") else null
            assertEquals(if (hasNextCard) 2 else 1, store.dueCards().size)
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(Uri.fromFile(pdf), "application/pdf")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var binding: ActivityMainBinding
                lateinit var decor: View
                lateinit var mainActivity: MainActivity
                scenario.onActivity { activity ->
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    decor = activity.window.decorView
                    mainActivity = activity
                    binding = ActivityMainBinding.bind(
                        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                }
                fun awaitUi(message: String, condition: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    var matched = false
                    while (!matched && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity { matched = condition() }
                        if (!matched) SystemClock.sleep(50)
                    }
                    if (!matched) scenario.onActivity {
                        fail("$message; question=${binding.reviewQuestion.text}; banner=" +
                            decor.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text)
                    }
                }
                val undoLabel = context.getString(R.string.review_undo)
                fun clickMenu(view: View, label: String = undoLabel): Boolean {
                    if (view is ListView) {
                        for (position in 0 until view.count) {
                            if ((view.adapter.getItem(position) as? MenuItem)?.title?.toString() == label) {
                                return view.performItemClick(view.getChildAt(position - view.firstVisiblePosition),
                                    position, view.adapter.getItemId(position))
                            }
                        }
                    }
                    return view is ViewGroup && (0 until view.childCount).any {
                        clickMenu(view.getChildAt(it), label)
                    }
                }
                fun chooseFromMenu(label: String, anchor: () -> View = { binding.reviewOptionsButton }) {
                    scenario.onActivity { anchor().performClick() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        assertTrue("$label is in the rendered options menu",
                            WindowInspector.getGlobalWindowViews().asReversed().any { clickMenu(it, label) })
                    }
                    // A dismissed popup remains in WindowInspector during its exit animation.
                    SystemClock.sleep(400)
                    instrumentation.waitForIdleSync()
                }
                // Read on the main thread (inside onActivity or awaitUi).
                fun activeDialog(): android.app.Dialog? = (MainActivity::class.java.getDeclaredField("reviewSessionController")
                    .apply { isAccessible = true }.get(mainActivity) as? ReviewSessionController)?.let {
                        ReviewSessionController::class.java.getDeclaredField("cardDialog")
                            .apply { isAccessible = true }.get(it) as? android.app.Dialog
                    }
                // The bar: the tag cell opens the tags-only sheet (the last answer's tags selected, none for a new card), the interval
                // cell opens the interval dialog, and Next records both.
                fun tagAndNext(action: String, amount: String? = null) {
                    if (amount != null) {
                        scenario.onActivity { binding.reviewIntervalButton.performClick() }
                        awaitUi("Interval dialog opens") { activeDialog()?.isShowing == true }
                        scenario.onActivity {
                            val dialog = checkNotNull(activeDialog())
                            dialog.findViewById<android.widget.EditText>(R.id.amount).setText(amount)
                            dialog.findViewById<TextView>(R.id.apply).performClick()
                        }
                        awaitUi("Interval dialog closes") { activeDialog() == null }
                    }
                    val wanted = if (action == "correct") ReviewTags.CORRECT else ReviewTags.WRONG
                    scenario.onActivity { binding.reviewTagButton.performClick() }
                    awaitUi("Tag sheet opens") { activeDialog()?.isShowing == true }
                    scenario.onActivity {
                        val dialog = checkNotNull(activeDialog())
                        assertNull("The tag sheet has no interval", dialog.findViewById<View>(R.id.amount))
                        assertNull("The tag sheet has no scope", dialog.findViewById<View>(R.id.reviewApplyAll))
                        // Chips sit under their group headings inside the tag list.
                        fun chips(view: View): List<com.google.android.material.chip.Chip> = when (view) {
                            is com.google.android.material.chip.Chip -> listOf(view)
                            is ViewGroup -> (0 until view.childCount).flatMap { chips(view.getChildAt(it)) }
                            else -> emptyList()
                        }
                        for (chip in chips(dialog.findViewById<ViewGroup>(R.id.reviewTags))) {
                            if (chip.tag is String) chip.isChecked = chip.tag == wanted
                        }
                        dialog.dismiss()
                    }
                    awaitUi("Tag sheet closes") { activeDialog() == null }
                    scenario.onActivity {
                        assertEquals(reviewTagLabel(context, wanted), binding.reviewTagButton.text.toString().substringAfter('\n'))
                        assertTrue(binding.reviewNextButton.isEnabled)
                        binding.reviewNextButton.performClick()
                    }
                }
                // Skip lives in the options menu and opens the Skip dialog (10 minutes by default).
                fun skip(wholeDocument: Boolean = false, amount: String? = null) {
                    chooseFromMenu(context.getString(R.string.review_skip))
                    awaitUi("Skip dialog opens") { activeDialog()?.isShowing == true }
                    scenario.onActivity {
                        val dialog = checkNotNull(activeDialog())
                        assertEquals("Skip", dialog.findViewById<TextView>(R.id.title).text.toString())
                        val applyAll = dialog.findViewById<android.widget.CheckBox>(R.id.reviewApplyAll)
                        assertFalse(applyAll.isChecked)
                        assertEquals("Apply to all cards in this", applyAll.text.toString())
                        applyAll.isChecked = wholeDocument
                        if (wholeDocument) assertEquals("Document",
                            dialog.findViewById<android.widget.AutoCompleteTextView>(R.id.reviewScopePicker).text.toString())
                        amount?.let(dialog.findViewById<android.widget.EditText>(R.id.amount)::setText)
                        dialog.findViewById<TextView>(R.id.apply).performClick()
                    }
                }
                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
                scenario.onActivity { activity ->
                    val controller = ReviewSessionController(activity, binding,
                        DocumentState(uri = Uri.fromFile(pdf), annotationPdfSha256 = hash), store)
                    MainActivity::class.java.getDeclaredField("reviewSessionController")
                        .apply { isAccessible = true }.set(activity, controller)
                    ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java,
                        ReviewInterval::class.java, Map::class.java).apply { isAccessible = true }
                        .invoke(controller, first, ReviewInterval.suggested(null, null), emptyMap<String, Int>())
                    binding.reviewRevealButton.performClick()
                }
                if (timingAction == null) skip()
                else if (timingAction != "skip") tagAndNext(timingAction, amount = "3")
                else {
                    assertEquals(2, store.dueCards().size)
                    skip(wholeDocument, amount = "3")
                }
                awaitUi("Skip opens the next card or completion") {
                    binding.reviewCard.visibility == View.VISIBLE &&
                    binding.reviewQuestion.text.toString() ==
                        (next?.takeUnless { wholeDocument }?.question ?: context.getString(R.string.review_session_complete))
                }
                fun assertNoBanner() {
                    assertNull("Skip must not show a confirmation banner",
                        decor.findViewById<View>(com.google.android.material.R.id.snackbar_text))
                }
                scenario.onActivity {
                    assertNoBanner()
                    assertEquals(View.VISIBLE, binding.reviewOptionsButton.visibility)
                    if (hasNextCard && !wholeDocument) assertEquals(View.VISIBLE, binding.reviewRevealButton.visibility)
                    else assertEquals(View.VISIBLE, binding.reviewDoneButton.visibility)
                }
                SystemClock.sleep(1200)
                scenario.onActivity { assertNoBanner() }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null),
                        if (hasNextCard) "review-skip-no-banner-next.png" else "review-skip-no-banner-complete.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                assertFalse(store.dueCards().any { it.id == first.id })
                if (timingAction == null || timingAction == "skip") {
                    // Skip postpones without answering: no review, no tag, no new schedule.
                    val skipped = store.browserCards().first { it.card.id == first.id }
                    assertEquals(0, skipped.reviewCount)
                    assertNull(skipped.currentTags())
                    assertNull(skipped.card.schedule)
                } else run {
                    // Every tag records a review; the interval alone decides when the card returns.
                    val reviewed = store.browserCards().first { it.card.id == first.id }
                    assertEquals(1, reviewed.reviewCount)
                    val schedule = reviewed.card.schedule!!
                    val reviewedAt = checkNotNull(reviewed.lastReviewedAt)
                    val expected = reviewedAt.atZone(java.time.ZoneId.systemDefault()).plusWeeks(3).toInstant()
                    assertEquals("The chosen interval must reach the saved schedule", expected, schedule.dueAt)
                    assertEquals(listOf(if (timingAction == "correct") "correct" else "wrong"), reviewed.currentTags())
                }
                if (wholeDocument) assertTrue(store.dueCards().isEmpty())
                if (nextAction != null) {
                    scenario.onActivity {
                        binding.reviewRevealButton.performClick()
                    }
                    if (nextAction == "skip") skip()
                    else tagAndNext(nextAction)
                    awaitUi("Second action completes the queue") {
                        binding.reviewDoneButton.visibility == View.VISIBLE
                    }
                    assertTrue(store.dueCards().isEmpty())
                }
                scenario.onActivity { binding.reviewOptionsButton.performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertTrue("Undo is available in the rendered options menu",
                        WindowInspector.getGlobalWindowViews().asReversed().any { clickMenu(it) })
                }
                if (nextAction != null) {
                    awaitUi("Undo restores the most recent card, before the older skip") {
                        binding.reviewQuestion.text.toString() == next!!.question &&
                            binding.reviewRevealButton.visibility == View.VISIBLE
                    }
                    assertTrue(store.dueCards().any { it.id == next!!.id })
                    assertFalse("The older skip must remain active", store.dueCards().any { it.id == first.id })
                    assertEquals(0, store.browserCards().first { it.card.id == next!!.id }.reviewCount)
                    SystemClock.sleep(400)
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { binding.reviewOptionsButton.performClick() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickMenu(it) })
                    }
                }
                awaitUi("Undo restores the original question") {
                    binding.reviewQuestion.text.toString() == first.question &&
                        binding.reviewRevealButton.visibility == View.VISIBLE
                }
                assertTrue(store.dueCards().any { it.id == first.id })
                assertEquals(0, store.browserCards().first { it.card.id == first.id }.reviewCount)
                assertNull(store.card(first.id)!!.schedule)
                if (wholeDocument) {
                    assertEquals(2, store.dueCards().size)
                    assertEquals(0, store.browserCards().first { it.card.id == next!!.id }.reviewCount)
                }
                // A dismissed popup remains in WindowInspector during its exit animation.
                SystemClock.sleep(400)
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertNoBanner(); binding.reviewOptionsButton.performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickMenu(it, context.getString(R.string.review_action_end)) })
                    assertEquals(View.GONE, binding.reviewCard.visibility)
                }
                assertEquals(0, store.browserCards().first { it.card.id == first.id }.reviewCount)
                assertNull(store.card(first.id)!!.schedule)
            }
        } finally {
            pdf.delete()
            root.deleteRecursively()
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }
}

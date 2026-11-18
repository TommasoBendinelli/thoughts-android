// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
import com.gitlab.mudlej.MjPdfReader.data.review.basicPdfSha256
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import java.io.File
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The before step (Confidence picked and a before note typed before Reveal) is kept per card until the
 * card's answer is recorded, so a before note is not lost when a pending proposal makes the card
 * unavailable for a while and the review goes on to other cards. Covered: the
 * review moving on to another card and coming back, the reader closed and opened again with nothing
 * saved (only what the phone kept), and Reveal before the card left (it comes back locked); Next then
 * records the step and nothing is kept any more.
 */
@RunWith(AndroidJUnit4::class)
class ReviewBeforeStepDraftInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val created = mutableListOf<File>()
    private val previousTagGroups = ReviewTagGroups.file
    private lateinit var tagGroups: File
    private val note = "I remember the outline but not the details."

    @Before fun setUp() {
        check(context.packageName.endsWith(".sandbox"))
        Preferences(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)).apply {
            setFirstInstall(false); setShowFeaturesDialog(false)
        }
        com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatteryPromptState(
            File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1")).markShown()
        // Confidence asked before the answer, Understanding after it.
        tagGroups = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "before-draft-tag-groups").toFile()
        ReviewTagGroups.file = { File(tagGroups, "tag-groups.json") }
        ReviewTagGroups.installServer(com.google.gson.JsonParser.parseString("""
            {"layout_id":"groups","schema_version":1,
             "groups":[{"group_id":"c","name":"Confidence","tags":["Blank","Guessing","Unsure","Fairly sure","Sure"],
                        "scale":["Blank","Guessing","Unsure","Fairly sure","Sure"],"phase":"before"},
                       {"group_id":"u","name":"Understanding","tags":["Lost","Partly","I get it"],"scale":["Lost","Partly","I get it"]}],
             "updated_at":"2026-11-16T11:15:00.000Z","origin_id":"desktop"}
        """).asJsonObject)
    }

    @After fun tearDown() {
        created.forEach(File::delete)
        ReviewTagGroups.file = previousTagGroups
        tagGroups.deleteRecursively()
    }

    private fun pdf(): Pair<File, String> {
        val directory = File(context.cacheDir, "review-before-draft").apply { mkdirs() }
        val pdf = File(directory, "review-before-draft-${TestRunIdentity.id}.pdf")
        PdfDocument().let { document ->
            try {
                for (number in 1..2) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, number).create())
                    val paint = Paint().apply { textSize = 18f }
                    page.canvas.drawText("Page $number", 20f, 60f, paint)
                    page.canvas.drawText("Policy gradients estimate the return's gradient.", 20f, 200f, paint)
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
        }
        created += pdf
        return pdf to basicPdfSha256(pdf)
    }

    private fun resumedMainOnMainThread(): MainActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>().firstOrNull()

    private fun bindingOf(activity: Activity) =
        ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))

    private fun controllerOf(activity: MainActivity): ReviewSessionController =
        MainActivity::class.java.getDeclaredField("reviewSessionController").apply { isAccessible = true }
            .get(activity) as ReviewSessionController

    private fun draftOf(activity: MainActivity): ReviewAnswerDraft =
        ReviewSessionController::class.java.getDeclaredField("answerDraft").apply { isAccessible = true }
            .get(controllerOf(activity)) as ReviewAnswerDraft

    /** What the before step shows for the card under review. */
    private data class Shown(
        val question: String,
        val panel: Boolean,
        val locked: Boolean,
        val tags: List<String>,
        val note: String,
        val noteBox: String,
        val summary: String,
        val answerShownAt: Instant?,
    )

    private fun shown(): Shown? {
        var result: Shown? = null
        instrumentation.runOnMainSync {
            val activity = resumedMainOnMainThread() ?: return@runOnMainSync
            val binding = bindingOf(activity)
            if (binding.reviewCard.visibility != View.VISIBLE) return@runOnMainSync
            val draft = draftOf(activity)
            result = Shown(
                binding.reviewQuestion.text.toString(),
                binding.reviewBeforePanel.visibility == View.VISIBLE,
                draft.beforeLocked,
                draft.beforeTags,
                draft.note(ReviewGroupNotes.BEFORE_NOTE_KEY),
                binding.reviewBeforeNote.text?.toString().orEmpty(),
                binding.reviewBeforeSummary.takeIf { it.visibility == View.VISIBLE }?.text?.toString().orEmpty(),
                draft.answerShownAt,
            )
        }
        return result
    }

    private fun waitFor(question: String, timeoutMs: Long = 20_000): Shown {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            shown()?.takeIf { it.question == question && it.panel }?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("The card \"$question\" was not shown with its before step")
    }

    private fun intentFor(pdf: File, cardId: String) = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        setDataAndType(Uri.fromFile(pdf), "application/pdf")
        putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, cardId)
        putExtra(HomeActivity.EXTRA_REVIEW_PDF_STEM, pdf.nameWithoutExtension)
    }

    /** The review goes on to [cardId] in the reader shown, as after Next or from the Home queue. */
    private fun reviewInPlace(cardId: String) = instrumentation.runOnMainSync {
        val activity = resumedMainOnMainThread()!!
        activity.intent.putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, cardId)
        activity.openReviewCardIfRequested()
    }

    @Test fun theBeforeStepComesBackAfterTheCardLeftTheReview() {
        val (pdf, hash) = pdf()
        val store = ReviewStore(context)
        val stem = pdf.nameWithoutExtension
        val rlQuestion = "Method overview: what does the estimator measure? (${TestRunIdentity.id})"
        val otherQuestion = "Another card on the same page (${TestRunIdentity.id})"
        val documentId = java.util.UUID.randomUUID().toString()
        val rl = store.createManualCard(stem, hash, 1, RectF(.05f, .3f, .95f, .36f), rlQuestion,
            sourceAvailable = false, documentId = documentId).getOrThrow()
        val other = store.createManualCard(stem, hash, 2, RectF(.05f, .3f, .95f, .36f), otherQuestion,
            sourceAvailable = false, documentId = documentId).getOrThrow()
        val drafts = ReviewBeforeDrafts(context)
        drafts.remove(rl.id)
        drafts.remove(other.id)

        var scenario = ActivityScenario.launch<MainActivity>(intentFor(pdf, rl.id))
        try {
            val fresh = waitFor(rlQuestion)
            assertTrue("Nothing picked yet", fresh.tags.isEmpty() && fresh.note.isEmpty() && !fresh.locked)
            // The before step filled in: Fairly sure, and the before note.
            instrumentation.runOnMainSync {
                val binding = bindingOf(resumedMainOnMainThread()!!)
                val confidence = binding.reviewBeforeGroups.getChildAt(0)
                val labels = confidence.findViewById<ViewGroup>(com.gitlab.mudlej.MjPdfReader.R.id.reviewBeforeScaleLabels)
                labels.getChildAt(3).performClick()
                binding.reviewBeforeNote.setText(note)
            }
            val filled = shown()!!
            assertEquals(listOf("Fairly sure"), filled.tags)
            assertEquals(note, filled.note)

            // 1. The card leaves the review and the review goes on with another card: its own empty step.
            reviewInPlace(other.id)
            val second = waitFor(otherQuestion)
            assertTrue("The other card starts empty", second.tags.isEmpty() && second.note.isEmpty() && second.noteBox.isEmpty())
            // ... and the card comes back, as it was left.
            reviewInPlace(rl.id)
            val back = waitFor(rlQuestion)
            assertEquals(listOf("Fairly sure"), back.tags)
            assertEquals(note, back.note)
            assertEquals("The note box shows it", note, back.noteBox)
            assertFalse(back.locked)

            // 2. The reader is closed and opened again with nothing saved: only what the phone kept.
            scenario.close()
            scenario = ActivityScenario.launch(intentFor(pdf, rl.id))
            val reopened = waitFor(rlQuestion)
            assertEquals(listOf("Fairly sure"), reopened.tags)
            assertEquals(note, reopened.noteBox)

            // 3. Reveal, then the card leaves again: it comes back locked, with when the answer was first shown.
            instrumentation.runOnMainSync { bindingOf(resumedMainOnMainThread()!!).reviewRevealButton.performClick() }
            val shownAt = shown()!!.answerShownAt
            assertNotNull(shownAt)
            reviewInPlace(other.id)
            waitFor(otherQuestion)
            reviewInPlace(rl.id)
            val locked = waitFor(rlQuestion)
            assertTrue(locked.locked)
            assertEquals(shownAt, locked.answerShownAt)
            assertTrue("The before line names the pick and the note: ${locked.summary}",
                locked.summary.contains("Fairly sure") && locked.summary.contains(note))

            // Next records the step with the answer; nothing is kept for the card any more.
            instrumentation.runOnMainSync {
                val binding = bindingOf(resumedMainOnMainThread()!!)
                binding.reviewRevealButton.performClick()
                binding.reviewNextButton.performClick()
            }
            val end = SystemClock.elapsedRealtime() + 10_000
            while (store.lastAnsweredAt(rl.id) == null && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
            val answered = store.lastAnsweredAt(rl.id)
            assertNotNull("The answer was recorded", answered)
            val answer = store.recallPredictionHistory(rl.id).first.last { it.rating != null && it.queueAction == null }
            assertEquals(note, answer.groupNotes[ReviewGroupNotes.BEFORE_NOTE_KEY])
            assertTrue("Confidence is recorded: ${answer.reviewTags}", "Fairly sure" in answer.reviewTags)
            assertNull("Nothing kept once answered", drafts.get(rl.id, answered))
            assertFalse(context.getSharedPreferences(ReviewBeforeDrafts.PREFERENCES, android.content.Context.MODE_PRIVATE).contains(rl.id))
        } finally {
            scenario.close()
            drafts.remove(rl.id)
            drafts.remove(other.id)
        }
    }
}

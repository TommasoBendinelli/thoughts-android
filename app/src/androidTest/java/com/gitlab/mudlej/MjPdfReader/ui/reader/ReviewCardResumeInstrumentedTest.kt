// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Intent
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewIntervalUnit
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags
import com.gitlab.mudlej.MjPdfReader.data.review.basicPdfSha256
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import java.io.File
import java.math.BigDecimal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A review in progress survives leaving the reader for another app and coming back: the card
 * stays, with its revealed answer and the tags, interval and group notes chosen so far, and no
 * "unavailable for the installed PDF version" notice. Covered for an imported Library PDF (the
 * 2026-10-14 report), a source-built Library PDF and a PDF outside the Library, each through
 * onStop/onStart, activity recreation, and destruction while in the background with only the
 * saved state left (a new view model, as after the process was killed), and through a reload of
 * the same PDF during which the card is asked for again.
 */
@RunWith(AndroidJUnit4::class)
class ReviewCardResumeInstrumentedTest {
    private enum class Kind { IMPORTED_LIBRARY, SOURCE_BUILT_LIBRARY, OUTSIDE_LIBRARY }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val canonical = PhoneRenderQueue.defaultCanonicalPdfDirectory()
    private val indexFiles = listOf(SourceLinkedPdfIndex.INDEX_FILENAME, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME,
        SourceLinkedPdfIndex.GENERATION_FILENAME).map { File(canonical, it) }
    private val savedIndexes = mutableMapOf<File, ByteArray?>()
    private val created = mutableListOf<File>()

    @Before fun setUp() {
        check(context.packageName.endsWith(".sandbox"))
        check(canonical.canonicalPath.startsWith(context.filesDir.canonicalPath))
        Preferences(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)).apply {
            setFirstInstall(false); setShowFeaturesDialog(false)
        }
        com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatteryPromptState(
            File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1")).markShown()
        indexFiles.forEach { savedIndexes[it] = it.takeIf(File::isFile)?.readBytes() }
    }

    @After fun tearDown() {
        savedIndexes.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
        created.forEach(File::delete)
        SourceLinkedPdfIndex().invalidate()
    }

    @Test fun importedLibraryPdf() = resumeKeepsTheReview(Kind.IMPORTED_LIBRARY)
    @Test fun sourceBuiltLibraryPdf() = resumeKeepsTheReview(Kind.SOURCE_BUILT_LIBRARY)
    @Test fun pdfOutsideTheLibrary() = resumeKeepsTheReview(Kind.OUTSIDE_LIBRARY)

    /** The PDF, its hash and its permanent document id. */
    private fun fixture(kind: Kind): Triple<File, String, String> {
        val stem = "review-resume-${kind.name.lowercase().replace('_', '-')}-${TestRunIdentity.id}"
        val directory = if (kind == Kind.OUTSIDE_LIBRARY) File(context.cacheDir, "review-resume").apply { mkdirs() } else canonical
        val pdf = File(directory, "$stem.pdf")
        pdf.parentFile!!.mkdirs()
        PdfDocument().let { document ->
            try {
                for (number in 1..3) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, number).create())
                    val paint = Paint().apply { textSize = 18f }
                    page.canvas.drawText("Page $number", 20f, 60f, paint)
                    page.canvas.drawText("Tiling and recomputation reduce HBM traffic.", 20f, 200f, paint)
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
        }
        created += pdf
        val hash = basicPdfSha256(pdf)
        val documentId = java.util.UUID.randomUUID().toString()
        if (kind != Kind.OUTSIDE_LIBRARY) {
            val libraryKind = if (kind == Kind.SOURCE_BUILT_LIBRARY) "source_linked" else "unlinked"
            val revision = "1".repeat(64)
            val sourceIndexSha = "2".repeat(64)
            val sourceEntries = if (kind == Kind.SOURCE_BUILT_LIBRARY)
                """{"filename":"${pdf.name}","pdf_sha256":"$hash"}""" else ""
            indexFiles[0].writeText("""{"schema_version":1,"pdfs":[$sourceEntries]}""")
            indexFiles[1].writeText("""{"schema_version":1,"library_revision":"$revision","source_index_sha256":"$sourceIndexSha",
                "pdfs":[{"filename":"${pdf.name}","kind":"$libraryKind","pdf_sha256":"$hash","document_id":"$documentId"}]}""")
            indexFiles[2].writeText("""{"schema_version":1,"library_revision":"$revision","source_index_sha256":"$sourceIndexSha",
                "publications":[{"filename":"${pdf.name}","sha256":"$hash","size_bytes":${pdf.length()},"metadata":{"kind":"$libraryKind"}}]}""")
            SourceLinkedPdfIndex().invalidate()
            val snapshot = SourceLinkedPdfIndex().snapshot()
            assertTrue("Library index fixture is current", snapshot.generationCurrent)
            assertEquals(hash, snapshot.libraryPdfSha256ByFilename[pdf.name])
        }
        return Triple(pdf, hash, documentId)
    }

    private fun resumedMainOnMainThread(): MainActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>().firstOrNull()

    private fun resumedMain(): MainActivity? {
        var found: MainActivity? = null
        instrumentation.runOnMainSync { found = resumedMainOnMainThread() }
        return found
    }

    private fun bindingOf(activity: Activity) =
        ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))

    private fun controllerOf(activity: MainActivity): ReviewSessionController =
        MainActivity::class.java.getDeclaredField("reviewSessionController").apply { isAccessible = true }
            .get(activity) as ReviewSessionController

    private fun field(controller: ReviewSessionController, name: String) =
        ReviewSessionController::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun call(controller: ReviewSessionController, name: String) =
        ReviewSessionController::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(controller)

    private fun containsText(view: View, text: String): Boolean = when (view) {
        is TextView -> view.text?.toString() == text
        is ViewGroup -> (0 until view.childCount).any { containsText(view.getChildAt(it), text) }
        else -> false
    }

    /** What the review shows: question, revealed bar, chosen tags and interval, notes in the draft. */
    private data class Shown(
        val question: String,
        val revealed: Boolean,
        val tags: String,
        val interval: String,
        val notes: Map<String, String>,
    )

    private var staleNoticeSeen = false

    private fun shown(): Shown? {
        var result: Shown? = null
        instrumentation.runOnMainSync {
            val activity = resumedMainOnMainThread() ?: return@runOnMainSync
            if (containsText(activity.window.decorView, context.getString(R.string.review_stale_geometry))) staleNoticeSeen = true
            val binding = bindingOf(activity)
            if (binding.reviewCard.visibility != View.VISIBLE) return@runOnMainSync
            val draft = field(controllerOf(activity), "answerDraft").get(controllerOf(activity)) as ReviewAnswerDraft
            result = Shown(
                binding.reviewQuestion.text.toString(),
                binding.reviewRatingScroll.visibility == View.VISIBLE,
                binding.reviewTagButton.contentDescription?.toString().orEmpty(),
                binding.reviewIntervalButton.contentDescription?.toString().orEmpty(),
                draft.recordedNotes(),
            )
        }
        return result
    }

    private fun waitForShown(question: String, timeoutMs: Long = 20_000): Shown? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            shown()?.takeIf { it.question == question }?.let { return it }
            SystemClock.sleep(50)
        }
        return null
    }

    /** Watches for the stale notice and for the card going away for [ms], as the user would. */
    private fun observeStable(expected: Shown, step: String, ms: Long = 6_500) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            val now = shown()
            assertFalse("$step: the stale-version notice was shown", staleNoticeSeen)
            if (now != null) assertEquals("$step: the review in progress", expected, now)
            SystemClock.sleep(100)
        }
        assertEquals("$step: the review in progress at the end", expected, shown())
    }

    private fun resumeKeepsTheReview(kind: Kind) {
        val (pdf, hash, documentId) = fixture(kind)
        val stem = pdf.nameWithoutExtension
        val store = ReviewStore(context)
        val question = "Which two techniques reduce HBM traffic? (${kind.name})"
        val card = store.createManualCard(stem, hash, 2, RectF(.05f, .3f, .95f, .38f), question,
            sourceAvailable = false, documentId = documentId).getOrThrow()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, card.id)
            putExtra(HomeActivity.EXTRA_REVIEW_PDF_STEM, stem)
        }
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            val initial = waitForShown(question)
            assertTrue("$kind: card displayed", initial != null)
            // Set knowledge, then choose two tags, an interval and a group note, as the user did.
            instrumentation.runOnMainSync {
                val activity = resumedMainOnMainThread()!!
                bindingOf(activity).reviewRevealButton.performClick()
                val controller = controllerOf(activity)
                field(controller, "selectedTags").set(controller, listOf(ReviewTags.WRONG, "plus-one"))
                field(controller, "suggestedInterval").set(controller, ReviewInterval(BigDecimal("1.5"), ReviewIntervalUnit.WEEKS))
                (field(controller, "answerDraft").get(controller) as ReviewAnswerDraft).setNote("other", "mixed up tiling")
                call(controller, "updateIntervalButton")
                call(controller, "updateTagButton")
            }
            val chosen = shown()!!
            assertTrue(chosen.revealed)
            assertEquals(mapOf("other" to "mixed up tiling"), chosen.notes)
            assertNotEquals("Choices differ from the defaults", initial!!.tags, chosen.tags)
            assertNotEquals("Choices differ from the defaults", initial.interval, chosen.interval)
            Log.i(TAG, "$kind chosen=$chosen")

            // 1. Another app comes to the front and the reader comes back (onStop, onStart).
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(500)
            scenario.moveToState(Lifecycle.State.RESUMED)
            observeStable(chosen, "$kind after onStop/onStart")

            // 2. The activity is recreated (configuration change).
            scenario.recreate()
            assertEquals("$kind after recreation", chosen, waitForShown(question))
            observeStable(chosen, "$kind after recreation")

            // 3. The reader is in the background when Android destroys it, and the user comes
            // back: it is restored from its saved state only, with a new view model, as after the
            // process was killed (the reader's own process cannot be killed under instrumentation).
            scenario.moveToState(Lifecycle.State.CREATED)
            var before: Activity? = null
            scenario.onActivity { before = it }
            var dropViewModels = true
            val dropOnSave = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPostSaveInstanceState(activity: Activity, outState: Bundle) {
                    if (activity is MainActivity && dropViewModels) {
                        dropViewModels = false
                        activity.viewModelStore.clear()
                    }
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            val application = context.applicationContext as Application
            application.registerActivityLifecycleCallbacks(dropOnSave)
            try {
                scenario.recreate()
            } finally {
                application.unregisterActivityLifecycleCallbacks(dropOnSave)
            }
            assertFalse("$kind: the view model was dropped", dropViewModels)
            scenario.moveToState(Lifecycle.State.RESUMED)
            val restored = waitForShown(question)
            assertTrue("$kind: a new reader was created", resumedMain() !== before)
            assertEquals("$kind after restore from saved state", chosen, restored)
            observeStable(chosen, "$kind after restore from saved state")

            // 4. Sync replaces the PDF with the same content and the reader reloads it, while the
            // review asks to open the card again during the reload (its hash and pages are gone).
            instrumentation.runOnMainSync {
                val activity = resumedMainOnMainThread()!!
                val reader = MainActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }
                    .get(activity) as ReaderComposition
                reader.documentLoader.reloadAfterExternalUpdate()
                activity.openReviewCardIfRequested()
            }
            assertEquals("$kind after a reload of the same PDF", chosen, waitForShown(question))
            observeStable(chosen, "$kind after a reload of the same PDF")
        } finally {
            scenario.close()
        }
    }

    private companion object { const val TAG = "ReviewResumeTest" }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.source.SourceConflictBundle
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TeX conflict screen with a fixture conflict: every control says whether it can be used and
 * why, and Save becomes available only once every conflict is resolved. Screenshots of each step
 * are written to the sandbox's external files directory under conflict-ux/.
 */
@RunWith(AndroidJUnit4::class)
class SourceConflictResolutionInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val backend = FakeBackend()
    private var nightModeBefore = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    @Before
    fun setUp() {
        nightModeBefore = AppCompatDelegate.getDefaultNightMode()
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        SourceConflictBackend.testOverride = backend
    }

    @After
    fun tearDown() {
        SourceConflictBackend.testOverride = null
        instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(nightModeBefore) }
    }

    @Test
    fun keepingAndEditingEachConflictEnablesSaveOnlyWhenAllAreResolved() {
        backend.bundle = fixture(TWO_CONFLICTS)
        launch().use { scenario ->
            waitFor(scenario) { it.view<View>(R.id.sourceConflictCard).isShown }
            scenario.onActivity { activity ->
                assertFalse("Save starts disabled", activity.view<Button>(R.id.sourceSave).isEnabled)
                assertEquals("Resolve 2 remaining conflicts first.", activity.text(R.id.sourceSaveHelper))
                assertEquals("0 of 2 conflicts resolved", activity.text(R.id.sourceConflictProgressLabel))
                assertEquals("Conflict 1 of 2", activity.text(R.id.sourceConflictPosition))
                assertEquals(
                    "Choose which version to keep for each conflict, or edit the text, then Save.",
                    activity.text(R.id.sourceConflictTabDescription),
                )
                assertEquals(View.GONE, activity.view<View>(R.id.sourceContent).visibility)
                assertFalse(activity.text(R.id.sourceConflictFedoraText).contains("<<<<<<<"))
                assertTrue(activity.text(R.id.sourceConflictFedoraText).contains("only on Fedora"))
                assertTrue(activity.text(R.id.sourceConflictPhoneText).contains("written on the phone"))
                listOf(R.id.sourceUseFedora, R.id.sourceUsePhone, R.id.sourceKeepBoth, R.id.sourceEditConflict,
                    R.id.sourcePreviousConflict, R.id.sourceNextConflict, R.id.sourceDiscardConflict,
                ).forEach { id -> assertTrue(activity.resources.getResourceEntryName(id), activity.view<View>(id).isEnabled) }
                // Enabled actions have full-contrast labels (WCAG AA), never pale-on-pale.
                listOf(R.id.sourceUseFedora, R.id.sourceUsePhone, R.id.sourceKeepBoth, R.id.sourceEditConflict,
                    R.id.sourceDiscardConflict,
                ).forEach { id -> assertReadable(activity.view(id)) }
                assertNotReadableAsEnabled(activity.view(R.id.sourceSave))
            }
            screenshot("01-initial-light.png")

            click(scenario, R.id.sourceUsePhone)
            scenario.onActivity { activity ->
                assertEquals("1 of 2 conflicts resolved", activity.text(R.id.sourceConflictProgressLabel))
                assertEquals("Conflict 1 of 1", activity.text(R.id.sourceConflictPosition))
                assertFalse(activity.view<Button>(R.id.sourceSave).isEnabled)
                assertEquals("Resolve 1 remaining conflict first.", activity.text(R.id.sourceSaveHelper))
                // One conflict left: there is nowhere to navigate to, and the arrows look it.
                listOf(R.id.sourcePreviousConflict, R.id.sourceNextConflict).forEach { id ->
                    val arrow = activity.view<MaterialButton>(id)
                    assertFalse(arrow.isEnabled)
                    assertTrue(Color.alpha(arrow.iconTint!!.getColorForState(arrow.drawableState, 0)) < 128)
                }
            }

            click(scenario, R.id.sourceEditConflict)
            scenario.onActivity { activity ->
                assertTrue(activity.view<View>(R.id.sourceConflictManualEditor).isShown)
                val manual = activity.view<TextView>(R.id.sourceConflictManualText)
                assertEquals("We tuned the margin to 0.2.", manual.text.toString())
                manual.text = "We tuned the margin to 0.25 after the ablation."
            }
            screenshot("02-edit-manually-light.png")

            click(scenario, R.id.sourceConflictManualApply)
            waitFor(scenario) { it.view<View>(R.id.sourceConflictDoneCard).isShown }
            scenario.onActivity { activity ->
                assertTrue("Save is enabled once resolved", activity.view<Button>(R.id.sourceSave).isEnabled)
                assertReadable(activity.view(R.id.sourceSave))
                assertEquals(
                    "All conflicts resolved. Save keeps this version on the phone; the next Sync sends it to the server.",
                    activity.text(R.id.sourceSaveHelper),
                )
                assertEquals("2 of 2 conflicts resolved", activity.text(R.id.sourceConflictProgressLabel))
                assertTrue("The final text is shown for review", activity.view<View>(R.id.sourceContent).isShown)
                assertEquals(RESOLVED, activity.view<TextView>(R.id.sourceEditor).text.toString())
                assertFalse("The keyboard is not handed to the full text", activity.view<View>(R.id.sourceEditor).hasFocus())
            }
            Thread.sleep(400) // Let the snackbar finish sliding in.
            screenshot("03-resolved-light.png")

            click(scenario, R.id.sourceSave)
            waitFor(scenario) { backend.saved.isNotEmpty() && !it.view<Button>(R.id.sourceSave).isEnabled }
            assertEquals(listOf(RESOLVED), backend.saved.toList())
            scenario.onActivity { activity ->
                assertEquals("Saved on this phone. Sync to send it to the server.", activity.text(R.id.sourceSaveHelper))
            }
            Thread.sleep(400)
            screenshot("04-saved-light.png")
        }
    }

    @Test
    fun keepBothUndoRawTextAndReadOnlyTabsExplainThemselves() {
        backend.bundle = fixture(TWO_CONFLICTS)
        launch().use { scenario ->
            waitFor(scenario) { it.view<View>(R.id.sourceConflictCard).isShown }
            click(scenario, R.id.sourceNextConflict)
            scenario.onActivity { assertEquals("Conflict 2 of 2", it.text(R.id.sourceConflictPosition)) }
            click(scenario, R.id.sourceKeepBoth)
            scenario.onActivity { activity ->
                assertTrue(activity.view<TextView>(R.id.sourceEditor).text.contains(
                    "We tuned the margin to 0.1.\nWe tuned the margin to 0.2.\n",
                ))
                assertEquals("1 of 2 conflicts resolved", activity.text(R.id.sourceConflictProgressLabel))
                activity.window.decorView.findViewById<View>(com.google.android.material.R.id.snackbar_action)
                    .performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(TWO_CONFLICTS, activity.view<TextView>(R.id.sourceEditor).text.toString())
                assertEquals("0 of 2 conflicts resolved", activity.text(R.id.sourceConflictProgressLabel))
            }

            click(scenario, R.id.sourceShowRawText)
            scenario.onActivity { activity ->
                assertTrue(activity.view<View>(R.id.sourceContent).isShown)
                assertTrue(activity.view<View>(R.id.sourceLineNumbers).isShown)
                assertTrue(activity.view<TextView>(R.id.sourceEditor).text.contains("<<<<<<< FEDORA_CURRENT"))
                assertTrue("Raw text wraps instead of scrolling sideways",
                    activity.view<TextView>(R.id.sourceEditor).layout.let { layout ->
                        layout.lineCount > layout.text.count { it == '\n' } + 1 &&
                            (0 until layout.lineCount).none { layout.getLineMax(it) > layout.width + 1 }
                    })
            }
            Thread.sleep(300)
            screenshot("05-raw-text-light.png")

            scenario.onActivity { it.view<TabLayout>(R.id.sourceConflictTabs).getTabAt(1)!!.select() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.text(R.id.sourceConflictTabDescription).startsWith("Read-only: the whole file as the server has it"))
                assertFalse(activity.view<Button>(R.id.sourceSave).isEnabled)
                assertEquals("You’re looking at a read-only version. Go back to Resolve to save.", activity.text(R.id.sourceSaveHelper))
                assertEquals(FEDORA, activity.view<TextView>(R.id.sourceEditor).text.toString())
            }
            screenshot("06-fedora-tab-light.png")
            scenario.onActivity { it.view<TabLayout>(R.id.sourceConflictTabs).getTabAt(0)!!.select() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(TWO_CONFLICTS, activity.view<TextView>(R.id.sourceEditor).text.toString())
                assertEquals("Resolve 2 remaining conflicts first.", activity.text(R.id.sourceSaveHelper))
            }
        }
    }

    @Test
    fun incompleteMarkersDisableTheChoicesAndSayWhy() {
        backend.bundle = fixture("before\n<<<<<<< FEDORA_CURRENT\nfedora only\n=======\nphone only\nafter\n")
        launch().use { scenario ->
            waitFor(scenario) { it.view<View>(R.id.sourceConflictCard).isShown }
            scenario.onActivity { activity ->
                assertTrue(activity.view<View>(R.id.sourceConflictMalformed).isShown)
                assertNotReadableAsEnabled(activity.view(R.id.sourceKeepBoth))
                assertTrue(activity.view<View>(R.id.sourceEditConflict).isEnabled)
                assertEquals("Fix the incomplete conflict markers first.", activity.text(R.id.sourceSaveHelper))
            }
            screenshot("07-incomplete-markers-light.png")
            click(scenario, R.id.sourceEditConflict)
            scenario.onActivity { assertTrue(it.view<View>(R.id.sourceContent).isShown) }
        }
    }

    @Test
    fun discardAsksFirstAndExplainsWhatIsLost() {
        backend.bundle = fixture(TWO_CONFLICTS)
        launch().use { scenario ->
            waitFor(scenario) { it.view<View>(R.id.sourceConflictCard).isShown }
            scenario.onActivity { activity ->
                val discard = activity.view<MaterialButton>(R.id.sourceDiscardConflict)
                val error = MaterialColors.getColor(discard, androidx.appcompat.R.attr.colorError)
                assertEquals("Destructive actions use the error color", error, discard.currentTextColor)
            }
            click(scenario, R.id.sourceDiscardConflict)
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                dialog = activity.discardDialog()!!
                assertTrue(dialog.isShowing)
                val message = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
                assertTrue(message, message.contains("sdpo.tex"))
                assertTrue(message, message.contains("The server’s version stays as it is"))
                assertTrue(message, message.contains("This can’t be undone."))
                assertEquals("Discard phone draft", dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
            }
            Thread.sleep(300)
            screenshot("08-discard-dialog-light.png")
            scenario.onActivity { dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick() }
            instrumentation.waitForIdleSync()
            assertTrue("Cancel keeps the draft", backend.discarded.isEmpty())

            click(scenario, R.id.sourceDiscardConflict)
            scenario.onActivity { it.discardDialog()!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
            val deadline = System.currentTimeMillis() + 5_000
            while (backend.discarded.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals(listOf(CONFLICT_ID), backend.discarded.toList())
        }
    }

    @Test
    fun typingInTheRawTextOfALargeFileStaysCheap() {
        val line = "The \\emph{student} policy is evaluated across the interval with the largest positive change.\n"
        val before = line.repeat(1500)
        val merged = before + "<<<<<<< FEDORA_CURRENT\nfedora\n=======\nphone\n>>>>>>> PHONE_INCOMING\n" + line.repeat(1500)
        backend.bundle = fixture(merged)
        launch().use { scenario ->
            waitFor(scenario) { it.view<View>(R.id.sourceConflictCard).isShown }
            click(scenario, R.id.sourceShowRawText)
            lateinit var editor: SourceEditText
            lateinit var connection: android.view.inputmethod.InputConnection
            scenario.onActivity { activity ->
                editor = activity.view(R.id.sourceEditor)
                editor.showSoftInputOnFocus = false
                editor.requestFocus()
                editor.setSelection(before.length / 2)
                connection = checkNotNull(editor.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            }
            instrumentation.waitForIdleSync()
            val input = mutableListOf<Double>()
            repeat(30) { index ->
                scenario.onActivity {
                    val cpu = android.os.Debug.threadCpuTimeNanos()
                    connection.beginBatchEdit()
                    connection.commitText(if (index % 6 == 5) "\n" else "x", 1)
                    connection.endBatchEdit()
                    input += (android.os.Debug.threadCpuTimeNanos() - cpu) / 1e6
                }
                Thread.sleep(40)
            }
            instrumentation.waitForIdleSync()
            val sorted = input.sorted()
            println("CONFLICT_RAW_TYPING chars=${merged.length} input_cpu_ms median=${sorted[sorted.size / 2]} max=${sorted.last()}")
            scenario.onActivity { activity ->
                assertEquals("1 conflict still open", "Conflict 1 of 1", activity.text(R.id.sourceConflictPosition))
                assertEquals(merged.length + 30, editor.length())
            }
            // Per keystroke the editor counts only the changed characters; parsing waits for a pause.
            assertTrue("Median keystroke ${sorted[sorted.size / 2]} ms", sorted[sorted.size / 2] < 16.0)
        }
    }

    @Test
    fun darkThemeScreenshots() {
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        backend.bundle = fixture(TWO_CONFLICTS)
        launch().use { scenario ->
            waitFor(scenario) { it.view<View>(R.id.sourceConflictCard).isShown }
            scenario.onActivity { activity ->
                listOf(R.id.sourceUseFedora, R.id.sourceUsePhone, R.id.sourceKeepBoth, R.id.sourceDiscardConflict)
                    .forEach { id -> assertReadable(activity.view(id)) }
            }
            screenshot("09-initial-dark.png")
            click(scenario, R.id.sourceUseFedora)
            click(scenario, R.id.sourceUsePhone)
            waitFor(scenario) { it.view<View>(R.id.sourceConflictDoneCard).isShown }
            Thread.sleep(400)
            screenshot("10-resolved-dark.png")
        }
    }

    private fun launch(): ActivityScenario<SourceEditorActivity> =
        ActivityScenario.launch<SourceEditorActivity>(SourceEditorActivity.conflictIntent(context, CONFLICT_ID))
            .also { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

    private fun click(scenario: ActivityScenario<SourceEditorActivity>, id: Int) {
        scenario.onActivity { activity ->
            val view = activity.view<View>(id)
            assertTrue("${activity.resources.getResourceEntryName(id)} is enabled", view.isEnabled)
            // A switch toggles without an OnClickListener, so performClick() reports false.
            if (view is android.widget.CompoundButton) view.performClick() else assertTrue(view.performClick())
        }
        instrumentation.waitForIdleSync()
    }

    private fun waitFor(
        scenario: ActivityScenario<SourceEditorActivity>,
        condition: (SourceEditorActivity) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        var met = false
        while (!met && System.currentTimeMillis() < deadline) {
            scenario.onActivity { met = condition(it) }
            if (!met) Thread.sleep(50)
        }
        assertTrue("Condition met in time", met)
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        val directory = File(context.getExternalFilesDir(null), "conflict-ux").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun <T : View> SourceEditorActivity.view(id: Int): T = findViewById(id)

    private fun SourceEditorActivity.text(id: Int): String = view<TextView>(id).text.toString()

    private fun SourceEditorActivity.discardDialog(): AlertDialog? =
        SourceEditorActivity::class.java.getDeclaredField("discardConfirmation")
            .apply { isAccessible = true }
            .get(this) as AlertDialog?

    /** The button's label against the container it actually draws, not the tint it reports. */
    private fun renderedContrast(button: MaterialButton): Double {
        val bitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(MaterialColors.getColor(button, com.google.android.material.R.attr.colorSurface))
        button.draw(canvas)
        // The label is centred; the most common colour across the middle row is the container.
        val y = button.height / 2
        val counts = HashMap<Int, Int>()
        for (x in button.height / 2 until button.width - button.height / 2) {
            val pixel = bitmap.getPixel(x, y)
            counts[pixel] = (counts[pixel] ?: 0) + 1
        }
        bitmap.recycle()
        return contrast(button.currentTextColor, counts.maxBy { it.value }.key)
    }

    private fun assertReadable(button: MaterialButton) {
        val name = button.resources.getResourceEntryName(button.id)
        assertTrue("$name is enabled", button.isEnabled)
        val ratio = renderedContrast(button)
        assertTrue("$name label contrast $ratio", ratio >= 4.5)
    }

    private fun assertNotReadableAsEnabled(button: MaterialButton) {
        assertFalse(button.isEnabled)
        assertTrue("Disabled buttons look disabled", renderedContrast(button) < 3.0)
    }

    private fun contrast(foreground: Int, background: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int) = (value / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
        }
        val opaque = Color.alpha(foreground) == 255
        val a = luminance(if (opaque) foreground else androidx.core.graphics.ColorUtils.compositeColors(foreground, background))
        val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun fixture(merged: String) = SourceConflictBundle(
        conflictId = CONFLICT_ID,
        requestId = "00000000-0000-4000-8000-000000000002",
        sourceFile = "docs-concepts/tex/source/sdpo.tex",
        pdfFilename = "sdpo.pdf",
        sourceLine = 3,
        resolutionId = "00000000-0000-4000-8000-000000000003",
        conflictCount = 2,
        expectedRevision = 7,
        currentSha256 = "a".repeat(64),
        incomingSha256 = "b".repeat(64),
        mergedObjectSha256 = "c".repeat(64),
        currentModifiedAt = Instant.now().minusSeconds(180).toString(),
        incomingModifiedAt = Instant.now().minusSeconds(120).toString(),
        currentContents = FEDORA,
        incomingContents = PHONE,
        mergedContents = merged,
    )

    private class FakeBackend : SourceConflictBackend {
        @Volatile var bundle: SourceConflictBundle? = null
        val saved = CopyOnWriteArrayList<String>()
        val discarded = CopyOnWriteArrayList<String>()

        override suspend fun load(context: Context, conflictId: String) = checkNotNull(bundle)

        override fun saveResolution(context: Context, bundle: SourceConflictBundle, contents: String) {
            saved += contents
        }

        override suspend fun discard(context: Context, conflictId: String, expectedSha256: String): Boolean {
            discarded += conflictId
            return true
        }
    }

    private companion object {
        const val CONFLICT_ID = "00000000-0000-4000-8000-000000000001"
        const val INTRO = "\\section{Self-distilled preference optimisation}\n" +
            "SDPO trains the policy on its own best samples, so the preference signal comes from the model itself rather than from a separately trained reward model, which keeps the method cheap.\n"
        const val FEDORA = INTRO +
            "The update uses a KL penalty only on Fedora, which keeps the policy close to the reference model while it learns from the distilled preferences.\n" +
            "We tuned the margin to 0.1.\n\\end{document}\n"
        const val PHONE = INTRO +
            "The update uses a KL penalty written on the phone, which keeps the policy close to the reference model during the first 500 steps of training.\n" +
            "We tuned the margin to 0.2.\n\\end{document}\n"
        const val TWO_CONFLICTS = INTRO +
            "<<<<<<< FEDORA_CURRENT\n" +
            "The update uses a KL penalty only on Fedora, which keeps the policy close to the reference model while it learns from the distilled preferences.\n" +
            "=======\n" +
            "The update uses a KL penalty written on the phone, which keeps the policy close to the reference model during the first 500 steps of training.\n" +
            ">>>>>>> PHONE_INCOMING\n" +
            "<<<<<<< FEDORA_CURRENT\n" +
            "We tuned the margin to 0.1.\n" +
            "=======\n" +
            "We tuned the margin to 0.2.\n" +
            ">>>>>>> PHONE_INCOMING\n" +
            "\\end{document}\n"
        const val RESOLVED = INTRO +
            "The update uses a KL penalty written on the phone, which keeps the policy close to the reference model during the first 500 steps of training.\n" +
            "We tuned the margin to 0.25 after the ablation.\n\\end{document}\n"
    }
}

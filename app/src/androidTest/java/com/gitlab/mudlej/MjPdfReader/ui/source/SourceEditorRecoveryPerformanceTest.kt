// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.os.Bundle
import android.os.Debug
import android.os.Parcel
import android.os.SystemClock
import android.graphics.Rect
import android.graphics.Bitmap
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.SourceSnapshot
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the retained native widget used for conflict recovery; disposable snapshots only. */
@RunWith(AndroidJUnit4::class)
class SourceEditorRecoveryPerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withEditor(contents: String, run: (ActivityScenario<SourceEditorActivity>) -> Unit) {
        val context = instrumentation.targetContext
        val key = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val file = File(context.filesDir, "source-editor/snapshots/$key.json")
        assertFalse(file.exists())
        SourceEditQueue(context, scheduleRequests = false).cacheSnapshot(key, SourceSnapshot(
            "editor-test", "docs-test/tex/source/disposable.tex", 1, "a".repeat(64), contents,
        ))
        try {
            ActivityScenario.launch<SourceEditorActivity>(SourceEditorActivity.intent(
                context, "", key, "editor-test.pdf", "a".repeat(64), "",
            ).setClass(context, SourceEditorActivity::class.java)).use { scenario ->
                instrumentation.waitForIdleSync()
                run(scenario)
            }
        } finally { file.delete() }
    }

    @Test fun largeDraftRoundTripsThroughSmallActivityState() {
        val contents = "A long source paragraph with formatting and unicode café.\n".repeat(14000)
        withEditor(contents) { scenario ->
            scenario.onActivity { activity ->
                val editor = activity.findViewById<SourceEditText>(R.id.sourceEditor)
                editor.text!!.insert(100, "UNSAVED")
                editor.setSelection(105, 109)
                val state = Bundle()
                instrumentation.callActivityOnSaveInstanceState(activity, state)
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(state)
                    println("EDITOR_RECOVERY activity_state_bytes=${parcel.dataSize()} chars=${editor.length()}")
                    assertTrue("Activity state must stay small", parcel.dataSize() < 32_000)
                } finally { parcel.recycle() }
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val editor = activity.findViewById<SourceEditText>(R.id.sourceEditor)
                assertEquals(contents.substring(0, 100) + "UNSAVED" + contents.substring(100), editor.text.toString())
                assertEquals(105, editor.selectionStart)
                assertEquals(109, editor.selectionEnd)
                assertTrue("Recovered edits must still require explicit Save", activity.findViewById<View>(R.id.sourceSave).isEnabled)
                assertTrue(editor.restoredFromDisk)
                assertTrue("Undo history must survive recreation", editor.onTextContextMenuItem(android.R.id.undo))
                assertEquals(contents, editor.text.toString())
            }
        }
    }

    @Test fun measureKeyboardCompositionAndRedraw() {
        // Match the approximate size and command density of the affected source.
        val source = ("\\paragraph{Result}\n" + "The \\emph{student} policy is evaluated across the interval with the largest positive change. ".repeat(7) + "\n").repeat(86)
        withEditor(source) { scenario ->
            lateinit var editor: EditText
            lateinit var connection: InputConnection
            scenario.onActivity { activity ->
                editor = activity.findViewById(R.id.sourceEditor)
                // Drive one synthetic IME only. The device's real IME otherwise
                // reacts to our selection updates by finishing composition.
                editor.showSoftInputOnFocus = false
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(editor.windowToken, 0)
                val root = activity.findViewById<View>(android.R.id.content)
                root.isFocusableInTouchMode = true
                root.requestFocus()
                (editor as SourceEditText).isEmojiCompatEnabled =
                    InstrumentationRegistry.getArguments().getString("editorEnableEmoji") == "true"
                (editor as SourceEditText).clipDrawingToViewport =
                    InstrumentationRegistry.getArguments().getString("editorDisableClip") != "true"
                editor.setSelection(10735)
                connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            }
            instrumentation.waitForIdleSync()
            val profile = InstrumentationRegistry.getArguments().getString("editorProfile") == "true"
            val path = File(instrumentation.targetContext.getExternalFilesDir(null), "editor-typing.trace")
            if (profile) Debug.startMethodTracingSampling(path.absolutePath, 16 * 1024 * 1024, 5000)
            val input = mutableListOf<Double>()
            val frame = mutableListOf<Double>()
            try {
                repeat(40) { index ->
                    val drawn = CountDownLatch(1)
                    var start = 0L
                    scenario.onActivity {
                        val listener = object : ViewTreeObserver.OnDrawListener {
                            override fun onDraw() {
                                editor.post {
                                    editor.viewTreeObserver.removeOnDrawListener(this)
                                    drawn.countDown()
                                }
                            }
                        }
                        editor.viewTreeObserver.addOnDrawListener(listener)
                        start = System.nanoTime()
                        val cpu = Debug.threadCpuTimeNanos()
                        connection.beginBatchEdit()
                        if (index % 8 == 7) connection.commitText("result ", 1)
                        else connection.setComposingText("result".take(index % 8 + 1), 1)
                        connection.endBatchEdit()
                        input += (Debug.threadCpuTimeNanos() - cpu) / 1e6
                        editor.invalidate()
                    }
                    assertTrue("Editor failed to draw", drawn.await(5, TimeUnit.SECONDS))
                    frame += (System.nanoTime() - start) / 1e6
                    SystemClock.sleep(70)
                }
            } finally { if (profile) Debug.stopMethodTracing() }
            fun stats(values: List<Double>): String {
                val sorted = values.sorted()
                return "median=${sorted[sorted.size / 2]} p95=${sorted[(sorted.size * .95).toInt()]} max=${sorted.last()}"
            }
            println("EDITOR_TYPING profiled=$profile chars=${source.length} input_cpu_ms ${stats(input)} input_to_draw_ms ${stats(frame)}")
            println("EDITOR_TYPING_INPUT $input")
            println("EDITOR_TYPING_FRAME $frame")
            scenario.onActivity {
                assertEquals(source.substring(0, 10735) + "result ".repeat(5) + source.substring(10735), editor.text.toString())
                connection.commitText("café 😀 漢字", 1)
                assertTrue(editor.text.toString().contains("café 😀 漢字"))
            }
        }
    }

    @Test fun viewportDrawingMatchesUnclippedRenderingAfterScrolling() {
        withEditor(("\\section{Heading}\n" + "Visible text with café and 😀. ".repeat(6) + "\n").repeat(180)) { scenario ->
            lateinit var editor: SourceEditText
            lateinit var scroll: ScrollView
            scenario.onActivity { activity ->
                editor = activity.findViewById(R.id.sourceEditor)
                scroll = activity.findViewById(R.id.sourceScroll)
                editor.isCursorVisible = false
            }
            SystemClock.sleep(500)
            for (fraction in listOf(0.0, 0.5, 1.0, 0.1)) {
                scenario.onActivity { scroll.scrollTo(0, ((editor.height - scroll.height) * fraction).toInt()) }
                SystemClock.sleep(150)
                fun capture(clipped: Boolean): Bitmap {
                    val rect = Rect()
                    scenario.onActivity {
                        editor.clipDrawingToViewport = clipped
                        editor.invalidate()
                        assertTrue(editor.getGlobalVisibleRect(rect))
                    }
                    SystemClock.sleep(150)
                    val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    return Bitmap.createBitmap(screen, rect.left, rect.top, rect.width(), rect.height()).also { screen.recycle() }
                }
                val clipped = capture(true)
                val reference = capture(false)
                try {
                    val a = IntArray(clipped.width * clipped.height)
                    val b = IntArray(reference.width * reference.height)
                    clipped.getPixels(a, 0, clipped.width, 0, 0, clipped.width, clipped.height)
                    reference.getPixels(b, 0, reference.width, 0, 0, reference.width, reference.height)
                    val changed = a.indices.count { a[it] != b[it] }
                    assertTrue("Scrolling $fraction changed $changed of ${a.size} pixels", changed < a.size / 100)
                } finally { clipped.recycle(); reference.recycle() }
            }
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.graphics.Rect
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.SourceSnapshot
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
// Legacy native conflict-widget layout coverage; normal editing is covered by UnifiedSourceEditorInstrumentedTest.
class SourceProseLayoutInstrumentedTest {
    @Test
    fun longProseWrapsWithinViewportAndKeepsSelectedTextVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = "f13d9b0e".repeat(8)
        val paragraph = "Tensor parallelism splits the computation of an individual layer across GPUs, including attention projections and the MLP, so each device processes only part of the layer."
        val contents = "\\section{Parallelism}\n" + (1..8).joinToString("\n\n") { paragraph }
        val snapshotFile = File(context.filesDir, "source-editor/snapshots/$key.json")
        assertFalse("Test cache key must be unused", snapshotFile.exists())
        SourceEditQueue(context).cacheSnapshot(key, SourceSnapshot(
            "prose-layout-test", "docs-concepts/tex/source/prose-layout-test.tex", 2,
            "a".repeat(64), contents,
        ))
        try {
            ActivityScenario.launch<SourceEditorActivity>(SourceEditorActivity.intent(
                context, "", key, "concepts.pdf", "a".repeat(64), "",
            ).setClass(context, SourceEditorActivity::class.java)).use { scenario ->
                instrumentation.waitForIdleSync()
                // Allow the keyboard resize and posted source-selection positioning to finish.
                Thread.sleep(1500)
                scenario.onActivity { activity ->
                    val editor = activity.findViewById<EditText>(R.id.sourceEditor)
                    val root = activity.findViewById<View>(android.R.id.content)
                    val layout = checkNotNull(editor.layout)
                    val available = editor.width - editor.totalPaddingLeft - editor.totalPaddingRight
                    assertTrue("Editor width ${editor.width} exceeds viewport ${root.width}", editor.width <= root.width)
                    assertTrue("Long prose must wrap into multiple visual lines", layout.lineCount > contents.count { it == '\n' } + 1)
                    for (line in 0 until layout.lineCount) {
                        // getLineWidth includes trailing whitespace beyond the wrap edge.
                        assertTrue("Visual line $line exceeds available width $available", layout.getLineMax(line) <= available + 1)
                    }
                    assertEquals("Soft wrapping must not change the source", contents, editor.text.toString())
                    assertEquals("No horizontal scroll in the editor", 0, editor.scrollX)
                    val location = IntArray(2)
                    editor.getLocationOnScreen(location)
                    assertTrue("Editor starts outside the screen", location[0] >= 0)
                    val visible = Rect()
                    assertTrue(editor.getGlobalVisibleRect(visible))
                    val line = layout.getLineForOffset(editor.selectionStart)
                    val caretY = location[1] + editor.totalPaddingTop + layout.getLineTop(line)
                    assertTrue("Caret must be inside the visible editor", caretY in visible.top until visible.bottom)
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "prose-layout-check.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        } finally {
            snapshotFile.delete()
        }
    }
}

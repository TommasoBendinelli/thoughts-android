// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.notes

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDrafts
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Creates synthetic data only on an explicitly opted-in, offline emulator. */
class NoteDetailsUiTest {
    @Test fun newNoteHasDateAndContextWithoutProjectAssignment() {

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var noteId = ""
        ActivityScenario.launch(QuickNoteActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val editor = activity.findViewById<TextInputEditText>(android.R.id.edit)
                noteId = activity.javaClass.getDeclaredField("noteId").apply { isAccessible = true }.get(activity) as String
                editor.setText("Discuss the next experiment.")
                views(activity.window.decorView).filterIsInstance<TextView>()
                    .first { it.text.toString() == activity.getString(R.string.note_details) }.performClick()
                val where = views(activity.window.decorView).filterIsInstance<TextInputLayout>()
                    .first { it.hint.toString() == activity.getString(R.string.note_check_where) }.editText!!
                where.setText("At the office, before the weekly meeting.")
                assertFalse(views(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString().startsWith("Project:") })
            }
            scenario.onActivity { activity ->
                views(activity.window.decorView).filterIsInstance<TextView>()
                    .first { it.text.toString().startsWith("Date:") }.performClick()
            }
            instrumentation.runOnMainSync {
                val root = WindowInspector.getGlobalWindowViews().first { views(it).any { view -> view is android.widget.DatePicker } }
                views(root).filterIsInstance<android.widget.DatePicker>().first().updateDate(2026, 9, 2)
                root.findViewById<View>(android.R.id.button1).performClick()
            }
            scenario.onActivity { activity ->
                views(activity.window.decorView).filterIsInstance<TextView>()
                    .first { it.text.toString().startsWith("Time:") }.performClick()
            }
            instrumentation.runOnMainSync {
                val root = WindowInspector.getGlobalWindowViews().first { views(it).any { view -> view is android.widget.TimePicker } }
                views(root).filterIsInstance<android.widget.TimePicker>().first().apply { hour = 9; minute = 30 }
                root.findViewById<View>(android.R.id.button1).performClick()
            }
            runBlocking {
                NoteDrafts.flush(context)
                val details = NoteDrafts.details(context, noteId)
                assertEquals("2026-10-02", details.calendarDate)
                assertEquals("09:30", details.calendarTime)
                assertTrue(details.calendarZoneId.isNotBlank())
                assertEquals("", details.projectId)
                assertEquals("", details.projectName)
                assertEquals("At the office, before the weekly meeting.", details.checkWhere)
            }
            scenario.onActivity { activity ->
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "note-details-test.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        runBlocking { assertEquals("", NoteDrafts.details(context, noteId).projectId) }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}

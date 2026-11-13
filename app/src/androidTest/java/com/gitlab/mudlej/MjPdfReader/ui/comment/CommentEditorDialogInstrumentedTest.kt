// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.comment

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentPriority
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.os.ParcelFileDescriptor
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CommentEditorDialogInstrumentedTest {
    @Test fun notesHaveNoLinkingDetailsButCommentsKeepThem() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (isNote in listOf(true, false)) {
                    val value = CommentEditorValue("An idea.",
                        noteDetails = if (isNote) com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails() else null,
                        linking = com.google.gson.JsonParser.parseString("""{"status":"failed"}""").asJsonObject)
                    val dialog = CommentEditorDialog(activity).show(
                        CommentEditorConfig(R.string.comment, value, focusText = false), onSave = { true })
                    dialog.findViewById<View>(R.id.commentEditorOptionsToggle)!!.performClick()
                    val expected = if (isNote) View.GONE else View.VISIBLE
                    assertEquals(expected, dialog.findViewById<View>(R.id.commentEditorLinkingAttempt)!!.visibility)
                    assertEquals(expected, dialog.findViewById<View>(R.id.commentEditorLinkingMethod)!!.visibility)
                    dialog.dismiss()
                }
            }
        }
    }

    @Test fun noteAutosavesWithoutClosingAndFlushesLatestTextOnClose() {
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var dialog: AlertDialog
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                dialog = CommentEditorDialog(activity).show(
                    CommentEditorConfig(R.string.note_edit,
                        CommentEditorValue("Original", noteDetails = com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails()),
                        focusText = false, allowEmptyText = true),
                    onSave = { error("Notes must autosave") },
                    onAutosave = { kotlinx.coroutines.delay(100); writes.add(it.value.text); true })
                val editor = dialog.findViewById<TextInputEditText>(R.id.commentEditorText)!!
                editor.setText("First edit")
                editor.setSelection(3)
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            while (writes.isEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
            assertEquals(listOf("First edit"), writes.toList())
            scenario.onActivity {
                val editor = dialog.findViewById<TextInputEditText>(R.id.commentEditorText)!!
                assertEquals(3, editor.selectionStart)
                assertTrue(dialog.isShowing)
                editor.setText("Latest edit")
                dialog.findViewById<MaterialButton>(R.id.commentEditorSave)!!.performClick()
            }
            val closeDeadline = SystemClock.uptimeMillis() + 5000
            while (writes.size < 2 && SystemClock.uptimeMillis() < closeDeadline) SystemClock.sleep(25)
            assertEquals(listOf("First edit", "Latest edit"), writes.toList())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertFalse(dialog.isShowing) }
        }
    }

    @Test fun failedNoteSaveKeepsEditorOpenForRetry() {
        var fail = true
        lateinit var dialog: AlertDialog
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                dialog = CommentEditorDialog(activity).show(
                    CommentEditorConfig(R.string.note_edit,
                        CommentEditorValue("Original", noteDetails = com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails()),
                        focusText = false, allowEmptyText = true), onSave = { true },
                    onAutosave = { !fail })
                dialog.findViewById<TextInputEditText>(R.id.commentEditorText)!!.setText("Retained")
                dialog.findViewById<MaterialButton>(R.id.commentEditorSave)!!.performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(dialog.isShowing)
                assertEquals("Retained", dialog.findViewById<TextInputEditText>(R.id.commentEditorText)!!.text.toString())
                fail = false
                dialog.findViewById<MaterialButton>(R.id.commentEditorSave)!!.performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertFalse(dialog.isShowing) }
        }
    }

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Preferences(PreferenceManager.getDefaultSharedPreferences(context)).apply {
            setFirstInstall(false)
            setShowFeaturesDialog(false)
        }
    }

    private fun capture(name: String, dialog: AlertDialog) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        saveScreenshot(bitmap, name)
        bitmap.recycle()
        instrumentation.runOnMainSync {
            val view = dialog.window!!.decorView
            val rendered = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(rendered))
            saveScreenshot(rendered, "native-$name")
            rendered.recycle()
        }
    }

    private fun saveScreenshot(bitmap: Bitmap, name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pipes = automation.executeShellCommandRw("dd of=/data/local/tmp/test-${TestRunIdentity.id}-$name")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
    }

    @Test fun flagDetailsAndSavePreserveValuesWithKeyboard() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            lateinit var dialog: AlertDialog
            var saved: CommentEditorResult? = null
            scenario.onActivity { activity ->
                dialog = CommentEditorDialog(activity).show(
                    CommentEditorConfig(R.string.comment, CommentEditorValue("", priority = CommentPriority.HIGH)),
                    onSave = { saved = it; true })
                assertEquals("Comment", dialog.findViewById<TextView>(R.id.commentEditorTitle)!!.text)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.commentEditorOptions)!!.visibility)
                val flag = dialog.findViewById<MaterialButton>(R.id.commentEditorActionFlag)!!
                assertFalse(flag.isChecked)
                flag.performClick()
                assertTrue(flag.isChecked)
                dialog.findViewById<TextInputEditText>(R.id.commentEditorText)!!.setText("Please clarify this argument.")
            }
            capture("comment-collapsed.png", dialog)
            scenario.onActivity {
                val save = dialog.findViewById<View>(R.id.commentEditorSave)!!
                val visible = android.graphics.Rect()
                assertTrue(save.getGlobalVisibleRect(visible))
                assertEquals(save.height, visible.height())
                assertTrue(save.height >= 48 * save.resources.displayMetrics.density - 1)
                dialog.findViewById<View>(R.id.commentEditorOptionsToggle)!!.performClick()
                assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.commentEditorOptions)!!.visibility)
            }
            capture("comment-details.png", dialog)
            scenario.onActivity {
                dialog.findViewById<View>(R.id.commentEditorOptionsToggle)!!.performClick()
                dialog.findViewById<View>(R.id.commentEditorSave)!!.performClick()
                assertFalse(dialog.isShowing)
                assertEquals("Please clarify this argument.", saved!!.value.text)
                assertEquals(CommentPriority.HIGH, saved!!.value.priority)
                assertTrue(saved!!.value.needsAction)
            }
        }
    }

    @Test fun cancelAndFailedSaveDoNotDismissOrCommitUnexpectedly() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var attempts = 0
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                dialog = CommentEditorDialog(activity).show(
                    CommentEditorConfig(R.string.comment, CommentEditorValue("Existing", needsAction = true), focusText = false),
                    onSave = { attempts++; false })
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val flag = dialog.findViewById<MaterialButton>(R.id.commentEditorActionFlag)!!
                assertTrue(flag.isChecked)
                flag.performClick()
                assertFalse(flag.isChecked)
                dialog.findViewById<View>(R.id.commentEditorSave)!!.performClick()
                assertTrue(dialog.isShowing)
                assertEquals(1, attempts)
                dialog.findViewById<View>(R.id.commentEditorCancel)!!.performClick()
                assertFalse(dialog.isShowing)
                assertEquals(1, attempts)
            }
        }
    }
}

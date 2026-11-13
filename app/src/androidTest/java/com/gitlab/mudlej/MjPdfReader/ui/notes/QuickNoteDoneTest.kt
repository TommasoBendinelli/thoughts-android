// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.notes

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDrafts
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Run through the isolated sandbox runner; no production data or server account is used. */
class QuickNoteDoneTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before fun requireIsolatedDevice() {
        check(context.packageName == "org.tbend.thoughts.sandbox")
        com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity.id
        val preferences = com.gitlab.mudlej.MjPdfReader.data.Preferences(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
        preferences.setFirstInstall(false)
        preferences.setShowFeaturesDialog(false)
    }

    @Test fun doneIsFullWidthAndLargeWithDetailsAboveText() {
        ActivityScenario.launch<QuickNoteActivity>(Intent(context, QuickNoteActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            awaitCondition {
                var visible = false
                scenario.onActivity { activity ->
                    visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            scenario.onActivity { activity ->
                val done = activity.findViewById<View>(R.id.quickNoteDone)
                val editor = activity.findViewById<View>(android.R.id.edit)
                val details = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .first { it.text.toString() == activity.getString(R.string.note_details) }
                val density = activity.resources.displayMetrics.density
                assertTrue(done.height >= 87 * density)
                assertEquals((done.parent as View).width - (done.parent as View).paddingLeft - (done.parent as View).paddingRight, done.width)
                val textPosition = IntArray(2).also(editor::getLocationOnScreen)
                val detailsPosition = IntArray(2).also(details::getLocationOnScreen)
                assertTrue(detailsPosition[1] < textPosition[1])
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                val keyboardHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                val donePosition = IntArray(2).also(done::getLocationOnScreen)
                assertTrue("Done must remain above the open keyboard", donePosition[1] + done.height <= activity.window.decorView.height - keyboardHeight)
            }
        }
    }

    @Test fun discardDoesNotResurrectAfterStaleEditorSave() = runBlocking {
        val id = "test-${com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity.id}-${UUID.randomUUID()}"
        NoteDrafts.begin(context, id)
        NoteDrafts.keep(context, id, "Discard me.")
        NoteDrafts.discard(context, id)
        assertFalse(NoteDrafts.keep(context, id, "Stale text."))
        NoteDrafts.flush(context)
        assertNull(NoteDrafts.text(context, id))
        assertNoNote(id)
        assertFalse(NoteDrafts.unfinished(context).any { it.id == id })
    }

    private suspend fun payload(id: String) = FedoraRepository(context).effectiveLogicalCommentPayloads().firstOrNull { it.get("comment_id").asString == id }
    private suspend fun assertNoNote(id: String) { assertNull("An unfinished capture must not enter the Notes outbox", payload(id)) }
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(50) }
        fail("Expected local note state was not reached")
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}

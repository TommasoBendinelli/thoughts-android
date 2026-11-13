// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.notes

import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails
import com.gitlab.mudlej.MjPdfReader.data.annotation.newNotePayload
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTab
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewTabController
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The To-dos tab hides to-dos until their date and shows today's calendar above them. */
class TodoListUiTest {
    @Test fun laterTodosAreHiddenUntilShownAndTodayCardReadsTheCalendar() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.READ_CALENDAR)
        val preferences = Preferences(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
        val previousTab = preferences.getHomeTab()
        val repository = FedoraRepository(context)
        val now = "test-${TestRunIdentity.id}-todo-${UUID.randomUUID()}"
        val later = "$now-later"
        val timestamp = Instant.now().toString()
        val payloads = listOf(
            newNotePayload(now, "[TEST ${TestRunIdentity.id}] Visible to-do", timestamp),
            NoteDetails(visibleFrom = LocalDate.now().plusDays(3).toString())
                .applyTo(newNotePayload(later, "[TEST ${TestRunIdentity.id}] Later to-do", timestamp)),
        )
        try {
            runBlocking { repository.enqueueLogicalComments(payloads) }
            val snapshot = runBlocking { OfflineOverviewSourceSnapshot().withLogicalPayloads(repository.effectiveLogicalCommentPayloads()) }
            preferences.setHomeTab(HomeTab.NOTES)
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                lateinit var controller: OverviewTabController
                scenario.onActivity {
                    controller = field(it, "notesTab") as OverviewTabController
                    controller.render(emptyList(), SourceLinkedPdfIndexSnapshot(), emptyList(), snapshot)
                }
                fun page() = field(controller, "page") as View
                fun shownIds(): List<String> {
                    val method = controller.javaClass.declaredMethods.single { it.name == "filteredComments" && it.parameterCount == 1 }
                    @Suppress("UNCHECKED_CAST")
                    val all = controller.javaClass.getDeclaredMethod("allComments").apply { isAccessible = true }.invoke(controller)
                    @Suppress("UNCHECKED_CAST")
                    return (method.apply { isAccessible = true }.invoke(controller, all) as List<com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment>).map { it.id }
                }
                fun awaitState(label: String, predicate: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 10000
                    var ready = false
                    while (!ready && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity { ready = runCatching(predicate).getOrDefault(false) }
                        if (!ready) SystemClock.sleep(50)
                    }
                    assertTrue(label, ready)
                }
                awaitState("the visible to-do is listed and the later one is not") {
                    shownIds().let { now in it && later !in it }
                }
                awaitState("the toggle offers the hidden to-do") {
                    page().findViewById<MaterialButton>(R.id.overviewHiddenTodosToggle).visibility == View.VISIBLE
                }
                scenario.onActivity {
                    val summary = page().findViewById<TextView>(R.id.overviewSummary).text.toString()
                    assertTrue(summary, summary.contains("hidden until later"))
                    page().findViewById<MaterialButton>(R.id.overviewHiddenTodosToggle).performClick()
                    assertTrue("Show hidden lists the later to-do", later in shownIds())
                    page().findViewById<MaterialButton>(R.id.overviewHiddenTodosToggle).performClick()
                    assertFalse(later in shownIds())
                }
                awaitState("the Today card read the calendar") {
                    val container = page().findViewById<FrameLayout>(R.id.todoCalendarContainer)
                    val card = container.getChildAt(0) as TodayCalendarCard
                    container.visibility == View.VISIBLE && texts(card).any {
                        it == context.getString(R.string.todo_calendar_empty) || it.contains("–") || it.startsWith("All day")
                    } && context.getString(R.string.todo_open_calendar) in texts(card)
                }
            }
        } finally {
            preferences.setHomeTab(previousTab)
            runBlocking {
                val ids = setOf(now, later)
                repository.enqueueLogicalComments(repository.effectiveLogicalCommentPayloads().filter { it.get("comment_id").asString in ids }.map {
                    it.deepCopy().apply {
                        addProperty("deleted", true); addProperty("revision", get("revision").asInt + 1)
                        addProperty("updated_at", Instant.now().toString())
                    }
                })
            }
        }
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}

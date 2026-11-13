// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.annotation.DiaryRow
import com.gitlab.mudlej.MjPdfReader.data.annotation.newNotePayload
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Typing in the Diary and pressing Enter saves an entry under Today; it never shows among the To-dos. */
class DiaryTabUiTest {
    @Test fun enterSavesAnEntryUnderTodayAndKeepsItOutOfTodos() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = Preferences(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
        val previousTab = preferences.getHomeTab()
        val repository = FedoraRepository(context)
        val text = "[TEST ${TestRunIdentity.id}] Diary ${UUID.randomUUID()}"
        val todo = "test-${TestRunIdentity.id}-todo-${UUID.randomUUID()}"
        fun written() = runBlocking { repository.effectiveLogicalCommentPayloads() }
            .filter { it.get("contents")?.takeIf { value -> value.isJsonPrimitive }?.asString == text }
        try {
            runBlocking { repository.enqueueLogicalComments(listOf(newNotePayload(todo, "[TEST ${TestRunIdentity.id}] To-do", Instant.now().toString()))) }
            preferences.setHomeTab(HomeTab.DIARY)
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                lateinit var controller: DiaryTabController
                scenario.onActivity { controller = field(it, "diaryTab") as DiaryTabController }
                fun awaitState(label: String, predicate: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 10000
                    var ready = false
                    while (!ready && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity { ready = runCatching(predicate).getOrDefault(false) }
                        if (!ready) SystemClock.sleep(50)
                    }
                    assertTrue(label, ready)
                }
                awaitState("the Diary field is attached") { field(controller, "input") is TextInputEditText }
                val before = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                scenario.onActivity {
                    val input = field(controller, "input") as TextInputEditText
                    input.setText(text)
                    input.onEditorAction(EditorInfo.IME_ACTION_SEND)
                    assertEquals("saving clears the field", "", input.text.toString())
                }
                awaitState("the entry is listed under Today") {
                    placed(controller, text)?.first == context.getString(R.string.diary_today)
                }
                scenario.onActivity {
                    val time = placed(controller, text)!!.second.time
                    val shown = LocalTime.parse(time)
                    val now = LocalTime.now()
                    assertTrue("$time is the time it was written", !shown.isAfter(now) &&
                        !shown.isBefore(before.toLocalTime().truncatedTo(ChronoUnit.MINUTES)))
                }
                awaitState("the entry is queued for Sync as a diary note") { written().size == 1 }
                val payload = written().single()
                assertEquals("Note", payload.get("subtype").asString)
                assertEquals("diary", payload.get("entry_kind").asString)
                val at = OffsetDateTime.parse(payload.get("diary_at").asString)
                assertFalse(at.isBefore(before))
                assertFalse(at.isAfter(OffsetDateTime.now()))
                assertTrue(payload.get("diary_zone_id").asString.isNotBlank())

                val snapshot = runBlocking { OfflineOverviewSourceSnapshot().withLogicalPayloads(repository.effectiveLogicalCommentPayloads()) }
                scenario.onActivity {
                    val notes = field(it, "notesTab") as OverviewTabController
                    notes.render(emptyList(), SourceLinkedPdfIndexSnapshot(), emptyList(), snapshot)
                    val method = notes.javaClass.declaredMethods.single { m -> m.name == "filteredComments" && m.parameterCount == 1 }
                    val all = notes.javaClass.getDeclaredMethod("allComments").apply { isAccessible = true }.invoke(notes)
                    @Suppress("UNCHECKED_CAST")
                    val shown = (method.apply { isAccessible = true }.invoke(notes, all) as List<com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment>).map { c -> c.id }
                    assertTrue("the to-do is listed", todo in shown)
                    assertFalse("the diary entry is not a to-do", payload.get("comment_id").asString in shown)
                    assertNull("the to-do is not in the Diary", rows(controller).firstOrNull { row ->
                        ((row as? DiaryListRow.Row)?.value as? DiaryRow.Entry)?.entry?.id == todo
                    })
                }
            }
        } finally {
            preferences.setHomeTab(previousTab)
            runBlocking {
                repository.enqueueLogicalComments(repository.effectiveLogicalCommentPayloads().filter {
                    it.get("comment_id").asString == todo || it.get("contents")?.takeIf { value -> value.isJsonPrimitive }?.asString == text
                }.map {
                    it.deepCopy().apply {
                        addProperty("deleted", true); addProperty("revision", get("revision").asInt + 1)
                        addProperty("updated_at", Instant.now().toString())
                    }
                })
            }
        }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    @Suppress("UNCHECKED_CAST")
    private fun rows(controller: DiaryTabController): List<DiaryListRow> =
        (field(controller, "adapter") as DiaryAdapter).currentList

    /** The day header above the entry with [text], and the entry row itself. */
    private fun placed(controller: DiaryTabController, text: String): Pair<String, DiaryRow.Entry>? {
        var day: String? = null
        rows(controller).forEach { row ->
            val value = (row as? DiaryListRow.Row)?.value ?: return@forEach
            when (value) {
                is DiaryRow.Day -> day = value.label
                is DiaryRow.Entry -> if (value.entry.text == text) return (day ?: return null) to value
            }
        }
        return null
    }
}

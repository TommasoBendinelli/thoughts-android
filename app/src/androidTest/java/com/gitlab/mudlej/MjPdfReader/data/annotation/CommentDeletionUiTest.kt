// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.os.SystemClock
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTab
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewTabController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity

/** Exercises the actual selection toolbar with unique fixtures and restores preferences. */
class CommentDeletionUiTest {
    @Test fun selectionDeleteUndoRedoWorksWithoutPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = Preferences(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
        val previousTab = preferences.getHomeTab()
        val previousFilter = preferences.getOverviewCommentTypeFilter()
        val repository = FedoraRepository(context)
        val root = "test-${TestRunIdentity.id}-ui-${UUID.randomUUID()}"
        val reply = "$root-reply"
        val keep = "$root-keep"
        val ids = setOf(root, reply, keep)
        val timestamp = Instant.now().toString()
        val payloads = listOf(newNotePayload(root, "[TEST ${TestRunIdentity.id}] Deletion UI fixture", timestamp),
            newNotePayload(reply, "[TEST ${TestRunIdentity.id}] Reply fixture", timestamp).apply {
                addProperty("parent_id", root); addProperty("thread_root_id", root)
            }, newNotePayload(keep, "[TEST ${TestRunIdentity.id}] Unrelated fixture", timestamp))
        try {
            runBlocking { repository.enqueueLogicalComments(payloads) }
            val snapshot = runBlocking { OfflineOverviewSourceSnapshot().withLogicalPayloads(repository.effectiveLogicalCommentPayloads()) }
            preferences.setHomeTab(HomeTab.NOTES)
            preferences.setOverviewCommentTypeFilter("notes")
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                lateinit var controller: OverviewTabController
                scenario.onActivity {
                    controller = field(it, "notesTab") as OverviewTabController
                    controller.render(emptyList(), SourceLinkedPdfIndexSnapshot(), emptyList(), snapshot)
                }
                fun comments(): List<OverviewComment> {
                    @Suppress("UNCHECKED_CAST")
                    return (field(controller, "documents") as List<OverviewCommentDocument>).flatMap { it.comments }
                }
                fun awaitState(label: String, predicate: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 10000
                    var ready = false
                    while (!ready && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity { ready = predicate() }
                        if (!ready) SystemClock.sleep(50)
                    }
                    assertTrue(label, ready)
                }
                awaitState("Synthetic note is available") { comments().any { it.id == root } }
                scenario.onActivity {
                    controller.javaClass.getDeclaredMethod("commentLongClicked", OverviewComment::class.java)
                        .apply { isAccessible = true }.invoke(controller, comments().single { it.id == root })
                    assertTrue((field(controller, "page") as android.view.View).findViewById<Toolbar>(R.id.overviewSelectionToolbar).menu.performIdentifierAction(103, 0))
                }
                awaitState("Delete hides the root and reply and preserves the other note") {
                    comments().none { it.id == root || it.id == reply } && comments().any { it.id == keep } && field(controller, "operationPending") == false
                }
                scenario.onActivity {
                    controller.render(emptyList(), SourceLinkedPdfIndexSnapshot(), emptyList(), snapshot)
                    assertTrue("A delayed snapshot must not resurrect the deleted note", comments().none { it.id == root })
                    assertTrue("Catalogue refresh must preserve service Undo", (field(controller, "history") as com.gitlab.mudlej.MjPdfReader.ui.home.OverviewHistory<*>).canUndo)
                    controller.javaClass.getDeclaredMethod("undo").apply { isAccessible = true }.invoke(controller)
                }
                awaitState("Undo restores the thread") { comments().any { it.id == root } && comments().any { it.id == reply } && field(controller, "operationPending") == false }
                scenario.onActivity { controller.javaClass.getDeclaredMethod("redo").apply { isAccessible = true }.invoke(controller) }
                awaitState("Redo hides the thread") { comments().none { it.id == root || it.id == reply } && field(controller, "operationPending") == false }
            }
        } finally {
            preferences.setHomeTab(previousTab)
            preferences.setOverviewCommentTypeFilter(previousFilter)
            runBlocking {
                repository.enqueueLogicalComments(repository.effectiveLogicalCommentPayloads().filter { it.get("comment_id").asString in ids }.map {
                    it.deepCopy().apply {
                        addProperty("deleted", true); addProperty("revision", get("revision").asInt + 1)
                        addProperty("updated_at", Instant.now().toString())
                    }
                })
            }
        }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.ContextWrapper
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.agents.AgentConversations
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class LibraryAgentConversationsInstrumentedTest {
    private val projectId = "test"
    private val claudeId = "5ef4cd0f-6a8a-5b7a-ac7b-1e9f51cd3139"
    private val codexId = "01900000-0000-7000-8000-000000000002"
    private val claudeUrl = "https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv"

    private fun record(agent: String, conversationId: String, url: String, title: String, lastActive: String) = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("link_id", AgentConversations.linkId(projectId, agent, conversationId))
        addProperty("project_id", projectId)
        addProperty("agent", agent)
        addProperty("conversation_id", conversationId)
        addProperty("conversation_url", url)
        addProperty("title", title)
        addProperty("title_source", "app")
        addProperty("session_name", if (agent == "claude") "alex-94" else "")
        addProperty("linked_by", "tool")
        addProperty("last_tool", "create_proposal")
        addProperty("device_name", "Mac")
        addProperty("created_at", "2026-10-26T11:02:13.511Z")
        addProperty("last_active_at", lastActive)
        addProperty("updated_at", lastActive)
        addProperty("revision", 2)
        addProperty("origin_id", "desktop-test")
        addProperty("removed", false)
        add("removed_at", JsonNull.INSTANCE)
        addProperty("deleted", false)
    }

    private fun configuration(id: String, name: String) = """{"schema_version":5,"kind":"thoughts-project","id":"$id","name":"$name",
        "default_folder_id":"default","documents":[],"source_folders":[{"folder_id":"default","path":".thoughts/projects/$id/documents"}]}"""

    @Test fun projectShowsCollapsibleAgentConversations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "agent-conversations-${UUID.randomUUID()}").apply { mkdirs() }
        val previousFile = AgentConversations.file
        AgentConversations.file = { File(root, "agent-conversations.json") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            File(root, "state.json").writeText("""
                {"pending":[],"projects":[{
                  "id":"$projectId","name":"Concepts","documents":[],"target":"",
                  "project_configuration":${configuration(projectId, "Concepts")}
                }]}
            """.trimIndent())
            val server = listOf(
                record("codex", codexId, "codex://threads/$codexId", "Codex refactor", "2026-10-25T10:00:00.000Z"),
                record("claude", claudeId, claudeUrl, "Thoughts search on mobile", "2026-10-26T11:40:02.004Z"),
                record("claude", "11111111-1111-4111-8111-111111111111", "", "Old study chat", "2026-10-20T08:00:00.000Z"),
                record("claude", "22222222-2222-4222-8222-222222222222", claudeUrl, "Removed one", "2026-10-26T12:00:00.000Z").apply {
                    addProperty("removed", true); addProperty("removed_at", "2026-10-26T12:10:00.000Z")
                },
            )
            val started = mutableListOf<Intent>()
            val functions = object : HomeItemFunctions {
                override fun onItemClicked(item: HomeItem) = Unit
                override fun onProjectDocumentClicked(item: HomeItem, pageIndex: Int, documentId: String?, displayTitle: String?, projectId: String?) = Unit
                override fun onItemLongClicked(item: HomeItem) = false
                override fun onItemOptionsClicked(item: HomeItem) = Unit
            }
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                lateinit var pane: LibraryProjectPane
                lateinit var parent: LinearLayout
                scenario.onActivity { activity ->
                    val capturing = object : ContextWrapper(activity) {
                        override fun startActivity(intent: Intent) { started += intent }
                    }
                    pane = LibraryProjectPane(capturing, scope, functions, {}, {}, LibraryProjectStore(context, root)) {
                        AgentConversations.visibleByProject(server, AgentConversations.pending())
                    }
                    parent = LinearLayout(activity)
                    pane.render(emptyList(), SourceLinkedPdfIndexSnapshot())
                }
                fun bind(position: Int) = pane.onCreateViewHolder(parent, 0).also { pane.onBindViewHolder(it, position) }.row
                fun label(position: Int): String {
                    val row = bind(position)
                    return (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TextView>().first().text.toString()
                }
                fun waitUntil(what: String, condition: () -> Boolean) {
                    val deadline = System.currentTimeMillis() + 10_000
                    while (true) {
                        instrumentation.waitForIdleSync()
                        var done = false
                        scenario.onActivity { done = condition() }
                        if (done) return
                        check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
                        Thread.sleep(50)
                    }
                }
                scenario.onActivity { bind(1).getChildAt(0).performClick() } // Expand Concepts.
                // header, project, Documents, empty, Sources, Agent conversations
                waitUntil("the conversations to load") { pane.itemCount == 6 && label(5).contains("Agent conversations (3)") }
                scenario.onActivity {
                    assertTrue(label(5).startsWith("›  "))
                    bind(5).performClick() // Expand the group.
                    assertEquals(9, pane.itemCount)
                    assertTrue(label(5).startsWith("⌄  "))
                    // Newest activity first; the removed record is hidden.
                    assertTrue(label(6).startsWith("Thoughts search on mobile\nClaude · "))
                    assertTrue(label(6).endsWith(" · alex-94"))
                    assertTrue(label(7).startsWith("Codex refactor\nCodex · "))
                    assertTrue(label(8).startsWith("Old study chat\nClaude · "))
                    assertEquals(1f, bind(6).alpha)
                    assertEquals(0.5f, bind(7).alpha) // Codex opens only on the Mac.
                    assertEquals(0.5f, bind(8).alpha) // Claude without a Remote Control link.
                    bind(7).performClick()
                    bind(8).performClick()
                    assertTrue(started.isEmpty())
                    bind(6).performClick()
                    assertEquals(1, started.size)
                    assertEquals(Intent.ACTION_VIEW, started.single().action)
                    assertEquals(claudeUrl, started.single().dataString)
                    assertTrue(started.single().hasCategory(Intent.CATEGORY_BROWSABLE))

                    // Remove the Codex conversation: pending locally, hidden at once.
                    val codex = AgentConversations.parse(server[0])!!
                    pane.removeConversation(codex)
                    assertEquals(8, pane.itemCount)
                    assertTrue(label(5).contains("Agent conversations (2)"))
                    assertEquals(listOf(codex.linkId), AgentConversations.pending().map { it.linkId })
                    assertTrue(AgentConversations.pending().single().removed)
                    assertEquals(3L, AgentConversations.pending().single().revision)
                }
                // A reload keeps it hidden.
                scenario.onActivity { pane.render(emptyList(), SourceLinkedPdfIndexSnapshot()) }
                waitUntil("the reload") { pane.itemCount == 8 }
                scenario.onActivity {
                    assertTrue((6 until 8).map(::label).none { it.startsWith("Codex refactor") })
                    bind(5).performClick() // Collapse.
                    assertEquals(6, pane.itemCount)
                }
            }
        } finally {
            scope.cancel()
            AgentConversations.file = previousFile
            root.deleteRecursively()
        }
    }

    @Test fun projectWithoutConversationsSaysSo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "agent-conversations-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            File(root, "state.json").writeText("""{"pending":[],"projects":[{"id":"p","name":"Empty","documents":[],"target":"","project_configuration":${configuration("p", "Empty")}}]}""")
            val functions = object : HomeItemFunctions {
                override fun onItemClicked(item: HomeItem) = Unit
                override fun onProjectDocumentClicked(item: HomeItem, pageIndex: Int, documentId: String?, displayTitle: String?, projectId: String?) = Unit
                override fun onItemLongClicked(item: HomeItem) = false
                override fun onItemOptionsClicked(item: HomeItem) = Unit
            }
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val pane = LibraryProjectPane(activity, scope, functions, {}, {}, LibraryProjectStore(context, root)) { emptyMap() }
                    pane.render(emptyList(), SourceLinkedPdfIndexSnapshot())
                    val parent = LinearLayout(activity)
                    fun bind(position: Int) = pane.onCreateViewHolder(parent, 0).also { pane.onBindViewHolder(it, position) }.row
                    fun label(position: Int) = (0 until bind(position).childCount).map { bind(position).getChildAt(it) }
                        .filterIsInstance<TextView>().first().text.toString()
                    bind(1).getChildAt(0).performClick()
                    assertEquals(6, pane.itemCount)
                    assertTrue(label(5).contains("Agent conversations (0)"))
                    bind(5).performClick()
                    assertEquals(7, pane.itemCount)
                    assertEquals("No agent conversations yet", label(6))
                }
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}

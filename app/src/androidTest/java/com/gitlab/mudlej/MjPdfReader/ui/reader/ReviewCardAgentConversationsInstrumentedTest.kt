// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.agents.AgentConversations
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsProjectionEntity
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * thoughts-card-agent-conversations-SPEC.md §5 with fixture records (the Mac writes the real ones):
 * the newest conversation linked to the card shows above Flip | Reveal | Set knowledge, the menu
 * counts them, Set knowledge keeps the line where it is, and Remove from this card hides it.
 */
@RunWith(AndroidJUnit4::class)
class ReviewCardAgentConversationsInstrumentedTest {
    private val projectId = "source:docs-concepts"
    private val claudeId = "5ef4cd0f-6a8a-5b7a-ac7b-1e9f51cd3139"
    private val codexId = "01900000-0000-7000-8000-000000000002"
    private val removedId = "11111111-1111-4111-8111-111111111111"
    private val otherCardId = "22222222-2222-4222-8222-222222222222"
    private val claudeUrl = "https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv"

    private fun entry(cardId: String, lastActive: String, removed: Boolean = false) = JsonObject().apply {
        addProperty("card_id", cardId)
        addProperty("linked_at", "2026-10-26T10:00:00.000Z")
        addProperty("last_active_at", lastActive)
        addProperty("linked_by", "prompt")
        addProperty("removed", removed)
    }

    private fun record(agent: String, conversationId: String, url: String, title: String, entries: List<JsonObject>) = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("link_id", AgentConversations.linkId(projectId, agent, conversationId))
        addProperty("project_id", projectId)
        addProperty("agent", agent)
        addProperty("conversation_id", conversationId)
        addProperty("conversation_url", url)
        addProperty("title", title)
        addProperty("title_source", "prompt")
        addProperty("session_name", if (agent == "claude") "alex-61" else "")
        addProperty("linked_by", "tool")
        addProperty("last_tool", "prompt")
        addProperty("device_name", "Mac")
        addProperty("created_at", "2026-10-26T10:00:00.000Z")
        addProperty("last_active_at", "2026-10-26T11:00:00.000Z")
        addProperty("updated_at", "2026-10-26T11:00:00.000Z")
        addProperty("revision", 2)
        addProperty("origin_id", "desktop-test")
        addProperty("removed", false)
        add("removed_at", JsonNull.INSTANCE)
        addProperty("deleted", false)
        add("review_cards", JsonArray().apply { entries.forEach(::add) })
    }

    @Test fun cardShowsNewestConversationAndRemovesItFromTheCard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1").apply {
            parentFile!!.mkdirs()
            writeText("shown\n")
        }
        val runId = InstrumentationRegistry.getArguments().getString("isolation_run_id") ?: UUID.randomUUID().toString()
        val cardId = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "card-agent-conversations-$runId").apply { mkdirs() }
        val previousFile = AgentConversations.file
        AgentConversations.file = { File(root, "agent-conversations.json") }
        val dao = AppDatabase.getInstance(context.applicationContext).thoughtsSyncDao()
        val records = listOf(
            record("claude", claudeId, claudeUrl, "Why the eigenvalues of a symmetric matrix are real",
                listOf(entry(otherCardId, "2026-10-26T18:00:00.000Z"), entry(cardId, "2026-10-26T17:46:58.120Z"))),
            record("codex", codexId, "codex://threads/$codexId", "Codex: proof sketch", listOf(entry(cardId, "2026-10-26T09:00:00.000Z"))),
            record("claude", removedId, claudeUrl, "Removed from the card", listOf(entry(cardId, "2026-10-26T19:00:00.000Z", removed = true))),
        )
        fun install(payloads: List<JsonObject>) = runBlocking {
            records.forEach { dao.deleteProjection(AgentConversations.ENTITY_TYPE, AgentConversations.COLLECTION, it.get("link_id").asString) }
            payloads.forEach {
                dao.insertProjection(ThoughtsProjectionEntity(AgentConversations.ENTITY_TYPE, AgentConversations.COLLECTION,
                    it.get("link_id").asString, it.get("revision").asLong, false, it.toString(), 1))
            }
        }
        val pdf = File(context.cacheDir, "card-agent-conversations-$runId.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            val paint = Paint().apply { textSize = 20f }
            page.canvas.drawText("Spectral theorem", 40f, 70f, paint)
            page.canvas.drawText("A symmetric real matrix has real eigenvalues.", 40f, 550f, paint)
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val card = ReviewCard(cardId, "approved", "doc-$runId", "a".repeat(64),
            1, "b".repeat(64), "Why are the eigenvalues of a symmetric matrix real?",
            RectF(0.05f, 0.65f, 0.95f, 0.72f), null, "")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            install(records)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var binding: ActivityMainBinding
                lateinit var controller: ReviewSessionController
                var loaded = false
                val deadline = SystemClock.uptimeMillis() + 20000
                while (!loaded && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        binding = ActivityMainBinding.bind(activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                        loaded = binding.pdfView.pageCount > 0
                        controller = MainActivity::class.java.getDeclaredField("reviewSessionController")
                            .apply { isAccessible = true }.get(activity) as ReviewSessionController
                    }
                    if (!loaded) SystemClock.sleep(100)
                }
                assertTrue("Fixture PDF loaded", loaded)
                fun conversations() = ReviewSessionController::class.java.getDeclaredField("agentConversations")
                    .apply { isAccessible = true }.get(controller) as ReviewAgentConversations
                fun private(name: String) = ReviewSessionController::class.java.getDeclaredMethod(name)
                    .apply { isAccessible = true }.invoke(controller)
                fun waitFor(what: String, condition: () -> Boolean) {
                    val until = SystemClock.uptimeMillis() + 10000
                    var ok = false
                    while (!ok && SystemClock.uptimeMillis() < until) {
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { ok = condition() }
                        if (!ok) SystemClock.sleep(100)
                    }
                    assertTrue(what, ok)
                }
                // Kept in /data/local/tmp (shell-owned), which outlives the sandbox app's cleanup.
                fun capture(name: String) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(400)
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name").close()
                    SystemClock.sleep(1500)
                }
                scenario.onActivity {
                    ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java,
                        ReviewInterval::class.java, Map::class.java)
                        .apply { isAccessible = true }.invoke(controller, card, ReviewInterval.suggested(null, null), emptyMap<String, Int>())
                }
                waitFor("Newest conversation shown above the buttons") { binding.reviewAgentConversation.visibility == View.VISIBLE }
                capture("card-agent-conversations-line.png")
                scenario.onActivity {
                    assertEquals("Why the eigenvalues of a symmetric matrix are real", binding.reviewAgentConversationTitle.text.toString())
                    assertTrue(binding.reviewAgentConversationDetails.text.toString(),
                        binding.reviewAgentConversationDetails.text.startsWith("Claude · alex-61 · "))
                    assertEquals(1f, binding.reviewAgentConversation.alpha)
                    // The menu lists both live ones, newest first; the removed entry is not there.
                    assertEquals(listOf(claudeId, codexId), conversations().conversations.map { it.record.conversationId })
                    // The line is the last of the card's details, which sit directly above the Flip | Reveal |
                    // Set knowledge row (the compact card folds them away).
                    val line = binding.reviewAgentConversation
                    val details = binding.reviewDetails
                    assertTrue(line.parent === details && details.indexOfChild(line) == details.childCount - 1)
                    val parent = details.parent as android.view.ViewGroup
                    val buttons = parent.getChildAt(parent.indexOfChild(details) + 1) as android.view.ViewGroup
                    assertTrue(buttons.indexOfChild(binding.reviewRevealButton) >= 0)
                    assertTrue(details.bottom <= buttons.top)
                    // Reveal: a Sync that unlinks everything does not make the line vanish under the finger.
                    binding.reviewRevealButton.performClick()
                }
                install(emptyList())
                scenario.onActivity { controller.onSyncFinished() }
                SystemClock.sleep(500)
                scenario.onActivity { assertEquals(View.VISIBLE, binding.reviewAgentConversation.visibility) }
                scenario.onActivity { private("hideAgain") }
                waitFor("Deferred change applied once the answer is hidden") { binding.reviewAgentConversation.visibility == View.GONE }
                // Linked again; then Remove from this card leaves the Codex one, greyed out.
                install(records)
                scenario.onActivity { controller.onSyncFinished() }
                waitFor("Line back after Sync") { binding.reviewAgentConversation.visibility == View.VISIBLE }
                scenario.onActivity {
                    val newest = conversations().conversations.first()
                    ReviewAgentConversations::class.java.getDeclaredMethod("remove", String::class.java,
                        AgentConversations.CardConversation::class.java).apply { isAccessible = true }
                        .invoke(conversations(), cardId, newest)
                }
                waitFor("Codex conversation is now the newest") { binding.reviewAgentConversationTitle.text.toString() == "Codex: proof sketch" }
                capture("card-agent-conversations-codex.png")
                scenario.onActivity {
                    assertEquals(0.5f, binding.reviewAgentConversation.alpha)
                    assertEquals(listOf(codexId), conversations().conversations.map { it.record.conversationId })
                }
                val pending = AgentConversations.pending().single()
                assertEquals(claudeId, pending.conversationId)
                assertEquals(3L, pending.revision)
                assertTrue(AgentConversations.cardLink(pending, cardId)!!.removed)
                assertTrue(!AgentConversations.cardLink(pending, otherCardId)!!.removed)
                assertTrue(!pending.removed)
            }
        } finally {
            install(emptyList())
            AgentConversations.file = previousFile
            root.deleteRecursively()
            pdf.delete()
        }
    }
}

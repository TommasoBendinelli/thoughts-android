// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.sync.AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE
import com.gitlab.mudlej.MjPdfReader.data.sync.AGENT_PROPOSAL_UNDO_ENTITY_TYPE
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderComposition
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Agent edits sheet with seeded, made-up proposals: a long press starts selection mode, taps
 * and Select all choose, Accept decides every selected edit through the single decision's queue
 * (one skipped, its source changed on this phone), Undo takes them all back newest first, and
 * Back leaves selection mode. Screenshots go to the app's external files folder.
 */
@RunWith(AndroidJUnit4::class)
class AgentEditsBulkSelectionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val run = TestRunIdentity.id.lowercase().take(8)
    private val pdfName = "garden-$run.pdf"
    private val introPath = "docs-garden-$run/tex/source/intro.tex"
    private val methodsPath = "docs-garden-$run/tex/source/methods.tex"
    private val meta = "[created=2026-11-15T09:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]"
    private fun id(n: Int) = "agent-garden-$run-$n"
    private fun wrapper(n: Int, before: String, after: String) = "\\agentProposedEdit$meta{${id(n)}}{reason}{$before}{$after}"

    private val intro = "\\section{Tomatoes}\n" +
        wrapper(1, "Tomatoes like shade.", "Tomatoes like sun.") + "\n" +
        wrapper(2, "Water them weekly.", "Water them daily.") + "\n" +
        wrapper(3, "Pick them green.", "Pick them red.") + "\n"
    private val methods = wrapper(4, "Use sand.", "Use compost.") + "\n"
    private val explanations = mapOf(
        1 to "Say that tomatoes like sun.",
        2 to "Water daily in summer.",
        3 to "Ripe tomatoes are red.",
        4 to "Compost feeds the soil.",
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A source bundle with three adjacent proposals in one source and one in another, all on page 1. */
    private fun seedBundle() {
        val introBytes = intro.toByteArray()
        val methodsBytes = methods.toByteArray()
        val introSha = sha256(introBytes)
        val methodsSha = sha256(methodsBytes)
        val lookup = "bulk edits lookup $run".toByteArray()
        val lookupSha = sha256(lookup)
        fun source(path: String, sha: String, size: Int) = JsonObject().apply {
            addProperty("path", path); addProperty("sha256", sha); addProperty("object", sha); addProperty("bytes", size)
        }
        fun input(path: String, sha: String) = JsonObject().apply {
            addProperty("source_file", path); addProperty("source_sha256", sha); addProperty("synctex_path", "/workspace/$path")
        }
        val proposals = JsonArray()
        val objects = JsonArray()
        for (n in 1..4) {
            val path = if (n == 4) methodsPath else introPath
            proposals.add(JsonObject().apply {
                addProperty("id", id(n)); addProperty("kind", "edit"); addProperty("comment", explanations.getValue(n))
                addProperty("source_file", path); addProperty("source_sha256", if (n == 4) methodsSha else introSha)
                addProperty("line", if (n == 4) 1 else n + 1)
                addProperty("created_at", "2026-11-15T09:0$n:00Z")
                add("pdf_filenames", JsonArray().apply { add(pdfName) })
                add("placement", JsonObject().apply {
                    add("quad_fragments", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("pdf_filename", pdfName); addProperty("page", 1)
                            addProperty("left", 40f); addProperty("top", 80f + 60f * n)
                            addProperty("right", 400f); addProperty("bottom", 100f + 60f * n)
                        })
                    })
                })
            })
            objects.add(JsonObject().apply { addProperty("id", id(n)); addProperty("object", sha256("object ${id(n)}".toByteArray())) })
        }
        val manifest = JsonObject().apply {
            addProperty("schema_version", 3)
            add("sources", JsonArray().apply {
                add(source(introPath, introSha, introBytes.size)); add(source(methodsPath, methodsSha, methodsBytes.size))
            })
            add("pdfs", JsonArray().apply { add(JsonObject().apply {
                addProperty("filename", pdfName); addProperty("pdf_sha256", "a".repeat(64)); addProperty("page_count", 1)
                add("segments", JsonArray().apply { add(JsonObject().apply {
                    addProperty("start_page", 1); addProperty("page_count", 1)
                    addProperty("source_available", true); addProperty("primary_source_file", introPath)
                    addProperty("lookup_object", lookupSha); addProperty("lookup_sha256", lookupSha)
                    add("inputs", JsonArray().apply { add(input(introPath, introSha)); add(input(methodsPath, methodsSha)) })
                }) })
            }) })
            add("agent_proposals", proposals)
            add("proposal_objects", objects)
        }.toString().toByteArray()
        val bundleId = sha256(manifest)
        val index = JsonObject().apply {
            addProperty("schema_version", 3); addProperty("bundle_id", bundleId); addProperty("manifest_object", bundleId)
        }.toString().toByteArray()
        val store = OfflineSourceBundleStore(context)
        assertNotNull("Bundle installed", store.installDownloaded(index, mapOf(
            introSha to introBytes, methodsSha to methodsBytes, lookupSha to lookup, bundleId to manifest)))
        // An edit made on this phone: the fourth proposal's source is no longer the one it was made on.
        assertTrue(store.writeWorkingContents(methodsPath, methods + "Mulch keeps the soil moist.\n"))
    }

    private fun writePdf(): File {
        val pdf = File(context.cacheDir, pdfName)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            val paint = Paint().apply { textSize = 20f }
            page.canvas.drawText("Tomatoes", 40f, 70f, paint)
            listOf("Tomatoes like sun.", "Water them daily.", "Pick them red.", "Use compost.")
                .forEachIndexed { index, line -> page.canvas.drawText(line, 40f, 155f + 60f * index, paint) }
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        return pdf
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun outbox(): List<ThoughtsOutboxEntity> = runBlocking {
        AppDatabase.getInstance(context).thoughtsSyncDao().allOutbox()
    }

    private fun proposalIdsOf(row: ThoughtsOutboxEntity): List<String> =
        JsonParser.parseString(row.payloadJson).asJsonObject.getAsJsonArray("proposals").map { it.asJsonObject["id"].asString }

    private fun actionOf(row: ThoughtsOutboxEntity): String = JsonParser.parseString(row.payloadJson).asJsonObject["action"].asString

    private fun waitFor(what: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            var met = false
            instrumentation.runOnMainSync { met = runCatching(condition).getOrDefault(false) }
            if (met) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @Test fun selectionModeAcceptsRejectsAndUndoesEverySelectedEdit() {
        File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1").apply { parentFile!!.mkdirs(); writeText("shown\n") }
        seedBundle()
        val pdf = writePdf()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var activity: MainActivity
            lateinit var binding: ActivityMainBinding
            scenario.onActivity { activity = it }
            waitFor("the PDF") {
                binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                binding.pdfView.pageCount > 0
            }
            instrumentation.runOnMainSync {
                val reader = MainActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }.get(activity) as ReaderComposition
                reader.readerNavigationController.showAgentEdits()
            }
            fun sheet(): View = binding.commentSheetHost
            fun list() = sheet().findViewById<RecyclerView>(R.id.commentsList)
            fun title() = sheet().findViewById<TextView>(R.id.commentsTitle).text.toString()
            fun row(position: Int): View = list().findViewHolderForAdapterPosition(position)!!.itemView
            fun click(id: Int) = sheet().findViewById<View>(id).performClick()
            fun snackbarText(): String? = activity.window.decorView
                .findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString()
            waitFor("four agent edits") { list()?.adapter?.itemCount == 4 && list().findViewHolderForAdapterPosition(3) != null }
            assertEquals(activity.getString(R.string.agent_edits_title), title())

            // A long press starts selection mode with that edit; a tap adds another.
            instrumentation.runOnMainSync {
                assertTrue(row(0).performLongClick())
                row(1).performClick()
            }
            waitFor("2 selected") { title() == "2 selected" }
            instrumentation.runOnMainSync {
                assertEquals(View.VISIBLE, sheet().findViewById<View>(R.id.commentsSelectionActions).visibility)
                assertEquals(View.GONE, sheet().findViewById<View>(R.id.commentsClose).visibility)
                assertEquals("Accept 2 proposals", sheet().findViewById<View>(R.id.commentsAcceptSelected).contentDescription)
                assertEquals("Reject 2 proposals", sheet().findViewById<View>(R.id.commentsRejectSelected).contentDescription)
                val selected = row(0).createAccessibilityNodeInfo()
                assertTrue(selected.isCheckable)
                assertTrue(selected.isChecked)
                assertEquals("Selected", selected.stateDescription)
                val other = row(2).createAccessibilityNodeInfo()
                assertFalse(other.isChecked)
                assertEquals("Not selected", other.stateDescription)
            }
            capture("bulk-1-selection-mode.png")

            // Select all: every edit shown; then Accept.
            instrumentation.runOnMainSync { click(R.id.commentsSelectAll) }
            waitFor("4 selected") { title() == "4 selected" }
            instrumentation.runOnMainSync { assertFalse(sheet().findViewById<View>(R.id.commentsSelectAll).isEnabled) }
            capture("bulk-2-select-all.png")
            val before = outbox().size
            instrumentation.runOnMainSync { click(R.id.commentsAcceptSelected) }
            waitFor("the summary") { snackbarText()?.contains("accepted") == true }
            assertEquals("3 proposals accepted · 1 left undecided: source changed on this phone", snackbarText())
            val decisions = outbox().drop(before).filter { it.entityType == AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE }
            assertEquals("One queued decision each, in page order", listOf(listOf(id(1)), listOf(id(2)), listOf(id(3))), decisions.map(::proposalIdsOf))
            assertEquals(listOf("accept", "accept", "accept"), decisions.map(::actionOf))
            assertEquals(decisions.map { it.deviceSequence }.sorted(), decisions.map { it.deviceSequence })
            waitFor("selection mode off") { title() == activity.getString(R.string.agent_edits_title) }
            waitFor("rows no longer checked") {
                (0..3).all { row(it).createAccessibilityNodeInfo().let { node -> !node.isCheckable && !node.isChecked } }
            }
            waitFor("pending labels") {
                (0..2).all { row(it).findViewById<TextView>(R.id.commentMetadata).text.endsWith("Accept pending") }
            }
            capture("bulk-3-summary.png")

            // Undo takes all three back, newest first.
            instrumentation.runOnMainSync {
                activity.window.decorView.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
            }
            waitFor("three undos") { outbox().count { it.entityType == AGENT_PROPOSAL_UNDO_ENTITY_TYPE } == 3 }
            val undos = outbox().filter { it.entityType == AGENT_PROPOSAL_UNDO_ENTITY_TYPE }
            assertEquals(decisions.map { it.operationId }.reversed(), undos.map { it.entityId })
            waitFor("the undo message") { snackbarText() == "Acceptance undone — 3 proposals restored." }
            waitFor("pending labels gone") { (0..2).none { row(it).findViewById<TextView>(R.id.commentMetadata).text.contains("pending") } }
            capture("bulk-4-undone.png")

            // Back leaves selection mode, the sheet stays open.
            instrumentation.runOnMainSync { assertTrue(row(2).performLongClick()) }
            waitFor("1 selected") { title() == "1 selected" }
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            waitFor("selection mode off after Back") { title() == activity.getString(R.string.agent_edits_title) }
            instrumentation.runOnMainSync { assertEquals(View.VISIBLE, sheet().findViewById<View>(R.id.commentsClose).visibility) }

            // Two adjacent proposals of one source rejected together; Cancel without deciding first.
            instrumentation.runOnMainSync { row(0).performLongClick(); click(R.id.commentsCancelSelection) }
            waitFor("Cancel") { title() == activity.getString(R.string.agent_edits_title) }
            val beforeReject = outbox().size
            instrumentation.runOnMainSync { row(1).performLongClick(); row(2).performClick() }
            waitFor("2 selected again") { title() == "2 selected" }
            instrumentation.runOnMainSync { click(R.id.commentsRejectSelected) }
            waitFor("the reject summary") { snackbarText() == "2 proposals rejected — sent with the next Sync." }
            val rejects = outbox().drop(beforeReject).filter { it.entityType == AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE }
            assertEquals(listOf(listOf(id(2)), listOf(id(3))), rejects.map(::proposalIdsOf))
            assertEquals(listOf("disregard", "disregard"), rejects.map(::actionOf))
            capture("bulk-5-rejected.png")
        }
    }
}

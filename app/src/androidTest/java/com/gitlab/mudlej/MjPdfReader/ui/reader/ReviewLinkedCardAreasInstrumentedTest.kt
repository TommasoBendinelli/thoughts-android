// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.source.LocalReviewSources
import com.gitlab.mudlej.MjPdfReader.data.source.MappedSourceArea
import com.gitlab.mudlej.MjPdfReader.data.source.ReviewAreaLinkingException
import com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourceMarkup
import com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourcePlacer
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Always-linked cards in the reader: Edit card on a source-linked card edits its areas and marks
 * them again; an area that cannot be linked opens "Can't link to the source" with Adjust areas
 * and saves nothing. Adding a card shows every area as a box after Adjust areas.
 */
@RunWith(AndroidJUnit4::class)
class ReviewLinkedCardAreasInstrumentedTest {
    private val path = "docs-test/tex/source/linked-areas.tex"
    private val sourceText = "\\section{Training}\n\nWorkers average gradients using all-reduce.\n\n" +
        "Parameter servers hold the shared weights.\n\nA third paragraph about checkpoints.\n"

    /** The phone's verified source in memory: page 1 → line 3, page 2 → line 5; page 3 has no source text. */
    private class Sources(var contents: String, val path: String) : ReviewSourcePlacer {
        lateinit var store: ReviewStore
        val objects = mutableMapOf<String, String>()
        override val fallbackSource: String get() = path
        override fun placement(id: String, filename: String, hash: String, areas: List<LocalReviewSources.PlacementArea>, previous: JsonObject?): LocalReviewSources.Edit {
            val mapped = areas.mapIndexed { index, area ->
                val page = area.regions.minOf { it.page }
                val needle = when (page) { 1 -> "Workers"; 2 -> "Parameter"; else ->
                    throw ReviewAreaLinkingException(index, areas.size, page, "No editable text was found in the selection.") }
                val line = contents.split('\n').indexOfFirst { it.contains(needle) } + 1
                MappedSourceArea(listOf(line), contents.split('\n')[line - 1].replace(Regex("\\\\ReviewCardMask(Start|End)\\{[^}]*\\}\\{[^}]*\\}"), ""), rectangle = area.rectangle)
            }
            return ReviewSourceMarkup.placeAreas(path, contents, id, mapped).copy(base = contents)
        }
        override fun commit(edit: LocalReviewSources.Edit, record: JsonObject, destination: File, transactionId: String?) {
            check(edit.before == contents)
            val before = LocalReviewSources.digest(edit.before.toByteArray())
            val after = LocalReviewSources.digest(edit.after.toByteArray())
            objects[before] = edit.before; objects[after] = edit.after
            record.add("source_change", JsonObject().apply {
                addProperty("transaction_id", transactionId ?: record.get("card_id").asString)
                addProperty("path", edit.path); addProperty("base_sha256", before)
                addProperty("before_sha256", before); addProperty("after_sha256", after)
            })
            contents = edit.after
            store.saveSourceRecord(record, destination)
        }
        override fun hasRecoveryRecord(id: String): Boolean = false
    }

    @Test fun editCardMarksALinkedCardsAreasAgainOrExplainsWhichAreaCannotBeLinked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".sandbox"))
        val root = File(context.cacheDir, "linked-areas-${UUID.randomUUID()}").apply { mkdirs() }
        com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatteryPromptState(
            File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1")).markShown()
        val pdf = File(root, "linked-areas-ui-check.pdf")
        PdfDocument().let { document ->
            try {
                for (number in 1..3) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, number).create())
                    val paint = Paint().apply { textSize = 18f }
                    page.canvas.drawText("Page $number", 45f, 65f, paint)
                    page.canvas.drawText("Workers average gradients using all-reduce.", 45f, 175f, paint)
                    page.canvas.drawText("Parameter servers hold the shared weights.", 45f, 600f, paint)
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
        }
        val sources = Sources(sourceText, path)
        val store = ReviewStore(null, File(root, "review"), File(root, "incoming"),
            File(root, "events"), File(root, "outgoing-cards")).useSourcePlacerForTesting(sources)
        sources.store = store
        val cardId = UUID.randomUUID().toString()
        // A card linked to one passage, as Fedora returns it.
        val linked = ReviewSourceMarkup.placeAreas(path, sourceText, cardId, listOf(
            MappedSourceArea(listOf(3), "Workers average gradients using all-reduce.")))
        sources.contents = linked.after
        File(root, "review/cards").mkdirs()
        File(root, "review/cards/$cardId.json").writeText(JsonObject().apply {
            addProperty("schema_version", 4); addProperty("card_id", cardId); addProperty("status", "approved")
            addProperty("revision", 3); addProperty("origin_id", "test"); addProperty("source_authority", "client-v1")
            add("primary", JsonParser.parseString("""{"pdf_stem":"linked-areas","pdf_sha256":"${"a".repeat(64)}","page":1,
               "rect":[0.06,0.19,0.9,0.23],"regions":[{"page":1,"rect":[0.06,0.19,0.9,0.23]}],
               "page_visual_sha256":"${"b".repeat(64)}","question":"How are gradients combined?"}""").asJsonObject.apply {
                add("source_anchor", linked.anchor)
            })
            add("source_link", JsonParser.parseString("""{"state":"linked","source_file":"$path"}"""))
            add("reanchor", JsonParser.parseString("""{"state":"succeeded"}"""))
        }.toString())
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var binding: ActivityMainBinding
            lateinit var controller: ReviewSessionController
            var loaded = false
            val deadline = SystemClock.uptimeMillis() + 15000
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    binding = ActivityMainBinding.bind(activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                    loaded = binding.pdfView.pageCount == 3
                }
                if (!loaded) SystemClock.sleep(100)
            }
            assertTrue("PDF loaded", loaded)
            fun capture(name: String) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1000)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            fun call(name: String, vararg args: Pair<Class<*>, Any>) = ReviewSessionController::class.java
                .getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
                .apply { isAccessible = true }.invoke(controller, *args.map { it.second }.toTypedArray())
            fun boxes() = binding.pdfView.reviewRegionSelections.orEmpty()
            fun editing() = ReviewSessionController::class.java.getDeclaredField("editingRegion")
                .apply { isAccessible = true }.getBoolean(controller)
            fun dialog() = ReviewSessionController::class.java.getDeclaredField("cardDialog")
                .apply { isAccessible = true }.get(controller) as AlertDialog?
            fun await(what: String, condition: () -> Boolean) {
                val until = SystemClock.uptimeMillis() + 10000
                while (SystemClock.uptimeMillis() < until) {
                    var done = false
                    scenario.onActivity { done = condition() }
                    if (done) return
                    SystemClock.sleep(100)
                }
                throw AssertionError("$what did not happen")
            }
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controller = ReviewSessionController(activity, binding,
                    DocumentState(uri = Uri.fromFile(pdf), annotationPdfSha256 = "a".repeat(64)), store)
                val card = checkNotNull(store.card(cardId))
                assertFalse(card.canEditAnswerBox)
                call("showCard", ReviewCard::class.java to card, ReviewInterval::class.java to ReviewInterval.suggested(null, null),
                    Map::class.java to emptyMap<String, Int>())
                // A linked card opens the area editor, not the question dialog.
                call("editRegion", ReviewCard::class.java to card)
                assertTrue(editing())
                assertEquals(1, boxes().size)
                assertTrue(binding.reviewRelatedContext.text.toString().startsWith("Saving marks each area"))
            }
            // An area on page 3, which has no source text.
            scenario.onActivity { binding.pdfView.jumpTo(2, false) }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            scenario.onActivity {
                binding.reviewAddAreaButton.performClick()
                assertEquals(2, boxes().size)
                binding.reviewRegionSaveButton.performClick()
            }
            await("The linking dialog") { dialog()?.isShowing == true }
            capture("linked-areas-1-cannot-link.png")
            scenario.onActivity {
                val shown = checkNotNull(dialog())
                assertEquals("Area 2 of 2 (page 3) can't be linked to source text: No editable text was found in the selection.",
                    shown.findViewById<TextView>(android.R.id.message)!!.text.toString())
                assertEquals("Adjust areas", shown.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
                assertEquals("Cancel", shown.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
                assertEquals(View.GONE, shown.getButton(DialogInterface.BUTTON_NEUTRAL).visibility)
                // Nothing was saved.
                assertEquals(3, store.card(cardId)!!.revision)
                assertTrue(sources.contents == linked.after)
                shown.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            }
            await("Adjust areas") { dialog() == null }
            scenario.onActivity {
                assertTrue(editing())
                assertEquals(1, binding.pdfView.selectedReviewRegionIndex)
                assertTrue(binding.reviewRelatedContext.text.toString().contains("Area 2 of 2 (page 3)"))
                // Move the failing area to page 2 instead: remove it and add one there.
                binding.reviewRemoveAreaButton.performClick()
                assertEquals(1, boxes().size)
                binding.pdfView.jumpTo(1, false)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            scenario.onActivity {
                binding.reviewAddAreaButton.performClick()
                assertEquals(2, boxes().size)
                assertEquals(1, boxes()[1].pageIndex)
                binding.reviewRegionSaveButton.performClick()
            }
            await("Saving") { !editing() }
            capture("linked-areas-2-saved.png")
            scenario.onActivity {
                val saved = checkNotNull(store.card(cardId))
                assertEquals(4, saved.revision)
                assertEquals("linked", saved.sourceLinkState)
                assertEquals(listOf(1, 2), saved.regions.map { it.page })
                val segments = saved.sourceAnchor!!.getAsJsonArray("segments").map { it.asJsonObject.get("segment_id").asString }
                assertEquals(listOf("0", "1"), segments)
                assertTrue(sources.contents.contains("\\ReviewCardMaskStart{$cardId}{1}Parameter servers"))
                val payload = File(root, "outgoing-cards").walkTopDown().first { it.isFile && it.extension == "json" }
                    .readText().let { JsonParser.parseString(it).asJsonObject }
                val change = payload.getAsJsonObject("source_change")
                assertNotEquals(cardId, change.get("transaction_id").asString)
                // Fedora's rule: the source before still had the old markers; only this card's markers moved.
                val before = sources.objects.getValue(change.get("before_sha256").asString)
                assertTrue(before.contains(cardId))
                assertEquals(sourceText.trimEnd('\n'),
                    ReviewSourceMarkup.withoutCardMarkers(path, sources.contents, cardId).trimEnd('\n'))
            }
        }
        root.deleteRecursively()
    }

    @Test fun addingACardShowsEveryAreaAgainAfterAdjustAreas() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".sandbox"))
        val root = File(context.cacheDir, "linked-create-${UUID.randomUUID()}").apply { mkdirs() }
        com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatteryPromptState(
            File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1")).markShown()
        val pdf = File(root, "linked-create-ui-check.pdf")
        PdfDocument().let { document ->
            try {
                for (number in 1..3) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, number).create())
                    page.canvas.drawText("Page $number", 45f, 65f, Paint().apply { textSize = 18f })
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var binding: ActivityMainBinding
            var loaded = false
            val deadline = SystemClock.uptimeMillis() + 15000
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    binding = ActivityMainBinding.bind(activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                    loaded = binding.pdfView.pageCount == 3
                }
                if (!loaded) SystemClock.sleep(100)
            }
            assertTrue("PDF loaded", loaded)
            fun rect(top: Float) = RectF(0.1f, top, 0.9f, top + 0.1f)
            val areas = listOf(
                listOf(ReviewAnswerRegion(1, rect(0.2f))) to null,
                listOf(ReviewAnswerRegion(2, rect(0.3f)), ReviewAnswerRegion(2, rect(0.4f))) to "selected words",
                listOf(ReviewAnswerRegion(3, rect(0.5f))) to null,
            )
            val error = ReviewAreaLinkingException(1, 3, 2, "Its source lines overlap those of area 1. Select separate passages.")
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.manualReviewQuestion.setText("Which parts?")
                binding.manualReviewEditorCard.visibility = View.VISIBLE
                // The source of the document is unknown on this device: a fourth choice saves it for the server to link.
                val show = MainActivity::class.java.getDeclaredMethod("showManualReviewLinkingFailure",
                    ReviewAreaLinkingException::class.java, List::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                val unknown = show.invoke(activity, error, areas, 0, true) as AlertDialog
                assertEquals("Save; link at next Sync",
                    unknown.findViewById<TextView>(com.gitlab.mudlej.MjPdfReader.R.id.manualReviewLinkAtNextSyncButton)!!.text.toString())
                assertEquals("Adjust areas", unknown.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
                assertEquals(View.GONE, unknown.getButton(DialogInterface.BUTTON_NEUTRAL).visibility)
                unknown.dismiss()
                // One area keeps "Save and place markers by hand" beside it; a known source document has neither extra.
                val single = show.invoke(activity, ReviewAreaLinkingException(0, 1, 1, "reason"), areas.take(1), 0, true) as AlertDialog
                assertEquals("Save and place markers by hand", single.getButton(DialogInterface.BUTTON_NEUTRAL).text.toString())
                assertTrue(single.findViewById<View>(com.gitlab.mudlej.MjPdfReader.R.id.manualReviewLinkAtNextSyncButton) != null)
                single.dismiss()
                val known = show.invoke(activity, error, areas, 0, false) as AlertDialog
                assertTrue(known.findViewById<View>(com.gitlab.mudlej.MjPdfReader.R.id.manualReviewLinkAtNextSyncButton) == null)
                known.dismiss()
                MainActivity::class.java.getDeclaredMethod("enterManualReviewAreaEditing", List::class.java, Int::class.javaPrimitiveType, String::class.java)
                    .apply { isAccessible = true }.invoke(activity, areas, error.areaIndex, error.message)
                assertEquals(3, binding.pdfView.reviewRegionSelections.orEmpty().size)
                assertEquals(1, binding.pdfView.selectedReviewRegionIndex)
                assertEquals(View.VISIBLE, binding.manualReviewAreaActions.visibility)
                val instructions = binding.manualReviewInstructions.text.toString()
                assertTrue(instructions, instructions.startsWith("Area 2 of 3 (page 2) can't be linked to source text"))
                assertTrue(instructions, instructions.endsWith("Area 2 of 3 · page 2"))
                // The question typed so far is kept.
                assertEquals("Which parts?", binding.manualReviewQuestion.text.toString())
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(1000)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "linked-create-adjust-areas.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            scenario.onActivity {
                binding.manualReviewRemoveAreaButton.performClick()
                assertEquals(2, binding.pdfView.reviewRegionSelections.orEmpty().size)
                assertTrue(binding.manualReviewInstructions.text.toString().contains(" of 2 · page "))
                binding.manualReviewNextAreaButton.performClick()
                binding.manualReviewAnotherAreaButton.performClick()
                assertEquals(3, binding.pdfView.reviewRegionSelections.orEmpty().size)
                binding.manualReviewCancelButton.performClick()
                assertEquals(View.GONE, binding.manualReviewEditorCard.visibility)
                assertEquals(View.GONE, binding.manualReviewAreaActions.visibility)
                assertTrue(binding.pdfView.reviewRegionSelections.orEmpty().isEmpty())
            }
        }
        root.deleteRecursively()
    }
}

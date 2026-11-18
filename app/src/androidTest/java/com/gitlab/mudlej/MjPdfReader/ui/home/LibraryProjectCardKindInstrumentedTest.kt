// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A Project's menu (protocol/review-card-kinds.md sections 2.3, 5 and 6.4): its default card kind,
 * queued as a set_review_kind action and shown at once, only while the catalogue entry has
 * review_kind; and "Retire project cards…" with a confirmation naming the count.
 */
class LibraryProjectCardKindInstrumentedTest {
    private fun state(withKind: Boolean) = """{"pending":[],"projects":[{
        "id":"project-a","name":"Project A","revision":4,"target":"",
        "documents":[{"id":"paper-a.pdf","document_id":"doc-a","name":"Paper A","filename":"paper-a.pdf"}],
        "unpublished_documents":[]${if (withKind) ""","review_kind":"knowledge"""" else ""},
        "project_configuration":{"schema_version":5,"kind":"thoughts-project","id":"project-a","name":"Project A",
          "default_folder_id":"default","source_folders":[{"folder_id":"default","path":".thoughts/projects/project-a/documents"}],
          "documents":[{"document_id":"doc-a","source":{"folder_id":"default","path":"paper-a.pdf"},"format":"pdf","included":true}],
          "rendering":{},"document_names":{}}
    }]}"""

    @Test fun theProjectMenuChoosesTheCardKindAndRetiresItsProjectCards() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "project-card-kind-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            File(root, "projects/state.json").apply { parentFile!!.mkdirs(); writeText(state(withKind = true)) }
            val projects = LibraryProjectStore(context, File(root, "projects"))
            fun reviewStore() = ReviewStore(null, File(root, "review"), File(root, "incoming"), File(root, "events"), File(root, "cards"))
                .apply { kindCatalogueSource = { projects.reviewKindCatalogue() } }
            val first = addCard(root, "paper-a")
            val second = addCard(root, "paper-a")
            val elsewhere = addCard(root, "notes")
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
                    pane = LibraryProjectPane(activity, scope, functions, {}, {}, projects, reviewStore = ::reviewStore, loadConversations = { emptyMap() })
                    parent = LinearLayout(activity)
                    pane.render(emptyList(), SourceLinkedPdfIndexSnapshot())
                }
                fun openProjectMenu(): List<String> {
                    scenario.onActivity { pane.onCreateViewHolder(parent, 0).also { pane.onBindViewHolder(it, 1) }.row.performLongClick() }
                    var labels = emptyList<String>()
                    awaitUi(scenario, "the Project menu opens") {
                        labels = topWindowButtons().map { it.text.toString() }
                        "Rename…" in labels
                    }
                    return labels
                }
                fun press(label: String) {
                    scenario.onActivity {
                        val button = topWindowButtons().firstOrNull { it.text.toString() == label } ?: fail("No $label: ${topWindowButtons().map { it.text }}")
                        (button as Button).performClick()
                    }
                    instrumentation.waitForIdleSync()
                }

                // Knowledge cards now; nothing to retire.
                var labels = openProjectMenu()
                assertTrue(labels.toString(), "Card kind: Knowledge cards…" in labels)
                assertFalse(labels.toString(), "Retire project cards…" in labels)
                press("Card kind: Knowledge cards…")
                var choices = emptyList<String>()
                awaitUi(scenario, "the kind choice opens") { choices = topWindowListItems(); choices.isNotEmpty() }
                assertEquals(listOf("Knowledge cards", "Project cards"), choices)
                scenario.onActivity { assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickItem(it, "Project cards") }) }
                instrumentation.waitForIdleSync()
                awaitUi(scenario, "the change is queued") { projects.pendingCount() == 1 }
                // Shown at once, sent at Sync as the server takes it.
                val shown = projects.projects().single().asJsonObject
                assertEquals("project", shown.get("review_kind").asString)
                assertEquals(5, shown.get("revision").asInt)
                val queued = JsonParser.parseString(File(root, "projects/state.json").readText()).asJsonObject
                    .getAsJsonArray("pending").single().asJsonObject
                assertEquals("set_review_kind", queued.get("action").asString)
                assertEquals("project", queued.get("review_kind").asString)
                assertEquals("project-a", queued.get("project_id").asString)
                assertEquals(4, queued.get("base_revision").asInt)
                // The Project's cards follow at once; a card elsewhere stays a knowledge card.
                assertTrue(reviewStore().card(first)!!.isProjectCard)
                assertFalse(reviewStore().card(elsewhere)!!.isProjectCard)

                labels = openProjectMenu()
                assertTrue(labels.toString(), "Card kind: Project cards…" in labels)
                assertTrue(labels.toString(), "Retire project cards…" in labels)
                press("Retire project cards…")
                var title = ""
                awaitUi(scenario, "the confirmation opens") {
                    title = WindowInspector.getGlobalWindowViews().asReversed().flatMap(::texts).firstOrNull { it.startsWith("Retire 2") }.orEmpty()
                    title.isNotEmpty()
                }
                assertEquals("Retire 2 project cards", title)
                press("Retire")
                awaitUi(scenario, "the cards are retired") { reviewStore().activeProjectCards("project-a").isEmpty() }
                val after = reviewStore().browserCards().associateBy { it.card.id }
                assertEquals(ReviewBrowserState.RETIRED, after.getValue(first).state)
                assertEquals(ReviewBrowserState.RETIRED, after.getValue(second).state)
                assertEquals(after.getValue(first).card.retiredAt, after.getValue(second).card.retiredAt)
                assertTrue(after.getValue(elsewhere).state != ReviewBrowserState.RETIRED)
            }

            // An older server: no review_kind in the catalogue, so no choice.
            File(root, "projects/state.json").writeText(state(withKind = false))
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                lateinit var pane: LibraryProjectPane
                lateinit var parent: LinearLayout
                scenario.onActivity { activity ->
                    pane = LibraryProjectPane(activity, scope, functions, {}, {}, LibraryProjectStore(context, File(root, "projects")),
                        reviewStore = ::reviewStore, loadConversations = { emptyMap() })
                    parent = LinearLayout(activity)
                    pane.render(emptyList(), SourceLinkedPdfIndexSnapshot())
                    pane.onCreateViewHolder(parent, 0).also { pane.onBindViewHolder(it, 1) }.row.performLongClick()
                }
                var labels = emptyList<String>()
                awaitUi(scenario, "the Project menu opens") { labels = topWindowButtons().map { it.text.toString() }; "Rename…" in labels }
                assertFalse(labels.toString(), labels.any { it.startsWith("Card kind") })
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun awaitUi(scenario: ActivityScenario<HomeActivity>, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var matched = false
        while (!matched && SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { matched = condition() }
            if (!matched) SystemClock.sleep(50)
        }
        if (!matched) fail(message)
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    /** The buttons of the newest window that has any (the sheet or dialog on top). */
    private fun topWindowButtons(): List<Button> = WindowInspector.getGlobalWindowViews().asReversed()
        .map { window -> views(window).filterIsInstance<Button>().filter { it.isShown } }.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun topWindowListItems(): List<String> = WindowInspector.getGlobalWindowViews().asReversed()
        .map { window -> views(window).filterIsInstance<ListView>().flatMap { list -> (0 until list.count).map { list.adapter.getItem(it).toString() } } }
        .firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun texts(view: View): List<String> = views(view).filterIsInstance<TextView>().map { it.text.toString() }

    private fun clickItem(view: View, label: String): Boolean = views(view).filterIsInstance<ListView>().any { list ->
        (0 until list.count).firstOrNull { list.adapter.getItem(it).toString() == label }?.let { position ->
            list.performItemClick(list.getChildAt(position - list.firstVisiblePosition), position, list.adapter.getItemId(position))
        } == true
    }

    private fun addCard(root: File, stem: String): String {
        val id = UUID.randomUUID().toString()
        File(root, "incoming/cards/server/$id.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"schema_version":3,"card_type":"visual_region","card_id":"$id","status":"approved","revision":1,"origin_id":"server","primary":{"pdf_stem":"$stem","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"Saved question"},"reanchor":{"state":"succeeded","strategy":"source_markers_v2"}}""")
        }
        return id
    }
}

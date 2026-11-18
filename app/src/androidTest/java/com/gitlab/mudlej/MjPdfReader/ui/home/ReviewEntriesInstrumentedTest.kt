// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewKindCatalogue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewKinds
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.databinding.PageHomeReviewBinding
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Review tab's two entries (protocol/review-card-kinds.md section 7): "Study · N due" for
 * knowledge cards and "Projects · M due" for project cards, narrowed to one Project from a list of
 * the Projects holding active project cards, each with its due count, and "All Projects".
 */
class ReviewEntriesInstrumentedTest {
    @Test fun studyAndProjectsEachCountAndStartTheirOwnCards() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "review-entries-${UUID.randomUUID()}").apply { mkdirs() }
        val now = Instant.now()
        try {
            val catalogue = JsonParser.parseString("""[
                {"id":"project-a","name":"Project A","revision":1,"target":"","documents":[{"document_id":"doc-a","filename":"paper-a.pdf"}],"unpublished_documents":[],"review_kind":"project"},
                {"id":"project-b","name":"Project B","revision":1,"target":"","documents":[{"document_id":"doc-b","filename":"paper-b.pdf"}],"unpublished_documents":[],"review_kind":"project"},
                {"id":"project-c","name":"Project C","revision":1,"target":"","documents":[{"document_id":"doc-c","filename":"notes.pdf"}],"unpublished_documents":[],"review_kind":"knowledge"}
            ]""").asJsonArray
            fun store() = ReviewStore(null, File(root, "review"), File(root, "incoming"), File(root, "events"), File(root, "cards"))
                .apply { kindCatalogueSource = { ReviewKindCatalogue(catalogue) } }
            val cards = listOf("paper-a", "paper-a", "paper-b", "notes", "notes", "notes").associateBy { addCard(root, it) }
            val projectB = cards.filterValues { it == "paper-b" }.keys.single()
            val summary = store().summary(now)
            assertEquals(3, summary.knowledgeCounts.total)
            assertEquals(3, summary.projectCounts.total)
            assertEquals(listOf("project-a" to 2, "project-b" to 1), summary.projects.map { it.projectId to it.counts.total })
            // A session from one entry offers only its cards.
            assertEquals(listOf(projectB), store().dueCards(now, kind = ReviewKinds.PROJECT, projectId = "project-b").map { it.id })
            assertEquals(cards.filterValues { it == "notes" }.keys, store().dueCards(now, kind = ReviewKinds.KNOWLEDGE).map { it.id }.toSet())

            val started = mutableListOf<Pair<String, String?>>()
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                lateinit var page: PageHomeReviewBinding
                lateinit var controller: ReviewTabController
                scenario.onActivity { activity ->
                    page = PageHomeReviewBinding.inflate(activity.layoutInflater)
                    controller = ReviewTabController(onStartReview = {}, onStartEntry = { kind, project -> started += kind to project },
                        onBrowseCards = {}, onExportQaPdf = {}, onNavigationChanged = {}, onHideSearch = {})
                    controller.attach(page.root)
                    controller.render(summary)
                }
                scenario.onActivity {
                    assertEquals("Study · 3 due", page.reviewStartStudyButton.text.toString())
                    assertEquals("Projects · 3 due", page.reviewStartProjectsButton.text.toString())
                    assertTrue(page.reviewStartStudyButton.isEnabled && page.reviewStartProjectsButton.isEnabled)
                    assertEquals(View.VISIBLE, page.reviewProjectsScopeButton.visibility)
                    assertEquals("All Projects ▾", page.reviewProjectsScopeButton.text.toString())
                    page.reviewProjectsScopeButton.performClick()
                }
                instrumentation.waitForIdleSync()
                var choices = emptyList<String>()
                awaitUi(scenario, "the Project choice opens") {
                    choices = WindowInspector.getGlobalWindowViews().asReversed().map(::listItems).firstOrNull { it.isNotEmpty() }.orEmpty()
                    choices.isNotEmpty()
                }
                assertEquals(listOf("All Projects · 3 due", "Project A · 2 due", "Project B · 1 due"), choices)
                scenario.onActivity { assertTrue(WindowInspector.getGlobalWindowViews().asReversed().any { clickItem(it, "Project B · 1 due") }) }
                instrumentation.waitForIdleSync()
                awaitUi(scenario, "Projects is narrowed") { page.reviewStartProjectsButton.text.toString() == "Projects · 1 due" }
                scenario.onActivity {
                    assertEquals("Project B ▾", page.reviewProjectsScopeButton.text.toString())
                    page.reviewStartProjectsButton.performClick()
                    page.reviewStartStudyButton.performClick()
                }
                assertEquals(listOf(ReviewKinds.PROJECT to "project-b", ReviewKinds.KNOWLEDGE to null), started)

                // Only knowledge cards due: Projects has nothing to start and nothing to narrow.
                val knowledgeOnly = summary.copy(projectCounts = com.gitlab.mudlej.MjPdfReader.data.review.ReviewQueueCounts(), projects = emptyList())
                scenario.onActivity {
                    controller.render(knowledgeOnly)
                    assertEquals("Projects · 0 due", page.reviewStartProjectsButton.text.toString())
                    assertFalse(page.reviewStartProjectsButton.isEnabled)
                    assertEquals(View.GONE, page.reviewProjectsScopeButton.visibility)
                    assertEquals("Study · 3 due", page.reviewStartStudyButton.text.toString())
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun awaitUi(scenario: ActivityScenario<HomeActivity>, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var matched = false
        while (!matched && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { matched = condition() }
            if (!matched) SystemClock.sleep(50)
        }
        if (!matched) fail(message)
    }

    private fun listItems(view: View): List<String> = when (view) {
        is ListView -> (0 until view.count).map { view.adapter.getItem(it).toString() }
        is ViewGroup -> (0 until view.childCount).flatMap { listItems(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun clickItem(view: View, label: String): Boolean {
        if (view is ListView) {
            for (position in 0 until view.count) {
                if (view.adapter.getItem(position).toString() == label) {
                    return view.performItemClick(view.getChildAt(position - view.firstVisiblePosition), position, view.adapter.getItemId(position))
                }
            }
        }
        return view is ViewGroup && (0 until view.childCount).any { clickItem(view.getChildAt(it), label) }
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

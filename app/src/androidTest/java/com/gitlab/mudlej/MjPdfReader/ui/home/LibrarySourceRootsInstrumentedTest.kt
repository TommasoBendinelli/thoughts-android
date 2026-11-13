// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LibrarySourceRootsInstrumentedTest {
    @Test fun cachedCategoriesRenderAsTwoRootsAndNestedDirectories() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "source-roots-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            File(root, "state.json").writeText("""
                {"pending":[],"projects":[{
                  "id":"test","name":"Applications","documents":[],"target":"",
                  "project_configuration":{"schema_version":5,"kind":"thoughts-project","id":"test","name":"Applications",
                    "default_folder_id":"default","documents":[],"source_folders":[
                      {"folder_id":"default","path":".thoughts/projects/test/documents"},
                      {"folder_id":"mixed","path":"docs-mixed"}]},
                  "sources":[
                    {"id":"docs-mixed","name":"Project files","files":[{"id":"docs-mixed/.ThoughtIgnore","name":".ThoughtIgnore"}]},
                    {"id":"docs-mixed/markdown","name":"Markdown sources","files":[{"id":"docs-mixed/markdown/source/note.md","name":"note.md"}]},
                    {"id":"docs-mixed/tex","name":"Project sources","files":[{"id":"docs-mixed/tex/source/main.tex","name":"main.tex"}]}
                  ]
                }]}
            """.trimIndent())
            val functions = object : HomeItemFunctions {
                override fun onItemClicked(item: HomeItem) = Unit
                override fun onProjectDocumentClicked(item: HomeItem, pageIndex: Int, documentId: String?, displayTitle: String?, projectId: String?) = Unit
                override fun onItemLongClicked(item: HomeItem) = false
                override fun onItemOptionsClicked(item: HomeItem) = Unit
            }
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val pane = LibraryProjectPane(activity, scope, functions, {}, {}, LibraryProjectStore(context, root))
                    pane.render(emptyList(), SourceLinkedPdfIndexSnapshot())
                    val parent = LinearLayout(activity)
                    fun bind(position: Int) = pane.onCreateViewHolder(parent, 0).also { pane.onBindViewHolder(it, position) }.row
                    fun label(position: Int): String {
                        val row = bind(position)
                        return (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TextView>()
                            .first().text.toString()
                    }
                    bind(1).getChildAt(0).performClick() // Expand Applications.
                    bind(4).performClick() // Expand Sources.
                    assertEquals(8, pane.itemCount) // ... two source roots, then Agent conversations.
                    assertTrue(label(5).contains("Default source folder"))
                    assertTrue(label(6).contains("docs-mixed"))
                    bind(6).performClick() // Expand the connected root.
                    assertEquals(11, pane.itemCount)
                    assertTrue(label(8).contains("markdown"))
                    assertTrue(label(9).contains("tex"))
                    bind(8).performClick()
                    assertTrue(label(9).contains("source"))
                    bind(9).performClick()
                    assertEquals("note.md", label(10))
                    pane.render(emptyList(), SourceLinkedPdfIndexSnapshot())
                    assertEquals("note.md", label(10)) // Refresh preserves expansion.
                    bind(6).performClick()
                    assertEquals(8, pane.itemCount)
                    assertFalse((5 until pane.itemCount).map(::label).any { it.contains("Markdown sources") || it.contains("Project files") })
                }
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}

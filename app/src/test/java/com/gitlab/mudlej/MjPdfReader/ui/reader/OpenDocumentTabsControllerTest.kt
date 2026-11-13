// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDocumentTabsControllerTest {

    private val readerClass = "com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity"

    @Test
    fun keepsOnlyLiveReaderTasksInNewestFirstOpeningOrder() {
        val tabs = buildOpenDocumentTabs(
            snapshots = listOf(
                task(29, readerClass, "file:///documents/ideas.pdf"),
                task(
                    25,
                    "com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity",
                    null,
                    topActivityClassName = "com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity",
                ),
                task(28, readerClass, "file:///documents/clips.pdf"),
                task(
                    27,
                    readerClass,
                    "file:///documents/template.pdf",
                    topActivityClassName =
                        "com.gitlab.mudlej.MjPdfReader.ui.text_mode.TextModeActivity",
                ),
                task(15, null, "file:///documents/old-snapshot.pdf"),
            ),
            currentTaskId = 29,
            readerActivityClassName = readerClass,
        )

        assertEquals(listOf(29, 28, 27), tabs.map { it.taskId })
        assertEquals(listOf("ideas.pdf", "clips.pdf", "template.pdf"), tabs.map { it.title })
        assertTrue(tabs[0].isCurrent)
        assertFalse(tabs[1].isCurrent)
        assertFalse(tabs[2].isCurrent)
    }

    @Test
    fun removesDuplicateTaskSnapshots() {
        val tabs = buildOpenDocumentTabs(
            snapshots = listOf(
                task(28, readerClass, "file:///documents/clips.pdf"),
                task(28, readerClass, "file:///documents/clips.pdf"),
            ),
            currentTaskId = 28,
            readerActivityClassName = readerClass,
        )

        assertEquals(1, tabs.size)
    }

    @Test
    fun decodesFilenameWithoutTreatingPlusAsSpace() {
        assertEquals(
            "ideas and math+clip.pdf",
            documentTabTitle("content://documents/ideas%20and%20math+clip.pdf?version=2"),
        )
    }

    @Test
    fun currentTabFollowsInPlaceDocumentSwap() {
        // Review can advance from the stochastic-distillation card to a card in another PDF
        // without launching a new task, so baseIntent.data still names the first document.
        val tabs = buildOpenDocumentTabs(
            snapshots = listOf(
                task(
                    29,
                    readerClass,
                    "file:///documents/course-notes.pdf",
                    taskLabel = "course-notes.pdf",
                ),
            ),
            currentTaskId = 29,
            readerActivityClassName = readerClass,
            currentDocument = CurrentDocumentSnapshot(
                uri = "file:///documents/nvidia-academic-grant.pdf",
                title = "nvidia-academic-grant.pdf",
            ),
        )

        assertEquals(1, tabs.size)
        assertEquals("nvidia-academic-grant.pdf", tabs[0].title)
        assertEquals("file:///documents/nvidia-academic-grant.pdf", tabs[0].documentUri)
        assertTrue(tabs[0].isCurrent)
    }

    @Test
    fun liveCurrentDocumentWinsOverStaleTaskLabel() {
        val tabs = buildOpenDocumentTabs(
            snapshots = listOf(
                task(29, readerClass, "file:///documents/old.pdf", taskLabel = "old.pdf"),
            ),
            currentTaskId = 29,
            readerActivityClassName = readerClass,
            currentDocument = CurrentDocumentSnapshot(
                uri = "file:///documents/new.pdf",
                title = "new.pdf",
            ),
        )

        assertEquals("new.pdf", tabs.single().title)
    }

    @Test
    fun backgroundTabUsesUpdatedTaskLabelAfterInPlaceSwap() {
        val tabs = buildOpenDocumentTabs(
            snapshots = listOf(
                task(29, readerClass, "file:///documents/current.pdf"),
                task(
                    28,
                    readerClass,
                    "file:///documents/original.pdf",
                    taskLabel = "swapped-in.pdf",
                ),
            ),
            currentTaskId = 29,
            readerActivityClassName = readerClass,
            currentDocument = CurrentDocumentSnapshot(
                uri = "file:///documents/current.pdf",
                title = "current.pdf",
            ),
        )

        assertEquals(listOf(29, 28), tabs.map { it.taskId })
        assertEquals(listOf("current.pdf", "swapped-in.pdf"), tabs.map { it.title })
    }

    @Test
    fun fallsBackToLaunchUriWhenNoLiveTitleIsKnown() {
        val tabs = buildOpenDocumentTabs(
            snapshots = listOf(
                task(29, readerClass, "file:///documents/legacy.pdf", taskLabel = "  "),
            ),
            currentTaskId = 29,
            readerActivityClassName = readerClass,
            currentDocument = CurrentDocumentSnapshot(uri = null, title = "  "),
        )

        assertEquals("legacy.pdf", tabs.single().title)
    }

    @Test
    fun doesNotInventTabForTaskWithoutLaunchUri() {
        // The live document only retitles an existing tab; it never adds a task that
        // was opened without a document intent.
        val tabs = buildOpenDocumentTabs(
            snapshots = listOf(task(29, readerClass, null)),
            currentTaskId = 29,
            readerActivityClassName = readerClass,
            currentDocument = CurrentDocumentSnapshot(
                uri = "file:///documents/picked.pdf",
                title = "picked.pdf",
            ),
        )

        assertTrue(tabs.isEmpty())
    }

    @Test
    fun fallsBackToWholeUriWhenItHasNoFilename() {
        assertEquals("content://documents", documentTabTitle("content://documents"))
    }

    @Test
    fun closingOnlyCurrentTabReturnsToLibrary() {
        val current = openTab(29, isCurrent = true)

        assertTrue(shouldReturnToLibraryAfterClosing(listOf(current), current))
    }

    @Test
    fun closingCurrentTabWithAnotherDocumentDoesNotReturnToLibrary() {
        val other = openTab(28, isCurrent = false)
        val current = openTab(29, isCurrent = true)

        assertFalse(shouldReturnToLibraryAfterClosing(listOf(other, current), current))
    }

    @Test
    fun closingBackgroundTabDoesNotReturnToLibrary() {
        val other = openTab(28, isCurrent = false)
        val current = openTab(29, isCurrent = true)

        assertFalse(shouldReturnToLibraryAfterClosing(listOf(other, current), other))
    }

    @Test
    fun closingOlderCurrentTabRevealsPreviousTab() {
        val current = openTab(28, isCurrent = true)
        val newer = openTab(29, isCurrent = false)

        assertEquals(newer, replacementTabAfterClosing(listOf(newer, current), current))
    }

    @Test
    fun closingNewestCurrentTabRevealsNextTab() {
        val older = openTab(28, isCurrent = false)
        val current = openTab(29, isCurrent = true)

        assertEquals(older, replacementTabAfterClosing(listOf(current, older), current))
    }

    @Test
    fun closingOnlyOrBackgroundTabHasNoReplacement() {
        val background = openTab(28, isCurrent = false)
        val current = openTab(29, isCurrent = true)

        assertEquals(null, replacementTabAfterClosing(listOf(current), current))
        assertEquals(null, replacementTabAfterClosing(listOf(background, current), background))
    }

    @Test
    fun switchingTabsDismissesCurrentUiBeforeMovingTargetTaskToFront() {
        val operations = mutableListOf<String>()

        switchOpenDocumentTask(
            dismissCurrentDocumentUi = { operations += "dismiss" },
            moveTargetTaskToFront = { operations += "move" },
        )

        assertEquals(listOf("dismiss", "move"), operations)
    }

    @Test
    fun sideActionsUseDisplayedOrderAndNeverCloseAnchor() {
        val tabs = listOf(openTab(29, false), openTab(28, true), openTab(27, false))
        assertEquals(listOf(tabs[0]), tabsOnSide(tabs, tabs[1], left = true))
        assertEquals(listOf(tabs[2]), tabsOnSide(tabs, tabs[1], left = false))
        assertTrue(tabsOnSide(tabs, tabs.first(), left = true).isEmpty())
        assertTrue(tabsOnSide(tabs, tabs.last(), left = false).isEmpty())
        assertTrue(tabsOnSide(tabs, openTab(99, false), left = true).isEmpty())
    }

    @Test
    fun copiedPathDecodesLocalFilesAndPreservesProviderUris() {
        assertEquals("/documents/a b+c.pdf", documentTabPath("file:///documents/a%20b+c.pdf"))
        val provider = "content://documents/tree/primary%3Adocs/document/primary%3Adocs%2Fa.pdf"
        assertEquals(provider, documentTabPath(provider))
    }

    private fun openTab(taskId: Int, isCurrent: Boolean) = OpenDocumentTab(
        taskId = taskId,
        documentUri = "file:///documents/$taskId.pdf",
        title = "$taskId.pdf",
        isCurrent = isCurrent,
    )

    @Test
    fun agentNotesAreTabsNextToTheDocumentsTitledByTheirTaskLabel() {
        val noteClass = "com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNoteActivity"
        val tabs = buildOpenDocumentTabs(
            snapshots = listOf(
                task(31, noteClass, "thoughts-agent-note://note/n-1", taskLabel = "Your drill example"),
                task(30, readerClass, "file:///documents/MiMo.pdf"),
                task(29, noteClass, null),
            ),
            currentTaskId = 31,
            readerActivityClassName = readerClass,
            noteActivityClassName = noteClass,
        )

        assertEquals(listOf(31, 30), tabs.map { it.taskId })
        assertEquals(listOf("Your drill example", "MiMo.pdf"), tabs.map { it.title })
        assertTrue(tabs[0].isCurrent)
        assertTrue(isAgentNoteTab(tabs[0].documentUri))
        assertFalse(isAgentNoteTab(tabs[1].documentUri))
    }

    private fun task(
        taskId: Int,
        baseActivityClassName: String?,
        documentUri: String?,
        topActivityClassName: String? = baseActivityClassName,
        taskLabel: String? = null,
    ) = DocumentTaskSnapshot(taskId, baseActivityClassName, topActivityClassName, documentUri, taskLabel)
}

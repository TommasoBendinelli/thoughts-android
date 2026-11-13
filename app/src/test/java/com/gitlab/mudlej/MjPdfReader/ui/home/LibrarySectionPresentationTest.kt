// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySectionPresentationTest {
    @Test
    fun linkedAlwaysPrecedesUnlinkedAndDividerSeparatesThem() {
        val sections = librarySectionPresentation(linkedCount = 8, unlinkedCount = 3, archivedCount = 2)
        assertEquals(listOf(LibrarySectionKind.LINKED, LibrarySectionKind.UNLINKED, LibrarySectionKind.ARCHIVED), sections.map { it.kind })
        assertEquals(listOf(8, 3, 2), sections.map { it.count })
        assertFalse(sections.first().showDivider)
        assertTrue(sections.last().showDivider)
    }

    @Test
    fun emptySectionsDoNotRenderAndSingleUnlinkedGroupNeedsNoDivider() {
        assertTrue(librarySectionPresentation(0, 0, 0).isEmpty())
        val unlinkedOnly = librarySectionPresentation(0, 2, 0).single()
        assertEquals(LibrarySectionKind.UNLINKED, unlinkedOnly.kind)
        assertFalse(unlinkedOnly.showDivider)
    }

    @Test
    fun archivedGroupIsLastAndNeedsNoDividerWhenItIsAlone() {
        val archivedOnly = librarySectionPresentation(0, 0, 4).single()
        assertEquals(LibrarySectionKind.ARCHIVED, archivedOnly.kind)
        assertEquals(4, archivedOnly.count)
        assertFalse(archivedOnly.showDivider)
    }

    @Test
    fun eachLibrarySectionCanBeCollapsedWithoutChangingTheOthers() {
        val initial = LibrarySectionExpansionState()
        assertTrue(LibrarySectionKind.entries.all(initial::isExpanded))

        val linkedCollapsed = initial.toggled(LibrarySectionKind.LINKED)
        assertFalse(linkedCollapsed.isExpanded(LibrarySectionKind.LINKED))
        assertTrue(linkedCollapsed.isExpanded(LibrarySectionKind.UNLINKED))
        assertTrue(linkedCollapsed.isExpanded(LibrarySectionKind.ARCHIVED))

        val reopened = linkedCollapsed.toggled(LibrarySectionKind.LINKED)
        assertEquals(initial, reopened)
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolbarNavigationPresentationTest {

    @Test
    fun arrowsStayVisibleForAnOpenDocumentAndReflectHistoryAvailability() {
        assertEquals(
            ToolbarNavigationPresentation(visible = true, backEnabled = false, forwardEnabled = false),
            toolbarNavigationPresentation(hasDocument = true, canGoBack = false, canGoForward = false),
        )
        assertEquals(
            ToolbarNavigationPresentation(visible = true, backEnabled = true, forwardEnabled = false),
            toolbarNavigationPresentation(hasDocument = true, canGoBack = true, canGoForward = false),
        )
        assertEquals(
            ToolbarNavigationPresentation(visible = true, backEnabled = false, forwardEnabled = true),
            toolbarNavigationPresentation(hasDocument = true, canGoBack = false, canGoForward = true),
        )
    }

    @Test
    fun arrowsAreHiddenWithoutAnOpenDocument() {
        assertEquals(
            ToolbarNavigationPresentation(visible = false, backEnabled = false, forwardEnabled = false),
            toolbarNavigationPresentation(hasDocument = false, canGoBack = true, canGoForward = true),
        )
    }

    @Test
    fun dedicatedArrowsAreNotOfferedAsDuplicateConfigurableToolbarButtons() {
        assertFalse(ConfigurableAction.toolbarActions.contains(ConfigurableAction.NAV_BACK))
        assertFalse(ConfigurableAction.toolbarActions.contains(ConfigurableAction.NAV_FORWARD))
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMenuActionLayoutTest {

    @Test
    fun readerMenuKeepsOnlyFocusedPrimaryAndPageActions() {
        val layout = readerMenuActionLayout()

        assertEquals(
            listOf(
                ConfigurableAction.OPEN_LOCAL,
                ConfigurableAction.PUT_IN_LIBRARY,
                ConfigurableAction.SWITCH_THEME,
                ConfigurableAction.SEARCH,
                ConfigurableAction.BOOKMARK_PAGE,
                ConfigurableAction.READING_DIRECTION,
                ConfigurableAction.FULLSCREEN,
                ConfigurableAction.SHARE,
                ConfigurableAction.ROTATE,
            ),
            layout.primary,
        )
        assertEquals(
            listOf(
                ConfigurableAction.TABLE_OF_CONTENTS,
                ConfigurableAction.REVIEW_CARDS,
                ConfigurableAction.USER_NOTES,
                ConfigurableAction.AGENT_EDITS,
            ),
            layout.pages,
        )
    }

    @Test
    fun readerMenuOmitsActionsAvailableElsewhereOrOutsideTheReadingWorkflow() {
        val layout = readerMenuActionLayout()
        val menuActions = (layout.primary + layout.navigation + layout.pages).toSet()
        val omittedActions = setOf(
            ConfigurableAction.SCREENSHOT,
            ConfigurableAction.EXTRACT_TEXT,
            ConfigurableAction.FILE_METADATA,
            ConfigurableAction.ADD_SIGNATURE,
            ConfigurableAction.LINKS_IN_FILE,
            ConfigurableAction.SETTINGS,
            ConfigurableAction.ABOUT,
        )

        assertTrue(menuActions.intersect(omittedActions).isEmpty())
    }

    @Test
    fun readerNavigationActionsRemainContextualAndOrdered() {
        assertEquals(
            listOf(
                ConfigurableAction.NAV_BACK,
                ConfigurableAction.NAV_FORWARD,
                ConfigurableAction.NAV_HISTORY,
            ),
            readerMenuActionLayout().navigation,
        )
    }
}

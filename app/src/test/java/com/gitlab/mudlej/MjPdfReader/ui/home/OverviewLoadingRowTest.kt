// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewLoadingRowTest {
    @Test fun emptyListBeforeTheFirstCatalogueIsLoadingNotEmpty() {
        assertEquals(listOf(OverviewCommentRow.Empty(false, loading = true)),
            overviewCommentRows(emptyList(), emptySet(), hasAnyComments = false, loading = true))
        assertEquals(listOf(OverviewCommentRow.Empty(false)),
            overviewCommentRows(emptyList(), emptySet(), hasAnyComments = false))
    }
}

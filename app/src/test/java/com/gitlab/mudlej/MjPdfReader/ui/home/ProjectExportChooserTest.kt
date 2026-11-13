// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectExportChooserTest {
    private val documents = listOf(
        ProjectExportDocument("a", "First"),
        ProjectExportDocument("b", "Second"),
        ProjectExportDocument("c", "Third"),
    )

    @Test
    fun `every document ticked exports the whole project`() {
        assertNull(chosenProjectExportDocumentIds(documents, booleanArrayOf(true, true, true)))
    }

    @Test
    fun `unticked documents are left out in project order`() {
        assertEquals(listOf("a", "c"), chosenProjectExportDocumentIds(documents, booleanArrayOf(true, false, true)))
    }
}

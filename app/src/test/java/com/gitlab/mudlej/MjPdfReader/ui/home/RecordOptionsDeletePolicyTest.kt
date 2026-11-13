// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordOptionsDeletePolicyTest {
    @Test
    fun unmanagedPdfKeepsDeviceDeletion() {
        assertEquals(RecordDeleteAction.DELETE_LOCAL_FILE, recordDeleteAction(null))
    }

    @Test
    fun unlinkedLibraryPdfQueuesSharedRemoval() {
        assertEquals(
            RecordDeleteAction.QUEUE_LIBRARY_REMOVAL,
            recordDeleteAction(LibraryPdfKind.UNLINKED),
        )
    }

    @Test
    fun sourceLinkedLibraryPdfCannotBeDeleted() {
        assertEquals(
            RecordDeleteAction.UNAVAILABLE,
            recordDeleteAction(LibraryPdfKind.SOURCE_LINKED),
        )
    }
}

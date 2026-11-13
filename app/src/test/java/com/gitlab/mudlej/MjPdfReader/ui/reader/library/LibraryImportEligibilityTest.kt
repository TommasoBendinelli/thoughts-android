// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.library

import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedCommentIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryImportEligibilityTest {

    @Test
    fun libraryImportNormalizesNamesForPdfLink() {
        assertEquals("paper.pdf", pdfLinkSafeFilename("paper.PDF"))
        assertEquals("paper_copy.pdf", pdfLinkSafeFilename("paper copy.pdf"))
        assertEquals("document_draft.pdf", pdfLinkSafeFilename("_draft.pdf"))
        assertEquals("document.pdf", pdfLinkSafeFilename(".pdf"))
        assertEquals("paper-2026_v2.pdf", pdfLinkSafeFilename("paper-2026_v2.pdf"))
    }

    @Test
    fun externalDocumentOutsideLibraryCanBeImported() {
        assertTrue(
            canPutInLibrary(
                hasDocument = true,
                sourceIdentity = SourceLinkedCommentIdentity.NOT_SOURCE_LINKED,
                isLibraryPdf = false,
            ),
        )
    }

    @Test
    fun existingUnlinkedLibraryDocumentCannotBeImportedAgain() {
        assertFalse(
            canPutInLibrary(
                hasDocument = true,
                sourceIdentity = SourceLinkedCommentIdentity.NOT_SOURCE_LINKED,
                isLibraryPdf = true,
            ),
        )
    }

    @Test
    fun sourceLinkedAndUncertainIdentitiesCannotBeImported() {
        val hiddenIdentities = SourceLinkedCommentIdentity.entries -
            SourceLinkedCommentIdentity.NOT_SOURCE_LINKED

        hiddenIdentities.forEach { identity ->
            assertFalse(
                canPutInLibrary(
                    hasDocument = true,
                    sourceIdentity = identity,
                    isLibraryPdf = false,
                ),
            )
        }
    }

    @Test
    fun missingDocumentCannotBeImported() {
        assertFalse(
            canPutInLibrary(
                hasDocument = false,
                sourceIdentity = SourceLinkedCommentIdentity.NOT_SOURCE_LINKED,
                isLibraryPdf = false,
            ),
        )
    }
}

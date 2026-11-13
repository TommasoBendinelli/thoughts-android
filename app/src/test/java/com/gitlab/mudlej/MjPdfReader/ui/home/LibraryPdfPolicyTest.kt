// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPdfPolicyTest {

    @Test
    fun keepsOnlyCurrentCompanionPdfCopies() {
        assertTrue(
            isLibraryPdfCandidate(
                "/storage/emulated/0/Documents/docs-clean-pdfs/Benchmark-Conf.pdf"
            )
        )
        assertFalse(
            isLibraryPdfCandidate(
                "/storage/emulated/0/Documents/docs-clean-pdfs/" +
                    "Benchmark-Conf.sync-conflict-20260724-phone.pdf"
            )
        )
        assertFalse(
            isLibraryPdfCandidate(
                "/storage/emulated/0/Documents/docs-clean-pdfs/.stversions/Benchmark-Conf.pdf"
            )
        )
        assertFalse(
            isLibraryPdfCandidate(
                "/storage/emulated/0/Documents/archive/paper.sync-conflict-example.pdf"
            )
        )
        assertFalse(
            isLibraryPdfCandidate(
                "/storage/emulated/0/Documents/docs-clean-pdfs/.syncthing.paper.pdf.tmp"
            )
        )
        assertFalse(
            isLibraryPdfCandidate(
                "/storage/emulated/0/Documents/docs-clean-pdfs/.temporary.pdf"
            )
        )
    }
}

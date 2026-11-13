// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemotePdfLinksTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun parsesThePageAndOptionalPoint() {
        assertEquals(RemotePdfLink("concepts_sdpo.pdf", 2, 72f, 387.437f),
            parseRemotePdfLink("thoughts-pdf-link:concepts_sdpo.pdf?page=2&x=72.000&y=387.437"))
        val pageOnly = parseRemotePdfLink("thoughts-pdf-link:concepts_sdpo.pdf?page=0")!!
        assertEquals(0, pageOnly.pageIndex)
        assertTrue(pageOnly.x.isNaN() && pageOnly.y.isNaN())
        assertTrue(parseRemotePdfLink("thoughts-pdf-link:a.pdf?page=1&y=-3.5")!!.x.isNaN())
    }

    @Test fun rejectsPathsAndOtherSchemes() {
        assertNull(parseRemotePdfLink("thoughts-pdf-link:../secret.pdf?page=0"))
        assertNull(parseRemotePdfLink("thoughts-pdf-link:dir/a.pdf?page=0"))
        assertNull(parseRemotePdfLink("thoughts-pdf-link:a.txt?page=0"))
        assertNull(parseRemotePdfLink("https://example.org/a.pdf?page=0"))
        assertNull(parseRemotePdfLink(null))
    }

    @Test fun opensOnlyInstalledPlainFilenames() {
        val directory = temporary.newFolder("current-pdfs")
        java.io.File(directory, "concepts_sdpo.pdf").writeText("%PDF")
        assertEquals("concepts_sdpo.pdf", installedRemotePdf(directory, "concepts_sdpo.pdf")?.name)
        assertNull(installedRemotePdf(directory, "missing.pdf"))
        assertNull(installedRemotePdf(directory, "../concepts_sdpo.pdf"))
    }
}

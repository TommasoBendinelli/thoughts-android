// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfBuildLogPresentationTest {

    @Test
    fun anchorsToTheDetailedSourceErrorBeforeTheSummary() {
        val contents = """
            Changed input: docs-Benchmark/tex/source/simulation_stage.tex
            ----- compile Benchmark/simulation_stage.tex log -----
            /srv/thoughts/worktree/docs-Benchmark/tex/source/simulation_stage.tex:55: Argument of \reserved@a has an extra }.
             Fatal error occurred, no output PDF file produced!
            Error: LaTeX compilation failed. Log: docs-Benchmark/build/simulation_stage.render.log
        """.trimIndent()

        val anchor = pdfBuildLogAnchor(
            contents,
            source = "docs-Benchmark/tex/source/simulation_stage.tex",
            message = "Error: LaTeX compilation failed.",
            pdfFilename = "Benchmark.pdf",
        )!!

        assertEquals(
            "/srv/thoughts/worktree/docs-Benchmark/tex/source/simulation_stage.tex:55: Argument of \\reserved@a has an extra }.",
            contents.substring(anchor.start, anchor.endExclusive),
        )
    }

    @Test
    fun fallsBackToTheExactMessage() {
        val contents = "prelude\nA precise renderer failure\ntrailer"

        val anchor = pdfBuildLogAnchor(
            contents,
            source = "",
            message = "A precise renderer failure",
            pdfFilename = "manual.pdf",
        )!!

        assertEquals("A precise renderer failure", contents.substring(anchor.start, anchor.endExclusive))
    }

    @Test
    fun fallsBackToTheLastFatalErrorWhenNoPageHintMatches() {
        val contents = "Fatal error first\nprogress\nFatal error final\n"

        val anchor = pdfBuildLogAnchor(contents, "", "", "")!!

        assertEquals("Fatal error final", contents.substring(anchor.start, anchor.endExclusive))
    }

    @Test
    fun emptyLogHasNoAnchor() {
        assertNull(pdfBuildLogAnchor("", "source.tex", "failed", "manual.pdf"))
    }
}

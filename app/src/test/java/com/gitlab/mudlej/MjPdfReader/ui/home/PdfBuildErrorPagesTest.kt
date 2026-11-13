// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderFailure
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSnapshot
import com.gitlab.mudlej.MjPdfReader.data.sync.PdfBuildErrorReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfBuildErrorPagesTest {

    @Test
    fun oneFailureProducesOnePageWithoutNavigation() {
        val pages = pdfBuildErrorPages(
            snapshot(
                failure("Benchmark.pdf", "source.tex", "LaTeX failed")
            )
        )
        val navigation = pdfBuildErrorNavigation(0, pages.size)

        assertEquals("Benchmark.pdf", pages.single().pdfFilename)
        assertEquals("Benchmark.pdf", pages.single().actionTarget)
        assertEquals(1, navigation.displayIndex)
        assertEquals(1, navigation.total)
        assertFalse(navigation.canGoBack)
        assertFalse(navigation.canGoNext)
    }

    @Test
    fun threeFailuresExposeBackAndNextAtTheCorrectPositions() {
        val pages = pdfBuildErrorPages(
            snapshot(
                failure("one.pdf", "one.tex", "one"),
                failure("two.pdf", "two.tex", "two"),
                failure("three.pdf", "three.tex", "three"),
            )
        )

        val first = pdfBuildErrorNavigation(0, pages.size)
        val middle = pdfBuildErrorNavigation(1, pages.size)
        val last = pdfBuildErrorNavigation(2, pages.size)
        assertEquals(listOf(1, 2, 3), listOf(first.displayIndex, middle.displayIndex, last.displayIndex))
        assertFalse(first.canGoBack)
        assertTrue(first.canGoNext)
        assertTrue(middle.canGoBack)
        assertTrue(middle.canGoNext)
        assertTrue(last.canGoBack)
        assertFalse(last.canGoNext)
    }

    @Test
    fun aggregateAndDerivedFailureAreDisplayedOnlyOnce() {
        val failure = failure("Benchmark.pdf", "source.tex", "LaTeX failed")
        val aggregate = status(
            requestId = "aggregate",
            target = PhoneRenderQueue.ACTION_ALL,
            activityAt = 20,
            failures = listOf(failure),
        )
        val derived = status(
            requestId = "derived",
            target = "Benchmark.pdf",
            activityAt = 20,
            failures = listOf(failure),
        )

        val pages = pdfBuildErrorPages(PhoneRenderStatusSnapshot(listOf(aggregate, derived)))

        assertEquals(1, pages.size)
        assertEquals("Benchmark.pdf", pages.single().actionTarget)
    }

    @Test
    fun distinctErrorsForTheSamePdfRemainSeparatePages() {
        val pages = pdfBuildErrorPages(
            snapshot(
                failure("Benchmark.pdf", "one.tex", "first"),
                failure("Benchmark.pdf", "two.tex", "second"),
            )
        )

        assertEquals(2, pages.size)
        assertEquals(listOf("first", "second"), pages.map(PdfBuildErrorPage::message))
        assertTrue(pages.all { it.actionTarget == "Benchmark.pdf" })
    }

    @Test
    fun olderBuildFailureForTheSamePdfIsNotShownBesideTheLatestFailure() {
        val olderAggregate = status(
            requestId = "older",
            target = PhoneRenderQueue.ACTION_ALL,
            activityAt = 10,
            failures = listOf(failure("Benchmark.pdf", "old.tex", "old failure")),
        )
        val latestPdf = status(
            requestId = "latest",
            target = "Benchmark.pdf",
            activityAt = 20,
            message = "latest failure",
        )

        val pages = pdfBuildErrorPages(
            PhoneRenderStatusSnapshot(listOf(olderAggregate, latestPdf))
        )

        assertEquals(1, pages.size)
        assertEquals("latest failure", pages.single().message)
    }

    @Test
    fun unattributedGlobalFailureRetainsTheBuildLevelActionTarget() {
        val pages = pdfBuildErrorPages(
            PhoneRenderStatusSnapshot(
                listOf(
                    status(
                        requestId = "global",
                        target = PhoneRenderQueue.ACTION_ALL,
                        activityAt = 10,
                        message = "Renderer exited",
                    )
                )
            )
        )

        assertEquals(null, pages.single().pdfFilename)
        assertEquals(PhoneRenderQueue.ACTION_ALL, pages.single().actionTarget)
        assertEquals("Renderer exited", pages.single().message)
    }

    @Test
    fun successfulStatusDoesNotBecomeAnErrorPage() {
        val pages = pdfBuildErrorPages(
            PhoneRenderStatusSnapshot(
                listOf(
                    PhoneRenderJobStatus(
                        requestId = "success",
                        target = "Benchmark.pdf",
                        stage = PhoneRenderStage.UPDATED,
                    )
                )
            )
        )

        assertTrue(pages.isEmpty())
    }

    @Test
    fun serverReportUsesItsFailedPdfInsteadOfTheAggregateTarget() {
        val pages = pdfBuildErrorPages(
            PdfBuildErrorReport(
                buildId = "build",
                target = PhoneRenderQueue.ACTION_ALL,
                status = "attention",
                message = "",
                failedDocuments = listOf(
                    failure("Benchmark.pdf", "source.tex", "LaTeX failed")
                ),
                logReference = "server-object:log",
            )
        )

        assertEquals("Benchmark.pdf", pages.single().actionTarget)
        assertEquals("build", pages.single().buildId)
    }

    @Test
    fun statusRetainsBuildIdWhenItsReceiptDoesNotContainALogReference() {
        val pages = pdfBuildErrorPages(
            PhoneRenderStatusSnapshot(
                listOf(
                    PhoneRenderJobStatus(
                        requestId = "request",
                        target = "Benchmark.pdf",
                        stage = PhoneRenderStage.FAILED,
                        message = "LaTeX failed",
                        buildId = "build-id",
                    )
                )
            )
        )

        assertEquals("build-id", pages.single().buildId)
        assertEquals("", pages.single().logReference)
    }

    private fun snapshot(vararg failures: PhoneRenderFailure): PhoneRenderStatusSnapshot =
        PhoneRenderStatusSnapshot(
            listOf(
                status(
                    requestId = "aggregate",
                    target = PhoneRenderQueue.ACTION_ALL,
                    activityAt = 10,
                    failures = failures.toList(),
                )
            )
        )

    private fun status(
        requestId: String,
        target: String,
        activityAt: Long,
        failures: List<PhoneRenderFailure> = emptyList(),
        message: String = "",
    ): PhoneRenderJobStatus = PhoneRenderJobStatus(
        requestId = requestId,
        target = target,
        stage = PhoneRenderStage.PARTIAL,
        message = message,
        completedAtEpochMillis = activityAt,
        failedDocuments = failures,
        logReference = "server-object:log",
    )

    private fun failure(
        pdfFilename: String,
        source: String,
        message: String,
    ): PhoneRenderFailure = PhoneRenderFailure(pdfFilename, source, message)
}

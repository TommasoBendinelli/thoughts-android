// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tbend.thoughts.sdk.BuildRecord

class FedoraBuildErrorReportTest {

    @Test
    fun acceptsServerErrorFieldWithoutDroppingTheAffectedPdf() {
        val report = pdfBuildErrorReport(build(
            result = """{"failed_documents":[{"pdf_filename":"course-notes.pdf","error":"strict review correspondence gate failed"}]}""",
            status = "failed",
        ))
        assertEquals("course-notes.pdf", report.failedDocuments.single().pdfFilename)
        assertEquals("strict review correspondence gate failed", report.failedDocuments.single().message)
    }

    @Test
    fun retainsAttributedPdfWhenReasonIsOnlyAvailableAtBuildLevel() {
        val report = pdfBuildErrorReport(build(
            result = """{"failed_documents":[{"pdf_filename":"paper.pdf","message":""}]}""",
            error = "Renderer failed",
        ))
        assertEquals("paper.pdf", report.failedDocuments.single().pdfFilename)
        assertEquals("Renderer failed", report.failedDocuments.single().message)
    }

    @Test
    fun parsesAttributedFailuresAndTheServerLog() {
        val report = pdfBuildErrorReport(
            build(
                result = """
                    {
                      "failed_documents": [
                        {
                          "pdf_filename": "Benchmark.pdf",
                          "source": "source.tex",
                          "message": "LaTeX failed"
                        }
                      ],
                      "log_sha256": "${"a".repeat(64)}"
                    }
                """.trimIndent(),
            )
        )

        assertEquals("Benchmark.pdf", report.failedDocuments.single().pdfFilename)
        assertEquals("source.tex", report.failedDocuments.single().source)
        assertEquals("LaTeX failed", report.failedDocuments.single().message)
        assertEquals("server-object:${"a".repeat(64)}", report.logReference)
    }

    @Test
    fun rejectsUnsafeFilenamesWithoutLosingTheBuildLevelError() {
        val report = pdfBuildErrorReport(
            build(
                result = """
                    {
                      "failed_documents": [
                        {
                          "pdf_filename": "../private.pdf",
                          "source": "source.tex",
                          "message": "LaTeX failed"
                        }
                      ]
                    }
                """.trimIndent(),
                error = "Renderer failed",
            )
        )

        assertTrue(report.failedDocuments.isEmpty())
        assertEquals("Renderer failed", report.message)
    }

    @Test
    fun missingFailureInventoryFallsBackToProgressDetail() {
        val report = pdfBuildErrorReport(
            build(
                result = "{}",
                progress = JsonObject().apply { addProperty("detail", "Compiler stopped") },
            )
        )

        assertTrue(report.failedDocuments.isEmpty())
        assertEquals("Compiler stopped", report.message)
    }

    @Test
    fun compilerFailureIsTerminalAndKeepsTheExactServerLogReference() {
        val failure = terminalPdfBuildFailure(
            build(
                result = """{"log_sha256":"${"b".repeat(64)}"}""",
                error = "renderer exited with 1",
                status = "compiler_failure",
            ),
        )

        assertEquals(TerminalPdfBuildStatus.FAILED, failure.status)
        assertEquals("renderer exited with 1", failure.detail)
        assertTrue(failure.technicalMessage().contains("server-object:${"b".repeat(64)}"))
    }

    private fun build(
        result: String,
        progress: JsonObject = JsonObject(),
        error: String? = null,
        status: String = "attention",
    ): BuildRecord = BuildRecord(
        id = "build-id",
        target = "all",
        status = status,
        command = null,
        sourceFrontier = 0,
        sourceSnapshotSha256 = "",
        inputSnapshotSha256 = null,
        commentFrontier = 0,
        annotationFrontier = 0,
        toolchainKey = "test",
        renderFingerprint = null,
        progress = progress,
        result = JsonParser.parseString(result).asJsonObject,
        error = error,
        cancellationRequested = false,
    )
}

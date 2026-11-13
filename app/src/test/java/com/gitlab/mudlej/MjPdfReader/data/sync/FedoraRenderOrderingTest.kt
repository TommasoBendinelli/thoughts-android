// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tbend.thoughts.sdk.BuildRecord

class FedoraRenderOrderingTest {

    @Test
    fun targetedSyncContinuesForEitherPhoneOrDesktopBuild() {
        assertTrue(
            targetedBuildsActive(renderRequestActive = true, documentRenderStatus = "synced"),
        )
        assertTrue(
            targetedBuildsActive(renderRequestActive = false, documentRenderStatus = "building"),
        )
        assertFalse(
            targetedBuildsActive(renderRequestActive = false, documentRenderStatus = "synced"),
        )
        assertFalse(
            targetedBuildsActive(renderRequestActive = false, documentRenderStatus = null),
        )
    }

    @Test
    fun serverBuildProgressKeepsIdentityDocumentsAndCurrentRendererStep() {
        val progress = JsonObject().apply {
            addProperty("phase", "compiling_sources")
            addProperty("completed", 3)
            addProperty("total", 17)
            addProperty("unit", "files")
            addProperty("detail", "Compiling concepts.tex")
            add("documents", JsonArray().apply {
                add("Benchmark-Conf.pdf")
                add("not/a-library-file.pdf")
            })
        }
        val build = BuildRecord(
            id = "build-id",
            target = "all",
            status = "running",
            command = null,
            sourceFrontier = 1,
            sourceSnapshotSha256 = "a".repeat(64),
            inputSnapshotSha256 = null,
            commentFrontier = 2,
            annotationFrontier = 3,
            toolchainKey = "toolchain",
            progress = progress,
            result = JsonObject(),
            error = null,
            cancellationRequested = false,
        )

        assertEquals(
            SyncBuildProgress(
                buildId = "build-id",
                target = "all",
                serverStatus = "running",
                phase = "compiling_sources",
                completed = 3,
                total = 17,
                unit = "files",
                detail = "Compiling concepts.tex",
                documents = listOf("Benchmark-Conf.pdf"),
                failedDocuments = emptyList(),
                logReference = "",
                workKind = build.repairMode,
            ),
            syncBuildProgress(build),
        )
    }

    @Test
    fun terminalProjectFailureStillChecksAndRefreshesHealthyPublications() = runBlocking {
        var requestedBuildCalls = 0
        var conditionalBuildCalls = 0
        var publicationRefreshCalls = 0
        val failed = TerminalPdfBuildFailure(
            buildId = "aggregate-build",
            status = TerminalPdfBuildStatus.FAILED,
            detail = "course-notes.pdf: Undefined control sequence",
        )
        val progress = SyncBuildProgress(
            buildId = "aggregate-build",
            target = "all",
            serverStatus = "compiler_failure",
            phase = "compiling_sources",
            completed = 16,
            total = 17,
            unit = "documents",
            detail = "course-notes.pdf: Undefined control sequence",
            documents = listOf("concepts.pdf", "course-notes.pdf"),
        )

        val result = refreshPublicationsAfterRenderSync(
            explicit = true,
            syncRequestedRenders = {
                requestedBuildCalls += 1
                RenderRequestSyncResult(
                    active = false,
                    builds = listOf(progress),
                    terminalFailures = listOf(failed),
                )
            },
            syncConditionalRender = {
                conditionalBuildCalls += 1
                RenderRequestSyncResult(active = false, builds = emptyList())
            },
            refreshPublicationState = { render ->
                publicationRefreshCalls += 1
                assertEquals(listOf(failed), render.terminalFailures)
                mapOf(
                    "concepts.pdf" to "new-healthy-pdf",
                    "course-notes.pdf" to "last-good-pdf",
                    "docs-paper/tex/support/manual_style.sty" to "new-shared-style",
                )
            },
        )

        assertEquals(1, requestedBuildCalls)
        assertEquals(1, conditionalBuildCalls)
        assertEquals(1, publicationRefreshCalls)
        assertFalse(result.renderSync.active)
        assertEquals(listOf(progress), result.renderSync.builds)
        assertEquals(listOf(failed), result.renderSync.terminalFailures)
        assertEquals("new-healthy-pdf", result.publicationState["concepts.pdf"])
        assertEquals("last-good-pdf", result.publicationState["course-notes.pdf"])
        assertEquals(
            "new-shared-style",
            result.publicationState["docs-paper/tex/support/manual_style.sty"],
        )
    }

    @Test
    fun successfulRequestedRendersStillRunOneConditionalCheck() = runBlocking {
        var conditionalBuildCalls = 0

        refreshPublicationsAfterRenderSync(
            explicit = true,
            syncRequestedRenders = {
                RenderRequestSyncResult(active = false, builds = emptyList())
            },
            syncConditionalRender = {
                conditionalBuildCalls += 1
                RenderRequestSyncResult(active = false, builds = emptyList())
            },
            refreshPublicationState = { Unit },
        )

        assertEquals(1, conditionalBuildCalls)
    }

    @Test
    fun oneTerminalBuildDoesNotSuppressAnActiveSibling() {
        val failure = TerminalPdfBuildFailure(
            buildId = "failed",
            status = TerminalPdfBuildStatus.FAILED,
            detail = "compiler failure",
        )
        val merged = mergeRenderSyncResults(
            RenderRequestSyncResult(active = true, builds = emptyList()),
            RenderRequestSyncResult(
                active = false,
                builds = emptyList(),
                terminalFailures = listOf(failure),
            ),
        )

        assertTrue(merged.active)
        assertEquals(listOf(failure), merged.terminalFailures)
    }

    @Test
    fun resumedOperationReconstructsItsTerminalReceiptWithoutBlockingANewSync() {
        val originalOperation = "11111111-1111-4111-8111-111111111111"
        val laterOperation = "22222222-2222-4222-8222-222222222222"
        val payload = JsonObject().apply {
            addProperty("status", "failure")
            addProperty("server_status", "compiler_failure")
            addProperty("terminal_operation_id", originalOperation)
            addProperty("build_id", "aggregate-build")
            addProperty("message", "course-notes.pdf: Undefined control sequence")
            addProperty("log_reference", "server-object:${"a".repeat(64)}")
        }

        val resumed = requireNotNull(
            terminalBuildFailureFromReceipt(payload, originalOperation),
        )
        assertEquals(TerminalPdfBuildStatus.FAILED, resumed.status)
        assertEquals("aggregate-build", resumed.buildId)
        assertTrue(resumed.detail.contains("Undefined control sequence"))
        assertTrue(resumed.logReference.startsWith("server-object:"))
        assertNull(terminalBuildFailureFromReceipt(payload, laterOperation))
        assertEquals(
            resumed,
            terminalBuildFailureFromReceipt(
                payload.deepCopy().apply { remove("terminal_operation_id") },
                laterOperation,
                knownBuildIds = setOf("aggregate-build"),
            ),
        )
        val perDocumentOnly = payload.deepCopy().apply {
            addProperty("message", "")
            add(
                "failed_documents",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("pdf_filename", "course-notes.pdf")
                            addProperty("message", "Undefined control sequence")
                        },
                    )
                },
            )
        }
        assertEquals(
            "course-notes.pdf: Undefined control sequence",
            requireNotNull(
                terminalBuildFailureFromReceipt(perDocumentOnly, originalOperation),
            ).detail,
        )
    }

}

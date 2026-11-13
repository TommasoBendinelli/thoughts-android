// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tbend.thoughts.sdk.SourceSelectionRequest
import org.tbend.thoughts.sdk.SourceSelectionResult
import org.tbend.thoughts.sdk.SourceSnapshot

class FedoraSourceRequestTest {
    @Test
    fun canonicalSourceReusesExactLocallyUploadedBytes() {
        val local = "phone edit\n".toByteArray()
        val digest = sha256(local)
        var downloads = 0

        val canonical = verifiedCanonicalSourceBytes(local, digest, digest) {
            downloads += 1
            error("Exact canonical bytes must not be downloaded again")
        }

        assertArrayEquals(local, canonical)
        assertEquals(0, downloads)
    }

    @Test
    fun canonicalSourceDownloadsAndVerifiesMergedResult() {
        val local = "phone edit\n".toByteArray()
        val merged = "merged edit\n".toByteArray()
        val mergedDigest = sha256(merged)
        var requested = ""

        val canonical = verifiedCanonicalSourceBytes(local, mergedDigest, mergedDigest) { digest ->
            requested = digest
            merged
        }

        assertArrayEquals(merged, canonical)
        assertEquals(mergedDigest, requested)
    }

    @Test
    fun workspaceStyleMutationEscalatesAndKeepsGlobalSyncScope() {
        assertNull(
            effectiveSourceSyncTarget(
                requestedTargetPdfFilename = "template.pdf",
                hasWorkspaceStyleMutation = true,
                projectMemberPdfFilenames = emptySet(),
            ),
        )
        assertNull(
            effectiveSourceSyncTarget(
                requestedTargetPdfFilename = "template.pdf",
                hasWorkspaceStyleMutation = false,
                projectMemberPdfFilenames = emptySet(),
                preserveGlobalOperationScope = true,
            ),
        )
        assertEquals(
            "template.pdf",
            effectiveSourceSyncTarget(
                requestedTargetPdfFilename = "template.pdf",
                hasWorkspaceStyleMutation = false,
                projectMemberPdfFilenames = setOf("chapter.pdf"),
            ),
        )
    }

    @Test
    fun projectReadingMemberSyncsTheWholeLibrary() {
        assertNull(
            effectiveSourceSyncTarget(
                requestedTargetPdfFilename = "chapter.pdf",
                hasWorkspaceStyleMutation = false,
                projectMemberPdfFilenames = setOf("chapter.pdf"),
            ),
        )
        // Membership that could not be read must not risk a partial project update.
        assertNull(
            effectiveSourceSyncTarget(
                requestedTargetPdfFilename = "template.pdf",
                hasWorkspaceStyleMutation = false,
                projectMemberPdfFilenames = null,
            ),
        )
    }

    @Test
    fun commentDeltaDoesNotAdvanceLastSuccessWhileTexConflictNeedsAttention() {
        val previousSuccess = 4_000L
        val conflict = PendingSourceMutation(
            requestId = "3daaf7df-2e77-47cf-8e49-51ab5a761191",
            sourceFile = SOURCE_PATH,
            pdfFilename = "template.pdf",
            state = SourceMutationState.CONFLICT,
            error = "TeX conflict in $SOURCE_PATH",
            conflictId = "conflict-1",
        )

        val updated = deltaSyncHealthAfterSourceCheck(
            current = ThoughtsSyncStateEntity(lastSyncSuccessEpochMillis = previousSuccess),
            completedAtEpochMillis = 9_000L,
            blockedOperationCount = 2,
            sourceAttention = listOf(conflict),
        )

        assertEquals(previousSuccess, updated.lastSyncSuccessEpochMillis)
        assertEquals("source_attention", updated.lastSyncErrorCode)
        assertEquals(2, updated.blockedOperationCount)
        assertEquals("$SOURCE_PATH: TeX conflict in $SOURCE_PATH", updated.lastSyncErrorMessage)
    }

    @Test
    fun commentDeltaAdvancesLastSuccessAfterSourceAttentionIsClear() {
        val updated = deltaSyncHealthAfterSourceCheck(
            current = ThoughtsSyncStateEntity(
                lastSyncSuccessEpochMillis = 4_000L,
                lastSyncErrorCode = "source_attention",
                lastSyncErrorMessage = "old conflict",
            ),
            completedAtEpochMillis = 9_000L,
            blockedOperationCount = 0,
            sourceAttention = emptyList(),
        )

        assertEquals(9_000L, updated.lastSyncSuccessEpochMillis)
        assertEquals("", updated.lastSyncErrorCode)
        assertEquals("", updated.lastSyncErrorMessage)
    }

    @Test
    fun partialCompilerFailureDoesNotAdvanceLastSuccessOrLoseExactDetail() {
        val previousSuccess = 4_000L
        val failure = TerminalPdfBuildFailure(
            buildId = "aggregate-build",
            status = TerminalPdfBuildStatus.FAILED,
            detail = "course-notes.pdf: Undefined control sequence",
            logReference = "server-object:${"a".repeat(64)}",
        )

        val updated = controlSyncHealthAfterResult(
            current = ThoughtsSyncStateEntity(lastSyncSuccessEpochMillis = previousSuccess),
            completedAtEpochMillis = 9_000L,
            blockedOperationCount = 0,
            result = ControlSyncResult(
                buildsActive = false,
                artifactPlan = null,
                terminalBuildFailures = listOf(failure),
            ),
        )

        assertEquals(previousSuccess, updated.lastSyncSuccessEpochMillis)
        assertEquals("pdf_build_failed", updated.lastSyncErrorCode)
        assertEquals(0, updated.blockedOperationCount)
        assertTrue(updated.lastSyncErrorMessage.contains("Undefined control sequence"))
        assertTrue(updated.lastSyncErrorMessage.contains("server-object:"))
    }

    @Test
    fun blockedUploadTakesPriorityOverCompilerFailure() {
        val updated = controlSyncHealthAfterResult(
            current = ThoughtsSyncStateEntity(lastSyncSuccessEpochMillis = 4000),
            completedAtEpochMillis = 9000,
            blockedOperationCount = 1,
            result = ControlSyncResult(buildsActive = false, artifactPlan = null,
                terminalBuildFailures = listOf(TerminalPdfBuildFailure(
                    "build", TerminalPdfBuildStatus.FAILED, "compiler failed"))),
        )
        assertEquals("sync_failed", updated.lastSyncErrorCode)
        assertEquals(4000L, updated.lastSyncSuccessEpochMillis)
        assertEquals(1, updated.blockedOperationCount)
    }

    @Test
    fun missingPhoneSnapshotUsesMatchingCanonicalSourceAsTheApplyBase() {
        val current = serverSnapshot(sha256 = "a".repeat(64), revision = 17)

        val decision = sourceBaseDecision(
            cached = null,
            current = current,
            baseSha256 = current.sha256,
            resolutionId = RESOLUTION_ID,
        ) as SourceBaseDecision.Ready

        assertEquals(current.path, decision.snapshot.path)
        assertEquals(current.revision, decision.snapshot.revision)
        assertEquals(current.sha256, decision.snapshot.sha256)
        assertEquals(current.objectSha256, decision.snapshot.objectSha256)
        assertEquals(RESOLUTION_ID, decision.snapshot.resolutionId)
    }

    @Test
    fun newerCanonicalSourceReturnsStaleInsteadOfUsingPhoneDraftAsCanonical() {
        val current = serverSnapshot(sha256 = "b".repeat(64), revision = 18)

        val decision = sourceBaseDecision(
            cached = null,
            current = current,
            baseSha256 = "a".repeat(64),
            resolutionId = RESOLUTION_ID,
        ) as SourceBaseDecision.Stale

        assertSame(current, decision.snapshot)
    }

    @Test
    fun matchingCachedSnapshotAvoidsASecondCanonicalLookupDecision() {
        val cached = ThoughtsSourceSnapshotEntity(
            path = SOURCE_PATH,
            revision = 16,
            sha256 = "a".repeat(64),
            objectSha256 = "c".repeat(64),
            resolutionId = RESOLUTION_ID,
        )

        val decision = sourceBaseDecision(
            cached = cached,
            current = serverSnapshot(sha256 = "b".repeat(64), revision = 18),
            baseSha256 = cached.sha256,
            resolutionId = RESOLUTION_ID,
        ) as SourceBaseDecision.Ready

        assertSame(cached, decision.snapshot)
    }

    @Test
    fun verifiedVersionTwoBaseCanBeSentForAThreeWayMerge() {
        val base = "phone base\n"
        val baseSha = sha256(base.toByteArray(StandardCharsets.UTF_8))
        val current = serverSnapshot(sha256 = "b".repeat(64), revision = 18)

        val mergeBase = staleSourceMergeBase(current, baseSha, base, RESOLUTION_ID)

        assertEquals(current.path, mergeBase?.path)
        assertEquals(current.revision, mergeBase?.revision)
        assertEquals(baseSha, mergeBase?.sha256)
        assertEquals(baseSha, mergeBase?.objectSha256)
        assertEquals(RESOLUTION_ID, mergeBase?.resolutionId)
    }

    @Test
    fun unverifiedStaleBaseCannotBeSentForAMerge() {
        assertNull(
            staleSourceMergeBase(
                serverSnapshot(sha256 = "b".repeat(64), revision = 18),
                "a".repeat(64),
                "contents with a different hash\n",
                RESOLUTION_ID,
            ),
        )
    }

    @Test
    fun focusedSourceOpenResolvesAndDownloadsExactlyOneVerifiedObject() {
        val request = SourceSelectionRequest("template.pdf", "a".repeat(64), 4, 120.5f, 310.25f)
        val contents = "\\section{Phone source}\n".toByteArray(StandardCharsets.UTF_8)
        var resolvedRequest: SourceSelectionRequest? = null
        var downloadedObject: String? = null

        val verified = resolveVerifiedSourceOpen(
            request = request,
            resolve = {
                resolvedRequest = it
                sourceSelectionResult(contents)
            },
            downloadObject = {
                downloadedObject = it
                contents
            },
        )

        assertSame(request, resolvedRequest)
        assertEquals("d".repeat(64), downloadedObject)
        assertEquals(contents.toString(StandardCharsets.UTF_8), verified.contents)
        assertEquals(SOURCE_PATH, verified.resolution.path)
    }

    @Test
    fun focusedSourceOpenRejectsUnverifiedContents() {
        val expected = "expected\n".toByteArray(StandardCharsets.UTF_8)
        val received = "different\n".toByteArray(StandardCharsets.UTF_8)

        assertThrows(IllegalStateException::class.java) {
            resolveVerifiedSourceOpen(
                request = SourceSelectionRequest("template.pdf", "a".repeat(64), 1, 10f, 20f),
                resolve = { sourceSelectionResult(expected) },
                downloadObject = { received },
            )
        }
    }

    @Test
    fun focusedSourceOpenReusesAnExistingReceiptWithoutResolvingAgain() {
        runBlocking {
            var resolveCalls = 0

            val receipt = resolveSourceOpenOnce(
                existingReceipt = { "already delivered" },
                resolve = { resolveCalls += 1 },
            )

            assertEquals("already delivered", receipt)
            assertEquals(0, resolveCalls)
        }
    }

    @Test
    fun focusedSourceOpenRequiresAReceiptAfterResolution() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                resolveSourceOpenOnce<String>(
                    existingReceipt = { null },
                    resolve = {},
                )
            }
        }
    }

    @Test
    fun focusedRecoveryUsesOneUniqueWorkChainPerRequest() {
        val requestId = "3daaf7df-2e77-47cf-8e49-51ab5a761191"

        assertEquals("thoughts-source-open-$requestId", sourceOpenWorkName(requestId))
        assertThrows(IllegalArgumentException::class.java) { sourceOpenWorkName("not-a-request") }
    }

    private fun serverSnapshot(sha256: String, revision: Long) = SourceSnapshot(
        path = SOURCE_PATH,
        revision = revision,
        sha256 = sha256,
        objectSha256 = "d".repeat(64),
    )

    private fun sourceSelectionResult(contents: ByteArray) = SourceSelectionResult(
        path = SOURCE_PATH,
        line = 42,
        column = 1,
        buildId = RESOLUTION_ID,
        revision = 17,
        sha256 = sha256(contents),
        objectSha256 = "d".repeat(64),
    )

    private fun sha256(contents: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(contents)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SOURCE_PATH = "docs-template/tex/source/notes_system.tex"
        val RESOLUTION_ID = "e".repeat(64)
    }
}

// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tbend.thoughts.sdk.RenderPublicationIdentity
import org.tbend.thoughts.sdk.RenderStateDocumentRecord

class PhoneRenderStatusHistoryTest {

    @Test
    fun firstVerifiedTimestampIsRetainedUntilAReplacementRequest() {
        val updated = PhoneRenderJobStatus(
            requestId = "request-a",
            target = "template.pdf",
            stage = PhoneRenderStage.UPDATED,
        )

        val (first, stored) = applyVerifiedTimes(
            PhoneRenderStatusSnapshot(listOf(updated)),
            emptyMap(),
            nowEpochMillis = 100,
        )
        val (second, retained) = applyVerifiedTimes(
            first,
            stored,
            nowEpochMillis = 200,
        )

        assertEquals(100L, first.primary?.verifiedAtEpochMillis)
        assertEquals(100L, second.primary?.verifiedAtEpochMillis)
        assertEquals(mapOf("request-a" to 100L), retained)
    }

    @Test
    fun newQueuedRequestRemovesPreviousCompletionTimestamp() {
        val queued = PhoneRenderJobStatus(
            requestId = "request-b",
            target = "template.pdf",
            stage = PhoneRenderStage.QUEUED,
        )

        val (snapshot, stored) = applyVerifiedTimes(
            PhoneRenderStatusSnapshot(listOf(queued)),
            mapOf("request-a" to 100L),
            nowEpochMillis = 200,
        )

        assertEquals(PhoneRenderStage.QUEUED, snapshot.primary?.stage)
        assertEquals(0L, snapshot.primary?.verifiedAtEpochMillis)
        assertFalse(stored.containsKey("request-a"))
    }

    @Test
    fun attentionStatusRetainsTheTimeItWasVerifiedOnThePhone() {
        val attention = PhoneRenderJobStatus(
            requestId = "attention",
            target = "template.pdf",
            stage = PhoneRenderStage.ATTENTION,
        )

        val (first, stored) = applyVerifiedTimes(
            PhoneRenderStatusSnapshot(listOf(attention)),
            emptyMap(),
            nowEpochMillis = 123,
        )
        val (second, _) = applyVerifiedTimes(
            PhoneRenderStatusSnapshot(listOf(attention)),
            stored,
            nowEpochMillis = 999,
        )

        assertEquals(123L, first.jobs.single().verifiedAtEpochMillis)
        assertEquals(123L, second.jobs.single().verifiedAtEpochMillis)
    }

    @Test
    fun partialStatusRetainsTheTimeItWasVerifiedOnThePhone() {
        val partial = PhoneRenderJobStatus(
            requestId = "partial",
            target = PhoneRenderQueue.ACTION_ALL,
            stage = PhoneRenderStage.PARTIAL,
        )

        val (first, stored) = applyVerifiedTimes(
            PhoneRenderStatusSnapshot(listOf(partial)),
            emptyMap(),
            nowEpochMillis = 321,
        )
        val (second, _) = applyVerifiedTimes(
            PhoneRenderStatusSnapshot(listOf(partial)),
            stored,
            nowEpochMillis = 999,
        )

        assertEquals(321L, first.jobs.single().verifiedAtEpochMillis)
        assertEquals(321L, second.jobs.single().verifiedAtEpochMillis)
    }

    @Test
    fun explicitOutOfSyncStatusIsVisibleButDoesNotOverridePhoneOwnedWork() {
        val phoneOwned = PhoneRenderJobStatus(
            requestId = "phone-request",
            target = "paper.pdf",
            stage = PhoneRenderStage.QUEUED,
            createdAtEpochMillis = 10,
        )
        val merged = mergeExplicitPdfRenderStatuses(
            PhoneRenderStatusSnapshot(listOf(phoneOwned)),
            listOf(
                ExplicitPdfRenderStatus(
                    filename = "paper.pdf",
                    status = "out_of_sync",
                    checkedAtEpochMillis = 100,
                )
            ),
        )

        assertEquals(PhoneRenderStage.QUEUED, merged.statusForPdf("paper.pdf")?.stage)
        assertTrue(merged.jobs.any { it.stage == PhoneRenderStage.OUT_OF_SYNC })
    }

    @Test
    fun explicitStatusHasPriorityOverLibraryHeadFallback() {
        val libraryFallback = PhoneRenderJobStatus(
            requestId = "remote-library:build-old",
            target = "paper.pdf",
            stage = PhoneRenderStage.FAILED,
            completedAtEpochMillis = 500,
        )
        val merged = mergeExplicitPdfRenderStatuses(
            PhoneRenderStatusSnapshot(listOf(libraryFallback)),
            listOf(
                ExplicitPdfRenderStatus(
                    filename = "paper.pdf",
                    status = "synced",
                    publicationSha256 = "a".repeat(64),
                    checkedAtEpochMillis = 100,
                )
            ),
            SourceLinkedPdfIndexSnapshot(
                libraryPdfSha256ByFilename = mapOf("paper.pdf" to "a".repeat(64)),
                activatedPdfSha256ByFilename = mapOf("paper.pdf" to "a".repeat(64)),
                libraryAvailable = true,
                generationCurrent = true,
            ),
        )

        assertEquals(PhoneRenderStage.UPDATED, merged.statusForPdf("paper.pdf")?.stage)
    }

    @Test
    fun syncedServerStatusIsPendingUntilThisPhoneHasThePublishedHash() {
        val explicit = ExplicitPdfRenderStatus(
            filename = "paper.pdf",
            status = "synced",
            publicationSha256 = "b".repeat(64),
            publicationSizeBytes = 123,
            publicationBuildId = "build-new",
            checkedAtEpochMillis = 100,
        )

        val pending = mergeExplicitPdfRenderStatuses(
            PhoneRenderStatusSnapshot.EMPTY,
            listOf(explicit),
            SourceLinkedPdfIndexSnapshot(
                libraryPdfSha256ByFilename = mapOf("paper.pdf" to "a".repeat(64)),
                activatedPdfSha256ByFilename = mapOf("paper.pdf" to "a".repeat(64)),
                libraryAvailable = true,
                generationCurrent = true,
            ),
        )
        val current = mergeExplicitPdfRenderStatuses(
            PhoneRenderStatusSnapshot.EMPTY,
            listOf(explicit),
            SourceLinkedPdfIndexSnapshot(
                libraryPdfSha256ByFilename = mapOf("paper.pdf" to "b".repeat(64)),
                activatedPdfSha256ByFilename = mapOf("paper.pdf" to "b".repeat(64)),
                libraryAvailable = true,
                generationCurrent = true,
            ),
        )

        assertEquals(PhoneRenderStage.SYNC_PENDING, pending.statusForPdf("paper.pdf")?.stage)
        assertEquals(PhoneRenderStage.UPDATED, current.statusForPdf("paper.pdf")?.stage)
    }

    @Test
    fun aggregateDocumentStatusesPreserveExactPublicationIdentity() {
        val statuses = requireNotNull(
            renderStateDocumentStatuses(
                listOf(
                    RenderStateDocumentRecord(
                        filename = "paper.pdf",
                        status = "out_of_sync",
                        reason = "Published source files changed.",
                        buildId = "build-old",
                        lastSuccessfulBuildAt = "2026-08-19T12:00:00Z",
                        publication = RenderPublicationIdentity(
                            filename = "paper.pdf",
                            sha256 = "a".repeat(64),
                            sizeBytes = 42,
                            buildId = "build-old",
                            sourceFrontier = 10,
                            publishedAt = "2026-08-19T12:00:00Z",
                        ),
                    ),
                ),
                checkedAtEpochMillis = 100,
            ),
        )

        assertEquals("out_of_sync", statuses.single().status)
        assertEquals("build-old", statuses.single().buildId)
        assertEquals("a".repeat(64), statuses.single().publicationSha256)
        assertEquals(42L, statuses.single().publicationSizeBytes)
        assertEquals("build-old", statuses.single().publicationBuildId)
        assertEquals(1_787_140_800_000L, statuses.single().lastSuccessfulBuildAtEpochMillis)
    }

    @Test
    fun malformedAggregateDocumentInventoryIsRejectedWithoutReplacement() {
        assertNull(
            renderStateDocumentStatuses(
                listOf(
                    RenderStateDocumentRecord("paper.pdf", "synced"),
                    RenderStateDocumentRecord("paper.pdf", "out_of_sync"),
                ),
                checkedAtEpochMillis = 100,
            ),
        )
    }
}

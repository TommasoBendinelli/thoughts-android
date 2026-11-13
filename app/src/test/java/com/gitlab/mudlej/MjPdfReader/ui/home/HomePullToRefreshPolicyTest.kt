// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.render.ExplicitPdfRenderStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.pendingSourceRenderStatuses
import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePullToRefreshPolicyTest {

    @Test
    fun terminalSyncKeepsPendingStateOnTopAndFailureHistoryBelow() {
        val expected = HomeSection.FedoraStatus(
            label = "No pending changes",
            timestamp = "Last sync failed: Aug 30, 2026 at 10:25 PM",
            hasSyncFailure = true,
        )

        assertEquals(
            expected,
            terminalLibrarySyncStatus(
                SyncOperationStore.STATUS_ERROR,
                expected.label,
                expected.timestamp,
            ),
        )
        assertEquals(
            expected,
            terminalLibrarySyncStatus(
                SyncOperationStore.STATUS_ATTENTION,
                expected.label,
                expected.timestamp,
            ),
        )
        assertNull(
            terminalLibrarySyncStatus(
                SyncOperationStore.STATUS_SYNCED,
                expected.label,
                expected.timestamp,
            ),
        )
    }

    @Test
    fun pullSyncsEveryTabButReloadsTheAbTestingPage() {
        HomeTab.entries.forEach { tab ->
            val expected = if (tab == HomeTab.AB_TESTING) PullToRefreshAction.RELOAD_PAGE else PullToRefreshAction.SYNC
            assertEquals(expected, pullToRefreshAction(tab))
        }
    }

    @Test
    fun theAbTestingTabIsShownOnlyWhenTheBuildNamesItsPage() {
        assertEquals(HomeTab.entries, visibleHomeTabs(abTestingConfigured = true))
        val withoutPage = visibleHomeTabs(abTestingConfigured = false)
        assertEquals(listOf(HomeTab.DIARY, HomeTab.NOTES, HomeTab.LIBRARY, HomeTab.REVIEW), withoutPage)
        // Pager positions are ordinals, so only the last tab may be left out.
        withoutPage.forEachIndexed { position, tab -> assertEquals(position, tab.ordinal) }
        // A saved A/B tab opens Diary in a build without the page; any shown tab is kept.
        assertEquals(HomeTab.DIARY, initialHomeTab(HomeTab.AB_TESTING, withoutPage))
        assertEquals(HomeTab.REVIEW, initialHomeTab(HomeTab.REVIEW, withoutPage))
        assertEquals(HomeTab.AB_TESTING, initialHomeTab(HomeTab.AB_TESTING, HomeTab.entries))
        assertNull(AbTestingTabController.pageHost(""))
        assertNull(AbTestingTabController.pageHost("http://pilot.example.org:8445/"))
        assertEquals("pilot.example.org", AbTestingTabController.pageHost("https://Pilot.Example.org:8445/"))
    }

    @Test
    fun cachedFedoraSummaryUsesLocalOperationStateBeforeTheServerSnapshot() {
        assertEquals(
            CachedFedoraStatusCode.SYNC_NEEDED,
            cachedFedoraStatusCode(PhoneRenderStage.SOURCE_SYNC_NEEDED, "current"),
        )
        assertEquals(
            CachedFedoraStatusCode.SYNCING,
            cachedFedoraStatusCode(PhoneRenderStage.REBUILDING, "changed"),
        )
    }

    @Test
    fun cachedFedoraSummaryFallsBackToThePersistedGlobalSnapshot() {
        assertEquals(
            CachedFedoraStatusCode.SYNCED,
            cachedFedoraStatusCode(null, "current"),
        )
        assertEquals(
            CachedFedoraStatusCode.SYNC_NEEDED,
            cachedFedoraStatusCode(null, "changed"),
        )
        assertEquals(
            CachedFedoraStatusCode.SYNC_NEEDED,
            cachedFedoraStatusCode(null, null),
        )
    }

    @Test
    fun aggregateSummaryCountsTheHighestPriorityPdfState() {
        val remote = (1..16).map {
            ExplicitPdfRenderStatus("stale-$it.pdf", "out_of_sync", checkedAtEpochMillis = 100)
        } + ExplicitPdfRenderStatus(
            "current.pdf",
            "synced",
            publicationSha256 = "a".repeat(64),
            checkedAtEpochMillis = 100,
        )

        val obsoleteLocalJob = PhoneRenderJobStatus(
            requestId = "obsolete:historical.pdf",
            target = "historical.pdf",
            stage = PhoneRenderStage.REBUILDING,
        )
        val summary = cachedFedoraStatusSummary(
            PhoneRenderStatusSnapshot(listOf(obsoleteLocalJob)),
            remote,
            "changed",
        )

        assertEquals(CachedFedoraStatusCode.SYNC_NEEDED, summary.code)
        assertEquals(16, summary.count)
        assertEquals(17, summary.total)
        assertEquals(16, summary.counts[CachedFedoraStatusCode.SYNC_NEEDED])
        assertEquals(1, summary.counts[CachedFedoraStatusCode.SYNCED])
    }

    @Test
    fun aggregateSummarySaysEveryCurrentPdfIsUpToDate() {
        val remote = (1..19).map {
            ExplicitPdfRenderStatus(
                "current-$it.pdf",
                "synced",
                publicationSha256 = "a".repeat(64),
                checkedAtEpochMillis = 100,
            )
        }

        val summary = cachedFedoraStatusSummary(
            PhoneRenderStatusSnapshot.EMPTY,
            remote,
            "current",
        )

        assertEquals(CachedFedoraStatusCode.SYNCED, summary.code)
        assertEquals(19, summary.count)
    }

    @Test
    fun localAndServerChangesShareOneSyncNeededSummary() {
        val remote = listOf(
            ExplicitPdfRenderStatus("edited.pdf", "out_of_sync", checkedAtEpochMillis = 100),
            ExplicitPdfRenderStatus("stale.pdf", "out_of_sync", checkedAtEpochMillis = 100),
        )
        val local = PhoneRenderJobStatus(
            requestId = "local-source:edited.pdf",
            target = "edited.pdf",
            stage = PhoneRenderStage.SOURCE_SYNC_NEEDED,
        )

        val summary = cachedFedoraStatusSummary(
            PhoneRenderStatusSnapshot(listOf(local)),
            remote,
            "changed",
        )

        assertEquals(CachedFedoraStatusCode.SYNC_NEEDED, summary.code)
        assertEquals(setOf("edited.pdf", "stale.pdf"), summary.filenames)
        assertEquals(2, summary.count)
    }

    @Test
    fun libraryIdleStatusCountsOnlyUniqueFilesWithPhoneChanges() {
        val snapshot = PhoneRenderStatusSnapshot(
            listOf(
                PhoneRenderJobStatus(
                    requestId = "remote:current.pdf",
                    target = "current.pdf",
                    stage = PhoneRenderStage.UPDATED,
                ),
                PhoneRenderJobStatus(
                    requestId = "local-sync:edited.pdf",
                    target = "edited.pdf",
                    stage = PhoneRenderStage.SYNC_NEEDED,
                ),
                PhoneRenderJobStatus(
                    requestId = "local-source:edited.pdf",
                    target = "edited.pdf",
                    stage = PhoneRenderStage.SOURCE_SYNC_NEEDED,
                ),
                PhoneRenderJobStatus(
                    requestId = "active-sync:operation",
                    target = "review.pdf",
                    stage = PhoneRenderStage.WAITING_FOR_SYNC,
                ),
            ),
        )

        assertEquals(
            listOf(
                PendingPhoneSyncFile("edited.pdf", emptyList()),
                PendingPhoneSyncFile("review.pdf", emptyList()),
            ),
            pendingPhoneSyncFiles(snapshot),
        )
        assertEquals(setOf("edited.pdf", "review.pdf"), pendingPhoneSyncFilenames(snapshot))
        assertEquals(2, pendingPhoneSyncFileCount(snapshot))
        assertTrue(pendingPhoneSyncFilenames(PhoneRenderStatusSnapshot.EMPTY).isEmpty())
        assertEquals(0, pendingPhoneSyncFileCount(PhoneRenderStatusSnapshot.EMPTY))
    }

    @Test
    fun localChangeDetailsAreGroupedPerFileAndDeduplicated() {
        val snapshot = PhoneRenderStatusSnapshot(
            listOf(
                PhoneRenderJobStatus(
                    requestId = "local-sync:paper.pdf",
                    target = "paper.pdf",
                    stage = PhoneRenderStage.SYNC_NEEDED,
                    message = "2 unsynced comment changes\n1 unsynced review card",
                ),
                PhoneRenderJobStatus(
                    requestId = "local-source:paper.pdf",
                    target = "paper.pdf",
                    stage = PhoneRenderStage.SOURCE_SYNC_NEEDED,
                    message = "1 unsynced review card\nA local TeX change has not been synced",
                ),
            ),
        )

        assertEquals(
            listOf(
                PendingPhoneSyncFile(
                    "paper.pdf",
                    listOf(
                        "2 unsynced comment changes",
                        "1 unsynced review card",
                        "A local TeX change has not been synced",
                    ),
                ),
            ),
            pendingPhoneSyncFiles(snapshot),
        )
        assertEquals(1, pendingPhoneSyncFileCount(snapshot))
    }

    @Test
    fun durableSourceProblemsStayVisibleBeforeAnotherSyncWithCorrectActions() {
        val conflict = PendingSourceMutation(
            requestId = "conflict-request",
            sourceFile = "docs-paper/tex/source/conflicted.tex",
            pdfFilename = "paper.pdf",
            state = SourceMutationState.CONFLICT,
            error = "TeX conflict in docs-paper/tex/source/conflicted.tex",
            rawError = "Fedora preserved two conflicting hunks.",
            conflictId = "conflict-1",
        )
        val legacyFailure = PendingSourceMutation(
            requestId = "legacy-failure",
            sourceFile = "docs-paper/tex/source/orphaned.tex",
            pdfFilename = "paper.pdf",
            state = SourceMutationState.FAILED,
            error = "The server did not confirm whether this TeX change was applied. Sync needs attention.",
            rawError = "{\"detail\":\"\"}",
        )
        val statuses = pendingSourceRenderStatuses(
            mutations = listOf(conflict, legacyFailure),
            pendingMessage = "A local TeX change has not been synced to the server.",
        )
        val snapshot = PhoneRenderStatusSnapshot(statuses)

        assertTrue(statuses.all { it.stage == PhoneRenderStage.BLOCKED })
        assertTrue(pendingPhoneSyncNeedsAttention(snapshot))
        assertEquals(
            listOf(
                PendingPhoneSyncFile(
                    "paper.pdf",
                    listOf(
                        "TeX conflict in docs-paper/tex/source/conflicted.tex",
                        "Fedora preserved two conflicting hunks.",
                        "TeX change failed in docs-paper/tex/source/orphaned.tex",
                        "The server did not confirm whether this TeX change was applied. Sync needs attention.",
                    ),
                ),
            ),
            pendingPhoneSyncFiles(snapshot),
        )
        assertEquals(
            SourceAttentionActions(resolveConflicts = true, retryFailures = true),
            sourceAttentionActions(listOf(conflict, legacyFailure)),
        )
    }

    @Test
    fun sharedSourceMutationFansOutToEveryConsumingPdfStatus() {
        val mutation = PendingSourceMutation(
            requestId = "shared-style-request",
            sourceFile = "docs-paper/tex/support/manual_style.sty",
            pdfFilename = "alpha.pdf",
            pdfFilenames = listOf("alpha.pdf", "beta.pdf"),
            state = SourceMutationState.QUEUED,
        )

        val all = pendingSourceRenderStatuses(
            mutations = listOf(mutation),
            pendingMessage = "A local style change has not been synced to the server.",
        )
        val beta = pendingSourceRenderStatuses(
            mutations = listOf(mutation),
            target = "beta.pdf",
            pendingMessage = "A local style change has not been synced to the server.",
        )

        assertEquals(listOf("alpha.pdf", "beta.pdf"), all.map { it.target })
        assertEquals(listOf("beta.pdf"), beta.map { it.target })
        assertEquals(2, pendingPhoneSyncFileCount(PhoneRenderStatusSnapshot(all)))
    }

    @Test
    fun proposalDecisionAppearsInLibraryPendingChanges() {
        val snapshot = PhoneRenderStatusSnapshot(
            listOf(
                PhoneRenderJobStatus(
                    requestId = "local-sync:paper.pdf",
                    target = "paper.pdf",
                    stage = PhoneRenderStage.SYNC_NEEDED,
                    message = "Reject 1 agent proposal",
                ),
            ),
        )

        assertEquals(
            listOf(
                PendingPhoneSyncFile(
                    "paper.pdf",
                    listOf("Reject 1 agent proposal"),
                ),
            ),
            pendingPhoneSyncFiles(snapshot),
        )
    }

    @Test
    fun pendingChangeWithoutAnActivePdfStillAppearsInDetails() {
        val snapshot = PhoneRenderStatusSnapshot(
            listOf(
                PhoneRenderJobStatus(
                    requestId = "local-sync:other",
                    target = "Other changes",
                    stage = PhoneRenderStage.SYNC_NEEDED,
                    message = "1 pending review event",
                ),
            ),
        )

        assertEquals(
            listOf(PendingPhoneSyncFile("Other changes", listOf("1 pending review event"))),
            pendingPhoneSyncFiles(snapshot),
        )
    }

    @Test
    fun builtTimeUsesSecondsMinutesHoursThenAnExactDate() {
        val now = 100_000_000L
        assertEquals(BuiltTimeKind.JUST_NOW, builtTimePresentation(now, now).kind)
        assertEquals(BuiltTimePresentation(BuiltTimeKind.SECONDS, 59), builtTimePresentation(now - 59_000, now))
        assertEquals(BuiltTimePresentation(BuiltTimeKind.MINUTES, 1), builtTimePresentation(now - 60_000, now))
        assertEquals(BuiltTimePresentation(BuiltTimeKind.HOURS, 1), builtTimePresentation(now - 3_600_000, now))
        assertEquals(BuiltTimeKind.EXACT, builtTimePresentation(now - 86_400_000, now).kind)
    }

    @Test
    fun syncElapsedTimeCountsWholeSecondsAndClampsClockChanges() {
        assertEquals(0L, syncElapsedSeconds(10_000, 9_000))
        assertEquals(0L, syncElapsedSeconds(10_000, 10_999))
        assertEquals(1L, syncElapsedSeconds(10_000, 11_000))
        assertEquals(75L, syncElapsedSeconds(10_000, 85_999))
    }

    @Test
    fun staleDialogUsesSyncWhileAnActiveBuildHasNoSecondAction() {
        val buildRequired = PhoneRenderJobStatus(
            requestId = "cached-fedora:all",
            target = "all",
            stage = PhoneRenderStage.OUT_OF_SYNC,
        )
        val current = buildRequired.copy(stage = PhoneRenderStage.UPDATED)
        val building = buildRequired.copy(stage = PhoneRenderStage.REBUILDING)

        assertEquals(DocumentStatusAction.SYNC_NOW, syncDialogPrimaryAction(buildRequired))
        assertEquals(DocumentStatusAction.SYNC_NOW, syncDialogPrimaryAction(current))
        assertEquals(DocumentStatusAction.NONE, syncDialogPrimaryAction(building))
    }
}

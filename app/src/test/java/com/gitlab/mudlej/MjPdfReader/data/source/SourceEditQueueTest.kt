// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSourceRequestEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.planDeviceSequenceReconciliation
import com.gitlab.mudlej.MjPdfReader.data.sync.prepareSourceMutationsForDeviceSequenceReconciliation
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class SourceEditQueueTest {
    private lateinit var root: File
    private lateinit var requests: File
    private lateinit var receipts: File
    private lateinit var cache: File
    private lateinit var scans: MutableList<String>
    private lateinit var syncTriggers: MutableList<String>
    private lateinit var queue: SourceEditQueue

    @Test
    fun markdownSavePreservesMarkersAndWaitsForExplicitSync() {
        val base = "# Café\r\n\r\n<!-- thoughts:v1:point comment:c1:s1 -->\r\nPassage.\r\n"
        val edited = "New paragraph.\r\n\r\n" + base
        val source = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191").copy(
            sourceFile = "docs-template/markdown/source/main.md", sourceSha256 = sha256(base),
            contents = base, bundleId = "c".repeat(64), baseContents = base,
            bundlePdfFilename = "template.pdf", bundlePdfSha256 = "b".repeat(64),
        )
        val id = queue.saveForNextSync(source, "template.pdf", "b".repeat(64), edited)
        assertEquals(source.sourceFile, request(id)["source_file"].asString)
        assertEquals(edited, request(id)["contents"].asString)
        assertEquals(base, request(id)["base_contents"].asString)
        assertEquals(SourceMutationState.QUEUED, restartedQueue().pendingSourceMutations().single().state)
        assertTrue(syncTriggers.isEmpty())
    }

    @Test
    fun saveFromAnEditorOpenedBeforeTheDefaultFolderMigrationTargetsTheRenamedFile() {
        val base = "# Notes\n"
        val legacy = ".thoughts/projects/${"a".repeat(64)}/documents/notes.md"
        val source = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191").copy(
            sourceFile = legacy, sourceSha256 = sha256(base),
            contents = base, bundleId = "c".repeat(64), baseContents = base,
            bundlePdfFilename = "notes.pdf", bundlePdfSha256 = "b".repeat(64),
        )
        val before = DefaultFolder.localStateMigrated
        DefaultFolder.localStateMigrated = true
        try {
            val id = queue.saveForNextSync(source, "notes.pdf", "b".repeat(64), "# Notes, edited\n")
            assertEquals(".thoughts/projects/${"a".repeat(64)}/default/notes.md", request(id)["source_file"].asString)
            assertFalse(DefaultFolder.containsLegacy(request(id).toString()))
        } finally {
            DefaultFolder.localStateMigrated = before
        }
    }

    @Test
    fun appliedSharedStyleKeepsGlobalRefreshMarkerUntilArtifactActivation() {
        val base = "shared style base\n"
        val edited = "shared style edited\n"
        val snapshot = SourceSnapshot(
            resolutionId = "3daaf7df-2e77-47cf-8e49-51ab5a761191",
            sourceFile = "docs-paper/tex/support/manual_style.sty",
            sourceLine = 1,
            sourceSha256 = sha256(base),
            contents = base,
            bundleId = "d".repeat(64),
            baseContents = base,
            bundlePdfFilename = "alpha.pdf",
            bundlePdfSha256 = "a".repeat(64),
            bundlePdfFilenames = listOf("alpha.pdf", "beta.pdf"),
        )
        val requestId = queue.saveForNextSync(
            snapshot,
            "alpha.pdf",
            "a".repeat(64),
            edited,
        )
        val canonical = snapshot.copy(
            sourceSha256 = sha256(edited),
            contents = edited,
        )
        writeReceipt(
            requestId,
            SourceEditQueue.ACTION_SAVE,
            SourceEditQueue.STATUS_SUCCESS,
            canonical,
            draftRevision = 1,
            applied = true,
        )

        assertEquals(1, queue.recoverDeliveredRequests())
        assertTrue(queue.pendingSourceMutations().isEmpty())
        assertTrue(queue.requiresGlobalWorkspaceStyleRefresh())

        queue.clearGlobalWorkspaceStyleRefresh()

        assertFalse(queue.requiresGlobalWorkspaceStyleRefresh())
    }

    @Test
    fun workspaceStyleResourceIdentityAcceptsOnlyCanonicalPaths() {
        assertTrue(isWorkspaceStyleResourcePath("docs-paper/tex/support/manual_style.sty"))
        assertTrue(
            isWorkspaceStyleResourcePath(
                "docs-course-notes/tex/support/manual_style_override.sty",
            ),
        )
        assertFalse(isWorkspaceStyleResourcePath("docs-/tex/support/manual_style_override.sty"))
        assertFalse(isWorkspaceStyleResourcePath("../tools/latex/manual_style.sty"))
        assertFalse(
            isWorkspaceStyleResourcePath(
                "docs-paper/tex/source/manual_style_override.sty",
            ),
        )
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("source-edit-queue").toFile()
        requests = File(root, "phone-to-mac/docs-source-requests")
        receipts = File(root, "mac-to-phone/docs-source-receipts")
        cache = File(root, "private-cache")
        scans = mutableListOf()
        syncTriggers = mutableListOf()
        queue = SourceEditQueue(
            requests,
            receipts,
            cache,
            scans::add,
            {},
            null,
        ) { syncTriggers += "scheduled" }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun openRequestCarriesExactPdfSelectionAndTriggersScan() {
        val selection = selection()

        val handle = queue.enqueueOpen(selection)

        val payload = request(handle.requestId)
        assertEquals(1, payload["schema_version"].asInt)
        assertEquals("open", payload["action"].asString)
        assertEquals("template.pdf", payload["pdf_filename"].asString)
        assertEquals(4, payload["page"].asInt)
        assertEquals(120.5f, payload["x"].asFloat)
        assertEquals(310.25f, payload["y"].asFloat)
        assertEquals("selected passage", payload["selected_text"].asString)
        assertEquals(listOf("docs-source-requests/${handle.requestId}.json"), scans)
        assertEquals(listOf("scheduled"), syncTriggers)
    }

    @Test
    fun successfulReceiptCanBeCachedForOfflineOpening() {
        val handle = queue.enqueueOpen(selection())
        val snapshot = snapshot(handle.requestId)
        writeReceipt(handle.requestId, "open", "success", snapshot)

        val receipt = queue.receipt(handle.requestId)

        assertNotNull(receipt)
        assertEquals(snapshot, receipt?.snapshot)
        queue.cacheSnapshot(handle.cacheKey, snapshot)
        assertEquals(snapshot, queue.cachedSnapshot(handle.cacheKey))
    }

    @Test
    fun cachedPdfOpenRetainsBuildSourceSeparatelyFromCurrentSource() {
        val handle = queue.enqueueOpen(selection())
        val snapshot = snapshot(handle.requestId).copy(navigationBaseContents = "Original built source.\n", navigationContext = "Original PDF heading.")
        queue.cacheSnapshot(handle.cacheKey, snapshot)
        assertEquals(snapshot, queue.cachedSnapshot(handle.cacheKey))
        val file = File(cache, "snapshots/${handle.cacheKey}.json")
        val payload = JsonParser.parseString(file.readText()).asJsonObject
        File(cache, "navigation-bases/${payload.get("navigation_base_object").asString}.tex").writeText("Corrupt historical bytes.")
        assertNull(queue.cachedSnapshot(handle.cacheKey)?.navigationBaseContents)
    }

    @Test
    fun saveReceiptCarriesRebuiltPdfIdentityForTheNextSave() {
        val requestId = "3daaf7df-2e77-47cf-8e49-51ab5a761191"
        val rebuiltSha = "d".repeat(64)
        writeReceipt(requestId, "save", "success", snapshot(requestId), rebuiltSha)

        assertEquals(rebuiltSha, queue.receipt(requestId)?.publishedPdfSha256)
    }

    @Test
    fun fedoraBuildHashIsAcceptedAsASourceResolutionIdentity() {
        val requestId = "3daaf7df-2e77-47cf-8e49-51ab5a761191"
        val serverSnapshot = snapshot("e".repeat(64))
        writeReceipt(requestId, "open", "success", serverSnapshot)

        assertEquals(serverSnapshot, queue.receipt(requestId)?.snapshot)
    }

    @Test
    fun pdfBottomLeftCoordinatesAreConvertedForReverseSyncTex() {
        val point = reverseSyncTexPoint(
            left = 100f,
            top = 620f,
            right = 140f,
            bottom = 600f,
            pageHeight = 792f,
        )

        assertEquals(120f, point?.x)
        assertEquals(182f, point?.y)
    }

    @Test
    fun manualSaveUsesResolutionAndBaseHashWithoutStartingSync() {
        val snapshot = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")

        val requestId = queue.saveForNextSync(snapshot, "template.pdf", "b".repeat(64), "changed\n")

        val payload = request(requestId)
        assertEquals("save", payload["action"].asString)
        assertEquals(snapshot.resolutionId, payload["resolution_id"].asString)
        assertEquals(snapshot.sourceFile, payload["source_file"].asString)
        assertEquals(snapshot.sourceLine, payload["source_line"].asInt)
        assertEquals(snapshot.sourceSha256, payload["base_sha256"].asString)
        assertEquals("changed\n", payload["contents"].asString)
        assertFalse(payload["build_after_sync"].asBoolean)
        assertTrue(File(cache, "drafts").listFiles().orEmpty().isNotEmpty())
        assertTrue(queue.hasPendingSave())
        assertTrue(syncTriggers.isEmpty())
    }

    @Test
    fun repeatedManualSaveCoalescesBeforeSyncAndKeepsNewestContents() {
        val snapshot = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")

        val firstRequestId = queue.saveForNextSync(
            snapshot,
            "template.pdf",
            "b".repeat(64),
            "first phone edit\n",
        )
        val secondRequestId = queue.saveForNextSync(
            snapshot,
            "template.pdf",
            "b".repeat(64),
            "newest phone edit\n",
        )

        assertEquals(firstRequestId, secondRequestId)
        assertEquals("newest phone edit\n", request(secondRequestId)["contents"].asString)
        assertEquals(2, request(secondRequestId)["draft_revision"].asInt)
        assertEquals(1, requests.listFiles().orEmpty().size)
        assertTrue(syncTriggers.isEmpty())
    }

    @Test
    fun restartReconstructsRegularSaveMissingAfterDurableDraftPublication() {
        val original = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val requestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "preserved regular edit\n",
        )
        val beforeCrash = request(requestId)
        assertTrue(File(requests, "$requestId.json").delete())

        val restarted = restartedQueue()

        assertEquals(0, restarted.recoverDeliveredRequests())
        assertEquals(0, restarted.recoverStrandedMutationRequests())
        val restored = request(requestId)
        assertEquals(beforeCrash["request_id"], restored["request_id"])
        assertEquals(beforeCrash["created_at"], restored["created_at"])
        assertEquals(beforeCrash["contents"], restored["contents"])
        assertTrue(restored["recovered_after_restart"].asBoolean)
        assertEquals(SourceMutationState.QUEUED, restarted.pendingSourceMutations().single().state)
        assertTrue(restarted.hasUndeliveredRequests())
    }

    @Test
    fun restartReconstructsMergeSaveMissingAfterQueuedStateCrashWindow() {
        val base = "base\n"
        val original = SourceSnapshot(
            resolutionId = "3daaf7df-2e77-47cf-8e49-51ab5a761191",
            sourceFile = "docs-template/tex/source/main.tex",
            sourceLine = 42,
            sourceSha256 = sha256(base),
            contents = base,
            bundleId = "d".repeat(64),
            baseContents = base,
            bundlePdfFilename = "template.pdf",
            bundlePdfSha256 = "b".repeat(64),
        )
        val requestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "preserved merge edit\n",
        )
        val beforeCrash = request(requestId)
        assertTrue(File(requests, "$requestId.json").delete())

        val restarted = restartedQueue()

        assertEquals(1, restarted.recoverStrandedMutationRequests())
        val restored = request(requestId)
        assertEquals(beforeCrash["request_id"], restored["request_id"])
        assertEquals(beforeCrash["created_at"], restored["created_at"])
        assertEquals(beforeCrash["base_contents"], restored["base_contents"])
        assertEquals(beforeCrash["contents"], restored["contents"])
        assertEquals(2, restored["schema_version"].asInt)
        assertEquals(SourceMutationState.QUEUED, restarted.pendingSourceMutations().single().state)
    }

    @Test
    fun unreconstructableStrandedDraftBlocksSuccessInsteadOfLookingQueued() {
        val original = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val requestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "preserved edit\n",
        )
        assertTrue(File(requests, "$requestId.json").delete())
        val draftFile = File(cache, "drafts").listFiles().orEmpty().single()
        val damaged = JsonParser.parseString(draftFile.readText()).asJsonObject.apply {
            remove("contents")
        }
        draftFile.writeText(damaged.toString())

        val restarted = restartedQueue()

        assertEquals(0, restarted.recoverStrandedMutationRequests())
        val blocked = restarted.pendingSourceMutations().single()
        assertEquals(SourceMutationState.FAILED, blocked.state)
        assertTrue(blocked.rawError.contains("missing or invalid"))
        assertEquals(listOf(blocked), restarted.blockingSourceMutations())
        assertEquals(0, restarted.prepareFailedMutationsForExplicitSync())
        assertFalse(File(requests, "$requestId.json").exists())
    }

    @Test
    fun saveDuringDeliverySurvivesAsARebasedFollowUpRequest() {
        val original = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val firstRequestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "first phone edit\n",
        )
        queue.markDeliveryStarted(firstRequestId)

        val followUpRequestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "newer phone edit\n",
        )

        assertTrue(followUpRequestId != firstRequestId)
        assertEquals(firstRequestId, request(followUpRequestId)["predecessor_request_id"].asString)
        val accepted = original.copy(
            sourceSha256 = "d".repeat(64),
            contents = "first phone edit\n",
        )
        writeReceipt(
            firstRequestId,
            "save",
            "success",
            accepted,
            draftRevision = 1,
            applied = true,
        )

        assertEquals(1, queue.recoverDeliveredRequests())

        val followUp = request(followUpRequestId)
        assertEquals(accepted.sourceSha256, followUp["base_sha256"].asString)
        assertEquals("newer phone edit\n", followUp["contents"].asString)
        assertFalse(followUp["build_after_sync"].asBoolean)
        assertFalse(followUp.has("predecessor_request_id"))
        assertEquals("newer phone edit\n", queue.localDraftContents(accepted))
        assertFalse(File(requests, "$firstRequestId.json").exists())
        assertTrue(File(requests, "$followUpRequestId.json").isFile)
    }

    @Test
    fun discardRemovesAnUnsentRequestAndRestoresTheCanonicalDraft() {
        val snapshot = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val requestId = queue.saveForNextSync(
            snapshot,
            "template.pdf",
            "b".repeat(64),
            "phone edit\n",
        )

        assertTrue(queue.discardLocalChanges(snapshot))

        assertFalse(File(requests, "$requestId.json").exists())
        assertNull(queue.localDraftContents(snapshot))
        assertFalse(queue.hasPendingSave())
    }

    @Test
    fun discardRefusesAnEditFedoraAlreadyAccepted() {
        val snapshot = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val requestId = queue.saveForNextSync(
            snapshot,
            "template.pdf",
            "b".repeat(64),
            "phone edit\n",
        )
        writeReceipt(requestId, "save", "success", snapshot, applied = true)

        assertFalse(queue.discardLocalChanges(snapshot))

        assertTrue(File(requests, "$requestId.json").isFile)
        assertEquals("phone edit\n", queue.localDraftContents(snapshot))
    }

    @Test
    fun rejectedEditAndItsExactReceiptSurviveANewerSave() {
        val snapshot = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val rejectedRequestId = queue.saveForNextSync(
            snapshot,
            "template.pdf",
            "b".repeat(64),
            "conflicting phone edit\n",
        )
        writeReceipt(rejectedRequestId, "save", "conflict", snapshot)

        assertEquals(SourceEditQueue.STATUS_CONFLICT, queue.localSaveProblem(snapshot)?.status)

        val replacementRequestId = queue.saveForNextSync(
            snapshot,
            "template.pdf",
            "b".repeat(64),
            "resolved phone edit\n",
        )
        assertTrue(replacementRequestId != rejectedRequestId)
        assertTrue(File(requests, "$rejectedRequestId.json").exists())
        assertTrue(File(receipts, "$rejectedRequestId.json").exists())
        assertNull(queue.localSaveProblem(snapshot))
        assertEquals("resolved phone edit\n", request(replacementRequestId)["contents"].asString)
    }

    @Test
    fun buildAfterSyncRequiresExplicitAuthorization() {
        val requestId = queue.enqueueSave(
            snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191"),
            "template.pdf",
            "b".repeat(64),
            "changed\n",
        )

        assertEquals(setOf("template.pdf"), queue.pendingSavePdfFilenames())
        assertEquals(1, queue.authorizeBuildAfterSync("template.pdf"))
        assertTrue(request(requestId)["build_after_sync"].asBoolean)
        assertEquals(0, queue.authorizeBuildAfterSync("template.pdf"))
    }

    @Test
    fun openRequestDoesNotLookLikeAPendingRenderChange() {
        queue.enqueueOpen(selection())

        assertTrue(!queue.hasPendingSave())
    }

    @Test
    fun localDraftDoesNotCreateAnAuthoritativeRequest() {
        val snapshot = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")

        queue.saveLocalDraft(snapshot, "template.pdf", "b".repeat(64), "phone draft\n")

        assertEquals("phone draft\n", queue.localDraftContents(snapshot))
        assertTrue(requests.listFiles().orEmpty().isEmpty())
        assertTrue(scans.isEmpty())
        assertTrue(syncTriggers.isEmpty())
    }

    @Test
    fun offlineSaveCarriesMergeBaseAndCoalescesNewerRevision() {
        val snapshot = SourceSnapshot(
            resolutionId = "3daaf7df-2e77-47cf-8e49-51ab5a761191",
            sourceFile = "docs-template/tex/source/main.tex",
            sourceLine = 42,
            sourceSha256 = sha256("base\n"),
            contents = "base\n",
            bundleId = "d".repeat(64),
            baseContents = "base\n",
            bundlePdfFilename = "template.pdf",
            bundlePdfSha256 = "b".repeat(64),
        )

        val requestId = queue.enqueueSave(snapshot, "template.pdf", "b".repeat(64), "phone one\n")
        val first = request(requestId)
        val secondRequestId = queue.enqueueSave(
            snapshot,
            "template.pdf",
            "b".repeat(64),
            "phone two\n",
        )
        val second = request(secondRequestId)

        assertEquals(requestId, secondRequestId)
        assertEquals(2, first["schema_version"].asInt)
        assertEquals("base\n", first["base_contents"].asString)
        assertEquals(1, first["draft_revision"].asInt)
        assertEquals(2, second["draft_revision"].asInt)
        assertEquals("phone two\n", second["contents"].asString)
        assertFalse(second["build_after_sync"].asBoolean)
    }

    @Test
    fun mergeReceiptKeepsCanonicalIdentityWhileRetainingDraftMergeBase() {
        val base = "base\n"
        val original = SourceSnapshot(
            resolutionId = "3daaf7df-2e77-47cf-8e49-51ab5a761191",
            sourceFile = "docs-template/tex/source/main.tex",
            sourceLine = 42,
            sourceSha256 = sha256(base),
            contents = base,
            bundleId = "d".repeat(64),
            baseContents = base,
            bundlePdfFilename = "template.pdf",
            bundlePdfSha256 = "b".repeat(64),
        )
        val requestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "phone edit\n",
        )
        val canonical = original.copy(
            sourceSha256 = sha256("phone edit\n"),
            contents = "phone edit\n",
        )
        writeReceipt(requestId, "save", "success", canonical, draftRevision = 1)

        val delivered = queue.receipt(requestId)?.snapshot

        assertEquals(canonical.sourceSha256, delivered?.sourceSha256)
        assertEquals(base, delivered?.baseContents)
        assertEquals(original.bundleId, delivered?.bundleId)
    }

    @Test
    fun staleMergeDraftIsRequeuedForServerMergeWithoutLosingContents() {
        val base = "base\n"
        val original = SourceSnapshot(
            resolutionId = "3daaf7df-2e77-47cf-8e49-51ab5a761191",
            sourceFile = "docs-template/tex/source/main.tex",
            sourceLine = 42,
            sourceSha256 = sha256(base),
            contents = base,
            bundleId = "d".repeat(64),
            baseContents = base,
            bundlePdfFilename = "template.pdf",
            bundlePdfSha256 = "b".repeat(64),
        )
        val staleRequestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "preserved phone edit\n",
        )
        val canonical = original.copy(
            sourceSha256 = sha256("newer canonical\n"),
            contents = "newer canonical\n",
        )
        writeReceipt(staleRequestId, "save", "stale", canonical, draftRevision = 1)

        assertEquals(0, queue.recoverDeliveredRequests())
        assertEquals(SourceMutationState.FAILED, queue.pendingSourceMutations().single().state)
        assertEquals(1, queue.prepareFailedMutationsForExplicitSync())

        val recovered = request(staleRequestId)
        assertEquals(2, recovered["schema_version"].asInt)
        assertEquals(original.sourceSha256, recovered["base_sha256"].asString)
        assertEquals(base, recovered["base_contents"].asString)
        assertEquals("preserved phone edit\n", recovered["contents"].asString)
        assertEquals(setOf("template.pdf"), queue.pendingSavePdfFilenames())
        assertEquals("preserved phone edit\n", queue.localDraftContents(original))
        assertNull(queue.localSaveProblem(original))
    }

    @Test
    fun mergeSaveDuringDeliveryRebasesItsSuccessorContentsAndBase() {
        val base = "base\n"
        val original = SourceSnapshot(
            resolutionId = "3daaf7df-2e77-47cf-8e49-51ab5a761191",
            sourceFile = "docs-template/tex/source/main.tex",
            sourceLine = 42,
            sourceSha256 = sha256(base),
            contents = base,
            bundleId = "d".repeat(64),
            baseContents = base,
            bundlePdfFilename = "template.pdf",
            bundlePdfSha256 = "b".repeat(64),
        )
        val firstRequestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "accepted first edit\n",
        )
        queue.markDeliveryStarted(firstRequestId)
        val successorId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "newer phone edit\n",
        )
        val accepted = original.copy(
            sourceSha256 = sha256("accepted first edit\n"),
            contents = "accepted first edit\n",
        )
        writeReceipt(
            firstRequestId,
            "save",
            "success",
            accepted,
            draftRevision = 1,
            applied = true,
        )

        assertEquals(1, queue.recoverDeliveredRequests())

        val successor = request(successorId)
        assertEquals(accepted.sourceSha256, successor["base_sha256"].asString)
        assertEquals(accepted.contents, successor["base_contents"].asString)
        assertEquals("newer phone edit\n", successor["contents"].asString)
        assertFalse(successor.has("predecessor_request_id"))
    }

    @Test
    fun acknowledgingReceiptDeletesTheDeliveredRequestAndTriggersScan() {
        val handle = queue.enqueueOpen(selection())
        val requestFile = File(requests, "${handle.requestId}.json")
        assertTrue(requestFile.isFile)

        queue.acknowledge(handle.requestId)

        assertTrue(!requestFile.exists())
        assertEquals(
            listOf(
                "docs-source-requests/${handle.requestId}.json",
                "docs-source-requests/${handle.requestId}.json",
            ),
            scans,
        )
        assertEquals(listOf("scheduled"), syncTriggers)
    }

    @Test
    fun receiptMakesDurableRequestDeliveredEvenWhenConflictNeedsReview() {
        val handle = queue.enqueueOpen(selection())
        assertTrue(queue.hasUndeliveredRequests())
        writeReceipt(handle.requestId, "open", "conflict", snapshot(handle.requestId))

        assertTrue(!queue.hasUndeliveredRequests())
    }

    @Test
    fun focusedRecoveryListsOnlyUndeliveredOpenRequests() {
        val delivered = queue.enqueueOpen(selection())
        val pending = queue.enqueueOpen(selection().copy(x = 121.5f))
        queue.enqueueSave(snapshot(delivered.requestId), "template.pdf", "b".repeat(64), "changed\n")
        writeReceipt(delivered.requestId, "open", "success", snapshot(delivered.requestId))

        assertEquals(setOf(pending.requestId), queue.pendingOpenRequestIds().toSet())
    }

    @Test
    fun restartRecoveryCachesDeliveredOpenBeforeAcknowledgingIt() {
        val selection = selection()
        val handle = queue.enqueueOpen(selection)
        val snapshot = snapshot(handle.requestId)
        writeReceipt(handle.requestId, "open", "success", snapshot)

        assertEquals(1, queue.recoverDeliveredRequests())

        assertEquals(snapshot, queue.cachedSnapshot(selection.cacheKey()))
        assertTrue(!File(requests, "${handle.requestId}.json").exists())
    }

    @Test
    fun conflictProjectionRetainsRequestDraftAndStructuredServerEvidence() {
        val original = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val requestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "phone incoming\n",
        )
        writeReceipt(
            requestId = requestId,
            action = "save",
            status = "conflict",
            snapshot = original.copy(sourceSha256 = sha256("Fedora current\n")),
            message = "Fedora preserved two conflicting hunks.",
            conflictId = "conflict-1",
            conflictCount = 2,
            expectedRevision = 75,
            currentSha256 = sha256("Fedora current\n"),
            incomingSha256 = sha256("phone incoming\n"),
            mergedObjectSha256 = sha256("merged markers\n"),
        )

        val mutation = queue.pendingSourceMutations().single()

        assertEquals(SourceMutationState.CONFLICT, mutation.state)
        assertEquals("conflict-1", mutation.conflictId)
        assertEquals(2, mutation.conflictCount)
        assertEquals(75L, mutation.expectedRevision)
        assertEquals("TeX conflict in ${original.sourceFile}", mutation.error)
        assertEquals(0, queue.recoverDeliveredRequests())
        assertEquals(setOf("template.pdf"), queue.pendingSavePdfFilenames())
        assertTrue(File(requests, "$requestId.json").isFile)
        assertTrue(File(receipts, "$requestId.json").isFile)
        assertEquals("phone incoming\n", queue.localDraftContents(original))
    }

    @Test
    fun orphanedBlankDetailFailureRetriesSameOperationOnlyOnExplicitSync() {
        val original = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val requestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "preserved phone edit\n",
        )
        writeReceipt(
            requestId,
            "save",
            "failure",
            original,
            message = "{\"detail\":\"\"}",
        )
        assertTrue(File(requests, "$requestId.json").delete())

        val blocked = queue.pendingSourceMutations().single()

        assertEquals(SourceMutationState.FAILED, blocked.state)
        assertEquals("{\"detail\":\"\"}", blocked.rawError)
        assertTrue(blocked.error.contains("did not confirm"))
        assertFalse(File(requests, "$requestId.json").exists())
        assertEquals(setOf("template.pdf"), queue.pendingSavePdfFilenames())

        assertEquals(1, queue.prepareFailedMutationsForExplicitSync())

        assertTrue(File(requests, "$requestId.json").isFile)
        assertEquals(requestId, request(requestId)["request_id"].asString)
        assertTrue(request(requestId)["recovered_from_failure"].asBoolean)
        assertFalse(File(receipts, "$requestId.json").exists())
        assertEquals(1, File(cache, "receipt-history").listFiles().orEmpty().size)
        assertEquals(SourceMutationState.QUEUED, queue.pendingSourceMutations().single().state)
    }

    @Test
    fun explicitLegacyFailureIsRestoredBeforeItsConsumedSequenceIsReconciled() {
        val original = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val requestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "preserved phone edit\n",
        )
        writeReceipt(
            requestId,
            SourceEditQueue.ACTION_SAVE,
            SourceEditQueue.STATUS_FAILURE,
            original,
            message = "{\"detail\":\"\"}",
            draftRevision = 1,
            applied = false,
        )
        assertTrue(File(requests, "$requestId.json").delete())

        val activeRequestIds = prepareSourceMutationsForDeviceSequenceReconciliation(
            queue,
            targetPdfFilename = null,
            retryFailures = true,
        )
        val plan = planDeviceSequenceReconciliation(
            remoteNextDeviceSequence = 173,
            localNextDeviceSequence = 173,
            allOutbox = emptyList(),
            allSourceRequests = listOf(ThoughtsSourceRequestEntity(requestId, 172)),
            activeSourceRequestIds = activeRequestIds,
        )

        assertEquals(setOf(requestId), activeRequestIds)
        assertEquals(requestId, request(requestId)["request_id"].asString)
        assertEquals(listOf(requestId), plan.assignments.map { it.id })
        assertEquals(listOf(173L), plan.assignments.map { it.deviceSequence })
        assertEquals(174L, plan.nextDeviceSequence)

        val accepted = original.copy(
            sourceSha256 = sha256("preserved phone edit\n"),
            contents = "preserved phone edit\n",
        )
        writeReceipt(
            requestId,
            SourceEditQueue.ACTION_SAVE,
            SourceEditQueue.STATUS_SUCCESS,
            accepted,
            draftRevision = 1,
            applied = true,
        )
        assertEquals(1, queue.recoverDeliveredRequests())
        assertTrue(queue.pendingSourceMutations().isEmpty())
        assertTrue(queue.undeliveredRequestIds().isEmpty())

        val settledPlan = planDeviceSequenceReconciliation(
            remoteNextDeviceSequence = 174,
            localNextDeviceSequence = 174,
            allOutbox = emptyList(),
            allSourceRequests = listOf(ThoughtsSourceRequestEntity(requestId, 173)),
            activeSourceRequestIds = queue.undeliveredRequestIds(),
        )
        assertTrue(settledPlan.assignments.isEmpty())
        assertEquals(174L, settledPlan.nextDeviceSequence)
    }

    @Test
    fun successReceiptWithoutAppliedConfirmationRetainsRegularDraftAndRequest() {
        val original = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val requestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            "unconfirmed phone edit\n",
        )
        writeReceipt(
            requestId,
            SourceEditQueue.ACTION_SAVE,
            SourceEditQueue.STATUS_SUCCESS,
            original,
            draftRevision = 1,
            applied = false,
        )

        assertEquals(0, queue.recoverDeliveredRequests())

        assertTrue(File(requests, "$requestId.json").isFile)
        assertTrue(File(receipts, "$requestId.json").isFile)
        assertEquals("unconfirmed phone edit\n", queue.localDraftContents(original))
        assertEquals(SourceMutationState.FAILED, queue.pendingSourceMutations().single().state)
        assertEquals(setOf("template.pdf"), queue.pendingSavePdfFilenames())
    }

    @Test
    fun conflictResolutionIsSavedOfflineAndRejectsEveryDiff3Marker() {
        val bundle = createConflictBundle()

        listOf(
            "<<<<<<< phone\nresolved\n",
            "||||||| shared base\nresolved\n",
            "=======\nresolved\n",
            ">>>>>>> Fedora\nresolved\n",
        ).forEach { unresolved ->
            assertThrows(IllegalArgumentException::class.java) {
                queue.saveConflictResolution(bundle, unresolved)
            }
        }

        val requestId = queue.saveConflictResolution(bundle, "fully resolved\n")
        val request = request(requestId)

        assertEquals("resolve_conflict", request["action"].asString)
        assertEquals(bundle.conflictId, request["conflict_id"].asString)
        assertEquals(bundle.expectedRevision, request["expected_revision"].asLong)
        assertEquals(bundle.currentSha256, request["expected_sha256"].asString)
        assertEquals(sha256("fully resolved\n"), request["content_sha256"].asString)
        assertEquals(SourceMutationState.RESOLUTION_QUEUED, queue.pendingSourceMutations().single().state)
        assertTrue(syncTriggers.isEmpty())
    }

    @Test
    fun restartReconstructsConflictResolutionMissingAfterDurableDraftPublication() {
        val bundle = createConflictBundle()
        val resolutionRequestId = queue.saveConflictResolution(bundle, "fully resolved\n")
        val beforeCrash = request(resolutionRequestId)
        assertTrue(File(requests, "$resolutionRequestId.json").delete())

        val restarted = restartedQueue()

        assertEquals(1, restarted.recoverStrandedMutationRequests())
        val restored = request(resolutionRequestId)
        assertEquals(beforeCrash["request_id"], restored["request_id"])
        assertEquals(beforeCrash["created_at"], restored["created_at"])
        assertEquals(beforeCrash["conflict_id"], restored["conflict_id"])
        assertEquals(beforeCrash["expected_revision"], restored["expected_revision"])
        assertEquals(beforeCrash["expected_sha256"], restored["expected_sha256"])
        assertEquals(beforeCrash["content_sha256"], restored["content_sha256"])
        assertEquals(
            SourceMutationState.RESOLUTION_QUEUED,
            restarted.pendingSourceMutations().single().state,
        )
    }

    @Test
    fun successReceiptWithoutAppliedConfirmationRetainsConflictResolutionLineage() {
        val bundle = createConflictBundle()
        val originalRequestId = requests.listFiles().orEmpty().single().nameWithoutExtension
        val resolutionRequestId = queue.saveConflictResolution(bundle, "fully resolved\n")
        val canonical = snapshot(bundle.resolutionId).copy(
            sourceSha256 = sha256("fully resolved\n"),
            contents = "fully resolved\n",
        )
        writeReceipt(
            resolutionRequestId,
            SourceEditQueue.ACTION_RESOLVE_CONFLICT,
            SourceEditQueue.STATUS_SUCCESS,
            canonical,
            draftRevision = 2,
            applied = false,
        )

        assertEquals(0, queue.recoverDeliveredRequests())

        assertTrue(File(requests, "$originalRequestId.json").isFile)
        assertTrue(File(requests, "$resolutionRequestId.json").isFile)
        assertTrue(File(receipts, "$resolutionRequestId.json").isFile)
        assertEquals(SourceMutationState.FAILED, queue.pendingSourceMutations().single().state)
        assertEquals(setOf("template.pdf"), queue.pendingSavePdfFilenames())
    }

    @Test
    fun successfulConflictResolutionClearsOnlyAfterVerifiedAppliedReceipt() {
        val bundle = createConflictBundle()
        val originalRequestId = File(requests.listFiles().orEmpty().single().absolutePath).nameWithoutExtension
        val resolutionRequestId = queue.saveConflictResolution(bundle, "fully resolved\n")
        val canonical = snapshot(bundle.resolutionId).copy(
            sourceSha256 = sha256("fully resolved\n"),
            contents = "fully resolved\n",
        )
        writeReceipt(
            resolutionRequestId,
            SourceEditQueue.ACTION_RESOLVE_CONFLICT,
            SourceEditQueue.STATUS_SUCCESS,
            canonical,
            draftRevision = 2,
            applied = true,
        )

        assertTrue(queue.pendingSavePdfFilenames().isEmpty())

        assertEquals(1, queue.recoverDeliveredRequests())

        assertFalse(File(requests, "$originalRequestId.json").exists())
        assertFalse(File(requests, "$resolutionRequestId.json").exists())
        assertTrue(queue.pendingSourceMutations().isEmpty())
        assertNull(queue.localDraftContents(canonical))
    }

    @Test
    fun conflictBundleCacheIsHashVerifiedBeforeOfflineReuse() {
        val bundle = createConflictBundle()

        queue.cacheConflictBundle(bundle)

        assertEquals(bundle, queue.cachedConflictBundle(bundle.conflictId))
        val cachedFile = File(cache, "conflicts").listFiles().orEmpty().single()
        val tampered = JsonParser.parseString(cachedFile.readText()).asJsonObject.apply {
            addProperty("merged_contents", "tampered\n")
        }
        cachedFile.writeText(tampered.toString())
        assertNull(queue.cachedConflictBundle(bundle.conflictId))
    }

    @Test
    fun malformedReceiptIsIgnored() {
        receipts.mkdirs()
        File(receipts, "3daaf7df-2e77-47cf-8e49-51ab5a761191.json").writeText("{}")

        assertNull(queue.receipt("3daaf7df-2e77-47cf-8e49-51ab5a761191"))
    }

    private fun createConflictBundle(): SourceConflictBundle {
        val original = snapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191")
        val incomingContents = "phone incoming\n"
        val currentContents = "Fedora current\n"
        val mergedContents =
            "<<<<<<< PHONE\n$incomingContents||||||| SHARED BASE\nsource\n=======\n$currentContents>>>>>>> FEDORA\n"
        val requestId = queue.saveForNextSync(
            original,
            "template.pdf",
            "b".repeat(64),
            incomingContents,
        )
        writeReceipt(
            requestId = requestId,
            action = "save",
            status = "conflict",
            snapshot = original.copy(sourceSha256 = sha256(currentContents), contents = currentContents),
            conflictId = "conflict-1",
            conflictCount = 1,
            expectedRevision = 75,
            currentSha256 = sha256(currentContents),
            incomingSha256 = sha256(incomingContents),
            mergedObjectSha256 = sha256(mergedContents),
            currentModifiedAt = "2026-08-29T18:14:00Z",
            incomingModifiedAt = "2026-08-30T07:27:11.590Z",
        )
        return SourceConflictBundle(
            conflictId = "conflict-1",
            requestId = requestId,
            sourceFile = original.sourceFile,
            pdfFilename = "template.pdf",
            sourceLine = original.sourceLine,
            resolutionId = original.resolutionId,
            conflictCount = 1,
            expectedRevision = 75,
            currentSha256 = sha256(currentContents),
            incomingSha256 = sha256(incomingContents),
            mergedObjectSha256 = sha256(mergedContents),
            currentModifiedAt = "2026-08-29T18:14:00Z",
            incomingModifiedAt = "2026-08-30T07:27:11.590Z",
            currentContents = currentContents,
            incomingContents = incomingContents,
            mergedContents = mergedContents,
        )
    }

    private fun restartedQueue() = SourceEditQueue(
        requests,
        receipts,
        cache,
        scans::add,
        {},
        null,
    ) { syncTriggers += "scheduled" }

    private fun selection() = SourceSelection(
        "template.pdf",
        "a".repeat(64),
        4,
        120.5f,
        310.25f,
        "selected passage",
    )

    private fun snapshot(resolutionId: String) = SourceSnapshot(
        resolutionId,
        "docs-template/tex/source/main.tex",
        42,
        "c".repeat(64),
        "source\n",
    )

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun request(requestId: String): JsonObject = JsonParser.parseString(
        File(requests, "$requestId.json").readText()
    ).asJsonObject

    private fun writeReceipt(
        requestId: String,
        action: String,
        status: String,
        snapshot: SourceSnapshot,
        publishedPdfSha256: String? = null,
        draftRevision: Int? = null,
        message: String = "",
        conflictId: String? = null,
        conflictCount: Int? = null,
        expectedRevision: Long? = null,
        currentSha256: String? = null,
        incomingSha256: String? = null,
        mergedObjectSha256: String? = null,
        currentModifiedAt: String? = null,
        incomingModifiedAt: String? = null,
        applied: Boolean = false,
    ) {
        receipts.mkdirs()
        File(receipts, "$requestId.json").writeText(JsonObject().apply {
            addProperty(
                "schema_version",
                if (conflictId != null || action == SourceEditQueue.ACTION_RESOLVE_CONFLICT) 3
                else if (draftRevision == null) 1 else 2,
            )
            addProperty("request_id", requestId)
            addProperty("action", action)
            addProperty("status", status)
            addProperty("message", message)
            addProperty("applied", applied)
            addProperty("resolution_id", snapshot.resolutionId)
            addProperty("source_file", snapshot.sourceFile)
            addProperty("source_line", snapshot.sourceLine)
            addProperty("source_sha256", snapshot.sourceSha256)
            addProperty("contents", snapshot.contents)
            publishedPdfSha256?.let { addProperty("published_pdf_sha256", it) }
            draftRevision?.let { addProperty("draft_revision", it) }
            conflictId?.let { addProperty("conflict_path", it) }
            conflictCount?.let { addProperty("conflict_count", it) }
            expectedRevision?.let { addProperty("expected_revision", it) }
            currentSha256?.let { addProperty("current_sha256", it) }
            incomingSha256?.let { addProperty("incoming_sha256", it) }
            mergedObjectSha256?.let { addProperty("merged_object_sha256", it) }
            currentModifiedAt?.let { addProperty("current_modified_at", it) }
            incomingModifiedAt?.let { addProperty("incoming_modified_at", it) }
        }.toString())
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanReuseTest {
    private val root: File = Files.createTempDirectory("sync-plan-reuse").toFile()
    private val operation = "0190a000-0000-7000-8000-00000000000a"

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun digest(number: Int) = "%064x".format(number)

    private fun source(number: Int, size: Long = 100) =
        SyncTransferObject(digest(number), size, kind = "source", roles = listOf("source_snapshot"), publications = listOf("a.pdf"))

    /** The server as a plan sees it: the objects of each Library revision, and what was posted. */
    private inner class FakeServer {
        val libraries = linkedMapOf<String, List<SyncTransferObject>>()
        var revision = ""
        var planPosts = 0
        var postedHashes = 0

        fun plan(known: Set<String>, capabilities: List<String>): SyncTransferEstimate {
            planPosts++
            postedHashes += known.size
            val objects = libraries.getValue(revision).filter { it.sha256 !in known }
            val serverPlanId = digest((revision + known.sorted() + capabilities + objects.map { it.sha256 }).hashCode() and 0xfffffff)
            return SyncTransferEstimate(
                contractVersion = 2,
                sourceProfile = "reader-v1",
                includeVoiceMedia = false,
                planId = digest(serverPlanId.hashCode() and 0xfffffff),
                serverPlanId = serverPlanId,
                serverLibraryRevision = revision,
                targetPdfFilename = null,
                remoteObjects = objects,
                metadataDownloadBytes = 700,
                uploadBytes = 40,
                downloadBytes = 700 + objects.sumOf { it.sizeBytes },
            )
        }
    }

    /** The phone's content-addressed stores as real directories, so the real stamp is tested. */
    private inner class FakePhone {
        val stores = listOf(File(root, "objects"), File(root, "object-cache")).onEach { it.mkdirs() }
        val pdfs = File(root, "current-pdfs").apply { mkdirs() }
        var capabilities = listOf("project_reading_v1", "pdf_delta_v1")

        fun hold(number: Int, store: Int = 0) = File(stores[store], digest(number)).writeText("object $number")
        fun known(): Set<String> = stores.flatMap { it.list().orEmpty().toList() }.toSet()
        fun stamp() = localInventoryStamp(stores + pdfs)
    }

    private fun transferPlan(estimate: SyncTransferEstimate) = SyncTransferPlan(
        contractVersion = estimate.contractVersion,
        sourceProfile = estimate.sourceProfile,
        includeVoiceMedia = estimate.includeVoiceMedia,
        planId = estimate.planId,
        serverPlanId = estimate.serverPlanId,
        serverLibraryRevision = estimate.serverLibraryRevision,
        remoteObjects = estimate.remoteObjects,
        operationId = operation,
        targetPdfFilename = null,
        building = false,
        metadataDownloadBytes = estimate.metadataDownloadBytes,
        uploadBytes = estimate.uploadBytes,
        downloadBytes = estimate.downloadBytes,
        thresholdBytes = 1_000_000,
    )

    /** The download pass: fetches what the Library needs and the phone lacks, each checked against the plan. */
    private fun download(server: FakeServer, phone: FakePhone, plan: SyncTransferPlan): Set<String> {
        val missing = server.libraries.getValue(server.revision).map { it.sha256 }.filter { it !in phone.known() }
        missing.forEach { plan.requireRemoteObject(it, kind = "source", role = "source_snapshot") }
        return missing.toSet()
    }

    /** One Sync as it ran before: a plan before the control pass and another for the download pass. */
    private fun syncAskingTwice(server: FakeServer, phone: FakePhone, controlPass: () -> Unit): Set<String> {
        server.plan(phone.known(), phone.capabilities)
        controlPass()
        return download(server, phone, transferPlan(server.plan(phone.known(), phone.capabilities)))
    }

    /** One Sync as it runs now: the second plan only when [reusableArtifactEstimate] says the first no longer holds. */
    private fun sync(server: FakeServer, phone: FakePhone, controlPass: () -> Unit): Set<String> {
        val stamp = phone.stamp()
        val first = PlannedTransfer(operation, null, stamp, phone.capabilities, server.plan(phone.known(), phone.capabilities))
        controlPass()
        val estimate = reusableArtifactEstimate(
            planned = first,
            operationId = operation,
            targetPdfFilename = null,
            libraryRevision = server.revision,
            inventoryStamp = phone.stamp(),
            capabilities = phone.capabilities,
            artifactPlanId = { digest(it.hashCode() and 0xffffff) },
        ) ?: server.plan(phone.known(), phone.capabilities)
        return download(server, phone, transferPlan(estimate))
    }

    private fun scenario(controlPass: (FakeServer, FakePhone) -> Unit): Triple<Set<String>, Set<String>, Pair<FakeServer, FakeServer>> {
        fun world(): Pair<FakeServer, FakePhone> {
            val server = FakeServer().apply {
                libraries["r1"] = (1..6).map { source(it) }
                libraries["r2"] = (1..5).map { source(it) } + source(9)
                revision = "r1"
            }
            val phone = FakePhone()
            phone.stores.forEach { it.deleteRecursively(); it.mkdirs() }
            (1..4).forEach { phone.hold(it) }
            return server to phone
        }
        val (serverBefore, phoneBefore) = world()
        val before = syncAskingTwice(serverBefore, phoneBefore) { controlPass(serverBefore, phoneBefore) }
        val (serverNow, phoneNow) = world()
        val now = sync(serverNow, phoneNow) { controlPass(serverNow, phoneNow) }
        return Triple(before, now, serverBefore to serverNow)
    }

    @Test
    fun aSyncThatOnlyPullsDownloadsTheSameObjectsWithOnePlanInsteadOfTwo() {
        val (before, now, servers) = scenario { _, _ -> }
        assertEquals(setOf(digest(5), digest(6)), before)
        assertEquals(before, now)
        assertEquals(2, servers.first.planPosts)
        assertEquals(1, servers.second.planPosts)
        // Every local object hash was sent twice; now once.
        assertEquals(8, servers.first.postedHashes)
        assertEquals(4, servers.second.postedHashes)
    }

    @Test
    fun aSyncWhoseUploadChangedTheLibraryPlansAgainAndDownloadsTheSameObjects() {
        val (before, now, servers) = scenario { server, _ -> server.revision = "r2" }
        assertEquals(setOf(digest(5), digest(9)), before)
        assertEquals(before, now)
        assertEquals(2, servers.second.planPosts)
    }

    @Test
    fun aSyncWhoseControlPassStoredAnObjectPlansAgainAndDownloadsTheSameObjects() {
        val (before, now, servers) = scenario { _, phone -> phone.hold(5, store = 1) }
        assertEquals(setOf(digest(6)), before)
        assertEquals(before, now)
        assertEquals(2, servers.second.planPosts)
    }

    @Test
    fun aRejectedPdfPatchChangesWhatIsAskedForAndPlansAgain() {
        val (before, now, servers) = scenario { _, phone -> phone.capabilities = listOf("project_reading_v1") }
        assertEquals(before, now)
        assertEquals(2, servers.second.planPosts)
    }

    @Test
    fun theKeptPlanCountsOnlyTheObjectsStillToDownload() {
        val server = FakeServer().apply { libraries["r1"] = listOf(source(1, 300), source(2, 500)); revision = "r1" }
        val first = PlannedTransfer(operation, null, "stamp", listOf("c"), server.plan(emptySet(), listOf("c")))
        val kept = reusableArtifactEstimate(first, operation, null, "r1", "stamp", listOf("c")) { "artifact-of-$it" }
        assertNotNull(kept)
        assertEquals(first.estimate.remoteObjects, kept!!.remoteObjects)
        assertEquals(first.estimate.serverPlanId, kept.serverPlanId)
        assertEquals("artifact-of-${first.estimate.serverPlanId}", kept.planId)
        // The changes and the Library were downloaded by the control pass; nothing is left to upload.
        assertEquals(0, kept.uploadBytes)
        assertEquals(0, kept.metadataDownloadBytes)
        assertEquals(800, kept.downloadBytes)
    }

    @Test
    fun aPlanOfAnotherSyncATargetedOneOrAnUnreadableStoreIsNeverKept() {
        val server = FakeServer().apply { libraries["r1"] = listOf(source(1)); revision = "r1" }
        val first = PlannedTransfer(operation, null, "stamp", listOf("c"), server.plan(emptySet(), listOf("c")))
        fun kept(
            planned: PlannedTransfer? = first, operationId: String? = operation, target: String? = null,
            revision: String? = "r1", stamp: String = "stamp",
        ) = reusableArtifactEstimate(planned, operationId, target, revision, stamp, listOf("c")) { it }
        assertNotNull(kept())
        assertNull(kept(planned = null))
        assertNull(kept(operationId = "0190a000-0000-7000-8000-00000000000b"))
        assertNull(kept(operationId = null))
        assertNull(kept(target = "a.pdf"))
        assertNull(kept(planned = first.copy(targetPdfFilename = "a.pdf")))
        assertNull(kept(revision = "r2"))
        assertNull(kept(revision = null))
        assertNull(kept(stamp = "other"))
        assertNull(kept(planned = first.copy(inventoryStamp = ""), stamp = ""))
    }

    @Test
    fun aPlanThatDoesNotNameANeededObjectIsStillRefusedByTheDownloadPass() {
        val server = FakeServer().apply { libraries["r1"] = listOf(source(1), source(2)); revision = "r1" }
        val phone = FakePhone()
        val stale = transferPlan(server.plan(setOf(digest(2)), phone.capabilities))
        val refused = runCatching { download(server, phone, stale) }.exceptionOrNull()
        assertTrue(refused is ArtifactPlanSupersededException)
    }

    @Test
    fun theStampFollowsObjectsAndPdfsAddedReplacedOrRemoved() {
        val phone = FakePhone()
        phone.hold(1)
        val generation = File(phone.pdfs, "generation.json").apply { writeText("{\"a\":1}") }
        fun stamp() = localInventoryStamp(phone.stores + phone.pdfs + generation, extra = listOf("added:1"))
        val initial = stamp()
        assertEquals(initial, stamp())
        phone.hold(2)
        val added = stamp()
        assertNotEquals(initial, added)
        File(phone.stores[0], digest(1)).delete()
        val removed = stamp()
        assertNotEquals(added, removed)
        generation.writeText("{\"a\":12}")
        val rewritten = stamp()
        assertNotEquals(removed, rewritten)
        File(phone.pdfs, "a.pdf").writeText("pdf")
        assertNotEquals(rewritten, stamp())
        assertNotEquals(stamp(), localInventoryStamp(phone.stores + phone.pdfs + generation, extra = listOf("added:2")))
        // A store that does not exist yet is part of the stamp, not an error.
        val missing = File(root, "not-yet")
        val without = localInventoryStamp(listOf(missing))
        missing.mkdirs()
        assertNotEquals(without, localInventoryStamp(listOf(missing)))
    }

    @Test
    fun theDownloadPassAsksOnlyWhetherTheControlPassAnswerStillStands() {
        val asked = mutableListOf<String?>()
        // The server answers 304 (null) to the remembered revision: the remembered answer is used.
        val reused = librarySnapshotForDownloadPass("library r2", "r2", expectedRevision = "r2", installedRevision = "r1") {
            asked += it; null
        }
        assertEquals("library r2", reused)
        assertEquals(listOf<String?>("r2"), asked)
        // The server moved on meanwhile: its new answer is returned, and the caller sees the revision differ.
        assertEquals("library r3", librarySnapshotForDownloadPass("library r2", "r2", "r2", "r1") { "library r3" })
    }

    @Test
    fun withoutARememberedAnswerTheDownloadPassAsksAsBefore() {
        val asked = mutableListOf<String?>()
        fun fetch(etag: String?): String? { asked += etag; return if (etag == "r1") null else "library" }
        // Nothing remembered (another process ran the control pass): the installed revision is sent.
        assertNull(librarySnapshotForDownloadPass(null, null, "r2", "r1", ::fetch))
        // Remembered for another revision than the plan expects.
        assertEquals("library", librarySnapshotForDownloadPass("library r5", "r5", "r2", null, ::fetch))
        // No plan to compare with (a pass that is not a download pass).
        assertNull(librarySnapshotForDownloadPass("library r2", "r2", null, "r1", ::fetch))
        assertEquals(listOf("r1", null, "r1"), asked)
    }
}

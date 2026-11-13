// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderFailure
import com.gitlab.mudlej.MjPdfReader.data.render.ExplicitPdfRenderStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhonePdfAttention
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryHeadStateTest {
    @Test
    fun `activated head cannot hide a missing source artifact`() {
        val digest = "a".repeat(64)

        assertFalse(sourceArtifactActivationIsCurrent(digest, digest, null))
        assertFalse(sourceArtifactActivationIsCurrent(digest, digest, "b".repeat(64)))
        assertTrue(sourceArtifactActivationIsCurrent(digest, digest, digest))
        assertTrue(sourceArtifactActivationIsCurrent("", null, null))
    }

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("library-head-state").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun storeRoundTripsAuthoritativeBuildState() {
        val stateFile = File(root, "head.json")
        val store = LibraryHeadStateStore(stateFile)
        val latestBuild = JsonObject().apply {
            addProperty("id", "build-new")
            addProperty("status", "failed")
            addProperty("error", "new failure")
            add("failed_documents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("pdf_filename", "paper.pdf")
                    addProperty("source", "paper.tex")
                    addProperty("message", "new failure")
                })
            })
        }
        val snapshot = org.tbend.thoughts.sdk.LibrarySnapshot(
            publications = listOf(
                org.tbend.thoughts.sdk.LibraryEntry(
                    generationId = "e".repeat(64),
                    documentId = "paper",
                    filename = "paper.pdf",
                    sha256 = "a".repeat(64),
                    sizeBytes = 42,
                    project = "docs-paper",
                    sourceFrontier = 10,
                    commentFrontier = 11,
                    annotationFrontier = 12,
                    buildId = "build-published",
                    latestBuild = latestBuild,
                    pdfStale = true,
                    placementManifestSha256 = null,
                    metadata = JsonObject(),
                ),
            ),
            latestServerSequence = 12,
            sourceIndexSha256 = "b".repeat(64),
            sourceBundleSha256 = "c".repeat(64),
            libraryRevision = "d".repeat(64),
        )

        store.publish(snapshot, fetchedAtEpochMillis = 1234)
        val loaded = requireNotNull(store.load())

        assertEquals(snapshot.libraryRevision, loaded.libraryRevision)
        assertEquals("build-published", loaded.publications["paper.pdf"]?.publishedBuildId)
        assertEquals("build-new", loaded.publications["paper.pdf"]?.latestBuildId)
        assertEquals("new failure", loaded.publications["paper.pdf"]?.error)
        assertTrue(loaded.publications["paper.pdf"]?.pdfStale == true)
    }

    @Test
    fun unchangedHeadRefreshesItsValidationTime() {
        val stateFile = File(root, "head.json")
        val store = LibraryHeadStateStore(stateFile)
        val snapshot = org.tbend.thoughts.sdk.LibrarySnapshot(
            publications = listOf(
                org.tbend.thoughts.sdk.LibraryEntry(
                    generationId = "e".repeat(64),
                    documentId = "paper",
                    filename = "paper.pdf",
                    sha256 = "a".repeat(64),
                    sizeBytes = 42,
                    project = "docs-paper",
                    sourceFrontier = 10,
                    commentFrontier = 11,
                    annotationFrontier = 12,
                    buildId = "build-published",
                    latestBuild = null,
                    pdfStale = false,
                    placementManifestSha256 = null,
                    metadata = JsonObject(),
                ),
            ),
            latestServerSequence = 12,
            sourceIndexSha256 = "b".repeat(64),
            sourceBundleSha256 = "c".repeat(64),
            libraryRevision = "d".repeat(64),
        )
        store.publish(snapshot, fetchedAtEpochMillis = 100)

        val refreshed = store.refreshValidation(snapshot.libraryRevision, 200)

        assertEquals(200L, refreshed?.fetchedAtEpochMillis)
        assertEquals(200L, store.load()?.fetchedAtEpochMillis)
        assertNull(store.refreshValidation("f".repeat(64), 300))
    }

    @Test
    fun authoritativeVerificationUsesEachActivatedPdfIdentity() {
        val revision = "9".repeat(64)
        val sha = "b".repeat(64)
        val head = head(publication("paper.pdf", sha, stale = false), revision, fetchedAt = 200)

        assertEquals(
            mapOf("paper.pdf" to 200L),
            authoritativeLocalPdfVerificationTimes(head, localLibrary(revision, sha)),
        )
        assertEquals(
            mapOf("paper.pdf" to 200L),
            authoritativeLocalPdfVerificationTimes(
                head,
                localLibrary("1".repeat(64), sha),
            ),
        )
    }

    @Test
    fun unchangedLastGoodLibraryDoesNotEraseBuildNeeded() {
        val revision = "9".repeat(64)
        val sha = "b".repeat(64)
        val status = ExplicitPdfRenderStatus(
            filename = "paper.pdf",
            status = "out_of_sync",
            publicationSha256 = sha,
            publicationBuildId = "build-good",
            checkedAtEpochMillis = 100,
        )

        val superseded = supersededExplicitPdfRenderStatusFilenames(
            head(publication("paper.pdf", sha, stale = false), revision, fetchedAt = 200),
            localLibrary(revision, sha),
            listOf(status),
        )

        assertTrue(superseded.isEmpty())
    }

    @Test
    fun newerActivatedPublicationClearsOldBuildNeededStatus() {
        val revision = "9".repeat(64)
        val sha = "b".repeat(64)
        val status = ExplicitPdfRenderStatus(
            filename = "paper.pdf",
            status = "out_of_sync",
            publicationSha256 = sha,
            publicationBuildId = "build-old",
            checkedAtEpochMillis = 100,
        )
        val publication = publication("paper.pdf", sha, stale = false).copy(
            publishedBuildId = "build-new",
            latestBuildId = "build-new",
        )

        val superseded = supersededExplicitPdfRenderStatusFilenames(
            head(publication, revision, fetchedAt = 200),
            localLibrary(revision, sha),
            listOf(status),
        )

        assertEquals(setOf("paper.pdf"), superseded)
    }

    @Test
    fun exactActivatedPublicationClearsRedundantSyncedStatus() {
        val revision = "9".repeat(64)
        val sha = "b".repeat(64)
        val status = ExplicitPdfRenderStatus(
            filename = "paper.pdf",
            status = "synced",
            publicationSha256 = sha,
            publicationBuildId = "build-good",
            checkedAtEpochMillis = 100,
        )

        val superseded = supersededExplicitPdfRenderStatusFilenames(
            head(publication("paper.pdf", sha, stale = false), revision, fetchedAt = 200),
            localLibrary(revision, sha),
            listOf(status),
        )

        assertEquals(setOf("paper.pdf"), superseded)
    }

    @Test
    fun newerHealthyPublicationReplacesOldErrorWithUpdateReady() {
        val raw = PhoneRenderStatusSnapshot(
            listOf(oldFailure("paper.pdf", completedAt = 100)),
        )
        val head = head(
            publication("paper.pdf", sha = "b".repeat(64), stale = false),
            fetchedAt = 200,
        )
        val local = localLibrary(revision = "1".repeat(64), sha = "a".repeat(64))

        val reconciled = reconcileRemoteLibraryStatus(raw, head, local)

        assertEquals(PhoneRenderStage.WAITING_FOR_SYNC, reconciled.statusForPdf("paper.pdf")?.stage)
        assertTrue(reconciled.jobs.none { it.requestId == "old-error" })
    }

    @Test
    fun exactHealthyPublicationClearsSupersededError() {
        val revision = "9".repeat(64)
        val sha = "b".repeat(64)
        val reconciled = reconcileRemoteLibraryStatus(
            PhoneRenderStatusSnapshot(listOf(oldFailure("paper.pdf", completedAt = 100))),
            head(publication("paper.pdf", sha, stale = false), revision = revision, fetchedAt = 200),
            localLibrary(revision, sha),
        )

        assertNull(reconciled.statusForPdf("paper.pdf"))
    }

    @Test
    fun newerHealthyLibraryRetiresFullyResolvedAggregateFailure() {
        val failure = PhoneRenderFailure("paper.pdf", "paper.tex", "obsolete failure")
        val aggregate = PhoneRenderJobStatus(
            requestId = "old-all-error",
            target = "all",
            stage = PhoneRenderStage.FAILED,
            completedAtEpochMillis = 100,
            affectedPdfFilenames = setOf("paper.pdf"),
            failedDocuments = listOf(failure),
            attentionPdfs = listOf(PhonePdfAttention("paper.pdf", 1)),
        )

        val reconciled = reconcileRemoteLibraryStatus(
            PhoneRenderStatusSnapshot(listOf(aggregate)),
            head(publication("paper.pdf", "b".repeat(64), stale = false), fetchedAt = 200),
            localLibrary("9".repeat(64), "b".repeat(64)),
        )

        assertTrue(reconciled.jobs.isEmpty())
    }

    @Test
    fun healthyLibraryDoesNotHideUnscopedOrNewerAggregateFailure() {
        val head = head(
            publication("paper.pdf", "b".repeat(64), stale = false),
            fetchedAt = 200,
        )
        val local = localLibrary("9".repeat(64), "b".repeat(64))
        val unscoped = PhoneRenderJobStatus(
            requestId = "network-error",
            target = "all",
            stage = PhoneRenderStage.FAILED,
            completedAtEpochMillis = 100,
            message = "The phone could not activate the download.",
        )
        val newer = PhoneRenderJobStatus(
            requestId = "new-error",
            target = "all",
            stage = PhoneRenderStage.FAILED,
            completedAtEpochMillis = 300,
            affectedPdfFilenames = setOf("paper.pdf"),
        )

        assertEquals(
            listOf("network-error"),
            reconcileRemoteLibraryStatus(
                PhoneRenderStatusSnapshot(listOf(unscoped)),
                head,
                local,
            ).jobs.map(PhoneRenderJobStatus::requestId),
        )
        assertEquals(
            listOf("new-error"),
            reconcileRemoteLibraryStatus(
                PhoneRenderStatusSnapshot(listOf(newer)),
                head,
                local,
            ).jobs.map(PhoneRenderJobStatus::requestId),
        )
    }

    @Test
    fun currentFedoraFailureRemainsAnError() {
        val failure = PhoneRenderFailure("paper.pdf", "paper.tex", "current failure")
        val publication = publication("paper.pdf", "b".repeat(64), stale = true).copy(
            latestBuildId = "build-current",
            latestBuildStatus = "failed",
            error = "current failure",
            failedDocuments = listOf(failure),
        )

        val reconciled = reconcileRemoteLibraryStatus(
            PhoneRenderStatusSnapshot(listOf(oldFailure("paper.pdf", completedAt = 100))),
            head(publication, fetchedAt = 200),
            localLibrary("1".repeat(64), "a".repeat(64)),
        )

        val status = reconciled.statusForPdf("paper.pdf")
        assertEquals(PhoneRenderStage.FAILED, status?.stage)
        assertEquals("current failure", status?.message)
        assertEquals("remote-library:build-current", status?.requestId)
    }

    @Test
    fun synchronizedSourcesWithAStalePdfRequireBuildAndSync() {
        val publication = publication("paper.pdf", "b".repeat(64), stale = true).copy(
            latestBuildId = "",
            latestBuildStatus = "",
        )

        val status = reconcileRemoteLibraryStatus(
            PhoneRenderStatusSnapshot.EMPTY,
            head(publication, fetchedAt = 200),
            localLibrary("1".repeat(64), "a".repeat(64)),
        ).statusForPdf("paper.pdf")

        assertEquals(PhoneRenderStage.OUT_OF_SYNC, status?.stage)
        assertEquals(
            "The server must build this PDF from the synchronized sources.",
            status?.message,
        )
    }

    @Test
    fun partialFedoraFailureRemainsAttentionInsteadOfHardFailure() {
        val failure = PhoneRenderFailure("paper.pdf", "paper.tex", "current failure")
        val publication = publication("paper.pdf", "b".repeat(64), stale = true).copy(
            latestBuildId = "build-attention",
            latestBuildStatus = "attention",
            error = "current failure",
            failedDocuments = listOf(failure),
        )

        val status = reconcileRemoteLibraryStatus(
            PhoneRenderStatusSnapshot.EMPTY,
            head(publication, fetchedAt = 200),
            localLibrary("1".repeat(64), "a".repeat(64)),
        ).statusForPdf("paper.pdf")

        assertEquals(PhoneRenderStage.ATTENTION, status?.stage)
    }

    @Test
    fun partialAggregateActivationClearsHealthyPdfButKeepsFailedPdfLastGoodAttention() {
        val healthy = publication("healthy.pdf", "c".repeat(64), stale = false)
        val failed = publication("failed.pdf", "b".repeat(64), stale = true).copy(
            latestBuildId = "aggregate-build",
            latestBuildStatus = "attention",
            error = "Undefined control sequence",
            failedDocuments = listOf(
                PhoneRenderFailure(
                    "failed.pdf",
                    "failed.tex",
                    "Undefined control sequence",
                ),
            ),
        )
        val aggregate = PhoneRenderJobStatus(
            requestId = "aggregate-build",
            target = "all",
            stage = PhoneRenderStage.ATTENTION,
            completedAtEpochMillis = 100,
            affectedPdfFilenames = setOf("healthy.pdf", "failed.pdf"),
            failedDocuments = failed.failedDocuments,
            attentionPdfs = listOf(PhonePdfAttention("failed.pdf", 1)),
        )
        val remote = head(healthy, fetchedAt = 200).copy(
            publications = mapOf(
                healthy.filename to healthy,
                failed.filename to failed,
            ),
        )
        val local = SourceLinkedPdfIndexSnapshot(
            libraryPdfSha256ByFilename = mapOf(
                "healthy.pdf" to healthy.sha256,
                "failed.pdf" to failed.sha256,
            ),
            activatedPdfSha256ByFilename = mapOf(
                "healthy.pdf" to healthy.sha256,
                "failed.pdf" to failed.sha256,
            ),
            libraryAvailable = true,
            libraryRevision = remote.libraryRevision,
            generationCurrent = true,
        )

        val reconciled = reconcileRemoteLibraryStatus(
            PhoneRenderStatusSnapshot(listOf(aggregate)),
            remote,
            local,
        )

        assertNull(reconciled.statusForPdf("healthy.pdf"))
        val failedStatus = requireNotNull(reconciled.statusForPdf("failed.pdf"))
        assertEquals(PhoneRenderStage.ATTENTION, failedStatus.stage)
        assertEquals("remote-library:aggregate-build", failedStatus.requestId)
        assertEquals("Undefined control sequence", failedStatus.message)
    }

    private fun oldFailure(filename: String, completedAt: Long) = PhoneRenderJobStatus(
        requestId = "old-error",
        target = filename,
        stage = PhoneRenderStage.FAILED,
        message = "obsolete failure",
        completedAtEpochMillis = completedAt,
    )

    private fun publication(filename: String, sha: String, stale: Boolean) = RemoteLibraryPublication(
        filename = filename,
        sha256 = sha,
        sizeBytes = 42,
        publishedBuildId = "build-good",
        latestBuildId = "build-good",
        latestBuildStatus = "succeeded",
        pdfStale = stale,
        error = "",
        failedDocuments = emptyList(),
    )

    private fun head(
        publication: RemoteLibraryPublication,
        revision: String = "9".repeat(64),
        fetchedAt: Long,
    ) = RemoteLibraryHead(
        libraryRevision = revision,
        sourceIndexSha256 = "7".repeat(64),
        sourceBundleSha256 = "8".repeat(64),
        fetchedAtEpochMillis = fetchedAt,
        publications = mapOf(publication.filename to publication),
    )

    private fun localLibrary(revision: String, sha: String) = SourceLinkedPdfIndexSnapshot(
        libraryPdfSha256ByFilename = mapOf("paper.pdf" to sha),
        activatedPdfSha256ByFilename = mapOf("paper.pdf" to sha),
        libraryAvailable = true,
        libraryRevision = revision,
        generationCurrent = true,
    )
}

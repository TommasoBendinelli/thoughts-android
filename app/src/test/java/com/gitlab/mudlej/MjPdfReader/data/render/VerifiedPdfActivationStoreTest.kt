// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VerifiedPdfActivationStoreTest {

    private lateinit var root: File
    private lateinit var pdfs: File
    private lateinit var cache: File
    private lateinit var canonical: File
    private lateinit var store: VerifiedPdfActivationStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("verified-pdf-activation").toFile()
        pdfs = File(root, "docs-clean-pdfs").apply { mkdirs() }
        cache = File(root, "private-cache")
        canonical = File(pdfs, "template.pdf")
        store = VerifiedPdfActivationStore(cache, pdfs)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun pdfFirstDoesNotActivateUntilTheIndexMatches() {
        canonical.writeText("version two")
        val versionTwoHash = hash(canonical)
        val versionOneHash = hash(File(root, "version-one").apply { writeText("version one") })

        val beforeIndex = store.selectForOpen(canonical, snapshot(versionOneHash))

        assertNull(beforeIndex.renderFile)
        assertEquals(versionOneHash, beforeIndex.expectedPdfSha256)

        val afterIndex = store.selectForOpen(canonical, snapshot(versionTwoHash))

        assertEquals(versionTwoHash, afterIndex.activePdfSha256)
        assertEquals("version two", afterIndex.renderFile?.readText())
        assertFalse(afterIndex.updatePending)
    }

    @Test
    fun indexFirstKeepsTheOldVersionUntilThePdfMatches() {
        canonical.writeText("version one")
        val versionOneHash = hash(canonical)
        val oldSelection = store.selectForOpen(canonical, snapshot(versionOneHash))
        val versionTwoFile = File(root, "version-two").apply { writeText("version two") }
        val versionTwoHash = hash(versionTwoFile)

        val waiting = store.selectForOpen(canonical, snapshot(versionTwoHash))

        assertEquals(versionOneHash, waiting.activePdfSha256)
        assertEquals("version one", waiting.renderFile?.readText())
        assertTrue(waiting.updatePending)
        assertFalse(store.hasVerifiedUpdateReady(canonical, versionOneHash, snapshot(versionTwoHash)))

        canonical.writeText("version two")
        assertTrue(store.hasVerifiedUpdateReady(canonical, versionOneHash, snapshot(versionTwoHash)))

        val activated = store.selectForOpen(canonical, snapshot(versionTwoHash))

        assertEquals(versionTwoHash, activated.activePdfSha256)
        assertEquals("version two", activated.renderFile?.readText())
        assertEquals("version one", oldSelection.renderFile?.readText())
    }

    @Test
    fun anOlderPreviouslyVerifiedOpenVersionStillAllowsComments() {
        canonical.writeText("version one")
        val versionOneHash = hash(canonical)
        store.selectForOpen(canonical, snapshot(versionOneHash))
        val versionTwoHash = hash(File(root, "version-two").apply { writeText("version two") })
        val indexFile = File(pdfs, SourceLinkedPdfIndex.INDEX_FILENAME)
        val index = SourceLinkedPdfIndex(pdfs, indexFile)
        writeIndex(indexFile, versionTwoHash)

        assertEquals(
            SourceLinkedCommentIdentity.VERIFIED,
            store.commentIdentity(index, canonical, versionOneHash),
        )
        assertEquals(
            SourceLinkedCommentIdentity.HASH_MISMATCH,
            index.commentIdentity(canonical, versionOneHash),
        )
    }

    @Test
    fun interruptedOrChangedCopyLeavesThePreviousActivationUsable() {
        canonical.writeText("version one")
        val versionOneHash = hash(canonical)
        store.selectForOpen(canonical, snapshot(versionOneHash))
        canonical.writeText("version two")
        val versionTwoHash = hash(canonical)
        val interruptedStore = VerifiedPdfActivationStore(
            cache,
            pdfs,
            sha256 = { file -> computeSha256(file) },
            copyCandidate = { _, target ->
                target.writeText("partial")
                throw IllegalStateException("interrupted")
            },
        )

        val selection = interruptedStore.selectForOpen(canonical, snapshot(versionTwoHash))

        assertEquals(versionOneHash, selection.activePdfSha256)
        assertEquals("version one", selection.renderFile?.readText())
        assertTrue(cache.listFiles().orEmpty().none { it.name.endsWith(".tmp") })

        val changedDuringCopyStore = VerifiedPdfActivationStore(
            cache,
            pdfs,
            sha256 = { file -> computeSha256(file) },
            copyCandidate = { _, target -> target.writeText("different bytes") },
        )
        val changedSelection = changedDuringCopyStore.selectForOpen(canonical, snapshot(versionTwoHash))
        assertEquals(versionOneHash, changedSelection.activePdfSha256)
    }

    @Test
    fun tamperedActiveCacheIsNeverRendered() {
        canonical.writeText("version one")
        val versionOneHash = hash(canonical)
        val activated = store.selectForOpen(canonical, snapshot(versionOneHash))
        activated.renderFile?.writeText("tampered")
        canonical.writeText("incoming mismatch")

        val reopened = store.selectForOpen(canonical, snapshot(versionOneHash))

        assertNull(reopened.renderFile)
        assertNull(reopened.activePdfSha256)
    }

    @Test
    fun activationSurvivesRestartAndStartupPrunesOnlyInactiveFiles() {
        canonical.writeText("version one")
        val versionOneHash = hash(canonical)
        store.selectForOpen(canonical, snapshot(versionOneHash))
        val restarted = VerifiedPdfActivationStore(cache, pdfs)
        canonical.writeText("unverified incoming bytes")

        val reopened = restarted.selectForOpen(canonical, snapshot("f".repeat(64)))

        assertEquals(versionOneHash, reopened.activePdfSha256)
        val orphan = File(cache, "versions/${"e".repeat(64)}.pdf").apply { writeText("orphan") }
        val partial = File(cache, ".interrupted.tmp").apply { writeText("partial") }
        assertEquals(2, restarted.pruneInactiveVersions())
        assertFalse(orphan.exists())
        assertFalse(partial.exists())
        assertTrue(reopened.renderFile?.isFile == true)
    }

    @Test
    fun nonSourcePdfIsUnaffected() {
        val external = File(root, "template.pdf").apply { writeText("external") }

        val selection = store.selectForOpen(external, snapshot(hash(external)))

        assertNull(selection.renderFile)
        assertNull(selection.activePdfSha256)
        assertFalse(selection.updatePending)
    }

    private fun snapshot(hash: String) = SourceLinkedPdfIndexSnapshot(
        filenames = setOf(canonical.name),
        pdfSha256ByFilename = mapOf(canonical.name to hash),
        available = true,
    )

    private fun hash(file: File): String = requireNotNull(computeSha256(file))

    private fun writeIndex(indexFile: File, hash: String) {
        indexFile.writeText(
            """{"schema_version":1,"pdfs":[{"filename":"template.pdf","pdf_sha256":"$hash"}]}""",
        )
    }
}

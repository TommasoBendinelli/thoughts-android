// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SourceLinkedPdfIndexTest {

    private val conceptsHash = "a".repeat(64)
    private val playlistsHash = "b".repeat(64)

    private lateinit var root: File
    private lateinit var pdfs: File
    private lateinit var indexFile: File
    private lateinit var index: SourceLinkedPdfIndex

    @Before
    fun setUp() {
        root = Files.createTempDirectory("source-linked-pdf-index").toFile()
        pdfs = File(root, "docs-clean-pdfs").apply { mkdirs() }
        indexFile = File(pdfs, SourceLinkedPdfIndex.INDEX_FILENAME)
        index = SourceLinkedPdfIndex(pdfs, indexFile)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun validIndexProvidesExactCanonicalAllowlist() {
        writeIndex("""[{"filename":"concepts.pdf","project":"docs-concepts","pdf_sha256":"$conceptsHash"}]""")

        val snapshot = index.snapshot()

        assertTrue(snapshot.available)
        assertEquals(mapOf("concepts.pdf" to "docs-concepts"), snapshot.projectsByFilename)
        assertEquals(setOf("concepts.pdf"), snapshot.filenames)
        assertEquals(mapOf("concepts.pdf" to conceptsHash), snapshot.pdfSha256ByFilename)
        assertEquals(conceptsHash, index.canonicalPdfSha256(File(pdfs, "concepts.pdf")))
        assertEquals(null, index.canonicalPdfSha256(File(root, "concepts.pdf")))
        assertEquals(
            SourcePdfCapability.SOURCE_LINKED,
            index.capabilityFor(File(pdfs, "concepts.pdf")),
        )
        assertEquals(
            SourcePdfCapability.NOT_SOURCE_LINKED,
            index.capabilityFor(File(pdfs, "2512.13961v2-2.pdf")),
        )
        assertEquals(
            SourcePdfCapability.NOT_SOURCE_LINKED,
            index.capabilityFor(File(root, "concepts.pdf")),
        )
    }

    @Test
    fun missingMalformedOrUnsupportedIndexIsUnavailable() {
        assertFalse(index.snapshot().available)

        indexFile.writeText("{malformed")
        index.invalidate()
        assertFalse(index.snapshot().available)

        indexFile.writeText("""{"schema_version":2,"pdfs":[]}""")
        index.invalidate()
        assertFalse(index.snapshot().available)
    }

    @Test
    fun invalidFilenameRejectsEntireIndex() {
        writeIndex("""[{"filename":"../concepts.pdf","project":"docs-concepts","pdf_sha256":"$conceptsHash"}]""")

        assertFalse(index.snapshot().available)
    }

    @Test
    fun missingHashKeepsLegacyAllowlistButInvalidHashRejectsEntireIndex() {
        writeIndex("""[{"filename":"concepts.pdf","project":"docs-concepts"}]""")
        assertTrue(index.snapshot().available)
        assertTrue(index.isSourceLinked(File(pdfs, "concepts.pdf")))
        assertEquals(null, index.canonicalPdfSha256(File(pdfs, "concepts.pdf")))

        writeIndex("""[{"filename":"concepts.pdf","project":"docs-concepts","pdf_sha256":"bad"}]""")
        assertFalse(index.snapshot().available)
    }

    @Test
    fun invalidationReloadsAtomicallyReplacedContents() {
        writeIndex("""[{"filename":"concepts.pdf","project":"docs-concepts","pdf_sha256":"$conceptsHash"}]""")
        assertTrue(index.isSourceLinked(File(pdfs, "concepts.pdf")))

        writeIndex("""[{"filename":"playlists.pdf","project":"docs-playlists","pdf_sha256":"$playlistsHash"}]""")
        index.invalidate()

        assertFalse(index.isSourceLinked(File(pdfs, "concepts.pdf")))
        assertTrue(index.isSourceLinked(File(pdfs, "playlists.pdf")))
    }

    @Test
    fun libraryDisplayNameIsMetadataAndCanonicalIdentityStaysUnchanged() {
        val libraryIndex = File(pdfs, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME)
        libraryIndex.writeText(
            """{"schema_version":1,"pdfs":[{"filename":"paper.pdf","kind":"source_linked","pdf_sha256":"$conceptsHash","display_name":"Readable Paper","archived":true}]}"""
        )
        index.invalidate()

        val snapshot = index.snapshot()

        assertTrue(snapshot.libraryAvailable)
        assertEquals(LibraryPdfKind.SOURCE_LINKED, snapshot.libraryKindsByFilename["paper.pdf"])
        assertEquals(conceptsHash, snapshot.libraryPdfSha256ByFilename["paper.pdf"])
        assertEquals("Readable Paper", snapshot.libraryDisplayNamesByFilename["paper.pdf"])
        assertEquals(setOf("paper.pdf"), snapshot.archivedLibraryFilenames)
        assertEquals(
            LibraryPdfKind.SOURCE_LINKED,
            snapshot.libraryKindFor(File(pdfs, "paper.pdf"), pdfs),
        )
    }

    @Test
    fun activatedGenerationCarriesPublicationTimeAndExactIdentity() {
        val revision = "c".repeat(64)
        val sourceIndexHash = "d".repeat(64)
        val publishedAt = "2026-08-13T10:11:12Z"
        val paper = File(pdfs, "paper.pdf").apply { writeText("verified bytes") }
        val paperHash = requireNotNull(computeSha256(paper))
        File(pdfs, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME).writeText(
            """{"schema_version":1,"library_revision":"$revision","source_index_sha256":"$sourceIndexHash","pdfs":[{"filename":"paper.pdf","kind":"unlinked","pdf_sha256":"$paperHash","published_at":"$publishedAt"}]}""",
        )
        File(pdfs, SourceLinkedPdfIndex.GENERATION_FILENAME).writeText(
            """{"schema_version":1,"library_revision":"$revision","source_index_sha256":"$sourceIndexHash","publications":[{"filename":"paper.pdf","sha256":"$paperHash","size_bytes":${paper.length()},"metadata":{"kind":"unlinked"}}]}""",
        )
        index.invalidate()

        val snapshot = index.snapshot()

        assertTrue(snapshot.generationCurrent)
        assertEquals(publishedAt, snapshot.libraryPublishedAtByFilename[paper.name])
        assertEquals(paperHash, index.canonicalLibraryPdfSha256(paper))

        val extra = File(pdfs, "outside-generation.pdf").apply { writeText("extra") }
        index.invalidate()
        assertTrue(index.snapshot().generationCurrent)
        assertTrue(extra.delete())
        index.invalidate()
        assertTrue(index.snapshot().generationCurrent)

        paper.appendText("changed")
        index.invalidate()
        assertTrue(index.snapshot().generationCurrent)
        assertEquals(null, index.canonicalLibraryPdfSha256(paper))
    }

    @Test
    fun brokenProjectDoesNotInvalidateAnotherProjectsActivatedIdentity() {
        val revision = "c".repeat(64)
        val sourceIndexHash = "d".repeat(64)
        val alpha = File(pdfs, "alpha.pdf").apply { writeText("alpha") }
        val alphaHash = requireNotNull(computeSha256(alpha))
        val betaHash = "b".repeat(64)
        File(pdfs, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME).writeText(
            """{"schema_version":1,"library_revision":"$revision","source_index_sha256":"$sourceIndexHash","pdfs":[{"filename":"alpha.pdf","kind":"unlinked","pdf_sha256":"$alphaHash"},{"filename":"beta.pdf","kind":"unlinked","pdf_sha256":"$betaHash"}]}""",
        )
        File(pdfs, SourceLinkedPdfIndex.GENERATION_FILENAME).writeText(
            """{"schema_version":1,"library_revision":"$revision","source_index_sha256":"$sourceIndexHash","publications":[{"filename":"alpha.pdf","sha256":"$alphaHash","size_bytes":5,"metadata":{"kind":"unlinked"}},{"filename":"beta.pdf","sha256":"$betaHash","size_bytes":100,"metadata":{"kind":"unlinked"}}]}""",
        )
        index.invalidate()
        assertTrue(index.snapshot().generationCurrent)
        assertEquals(alphaHash, index.canonicalLibraryPdfSha256(alpha))
        assertEquals(null, index.canonicalLibraryPdfSha256(File(pdfs, "beta.pdf")))
        assertEquals(mapOf("alpha.pdf" to alphaHash), index.snapshot().activatedPdfSha256ByFilename)
    }

    @Test
    fun commentIdentityRequiresTheExactPublishedHash() {
        val canonical = File(pdfs, "concepts.pdf")
        val outside = File(root, "concepts.pdf")

        assertEquals(
            SourceLinkedCommentIdentity.INDEX_UNAVAILABLE,
            index.commentIdentity(canonical, conceptsHash),
        )

        writeIndex("""[{"filename":"concepts.pdf","project":"docs-concepts"}]""")
        assertEquals(
            SourceLinkedCommentIdentity.PUBLICATION_UNVERIFIED,
            index.commentIdentity(canonical, conceptsHash),
        )

        writeIndex("""[{"filename":"concepts.pdf","project":"docs-concepts","pdf_sha256":"$conceptsHash"}]""")
        assertEquals(
            SourceLinkedCommentIdentity.VERIFIED,
            index.commentIdentity(canonical, conceptsHash),
        )
        assertEquals(
            SourceLinkedCommentIdentity.HASH_MISMATCH,
            index.commentIdentity(canonical, playlistsHash),
        )
        assertEquals(
            SourceLinkedCommentIdentity.NOT_SOURCE_LINKED,
            index.commentIdentity(outside, conceptsHash),
        )
    }

    private fun writeIndex(entries: String) {
        indexFile.writeText("""{"schema_version":1,"pdfs":$entries}""")
        index.invalidate()
    }
}

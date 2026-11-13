// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentCatalogueTest {

    @Test
    fun countsOnlyUnresolvedTopLevelThreadsAndSearchesReplies() {
        val root = Files.createTempDirectory("comment-catalogue").toFile()
        try {
            File(root, "paper_comments.jsonl").writeText(
                """
                {"id":"top","revision":1,"updated_at":"2026-07-25T10:00:00Z","anchor_status":"anchored","contents":"root request"}
                {"id":"reply","revision":1,"updated_at":"2026-07-25T11:00:00Z","in_reply_to":"top","contents":"phone follow up"}
                {"id":"resolved","revision":1,"updated_at":"2026-07-25T12:00:00Z","contents":"done request"}
                {"id":"deleted","revision":2,"updated_at":"2026-07-25T13:00:00Z","deleted":true,"contents":"deleted"}
                """.trimIndent() + "\n"
            )
            File(root, "review_resolutions.jsonl").writeText(
                """
                {"id":"resolved","revision":2,"updated_at":"2026-07-25T12:30:00Z","deleted":true,"source_anchor_deletion":true,"origin_id":"mac-tex-anchor"}
                """.trimIndent() + "\n"
            )

            val metadata = CommentCatalogue(root).metadataFor("paper")

            assertEquals(1, metadata.openThreads)
            assertTrue(metadata.searchableText.contains("root request"))
            assertTrue(metadata.searchableText.contains("phone follow up"))
            assertFalse(metadata.searchableText.contains("done request"))
            assertEquals(1_784_977_200_000L, metadata.lastCommentAddedMillis)
            assertEquals(2, metadata.liveComments)
            assertEquals(1, metadata.anchoredComments)
            assertEquals(1, metadata.unanchoredComments)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun newerConflictRecordWinsWithoutCountingReplyAsThread() {
        val root = Files.createTempDirectory("comment-catalogue-conflict").toFile()
        try {
            File(root, "paper_comments.jsonl").writeText(
                """{"id":"same","revision":1,"updated_at":"2026-07-25T10:00:00Z","contents":"old"}""" + "\n"
            )
            File(root, "paper_comments.sync-conflict-phone.jsonl").writeText(
                """{"id":"same","revision":2,"updated_at":"2026-07-25T11:00:00Z","in_reply_to":"parent","contents":"new phone text"}""" + "\n"
            )

            val metadata = CommentCatalogue(root).metadataFor("paper.pdf")

            assertEquals(0, metadata.openThreads)
            assertTrue(metadata.searchableText.contains("new phone text"))
            assertFalse(metadata.searchableText.contains("old"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun commentEditsDoNotChangeWhenTheLastCommentWasAdded() {
        val root = Files.createTempDirectory("comment-catalogue-created").toFile()
        try {
            File(root, "paper_comments.jsonl").writeText(
                """
                {"id":"top","revision":3,"created":"2026-07-25T10:00:00Z","updated_at":"2026-07-27T14:00:00Z","contents":"edited root"}
                {"id":"reply","revision":1,"created":"2026-07-25T11:00:00Z","updated_at":"2026-07-25T11:00:00Z","in_reply_to":"top","contents":"newer reply"}
                """.trimIndent() + "\n"
            )

            val metadata = CommentCatalogue(root).metadataFor("paper")
            val noComments = CommentCatalogue(root).metadataFor("no-comments")

            assertEquals(1_784_977_200_000L, metadata.lastCommentAddedMillis)
            assertEquals(1, metadata.openThreads)
            assertEquals(0L, noComments.lastCommentAddedMillis)
            assertEquals(0, noComments.openThreads)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun overviewLoadsSearchableCommentDetailsAndSuppressesResolvedRecords() {
        val root = Files.createTempDirectory("comment-overview").toFile()
        try {
            File(root, "paper_comments.jsonl").writeText(
                """
                {"id":"top","page":3,"subtype":"Highlight","revision":2,"created":"2026-07-25T10:00:00Z","updated_at":"2026-07-25T10:00:00Z","anchor_status":"anchored","contents":"root request","selected_text":"quoted passage","author":"Alex","tex_file":"paper.tex","tex_line":42,"chatgpt_url":"https://chatgpt.com/share/example"}
                {"id":"reply","page":3,"subtype":"Text","revision":2,"updated_at":"2026-07-25T11:00:00Z","in_reply_to":"top","contents":"phone follow up"}
                {"id":"resolved","page":4,"revision":1,"updated_at":"2026-07-25T12:00:00Z","contents":"done request"}
                """.trimIndent() + "\n"
            )
            File(root, "review_resolutions.jsonl").writeText(
                """{"id":"resolved","revision":2,"updated_at":"2026-07-25T12:30:00Z","deleted":true,"source_anchor_deletion":true,"origin_id":"mac-tex-anchor"}""" + "\n"
            )

            val documents = CommentCatalogue(root).overviewDocuments()

            assertEquals(1, documents.size)
            assertEquals("paper", documents.single().stem)
            assertEquals(listOf("reply", "top"), documents.single().comments.map { it.id })
            val top = documents.single().comments.last()
            assertEquals(2, top.pageIndex)
            assertEquals("paper.tex", top.sourceFile)
            assertEquals(42, top.sourceLine)
            assertEquals("https://chatgpt.com/share/example", top.chatGptUrl)
            assertEquals(1_784_973_600_000L, top.activityMillis)
            assertTrue(top.searchText.contains("quoted passage"))
            assertTrue(documents.single().comments.first().isReply)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun overviewUsesNewestConflictRevision() {
        val root = Files.createTempDirectory("comment-overview-conflict").toFile()
        try {
            File(root, "paper_comments.jsonl").writeText(
                """{"id":"same","page":1,"revision":1,"updated_at":"2026-07-25T10:00:00Z","contents":"old"}""" + "\n"
            )
            File(root, "paper_comments.sync-conflict-phone.jsonl").writeText(
                """{"id":"same","page":1,"revision":2,"updated_at":"2026-07-25T11:00:00Z","contents":"new"}""" + "\n"
            )

            val comment = CommentCatalogue(root).overviewDocuments().single().comments.single()

            assertEquals("new", comment.contents)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun overviewDefaultsLegacyPriorityAndReadsConfirmedLocation() {
        val root = Files.createTempDirectory("comment-overview-metadata").toFile()
        try {
            File(root, "paper_comments.jsonl").writeText(
                """
                {"id":"legacy","page":1,"revision":1,"updated_at":"2026-08-02T10:00:00Z","contents":"legacy"}
                {"id":"placed","page":1,"revision":1,"updated_at":"2026-08-02T11:00:00Z","contents":"meet here","priority":"high","color":[0.25,0.5,1.0],"opacity":0.5,"location":{"label":"Bibliothèque François-Mitterrand","latitude":48.8335,"longitude":2.3758,"google_place_id":"place-id","source":"google_maps","confirmed_at":"2026-08-02T10:59:00Z"}}
                """.trimIndent() + "\n",
            )

            val comments = CommentCatalogue(root).overviewDocuments().single().comments
            val legacy = comments.single { it.id == "legacy" }
            val placed = comments.single { it.id == "placed" }

            assertEquals(CommentPriority.NORMAL, legacy.priority)
            assertEquals(null, legacy.location)
            assertEquals(CommentCreationMetadata.DEFAULT_POPUP_COLOR, legacy.color)
            assertEquals(CommentPriority.HIGH, placed.priority)
            assertEquals(0x7F3F7FFF, placed.color)
            assertEquals("Bibliothèque François-Mitterrand", placed.location?.label)
            assertEquals("place-id", placed.location?.googlePlaceId)
            assertTrue(placed.searchText.contains("Bibliothèque François-Mitterrand"))
            assertTrue(placed.searchText.contains("high"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun commentsForReturnsOnlyCurrentRecordsWithExportFields() {
        val root = Files.createTempDirectory("comment-share-catalogue").toFile()
        try {
            File(root, "paper_comments.jsonl").writeText(
                """
                {"id":"root","page":2,"subtype":"Highlight","revision":1,"updated_at":"2026-08-09T10:00:00Z","contents":"current","source_context":"surrounding source","anchor_confidence":"exact","rect":[10.0,20.0,30.0,40.0]}
                {"id":"reply","page":2,"revision":1,"updated_at":"2026-08-09T11:00:00Z","in_reply_to":"root","contents":"reply"}
                {"id":"deleted","page":3,"revision":2,"updated_at":"2026-08-09T12:00:00Z","deleted":true,"contents":"do not export"}
                {"id":"resolved","page":4,"revision":1,"updated_at":"2026-08-09T13:00:00Z","contents":"also do not export"}
                """.trimIndent() + "\n",
            )
            File(root, "review_resolutions.jsonl").writeText(
                """{"id":"resolved","revision":2,"updated_at":"2026-08-09T13:30:00Z","deleted":true,"source_anchor_deletion":true,"origin_id":"mac-tex-anchor"}""" + "\n",
            )

            val comments = CommentCatalogue(root).commentsFor("paper.pdf")
            val current = comments.single { it.id == "root" }

            assertEquals(listOf("root", "reply"), comments.map { it.id })
            assertEquals("surrounding source", current.sourceContext)
            assertEquals("exact", current.anchorConfidence)
            assertEquals(listOf(10f, 20f, 30f, 40f), current.pdfRect)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun snapshotBuildsLibraryAndOverviewDataOnceUntilInvalidated() {
        val root = Files.createTempDirectory("comment-snapshot").toFile()
        try {
            val sidecar = File(root, "paper_comments.jsonl")
            sidecar.writeText(
                """{"id":"first","page":1,"revision":1,"updated_at":"2026-07-25T10:00:00Z","contents":"first text"}""" + "\n"
            )
            val catalogue = CommentCatalogue(root)

            val first = catalogue.snapshot()
            val cached = catalogue.snapshot()

            assertSame(first, cached)
            assertEquals(1, first.metadataFor("paper.pdf").openThreads)
            assertEquals("first", first.overviewDocuments.single().comments.single().id)

            sidecar.appendText(
                """{"id":"second","page":2,"revision":1,"updated_at":"2026-07-25T11:00:00Z","contents":"second text"}""" + "\n"
            )
            catalogue.invalidate()
            val updated = catalogue.snapshot()

            assertNotSame(first, updated)
            assertEquals(2, updated.metadataFor("paper").openThreads)
            assertEquals(listOf("second", "first"), updated.overviewDocuments.single().comments.map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun persistentSnapshotIsReusedAndRejectedWhenSidecarChanges() {
        val root = Files.createTempDirectory("comment-persistent-snapshot").toFile()
        try {
            val sidecar = File(root, "paper_comments.jsonl")
            val cache = File(root, "private-cache/comment-catalogue.json")
            sidecar.writeText(
                """{"id":"first","page":1,"revision":1,"updated_at":"2026-07-25T10:00:00Z","contents":"first text"}""" + "\n"
            )
            val original = CommentCatalogue(root, cache).snapshot()
            val restored = CommentCatalogue(root, cache).snapshot()

            assertTrue(cache.isFile)
            assertEquals(original, restored)

            sidecar.appendText(
                """{"id":"second","page":2,"revision":1,"updated_at":"2026-07-25T11:00:00Z","contents":"second text"}""" + "\n"
            )
            val rebuilt = CommentCatalogue(root, cache).snapshot()

            assertEquals(2, rebuilt.metadataFor("paper").openThreads)
            assertEquals(listOf("second", "first"), rebuilt.overviewDocuments.single().comments.map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun persistentSnapshotWithoutLogicalFieldsIsRejected() {
        val root = Files.createTempDirectory("comment-legacy-persistent-snapshot").toFile()
        try {
            val sidecar = File(root, "paper_comments.jsonl")
            val cache = File(root, "private-cache/comment-catalogue.json")
            sidecar.writeText(
                """{"id":"current","page":1,"revision":1,"updated_at":"2026-08-27T10:00:00Z","contents":"current text"}""" + "\n"
            )
            cache.parentFile?.mkdirs()
            cache.writeText(
                """{"schemaVersion":5,"sourceSignature":"stale","snapshot":{"overviewDocuments":[{"stem":"paper","comments":[{"id":"stale"}]}]}}"""
            )

            val rebuilt = CommentCatalogue(root, cache).snapshot(usePersistentCacheImmediately = true)

            assertEquals("current", rebuilt.overviewDocuments.single().comments.single().id)
            assertEquals("", rebuilt.overviewDocuments.single().comments.single().logicalPayloadJson)
        } finally {
            root.deleteRecursively()
        }
    }
}

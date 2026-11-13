// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.sync.isolatedOperationBatches
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Comment missing" (desktop docs/comment-missing.md): detection, Remove Marker rules and Restore payloads. */
class CommentMissingStateTest {
    private val path = "docs-one/tex/source/main.tex"
    private val generated = "docs-one/tex/generated/table.tex"

    private val source =
        "\\CommentAnchor{live}{Live comment}{}%\n" +                       // 1 live
            "Text before \\CommentAnchor{dead}{Deleted comment}{}%\n" +     // 2 deleted, text before
            "\\CommentAnchor{unknown}{Unknown comment}{}%\n" +              // 3 unknown, own line
            "Words \\CommentHighlight{hl}{Highlight note}{kept words} end.\n" + // 4 deleted highlight
            "% \\CommentAnchor{commented}{In a TeX comment}{}%\n" +         // 5 commented out
            "\\begin{verbatim}\n" +
            "\\CommentAnchor{verb}{In verbatim}{}%\n" +                     // 7 verbatim
            "\\end{verbatim}\n" +
            "\\CommentAnchor{thoughts-formlive}{Other id form}{}%\n" +      // 9 live under another id form
            "\\CommentAnchor{unknown2}{Recreate me}{}%\n" +                 // 10 unknown
            "\\CommentAnchor{docgone}{Document deleted}{}%\n" +             // 11 deleted with its document
            "\\ReviewAnchor{review}{A review anchor}%\n" +                  // 12 never reported
            "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]" +
            "{agent-1}{Why}{Old \\CommentAnchor{oldtext}{In proposal old text}{}}{New text}\n" + // 13 proposal old text
            "End.\n"

    private fun comment(id: String, deleted: Boolean = false, extra: JsonObject.() -> Unit = {}) = JsonObject().apply {
        addProperty("schema_version", 1); addProperty("comment_id", id); addProperty("thread_root_id", id)
        addProperty("revision", 3); addProperty("deleted", deleted); addProperty("subtype", "Text")
        addProperty("contents", "Old text of $id"); addProperty("source_authority", "client-v1")
        add("origin", JsonObject().apply { addProperty("collection", "main"); addProperty("page_index", 0) })
        add("scope", JsonObject().apply {
            addProperty("state", "source_linked"); addProperty("anchor_id", id); addProperty("source_file", path)
            addProperty("reason", "unique_source_marker")
        })
        extra()
    }

    private val logical = listOf(
        comment("live"),
        comment("dead", true) { addProperty("deletion_reason", "canonical_source_marker_removed") },
        comment("hl", true) { addProperty("subtype", "Highlight"); addProperty("deletion_reason", "canonical_source_marker_removed") },
        comment("formlive") { add("scope", JsonObject().apply { addProperty("state", "pdf_only") }) },
        comment("docgone", true) { addProperty("deletion_reason", "document_deleted") },
    )

    private fun index(files: Map<String, Pair<String, Boolean>>, complete: Boolean = true) = JsonObject().apply {
        addProperty("schema_version", 1)
        add("files", JsonObject().apply {
            files.forEach { (path, value) -> add(path, SourceAnnotationMarkup.scan(value.first, value.second).apply { addProperty("project", SourceOwner.of(path)) }) }
        })
        add("projects", JsonObject().apply { files.keys.mapNotNull(SourceOwner::of).distinct().forEach { addProperty(it, complete) } })
    }

    private fun detect(store: CommentMissingStore, files: Map<String, Pair<String, Boolean>>, complete: Boolean = true) =
        CommentMissingState.detect(store, index(files, complete), files.keys.associateWith { listOf("main.pdf") })

    @Test fun identityFoldsTheIdForms() {
        val uuid = "8F0C4A8E-5B7D-4C1E-9A3F-2D6B1E7C9A10"
        assertEquals(uuid.lowercase(), CommentMissingState.identity(uuid))
        assertEquals(uuid.lowercase(), CommentMissingState.identity("thoughts-$uuid"))
        assertEquals(uuid.lowercase(), CommentMissingState.identity("thoughts-{$uuid}"))
        assertEquals("{}", CommentMissingState.identity("{}"))
    }

    @Test fun nothingIsReportedBeforeTheFirstSync() {
        assertFalse(CommentMissingState.storeLoaded(bootstrapped = false, cursor = 0, hasProjections = false))
        assertTrue(CommentMissingState.storeLoaded(bootstrapped = false, cursor = 3, hasProjections = false))
        assertTrue(CommentMissingState.storeLoaded(bootstrapped = true, cursor = 0, hasProjections = false))
        assertTrue(CommentMissingState.storeLoaded(bootstrapped = false, cursor = 0, hasProjections = true))
        assertTrue(detect(CommentMissingStore(false, emptyList()), mapOf(path to (source to false))).isEmpty())
    }

    @Test fun detectsDeletedAndUnknownMarkersOutsideIgnoredContexts() {
        val found = detect(CommentMissingStore(true, logical), mapOf(path to (source to false), generated to ("\\CommentAnchor{generated}{Generated}{}%\n" to false)))
            .associateBy { it.anchorId }
        assertEquals(setOf("dead", "unknown", "hl", "unknown2", "docgone"), found.keys)
        val dead = found.getValue("dead")
        assertEquals(MissingCommentMarker.STATE_DELETED, dead.state)
        assertEquals(2, dead.line)
        assertEquals("Deleted comment", dead.contents)
        assertEquals(path, dead.sourceFile)
        assertEquals(listOf("main.pdf"), dead.targets)
        assertEquals("anchor", dead.kind)
        assertEquals("dead", dead.commentId)
        assertEquals("canonical_source_marker_removed", dead.deletionReason)
        assertNull(dead.selectedText)
        assertEquals(MissingCommentMarker.STATE_UNKNOWN, found.getValue("unknown").state)
        assertNull(found.getValue("unknown").commentId)
        assertEquals("highlight", found.getValue("hl").kind)
        assertEquals("kept words", found.getValue("hl").selectedText)
        assertEquals(MissingCommentMarker.STATE_DELETED, found.getValue("docgone").state)
        // One row per occurrence.
        val twice = detect(CommentMissingStore(true, logical), mapOf(path to ("\\CommentAnchor{gone}{A}{}%\nText.\n\\CommentAnchor{gone}{B}{}%\n" to false)))
        assertEquals(listOf(1, 3), twice.map { it.line })
        assertEquals(2, twice.map { it.rowId }.toSet().size)
    }

    @Test fun onlyCompletelyScannedProjectsAreChecked() {
        assertTrue(detect(CommentMissingStore(true, logical), mapOf(path to (source to false)), complete = false).isEmpty())
        // A file the lexer could not read cleanly makes its whole project unreadable.
        val broken = index(mapOf(path to (source to false))).apply {
            getAsJsonObject("files").getAsJsonObject(path).addProperty("valid", false)
        }
        assertTrue(CommentMissingState.detect(CommentMissingStore(true, logical), broken, emptyMap()).isEmpty())
        // A path without a Project owner is not a managed source.
        assertTrue(detect(CommentMissingStore(true, logical), mapOf("loose/main.tex" to (source to false))).isEmpty())
    }

    @Test fun liveCommentsMatchByIdentityAnchorAndLegacyAnnotations() {
        val uuid = "8f0c4a8e-5b7d-4c1e-9a3f-2d6b1e7c9a10"
        val text = "\\CommentAnchor{thoughts-{$uuid}}{Braced}{}%\n\\CommentAnchor{anchored}{By anchor id}{}%\n" +
            "\\CommentAnchor{legacy}{Legacy}{}%\n\\CommentAnchor{legacydead}{Legacy shadowed}{}%\n\\CommentAnchor{reply}{A reply id}{}%\n"
        val store = CommentMissingStore(true, listOf(
            comment(uuid),
            comment("other") { getAsJsonObject("scope").addProperty("anchor_id", "anchored") },
            // A deleted logical comment hides a legacy annotation of the same id.
            comment("legacydead", true),
            // A deleted reply is never the deleted row of a marker.
            comment("reply", true) { addProperty("parent_id", "other") },
        ), listOf("legacy" to JsonObject(), "legacydead" to JsonObject()))
        val found = detect(store, mapOf(path to (text to false))).associateBy { it.anchorId }
        assertEquals(setOf("legacydead", "reply"), found.keys)
        assertEquals(MissingCommentMarker.STATE_DELETED, found.getValue("legacydead").state)
        assertEquals(MissingCommentMarker.STATE_UNKNOWN, found.getValue("reply").state)
        // A deleted legacy annotation is not live.
        assertEquals(setOf("legacy"), detect(CommentMissingStore(true, emptyList(), listOf("legacy" to JsonObject().apply { addProperty("deleted", true) })),
            mapOf(path to ("\\CommentAnchor{legacy}{Legacy}{}%\n" to false))).map { it.anchorId }.toSet())
    }

    @Test fun deletedRowPrefersAnExactMatch() {
        val similar = comment("thoughts-X", true)
        val exact = comment("other", true) { getAsJsonObject("scope").addProperty("anchor_id", "thoughts-x") }
        assertEquals("other", CommentMissingState.deletedCommentForMarker(listOf(similar, exact), "thoughts-x")?.get("comment_id")?.asString)
        assertEquals("thoughts-X", CommentMissingState.deletedCommentForMarker(listOf(similar), "x")?.get("comment_id")?.asString)
        assertNull(CommentMissingState.deletedCommentForMarker(listOf(comment("x")), "x"))
    }

    @Test fun markdownMarkersInCodeAreIgnored() {
        val markdown = "# Title\n\n" +
            "<!-- thoughts:v1:meta {\"key\":\"comment:mdgone:s1\",\"contents\":\"Markdown note\"} -->\n" +
            "<!-- thoughts:v1:point comment:mdgone:s1 -->\n" +
            "```\n<!-- thoughts:v1:meta {\"key\":\"comment:fenced:s1\",\"contents\":\"Fenced\"} -->\n<!-- thoughts:v1:point comment:fenced:s1 -->\n```\n" +
            "    <!-- thoughts:v1:meta {\"key\":\"comment:indented:s1\",\"contents\":\"Indented\"} -->\n" +
            "`<!-- thoughts:v1:meta {\"key\":\"comment:inline:s1\",\"contents\":\"Inline\"} -->`\n"
        val found = detect(CommentMissingStore(true, emptyList()), mapOf("docs-one/markdown/notes.md" to (markdown to true)))
        assertEquals(listOf("mdgone"), found.map { it.anchorId })
        assertEquals("markdown", found.single().kind)
        assertEquals("Markdown note", found.single().contents)
        assertEquals(3, found.single().line)
    }

    @Test fun managedSourcesExcludeGeneratedAndUntrackedFiles() {
        val tracked = listOf(path, generated, "docs-one/notes.txt")
        assertTrue(CommentMissingState.isManagedSource(path, tracked))
        assertFalse(CommentMissingState.isManagedSource(generated, tracked))
        assertFalse(CommentMissingState.isManagedSource("tex/generated/a.tex", listOf("tex/generated/a.tex")))
        assertFalse(CommentMissingState.isManagedSource("docs-one/notes.txt", tracked))
        assertFalse(CommentMissingState.isManagedSource("docs-one/tex/source/other.tex", tracked))
        assertFalse(CommentMissingState.isManagedSource("docs-one/../docs-one/tex/source/main.tex", tracked + "docs-one/../docs-one/tex/source/main.tex"))
    }

    private fun removed(text: String, id: String, markdown: Boolean = false): String =
        (CommentMissingState.remove(text, markdown, id) as CommentMissingState.Removal.Edited).text

    @Test fun removeMarkerRules() {
        // Text before the marker keeps its trailing '%'; a marker alone on its line takes the line.
        assertEquals("Text %\nNext.\n", removed("Text \\CommentAnchor{x}{t}{}%\nNext.\n", "x"))
        assertEquals("Before.\nAfter.\n", removed("Before.\n\\CommentAnchor{x}{t}{}%\nAfter.\n", "x"))
        assertEquals("Before.\nAfter.\n", removed("Before.\n  \\CommentAnchor{x}{t}%\nAfter.\n", "x"))
        assertEquals("Before.\n", removed("Before.\n\\CommentAnchor{x}{t}", "x"))
        // Text after the marker keeps the line.
        assertEquals(" more text.\n", removed("\\CommentAnchor{x}{t}{} more text.\n", "x"))
        // A highlight or strikethrough keeps its words.
        assertEquals("Words kept words end.\n", removed("Words \\CommentHighlight{hl}{Note}{kept words} end.\n", "hl"))
        assertEquals("More struck words.\n", removed("More \\CommentStrikethrough{s}{Note}{struck words}.\n", "s"))
        // Every other marker is kept.
        val two = "\\CommentAnchor{a}{A}{}%\n\\CommentAnchor{b}{B}{}%\n"
        assertEquals("\\CommentAnchor{b}{B}{}%\n", removed(two, "a"))
        assertTrue(CommentMissingState.removalVerified(two, removed(two, "a"), false, "a"))
        // Refusals: absent, or more than once in the file.
        assertEquals(CommentMissingState.Removal.Missing, CommentMissingState.remove("% \\CommentAnchor{x}{t}{}\n", false, "x"))
        assertEquals(CommentMissingState.Removal.Duplicated(2), CommentMissingState.remove(two + "\\CommentAnchor{a}{A}{}%\n", false, "a"))
    }

    @Test fun removalVerificationRejectsCollateralChanges() {
        val before = "\\CommentAnchor{a}{A}{}%\n\\CommentAnchor{b}{B}{}%\n"
        assertFalse(CommentMissingState.removalVerified(before, "", false, "a"))
        assertFalse(CommentMissingState.removalVerified(before, before, false, "a"))
        assertFalse(CommentMissingState.removalVerified(before, "\\CommentAnchor{b}{B}{}%\n\\CommentAnchor{c}{C}{}%\n", false, "a"))
        assertTrue(CommentMissingState.removalVerified(before, "\\CommentAnchor{b}{B}{}%\n", false, "a"))
    }

    @Test fun removeMarkdownMarkerAndItsTokens() {
        val point = "Intro.\n<!-- thoughts:v1:meta {\"key\":\"comment:x:s1\",\"contents\":\"Note\"} -->\n<!-- thoughts:v1:point comment:x:s1 -->\nText.\n"
        assertEquals("Intro.\nText.\n", removed(point, "x", true))
        val range = "<!-- thoughts:v1:meta {\"key\":\"comment:y:s1\",\"contents\":\"Note\"} -->\n" +
            "Some <!-- thoughts:v1:start comment:y:s1 -->marked<!-- thoughts:v1:end comment:y:s1 --> words.\n"
        assertEquals("Some marked words.\n", removed(range, "y", true))
    }

    @Test fun restorePayloads() {
        val scope = CommentMissingState.restoredScope("hl", path, 3)
        val marker = CommentMissingState.CurrentMarker(3, "Highlight note", "highlight", "kept words")
        val deleted = comment("hl", true) {
            addProperty("subtype", "Highlight"); addProperty("selected_text", "old words")
            addProperty("deletion_reason", "canonical_source_marker_removed")
            add("source_deletion", JsonObject().apply { add("sources", JsonArray()) })
        }
        val undeleted = CommentMissingState.undeletePayload(deleted, marker, scope, "2026-10-12T10:00:00Z", "thoughts-android")
        assertEquals(4, undeleted.get("revision").asInt)
        assertFalse(undeleted.get("deleted").asBoolean)
        assertFalse(undeleted.has("deletion_reason"))
        assertFalse(undeleted.has("source_deletion"))
        assertEquals("Highlight note", undeleted.get("contents").asString)
        assertEquals("kept words", undeleted.get("selected_text").asString)
        assertEquals(scope, undeleted.getAsJsonObject("scope"))
        assertEquals(deleted.get("origin"), undeleted.get("origin"))
        assertEquals("hl", undeleted.get("thread_root_id").asString)
        assertEquals("2026-10-12T10:00:00Z", undeleted.get("updated_at").asString)

        val created = CommentMissingState.createPayload("unknown2", CommentMissingState.CurrentMarker(10, "Recreate me", "anchor", null),
            CommentMissingState.restoredScope("unknown2", path, 10), "2026-10-12T10:00:00Z", "thoughts-android", "Tester",
            "main.pdf", "8f0c4a8e-5b7d-4c1e-9a3f-2d6b1e7c9a10", "a".repeat(64))
        assertEquals("unknown2", created.get("comment_id").asString)
        assertEquals("unknown2", created.get("thread_root_id").asString)
        assertTrue(created.get("parent_id").isJsonNull)
        assertEquals(1, created.get("revision").asInt)
        assertEquals("Text", created.get("subtype").asString)
        assertEquals("normal", created.get("priority").asString)
        assertEquals("client-v1", created.get("source_authority").asString)
        assertEquals("Tester", created.get("author").asString)
        assertFalse(created.has("selected_text"))
        val origin = created.getAsJsonObject("origin")
        assertEquals("main", origin.get("collection").asString)
        assertEquals("8f0c4a8e-5b7d-4c1e-9a3f-2d6b1e7c9a10", origin.get("document_id").asString)
        assertEquals(0, origin.get("page_index").asInt)
        assertEquals(0, origin.getAsJsonArray("rect").size())
        assertEquals(0, origin.getAsJsonArray("fragments").size())
        assertEquals("StrikeOut", CommentMissingState.createPayload("s", CommentMissingState.CurrentMarker(1, "N", "strikethrough", "w"),
            scope, "now", "o", "a", "main.pdf", "id", "s").get("subtype").asString)
    }

    @Test fun documentDeletionEvidenceBlocksRestore() {
        assertTrue(CommentMissingState.deletedCommentBlocksRestore(comment("a", true) { addProperty("deletion_reason", "document_deleted") }))
        assertTrue(CommentMissingState.deletedCommentBlocksRestore(comment("a", true) {
            add("document_deletion", JsonObject().apply { addProperty("document_id", "d") })
        }))
        assertTrue(CommentMissingState.deletedCommentBlocksRestore(comment("a", true) {
            add("source_deletion", JsonObject().apply { add("sources", JsonArray().apply { add(JsonObject().apply { addProperty("deleted", true) }) }) })
        }))
        assertFalse(CommentMissingState.deletedCommentBlocksRestore(comment("a", true) {
            addProperty("deletion_reason", "canonical_source_marker_removed")
            add("source_deletion", JsonObject().apply { add("sources", JsonArray().apply { add(JsonObject().apply { addProperty("deleted", false) }) }) })
        }))
    }

    @Test fun libraryTargetNeedsAUuid4AndSha256() {
        val identities = mapOf(
            "a.pdf" to JsonObject().apply { addProperty("document_id", "not-a-uuid"); addProperty("pdf_sha256", "a".repeat(64)) },
            "b.pdf" to JsonObject().apply { addProperty("document_id", "8f0c4a8e-5b7d-4c1e-9a3f-2d6b1e7c9a10"); addProperty("pdf_sha256", "b".repeat(64)) },
        )
        assertEquals(Triple("b.pdf", "8f0c4a8e-5b7d-4c1e-9a3f-2d6b1e7c9a10", "b".repeat(64)),
            CommentMissingState.libraryTarget(listOf("c.pdf", "b.pdf", "a.pdf"), identities::get))
        assertNull(CommentMissingState.libraryTarget(listOf("a.pdf"), identities::get))
    }

    @Test fun isolatedOperationsTravelAlone() {
        assertEquals(listOf(listOf("a", "b")), isolatedOperationBatches(listOf("a", "b"), setOf("x")) { it })
        assertEquals(listOf(listOf("x")), isolatedOperationBatches(listOf("x"), setOf("x")) { it })
        assertEquals(listOf(listOf("a"), listOf("x"), listOf("b", "c"), listOf("y")),
            isolatedOperationBatches(listOf("a", "x", "b", "c", "y"), setOf("x", "y")) { it })
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.sync.logicalCommentScopeKeys
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceOwnerTest {
    private val a = ".thoughts/projects/${"a".repeat(64)}/documents"
    private val b = ".thoughts/projects/${"b".repeat(64)}/documents"

    @Test fun `connected and default folders own their paths`() {
        assertEquals("docs-paper", SourceOwner.of("docs-paper/tex/source/main.tex"))
        assertEquals("docs-a_b.c-1", SourceOwner.of("docs-a_b.c-1/notes.md"))
        assertEquals("docs-paper", SourceOwner.of("docs-paper"))
        assertEquals(a, SourceOwner.of("$a/notes.md"))
        assertEquals(a, SourceOwner.of("$a/sub/dir/paper.tex"))
        assertEquals(a, SourceOwner.of(a))
        assertEquals("notes.md", SourceOwner.relative("$a/notes.md"))
        assertEquals("sub/dir/paper.tex", SourceOwner.relative("$a/sub/dir/paper.tex"))
        assertEquals("tex/source/main.tex", SourceOwner.relative("docs-paper/tex/source/main.tex"))
        assertTrue(SourceOwner.isOwner(a))
        assertTrue(SourceOwner.isOwner("docs-paper"))
        assertTrue(SourceOwner.isDefaultFolder(a))
        assertFalse(SourceOwner.isDefaultFolder("docs-paper"))
        // Two Projects' default folders never share an owner.
        assertFalse(SourceOwner.of("$a/notes.md") == SourceOwner.of("$b/notes.md"))
    }

    @Test fun `the renamed default folder owns its paths too`() {
        val renamed = ".thoughts/projects/${"a".repeat(64)}/default"
        assertEquals(renamed, SourceOwner.of("$renamed/notes.md"))
        assertEquals(renamed, SourceOwner.of(renamed))
        assertEquals("sub/paper.tex", SourceOwner.relative("$renamed/sub/paper.tex"))
        assertTrue(SourceOwner.isOwner(renamed))
        assertTrue(SourceOwner.isDefaultFolder(renamed))
        // One Project's two spellings are different owners: a Library uses one of them.
        assertFalse(SourceOwner.of("$renamed/notes.md") == SourceOwner.of("$a/notes.md"))
        assertEquals(setOf("comment:x", "project:$renamed"),
            logicalCommentScopeKeys("x", comment("x", "$renamed/notes.md").toString()))
        for (path in listOf(".thoughts/projects/${"a".repeat(64)}/defaults/n.md", ".thoughts/projects/${"a".repeat(64)}/Default/n.md"))
            assertNull(path, SourceOwner.of(path))
    }

    @Test fun `near misses have no owner`() {
        for (path in listOf(
            ".thoughts/projects/${"A".repeat(64)}/documents/notes.md", // uppercase
            ".thoughts/projects/${"a".repeat(63)}/documents/notes.md", // 63 hex
            ".thoughts/projects/${"a".repeat(65)}/documents/notes.md", // 65 hex
            ".thoughts/projects/${"g".repeat(64)}/documents/notes.md", // not hex
            ".thoughts/projects/${"a".repeat(64)}/notes.md", // missing documents
            ".thoughts/projects/${"a".repeat(64)}/document/notes.md",
            ".thoughts/projects/${"a".repeat(64)}",
            ".thoughts/${"a".repeat(64)}/documents/notes.md",
            ".thoughts/notes.md", ".thoughts", "thoughts/projects/${"a".repeat(64)}/documents/notes.md",
            "x/.thoughts/projects/${"a".repeat(64)}/documents/notes.md",
            "docs/tex/source/main.tex", "docs-/main.tex", "docs-a b/main.tex", "Docs-paper/main.tex", "", "/docs-paper/main.tex",
        )) {
            assertNull(path, SourceOwner.of(path))
            assertNull(path, SourceOwner.relative(path))
            assertFalse(path, SourceOwner.isOwner(path))
        }
        // The whole string must be an owner, not a path inside one.
        assertFalse(SourceOwner.isOwner("$a/notes.md"))
        assertFalse(SourceOwner.isOwner("$a/"))
        assertFalse(SourceOwner.isOwner("docs-paper/main.tex"))
        assertFalse(SourceOwner.isDefaultFolder("$a/notes.md"))
    }

    private fun comment(id: String, source: String) = JsonObject().apply {
        addProperty("comment_id", id); addProperty("thread_root_id", id)
        add("scope", JsonObject().apply { addProperty("state", "source_linked"); addProperty("source_file", source) })
    }

    @Test fun `default folder comments get their own project scope keys`() {
        val first = comment("first", "$a/notes.md")
        val second = comment("second", "$b/notes.md")
        assertEquals(setOf("comment:first", "project:$a"), logicalCommentScopeKeys("first", first.toString()))
        assertEquals(setOf("comment:second", "project:$b"), logicalCommentScopeKeys("second", second.toString()))
        assertEquals(setOf("comment:docs", "project:docs-paper"),
            logicalCommentScopeKeys("docs", comment("docs", "docs-paper/tex/source/main.tex").toString()))
        // No first-segment key that every default folder would share.
        assertFalse(logicalCommentScopeKeys("first", first.toString()).contains("project:.thoughts"))
        assertEquals(setOf("comment:stray"), logicalCommentScopeKeys("stray", comment("stray", ".thoughts/notes.md").toString()))

        // Opening Project A's PDF reads A's default-folder comments and none of B's.
        val known = OfflineLogicalComment("known", "known", null, 1, false, "source_linked", "$a/notes.md", 1, "", 0, "{}")
        val read = OfflineOverviewSourceSnapshot(logicalComments = listOf(known)).logicalReadScope("project-a.pdf")
        assertTrue(read.accepts(first))
        assertFalse(read.accepts(second))
        assertFalse(LogicalCommentReadScope(setOf("project:.thoughts")).accepts(first))
    }
}

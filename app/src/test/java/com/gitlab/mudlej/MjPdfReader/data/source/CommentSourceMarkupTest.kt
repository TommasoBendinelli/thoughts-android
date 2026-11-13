// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import org.junit.Assert.*
import org.junit.Test

class CommentSourceMarkupTest {
    @Test fun markdownMetadataTracksEditsAndPreservesPassageOnDeletion() {
        val source = "<!-- thoughts:v1:meta {\"key\":\"comment:id:s1\",\"contents\":\"Old\"} -->\n" +
            "<!-- thoughts:v1:start comment:id:s1 -->\nPassage.\n<!-- thoughts:v1:end comment:id:s1 -->\n"
        val marker = CommentSourceMarkup.markers(source, true).single()
        assertEquals("Old", marker.contents)
        val updated = CommentSourceMarkup.update(source, marker, "New")
        assertEquals("New", CommentSourceMarkup.markers(updated, true).single().contents)
        val removed = CommentSourceMarkup.remove(source, marker)
        assertTrue(removed.contains("Passage."))
        assertFalse(removed.contains("comment:id:s1"))
        assertTrue(CommentSourceMarkup.markers("```\n$source```\n", true).isEmpty())
        val example = "```\n<!-- thoughts:v1:point comment:id:s1 -->\n```\n"
        assertTrue(CommentSourceMarkup.remove(source + example, marker).endsWith(example))
        val misleading = "<!-- thoughts:v1:meta {\"key\":\"comment:id:s1\",\"contents\":\"Old\"} -->\n" + example
        assertTrue(CommentSourceMarkup.markers(misleading, true).isEmpty())
    }

    @Test fun roundTripAndEditPreserveBody() {
        val source = "Before.\nThe selected words occur here.\nAfter.\n"
        val marked = requireNotNull(CommentSourceMarkup.insert(source, 2, "id-1", "Why {this} & 50%?", "Highlight", "selected words"))
        val marker = CommentSourceMarkup.markers(marked).single()
        assertEquals("Why {this} & 50%?", marker.contents)
        assertEquals("selected words", marker.body)
        val edited = CommentSourceMarkup.update(marked, marker, "A replacement.")
        assertEquals("A replacement.", CommentSourceMarkup.markers(edited).single().contents)
        assertEquals(source, CommentSourceMarkup.remove(edited, CommentSourceMarkup.markers(edited).single()))
    }
    @Test fun proposalsDoNotPreventLinkingElsewhere() {
        val source = "\\agentProposedEdit[id]{why}{before}{after}{body}\nVisible words.\n"
        assertNotNull(CommentSourceMarkup.insert(source, 2, "id-2", "Question.", "Highlight", "Visible words"))
        assertTrue(CommentSourceMarkup.markers("\\agentProposedEdit[id]{why}{\\CommentAnchor{old}{Hidden}}{after}{body}").isEmpty())
        assertNotNull(CommentSourceMarkup.insert("\\section{Visible words}", 1, "id-2", "Question.", "Highlight", "Visible words"))
    }
    @Test fun emptyMirrorStillHasCorrectBoundaries() {
        val value = "\\CommentHighlight{id}{}{Text}"
        val marker = CommentSourceMarkup.markers(value).single()
        assertEquals("", marker.contents)
        assertEquals("Text", marker.body)
        assertEquals("\\CommentHighlight{id}{Hello}{Text}", CommentSourceMarkup.update(value, marker, "Hello"))
    }
    @Test fun ambiguousOrUnsafeSelectionDoesNotChangeSource() {
        assertNull(CommentSourceMarkup.insert("word word", 1, "id", "", "Highlight", "word"))
        assertNull(CommentSourceMarkup.insert("\\word", 1, "id", "", "Highlight", "word"))
        assertNull(CommentSourceMarkup.insert("word % hidden", 1, "id", "", "Highlight", "word"))
    }
    @Test fun commentedAndMalformedMarkersAreNotLive() {
        assertTrue(CommentSourceMarkup.markers("% \\CommentAnchor{id}{hidden}").isEmpty())
        assertTrue(CommentSourceMarkup.markers("\\CommentAnchor{id}{unclosed").isEmpty())
        assertTrue(CommentSourceMarkup.markers("\\begin{verbatim}\n\\CommentAnchor{id}{literal}\n\\end{verbatim}").isEmpty())
    }
    @Test fun deletingAnchorPreservesAdjacentProse() {
        val source = "Before \\CommentAnchor{id}{comment} after."
        assertEquals("Before  after.", CommentSourceMarkup.remove(source, CommentSourceMarkup.markers(source).single()))
    }

    @Test fun restoreInsertersPlacePointAnchorsAndMarkdownMarkers() {
        // A point anchor goes on its own line before the chosen line; unsafe lines are refused.
        val tex = "\\documentclass{article}\n\\begin{document}\n\\section{Intro}\n\nBody text here.\n\\end{document}\n"
        assertNull(CommentSourceMarkup.insert(tex, 3, "c-1", "Note", "Text", ""))
        assertNull(CommentSourceMarkup.insert(tex, 4, "c-1", "Note", "Text", ""))
        val point = requireNotNull(CommentSourceMarkup.insert(tex, 5, "c-1", "Note", "Text", ""))
        assertEquals(5, CommentSourceMarkup.markers(point).single { it.id == "c-1" }.line)
        // With no safe line the anchor goes before \end{document}.
        val end = requireNotNull(CommentSourceMarkup.insertPointAtEnd(tex, "c-1", "50% {odd}"))
        val endMarker = CommentSourceMarkup.markers(end).single()
        assertEquals(6, endMarker.line)
        assertEquals("50% {odd}", endMarker.contents)
        assertTrue(end.endsWith("}%\n\\end{document}\n"))
        assertTrue(CommentSourceMarkup.insertPointAtEnd("No document environment", "c-2", "x")!!.endsWith("\\CommentAnchor{c-2}{x}%\n"))
        assertNull(CommentSourceMarkup.insertPointAtEnd(point, "c-1", "again"))
        // A highlight re-wraps its passage when it occurs exactly once on the line.
        val wrapped = requireNotNull(CommentSourceMarkup.insert(tex, 5, "c-3", "Why?", "StrikeOut", "text"))
        assertEquals("text", CommentSourceMarkup.markers(wrapped).single().body)
        assertTrue(wrapped.contains("\\CommentStrikethrough{c-3}{Why?}{text}"))

        // Markdown: a two-line point marker before a visible line, never inside code.
        val md = "# Title\n\nA paragraph.\n```\ncode\n```\n"
        assertNull(CommentSourceMarkup.insertMarkdownPoint(md, 5, "c-4", "Inside code"))
        assertNull(CommentSourceMarkup.insertMarkdownPoint(md, 3, "bad:id", "Colon"))
        val marked = requireNotNull(CommentSourceMarkup.insertMarkdownPoint(md, 3, "c-4", "Ends --> early <b>"))
        assertTrue(marked.contains("<!-- thoughts:v1:point comment:c-4:s1 -->\nA paragraph."))
        val marker = CommentSourceMarkup.markers(marked, true).single()
        assertEquals("c-4", marker.id)
        assertEquals(3, marker.line)
        assertEquals("Ends --> early <b>", marker.contents)
        assertFalse(CommentSourceMarkup.remove(marked, marker).contains("c-4"))
        val appended = requireNotNull(CommentSourceMarkup.appendMarkdownPoint("Last line", "c-5", "End"))
        assertEquals("c-5", CommentSourceMarkup.markers(appended, true).single().id)
    }
}

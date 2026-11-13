// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ProposalTexExcerptTest {
    @Test fun markdownProposalKeepsExactBranches() {
        val metadata = """{"id":"agent-example","created_at":"2026-09-23T17:34:16.099Z","conversation_id":"0190c000-0000-7000-8000-00000000000c","explanation":"Align with abstract.","original_text":"Old intro.\n","replacement_length":14}"""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(metadata.toByteArray())
        val source = "<!-- thoughts:agent-proposal:v1:start $encoded -->\nNew 😀 intro.\n<!-- thoughts:agent-proposal:v1:end agent-example -->\n"
        assertEquals(ProposalTexExcerpt("Old intro.\n", "New 😀 intro.\n"), proposalTexExcerpt("notes.md", source, "agent-example"))
        assertNull(proposalTexExcerpt("notes.md", source.replace("end agent-example", "end other"), "agent-example"))
    }
    @Test fun readableMarkdownProposalKeepsExactBranches() {
        val source = """<!-- thoughts:agent-proposal:v2:start
id: agent-example
created_at: 2026-09-23T17:34:16.099Z
conversation_id: 0190c000-0000-7000-8000-00000000000c
replacement_length: 11
explanation:
| Align with abstract.
original_text:
| Old &amp; intro.
|${" "}
-->
New intro.
<!-- thoughts:agent-proposal:v2:end agent-example -->
"""
        assertEquals(ProposalTexExcerpt("Old & intro.\n", "New intro.\n"), proposalTexExcerpt("notes.md", source, "agent-example"))
        for (metadata in listOf("", "replacement_length: 1387\n", "replacement_length: 0\n")) {
            for (replacement in listOf("", "Edited 😀 text.\r\n\n", "Longer text.\n".repeat(100))) {
                val edited = source.replace("replacement_length: 11\n", metadata).replace("New intro.\n", replacement)
                assertEquals(ProposalTexExcerpt("Old & intro.\n", replacement), proposalTexExcerpt("notes.md", edited, "agent-example"))
                assertNull(proposalTexExcerpt("notes.md", edited.replace("v2:end agent-example", "v2:end other"), "agent-example"))
                assertNull(proposalTexExcerpt("notes.md", edited.replace("-->\n", "-->\n<!-- thoughts:agent-proposal:v1:start nested -->\n"), "agent-example"))
            }
        }
        assertNull(proposalTexExcerpt("notes.md", source.replace("end agent-example", "end other"), "agent-example"))
    }
    @Test fun editorDetectsAProposalWhoseMarkersNoLongerPair() {
        val proposal = """<!-- thoughts:agent-proposal:v2:start
id: agent-example
created_at: 2026-09-23T17:34:16.099Z
conversation_id: 0190c000-0000-7000-8000-00000000000c
explanation:
| Align with abstract.
original_text:
| Old intro.
-->
New intro.
<!-- thoughts:agent-proposal:v2:end agent-example -->
"""
        val source = "# Responses\n\n" + proposal + "\nMore text.\n"
        fun introduced(after: String, before: String = source) = introducedProposalIssues("notes.md", before, after).map { it.code }
        assertEquals(emptyList<String>(), introduced("# Plain Markdown\n"))
        assertEquals(emptyList<String>(), introduced(source.replace("More text.", "Edited text.")))
        // 2026-10-04: a phone edit dropped the end marker and Fedora could not build the PDF.
        assertEquals(listOf("unmatched_marker"), introduced(source.replace("<!-- thoughts:agent-proposal:v2:end agent-example -->\n", "")))
        assertEquals(listOf("unmatched_marker"), introduced(source.replace("<!-- thoughts:agent-proposal:v2:start\n", "")))
        assertEquals(listOf("duplicate_id", "duplicate_id"), introduced(source + proposal))
        // Full metadata is checked, not only the marker pairing.
        assertEquals(listOf("invalid_metadata"), introduced(source.replace("2026-09-23T17", "2026-02-30T17")))
        assertEquals(listOf("invalid_metadata"), introduced(source.replace("| Align with abstract.\n", "")))
        // A file that was already broken stays saveable while it is repaired elsewhere.
        val broken = source.replace("2026-09-23T17", "2026-09-23 17")
        assertEquals(emptyList<String>(), introduced(broken.replace("More text.", "Edited."), broken))
        // Removing a proposal entirely is an ordinary edit.
        assertEquals(emptyList<String>(), introduced("# Responses\n\nNew intro.\n"))
    }

    @Test fun editorChecksTexProposalsAndIgnoresOtherFormats() {
        val source = "Intro.\n\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{p1}{Why.}{Old.}{New.}\n"
        fun introduced(path: String, after: String) = introducedProposalIssues(path, source, after).map { it.code }
        assertEquals(listOf("unclosed_group"), introduced("doc.tex", source.replace("{New.}", "{New.")))
        assertEquals(listOf("invalid_metadata"), introduced("doc.tex", source.replace("12:00:00.000Z", "12:00:00+02:00")))
        assertEquals(listOf("invalid_id"), introduced("doc.tex", source.replace("{p1}", "{P1}")))
        assertEquals(emptyList<String>(), introduced("doc.tex", source.replace("New.", "Newer.")))
        assertEquals(emptyList<String>(), introduced("notes.txt", source.replace("{New.}", "{New.")))
        assertNull(proposalTexExcerpt("notes.md", source, "p1"))
    }

    @Test fun datedProposalKeepsExactBranches() {
        val source="\\agentProposedEdit[created=2026-09-12T12:13:36Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{id}{Reason.}{Old.}{New.}"
        assertEquals(ProposalTexExcerpt("Old.", "New."), proposalTexExcerpt("doc.tex", source, "id"))
    }
    @Test fun preservesExactRawTexAndMultilineArguments() {
        val text="\\agentProposedEdit [created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]\n{id}\n{Reason.}{Old \\textbf{words}.}{New \\ref{figure}.\nNext.}"
        assertEquals(ProposalTexExcerpt("Old \\textbf{words}.","New \\ref{figure}.\nNext."),proposalTexExcerpt("doc.tex", text,"id"))
    }
    @Test fun ignoresCommentsAndRejectsDuplicateOrNestedProposals() {
        val proposal="\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{id}{Reason.}{}{New.}"
        assertEquals("New.",proposalTexExcerpt("doc.tex", "% $proposal\n$proposal","id")?.after)
        assertNull(proposalTexExcerpt("doc.tex", "$proposal\n$proposal","id"))
        assertNull(proposalTexExcerpt("doc.tex", "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{outer}{Reason.}{}{$proposal}","outer"))
    }
    @Test fun escapedBracesAndCommentBracesDoNotChangeGroupBoundaries() {
        assertEquals("\\{x\\}",proposalTexExcerpt("doc.tex", "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{id}{Reason. % }\n}{}{\\{x\\}}","id")?.after)
        assertNull(proposalTexExcerpt("doc.tex", "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{id}{Reason.}{}{unfinished","id"))
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.sync.locallyAcceptedProposalIds
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 2026-10-10 19:49: three proposals of 06_proposals_review.tex were accepted in the phone's
 * source review editor, yet the open PDF kept marking them as pending until a rebuilt PDF
 * arrived with a later Sync. The page already shows the accepted text, so the reader stops
 * marking an editor Accept as soon as it is saved.
 */
class EditorProposalDecisionsTest {
    private lateinit var root: File
    private lateinit var queue: SourceEditQueue
    private val path = "docs-template/tex/fragments/internals/06_proposals_review.tex"
    private val meta = "[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]"
    private fun wrapper(id: String, after: String) = "\\agentProposedEdit$meta{$id}{reason}{old $id}{$after}"
    private val base = listOf("first", "second", "third").joinToString("\n") { wrapper("agent-$it", "new $it") } + "\n"

    @Before fun setUp() {
        root = Files.createTempDirectory("editor-decisions").toFile()
        queue = SourceEditQueue(File(root, "requests"), File(root, "receipts"), File(root, "cache"), {}, {}, null) {}
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun snapshot() = SourceSnapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191", path, 1, ProposalDecisionClaims.sha256(base), base,
        bundleId = "d".repeat(64), baseContents = base, bundlePdfFilename = "template_thoughts_internals.pdf", bundlePdfSha256 = "b".repeat(64))

    private fun claim(n: Int, id: String, action: String = "accept") =
        ProposalDecisionClaim("00000000-0000-4000-8000-%012d".format(n), id, action, "a".repeat(64), path, "2026-10-10T17:49:20Z")

    private fun save(contents: String, claims: List<ProposalDecisionClaim>) =
        queue.saveForNextSync(snapshot(), "template_thoughts_internals.pdf", "b".repeat(64), contents, claims)

    @Test fun editorAcceptsAreKnownBeforeSyncAndRejectsStayMarked() {
        val saved = base.replace(wrapper("agent-first", "new first"), "new first")
            .replace(wrapper("agent-second", "new second"), "new second")
            .replace(wrapper("agent-third", "new third"), "old agent-third")
        save(saved, listOf(claim(1, "agent-first"), claim(2, "agent-second"), claim(3, "agent-third", "reject")))

        val decisions = queue.editorProposalDecisions(listOf(path, path, "docs-template/tex/other.tex"))

        // The page shows the accepted text already; a Reject keeps its mark until the rebuild.
        assertEquals(setOf("agent-first", "agent-second"), decisions.accepted)
        assertEquals(emptySet<String>(), decisions.carried)
    }

    @Test fun aWrapperRestoredInTheDraftIsNotAcceptedAnyMore() {
        save(base.replace(wrapper("agent-first", "new first"), "new first"), listOf(claim(1, "agent-first")))
        save(base, emptyList()) // Undo in the editor puts the wrapper back.

        val decisions = queue.editorProposalDecisions(listOf(path))

        assertEquals(emptySet<String>(), decisions.accepted)
        assertEquals(setOf("agent-first", "agent-second", "agent-third"), decisions.carried)
    }

    @Test fun theReaderKeepsAnAcceptHiddenUntilThePublishedListDropsIt() {
        val loaded = setOf("agent-first", "agent-second", "agent-third")
        val saved = base.replace(wrapper("agent-first", "new first"), "new first")
            .replace(wrapper("agent-second", "new second"), "new second")
            .replace(wrapper("agent-third", "new third"), "new third")
        save(saved, listOf(claim(1, "agent-first"), claim(2, "agent-second"), claim(3, "agent-third")))

        // Saved in the editor: hidden at once, before any Sync.
        var hidden = locallyAcceptedProposalIds(emptySet(), queue.editorProposalDecisions(listOf(path)), loaded)
        assertEquals(loaded, hidden)

        // Sync #1 (19:49:35): Fedora answers the upload, the claims expire, but the PDF on
        // screen and its proposal list are still the old ones. They stay hidden.
        queue.expireProposalDecisions(path, setOf(claim(1, "").decisionId, claim(2, "").decisionId, claim(3, "").decisionId))
        hidden = locallyAcceptedProposalIds(hidden, queue.editorProposalDecisions(listOf(path)), loaded)
        assertEquals(loaded, hidden)

        // The published artifact confirms: the loaded list no longer has them.
        hidden = locallyAcceptedProposalIds(hidden, queue.editorProposalDecisions(listOf(path)), emptySet())
        assertEquals(emptySet<String>(), hidden)
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import com.gitlab.mudlej.MjPdfReader.data.source.AgentProposals

/** Read-only extraction for the PDF sheet; mutations use the shared JavaScript proposal model. */
internal data class ProposalTexExcerpt(val before: String, val after: String, val conversationUrl: String? = null)

/**
 * Original and replacement of the one valid proposal [id] in [source], or null when the ID is
 * missing, duplicated or names a broken wrapper (agent-proposal spec section 7). The format
 * follows [path]'s extension, never the content.
 */
internal fun proposalTexExcerpt(path: String, source: String, id: String): ProposalTexExcerpt? {
    val parsed = AgentProposals.parse(path, source)
    if (parsed.refusalCode(id) != null) return null
    return parsed.proposals.single { it.id == id }.let { ProposalTexExcerpt(it.original, it.replacement, it.conversationUrl) }
}

/**
 * Proposal issues (spec section 6: broken markers or arguments, invalid metadata or ID, nesting,
 * duplicate IDs) that [after] has and [before] did not, matched by code and ID. The editor must
 * not save an edit that breaks a proposal: Fedora cannot build such a file and the proposal could
 * no longer be reviewed. Issues that were already present do not block saving.
 */
internal fun introducedProposalIssues(path: String, before: String, after: String): List<AgentProposals.Issue> {
    val now = AgentProposals.parse(path, after).issues
    if (now.isEmpty()) return now
    val earlier = AgentProposals.parse(path, before).issues.groupingBy { it.code to it.id }.eachCount().toMutableMap()
    return now.filter { issue ->
        val key = issue.code to issue.id
        val left = earlier[key] ?: 0
        if (left > 0) { earlier[key] = left - 1; false } else true
    }
}

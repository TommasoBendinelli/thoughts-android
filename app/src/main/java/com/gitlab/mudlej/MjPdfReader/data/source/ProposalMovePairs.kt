// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

/**
 * Move pairs (agent-proposal spec section 12): an agent moves a review card's marked passage with
 * two proposals, "agent-<uuid>.cut" where it was and "agent-<uuid>.paste" where it goes, often in
 * another document. Fedora decides the other half with whichever half the phone decides, so the
 * phone sends only the half a document shows; the source editor, which sees one file, decides
 * neither (assets/proposal-review/engine.js).
 */
internal object ProposalMovePairs {
    private val half = Regex("(agent-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(cut|paste)")

    /** The other half's ID, or null when [id] is not a half of a move pair. */
    fun partner(id: String): String? = half.matchEntire(id)?.let { match ->
        match.groupValues[1] + if (match.groupValues[2] == "cut") ".paste" else ".cut"
    }

    fun isHalf(id: String): Boolean = partner(id) != null

    /** [items] with one half of each selected move pair: Fedora decides the other with it. */
    fun <T> oneHalfEach(items: List<T>, id: (T) -> String): List<T> {
        val kept = hashSetOf<String>()
        return items.filter { item ->
            val itemId = id(item)
            (partner(itemId)?.let { it !in kept } ?: true).also { keep -> if (keep) kept += itemId }
        }
    }
}

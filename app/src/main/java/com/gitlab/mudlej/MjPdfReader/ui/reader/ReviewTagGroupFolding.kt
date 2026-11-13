// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import android.content.SharedPreferences
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups

/**
 * Which groups of the tag sheet are open, remembered on this phone across reviews and restarts (not
 * synced), by group key ([ReviewGroupNotes.keyOf]: the group id, "other" for Other). Only groups the
 * user opened or closed are stored; the others follow [reviewTagGroupOpenByDefault]. Same rule as
 * the desktop. Section headers ([ReviewTagGroups.Group.section]) are remembered apart, by section
 * name, and start open.
 */
internal class ReviewTagGroupFolding(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    fun isOpen(key: String, byDefault: Boolean): Boolean =
        reviewTagGroupOpen(key, byDefault, opened(), closed())

    fun setOpen(key: String, open: Boolean) {
        val opened = opened() - key
        val closed = closed() - key
        preferences.edit()
            .putStringSet(OPEN, if (open) opened + key else opened)
            .putStringSet(CLOSED, if (open) closed else closed + key)
            .apply()
    }

    fun isSectionOpen(section: String): Boolean =
        reviewTagGroupOpen(section, true, sections(SECTIONS_OPEN), sections(SECTIONS_CLOSED))

    fun setSectionOpen(section: String, open: Boolean) {
        val opened = sections(SECTIONS_OPEN) - section
        val closed = sections(SECTIONS_CLOSED) - section
        preferences.edit()
            .putStringSet(SECTIONS_OPEN, if (open) opened + section else opened)
            .putStringSet(SECTIONS_CLOSED, if (open) closed else closed + section)
            .apply()
    }

    private fun opened(): Set<String> = preferences.getStringSet(OPEN, emptySet()).orEmpty()
    private fun closed(): Set<String> = preferences.getStringSet(CLOSED, emptySet()).orEmpty()
    private fun sections(key: String): Set<String> = preferences.getStringSet(key, emptySet()).orEmpty()

    companion object {
        const val PREFERENCES = "review_tag_group_folding"
        private const val OPEN = "open"
        private const val CLOSED = "closed"
        private const val SECTIONS_OPEN = "sections_open"
        private const val SECTIONS_CLOSED = "sections_closed"
    }
}

/** Whether the group of [key] is open: as the user left it ([opened] / [closed]), else [byDefault]. */
internal fun reviewTagGroupOpen(key: String, byDefault: Boolean, opened: Set<String>, closed: Set<String>): Boolean = when (key) {
    in opened -> true
    in closed -> false
    else -> byDefault
}

/**
 * A group the user never opened or closed: Understanding and Recall start open, every other group
 * closed (groups made later too). Other ([group] null) starts open only when there are no groups,
 * as it is then the only row.
 */
internal fun reviewTagGroupOpenByDefault(group: ReviewTagGroups.Group?, hasGroups: Boolean): Boolean =
    if (group == null) !hasGroups
    else OPEN_BY_DEFAULT.any { group.id.equals(it, ignoreCase = true) || group.name.equals(it, ignoreCase = true) }

private val OPEN_BY_DEFAULT = listOf("Understanding", "Recall")

/**
 * A group header's summary of what this answer chose in it: the slider's step (if any) first, then
 * the chosen free tags in the group's order ("Somewhat, Submitted"); empty when nothing is chosen.
 */
internal fun reviewTagGroupChoiceSummary(
    groupTags: List<String>,
    steps: List<String>,
    chosen: Collection<String>,
    label: (String) -> String,
): String = (steps.filter { it in chosen } + groupTags.filter { it !in steps && it in chosen })
    .joinToString(", ", transform = label)

/**
 * A closed section header's summary of what this answer chose in its groups ([children]: each
 * group's name and its [reviewTagGroupChoiceSummary]): "Structure: Missing steps · Style: Clean".
 * Groups with nothing chosen are left out; empty when nothing is chosen in any.
 */
internal fun reviewTagSectionSummary(children: List<Pair<String, String>>): String =
    children.filter { it.second.isNotEmpty() }.joinToString(" · ") { (name, choice) -> "$name: $choice" }

/** A section header's whole text, as its long-press shows it: "Work left · Structure: Missing steps". */
internal fun reviewTagSectionText(section: String, summary: String): String =
    if (summary.isEmpty()) section else "$section · $summary"

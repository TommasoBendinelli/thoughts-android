// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

/**
 * The selected agent edits of the Agent edits sheet, by proposal ID. Selection mode is on while
 * anything is selected: a long press starts it with that edit, a tap then adds or removes one,
 * and removing the last one, Cancel or Back end it.
 */
internal class AgentEditSelection {
    private val selected = linkedSetOf<String>()

    val active: Boolean get() = selected.isNotEmpty()
    val count: Int get() = selected.size
    val ids: Set<String> get() = selected.toSet()

    operator fun contains(id: String): Boolean = id in selected

    /** A long press: starts selection mode with [id], or adds or removes it when already on. */
    fun longPress(id: String) {
        if (active) toggle(id) else selected += id
    }

    /** A tap in selection mode; returns whether selection mode is still on. */
    fun toggle(id: String): Boolean {
        if (!selected.remove(id)) selected += id
        return active
    }

    /** Select all: every edit the list shows now (those that match the search), added to the selection. */
    fun selectAll(shownIds: Collection<String>) {
        if (!active) return
        selected += shownIds
    }

    /** Whether every edit the list shows is selected already (Select all then has nothing to add). */
    fun coversAll(shownIds: Collection<String>): Boolean = shownIds.all { it in selected }

    /** Keeps only edits that still exist (open, in this document); returns whether selection mode is still on. */
    fun retainExisting(existingIds: Set<String>): Boolean {
        selected.retainAll(existingIds)
        return active
    }

    fun clear() = selected.clear()

    /** For saved instance state. */
    fun restore(ids: Collection<String>) {
        selected.clear()
        selected += ids
    }
}

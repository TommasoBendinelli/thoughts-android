// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent notes that arrived and were not opened yet: the dot on the Agent notes icon (the reader's
 * title bar, Home's menu) and next to each such note in the list. While Thoughts is open a new note
 * shows only this dot, never a system notification ([agentNoteArrival]). Kept across restarts; a
 * note leaves the set when it is opened, or when it is no longer in the store.
 */
object AgentNoteUnseen {
    private const val PREFERENCES = "agent_notes_unseen"
    private const val KEY = "note_ids"

    private val state = MutableStateFlow<Set<String>>(emptySet())
    @Volatile private var loaded = false

    /** The unseen notes' IDs; read [load] once (any screen's onCreate) before relying on it. */
    val ids: StateFlow<Set<String>> = state.asStateFlow()

    fun load(context: Context): Set<String> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                state.value = preferences(context).getStringSet(KEY, emptySet()).orEmpty().toSet()
                loaded = true
            }
        }
        return state.value
    }

    /** Notes that just arrived. */
    fun arrived(context: Context, noteIds: Collection<String>) = change(context) { it + noteIds }

    /** The note was opened (its screen showed it). */
    fun opened(context: Context, noteId: String) = change(context) { it - noteId }

    /** Forgets notes that are gone (deleted, or no longer stored). */
    fun keepOnly(context: Context, existing: Set<String>) = change(context) { it intersect existing }

    private fun change(context: Context, transform: (Set<String>) -> Set<String>) = synchronized(this) {
        val before = load(context)
        val after = unseenAfter(before, transform)
        if (after != before) {
            state.value = after
            preferences(context).edit().putStringSet(KEY, after).apply()
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

/** The set after a change, as a new immutable set (SharedPreferences must not see it change later). */
internal fun unseenAfter(before: Set<String>, transform: (Set<String>) -> Set<String>): Set<String> =
    transform(before).filterTo(LinkedHashSet()) { it.isNotBlank() }.toSet()

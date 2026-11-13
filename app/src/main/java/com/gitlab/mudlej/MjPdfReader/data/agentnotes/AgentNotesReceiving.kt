// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.content.Context
import android.util.Log

/**
 * "Receive agent notes on this phone". The server hands each note to the first device that asks
 * its inbox, so a second phone that keeps asking takes notes meant for the daily one. Off: this
 * phone never asks the inbox (no live loop, no study listener, the periodic job only sends unsent
 * replies); the Agent notes lists still show notes from the server's list and the local cache.
 *
 * Kept in its own preferences file, excluded from backup and device transfer, so the choice stays
 * with this phone. Default on.
 */
object AgentNotesReceiving {
    const val PREFERENCES_FILE = "agent_notes_device"
    private const val KEY = "receive_on_this_phone"

    fun isOn(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)

    /** Turns receiving on or off; call from a user action in the foreground. Takes effect at once. */
    fun set(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
        Log.i(AgentNotes.TAG, "agent notes on this phone: ${if (on) "on" else "off"}")
        if (on) {
            AgentNoteInbox.receivingChanged()
            // Study mode was left on: listen again as it did before.
            if (AgentStudyMode.isOn(context)) AgentStudyListenerService.start(context)
        } else {
            AgentStudyListenerService.stop(context)
            AgentNoteInbox.receivingChanged()
        }
    }
}

/** The live inbox loop runs only while this phone receives notes and something wants them. */
fun agentNotesInboxShouldRun(receiving: Boolean, startedActivities: Int, listening: Boolean): Boolean =
    receiving && (startedActivities > 0 || listening)

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.content.Context
import androidx.preference.PreferenceManager

/** Study mode: the user asked Thoughts to listen for agent notes until they turn it off. */
object AgentStudyMode {
    private const val KEY = "agent_notes_study_mode"

    fun isOn(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false)

    /** Turns study mode on or off; call from a user action in the foreground. */
    fun set(context: Context, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, on).apply()
        if (on) AgentStudyListenerService.start(context) else AgentStudyListenerService.stop(context)
    }

    /** Clears the preference only; the service calls this when the user taps Stop. */
    internal fun clear(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, false).apply()
    }
}

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

/**
 * How long the Study mode listener goes on after Study mode was last used: a capture, a reply, a
 * note from the agent, the screen coming on, or Thoughts being opened. Until 13 November 2026 it
 * had no limit: the listener held its 25-second long poll all night (899 polls) until Android's
 * six-hour cap ended it. It starts again as soon as Thoughts is opened or a capture is taken.
 */
const val STUDY_MODE_IDLE_MILLIS = 60 * 60 * 1000L

/**
 * How long the Study mode listener still runs: until [idleMillis] after [lastUseAtEpochMillis].
 * Nothing when Study mode was never used (0), and never more than [idleMillis], so a clock that
 * was set back cannot keep the listener for longer.
 */
internal fun studyListenerRemainingMillis(
    lastUseAtEpochMillis: Long,
    nowEpochMillis: Long,
    idleMillis: Long = STUDY_MODE_IDLE_MILLIS,
): Long {
    if (lastUseAtEpochMillis <= 0) return 0
    return (lastUseAtEpochMillis + idleMillis - nowEpochMillis).coerceIn(0, idleMillis)
}

/** When Study mode was last used, kept across restarts of the process ([studyListenerRemainingMillis]). */
internal object AgentStudyUse {
    private const val PREFERENCES_FILE = "agent_notes_study_use"
    private const val KEY = "last_use_at_epoch_millis"

    fun record(context: Context, nowEpochMillis: Long = System.currentTimeMillis()) {
        context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit().putLong(KEY, nowEpochMillis).apply()
    }

    fun lastUseAtEpochMillis(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE).getLong(KEY, 0L)
}

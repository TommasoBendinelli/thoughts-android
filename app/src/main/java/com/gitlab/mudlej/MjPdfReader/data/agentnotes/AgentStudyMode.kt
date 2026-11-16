// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Study mode, shown as "Instant notes": the user asked Thoughts to listen for agent notes until they
 * turn it off (a foreground service with the notification Android requires). Needed only while push
 * ([AgentNotesPush]) does not deliver notes: push that becomes active turns it off for good
 * ([retireForPush]), and the switch is not shown then.
 */
object AgentStudyMode {
    private const val KEY = "agent_notes_study_mode"

    fun isOn(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false)

    /** Turns study mode on or off; call from a user action in the foreground. */
    fun set(context: Context, on: Boolean) {
        // Study mode also showed the agent tools under the page: they keep the state they had.
        AgentPageTools.isOn(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, on).apply()
        if (on) AgentStudyListenerService.start(context) else AgentStudyListenerService.stop(context)
    }

    /** Clears the preference only; the service calls this when the user taps Stop. */
    internal fun clear(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, false).apply()
    }

    /**
     * Push delivers notes now: Study mode was on, so it is turned off and its listener stopped. The
     * agent tools under the page, which Study mode also showed, stay. Returns whether it was on.
     */
    fun retireForPush(context: Context): Boolean {
        if (!isOn(context)) return false
        AgentPageTools.isOn(context)
        clear(context)
        AgentStudyListenerService.stop(context)
        Log.i(AgentNotes.TAG, "study mode off: push delivers agent notes")
        return true
    }
}

/**
 * The agent tools under the page in the reader (send the page on screen to the agent, or draw on
 * it first). Until push, Study mode showed them; the first read takes Study mode's state, after
 * that they have their own switch in the reader's Study session panel.
 */
object AgentPageTools {
    const val KEY = "agent_notes_page_tools"

    fun isOn(context: Context): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (preferences.contains(KEY)) return preferences.getBoolean(KEY, false)
        val inherited = AgentStudyMode.isOn(context)
        preferences.edit().putBoolean(KEY, inherited).apply()
        return inherited
    }

    fun set(context: Context, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, on).apply()
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

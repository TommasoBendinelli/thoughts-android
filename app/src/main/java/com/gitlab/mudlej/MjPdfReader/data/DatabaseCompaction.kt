// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Proposal history (database version 35) removed most rows, but SQLite keeps the
 * freed pages, so the file stays its old size. Compact it once, in the background.
 */
object DatabaseCompaction {
    private const val DONE = "database_compacted_after_proposal_history_v1"

    fun compactOnce(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (preferences.getBoolean(DONE, false)) return
        val file = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        // VACUUM writes a temporary copy; retry at a later start if space is short.
        if (!file.isFile || file.parentFile?.usableSpace ?: 0L < file.length() * 2) return
        val before = file.length()
        val started = SystemClock.elapsedRealtime()
        AppDatabase.getInstance(context).openHelper.writableDatabase.execSQL("VACUUM")
        preferences.edit().putBoolean(DONE, true).apply()
        Log.i("DatabaseCompaction", "Compacted ${before / 1_000_000} MB to ${file.length() / 1_000_000} MB in ${SystemClock.elapsedRealtime() - started} ms")
    }
}

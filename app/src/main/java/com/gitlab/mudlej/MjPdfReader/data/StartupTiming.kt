// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import android.os.Process
import android.os.SystemClock
import android.util.Log

/** Logcat tag ThoughtsStartup: milestones measured from process start, for cold-start profiling. */
object StartupTiming {
    fun sinceProcessStart(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    /** Diagnostics only: never fails the caller (JVM unit tests have no Android clock or log). */
    fun log(message: String) {
        runCatching { Log.i("ThoughtsStartup", "+${sinceProcessStart()} ms $message") }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent

internal object ReaderSessionNavigation {
    const val EXTRA_SHOW_EMPTY_VIEWER = "showEmptyViewer"
    private const val PREFERENCES = "reader_navigation"
    private const val LAST_TASK = "last_reader_task"

    fun remember(activity: Activity) {
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putInt(LAST_TASK, activity.taskId).apply()
    }

    fun open(activity: Activity) {
        val lastTask = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getInt(LAST_TASK, -1)
        val tasks = activity.getSystemService(ActivityManager::class.java).appTasks
            .filter { it.taskInfo.baseActivity?.className == MainActivity::class.java.name }
        val ordered = tasks.sortedByDescending { it.taskInfo.taskId == lastTask }
        for (task in ordered) {
            // A task may have been closed between enumeration and navigation.
            if (runCatching { task.moveToFront() }.isSuccess) return
        }
        activity.startActivity(Intent(activity, MainActivity::class.java)
            .putExtra(EXTRA_SHOW_EMPTY_VIEWER, true))
    }
}

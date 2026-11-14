// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import java.io.File

/**
 * A cheap description of files and directories, to tell whether anything was written since it was
 * last taken without reading what they hold: each directory by its modification time and number
 * of entries, each file by its size and modification time. Adding, replacing or removing a file
 * changes the stamp of its directory.
 */
internal fun pathsStamp(paths: List<File>, extra: List<String> = emptyList()): String =
    (paths.map { path ->
        when {
            path.isDirectory -> "d:${path.path}:${path.lastModified()}:${path.list()?.size ?: -1}"
            path.isFile -> "f:${path.path}:${path.length()}:${path.lastModified()}"
            else -> "-:${path.path}"
        }
    } + extra.map { "x:$it" }).joinToString("\n")

/**
 * Whether a periodic check reads everything again: only when what it reads may have changed
 * (the [pathsStamp] of its inputs differs from the one of the last full read, or could not be
 * taken), and otherwise once per [backstopMillis], for an input the stamp does not cover.
 */
internal class IdleRefreshGate(private val backstopMillis: Long) {
    private var lastStamp: String? = null
    private var lastReadAtMillis = 0L

    @Synchronized
    fun shouldRefresh(stamp: String, nowMillis: Long): Boolean =
        stamp.isBlank() || stamp != lastStamp || nowMillis - lastReadAtMillis >= backstopMillis

    /** A full read started when [stamp] was taken. */
    @Synchronized
    fun refreshed(stamp: String, nowMillis: Long) {
        lastStamp = stamp
        lastReadAtMillis = nowMillis
    }

    @Synchronized
    fun reset() {
        lastStamp = null
    }
}

/**
 * A value computed from one file, kept in memory while the file is as it was when the value was
 * computed (its [pathsStamp], and [generation] for anything else the value depends on) and no
 * writer of the file said otherwise ([invalidate]). Every caller gets its own copy ([copy]), so
 * nobody can change what the next caller reads. A cache that was filled on a background thread
 * spares the main thread reading and parsing the file.
 */
internal class ParsedFileCache<T : Any>(private val copy: (T) -> T) {
    private var keptFor: String? = null
    private var kept: T? = null

    @Synchronized
    fun get(file: File, generation: Long = 0, compute: () -> T): T {
        // Taken before the file is read: a write while it is read is then seen by the next call.
        val stamp = pathsStamp(listOf(file), extra = listOf(generation.toString()))
        kept?.takeIf { keptFor == stamp }?.let { return copy(it) }
        val computed = compute()
        kept = copy(computed)
        keptFor = stamp
        return computed
    }

    /** The file was written: the next read computes again, whatever its stamp says. */
    @Synchronized
    fun invalidate() {
        keptFor = null
        kept = null
    }
}

/**
 * Runs a task once for each key on a background thread, started by whoever comes first, so that
 * no caller has to run it where it stands (the main thread, at start). Whoever needs what the
 * task did waits for it with [await]; after the task has run that is a look at a flag. A task that
 * fails is reported to [failed] and counts as run: it must be safe to run again another time.
 */
internal class BackgroundOnce(
    private val threadName: String,
    private val failed: (Throwable) -> Unit = {},
) {
    private val tasks = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, java.util.concurrent.FutureTask<Unit>>())

    fun start(key: Any, task: () -> Unit) {
        val created = synchronized(tasks) {
            if (tasks.containsKey(key)) return
            java.util.concurrent.FutureTask { runCatching(task).onFailure(failed); Unit }.also { tasks[key] = it }
        }
        Thread(created, threadName).start()
    }

    /** Returns once the task started for [key] has run; at once when none was started. */
    fun await(key: Any) {
        val task = tasks[key] ?: return
        if (!task.isDone) task.get()
    }
}

// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

/**
 * Rejects an asynchronous UI result after a newer request or document reset supersedes it.
 *
 * This class is intentionally main-thread confined. Background work receives only the token and
 * checks it again after returning to the main dispatcher.
 */
internal class ReaderAsyncRequestGate {
    private var generation = 0L

    fun begin(): Long {
        generation += 1L
        return generation
    }

    /** The token of the newest request, for work that must not supersede it. */
    fun current(): Long = generation

    fun invalidate() {
        generation += 1L
    }

    fun isCurrent(token: Long): Boolean = token == generation
}

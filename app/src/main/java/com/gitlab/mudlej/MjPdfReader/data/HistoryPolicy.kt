// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

/**
 * Whether the app may remember opened files, positions and per file settings.
 * Thoughts always records reading history; the old "Save Reading History"
 * switch is gone, and a phone where it was saved off records again.
 */
class HistoryPolicy {

    fun canRecord(): Boolean = true
}

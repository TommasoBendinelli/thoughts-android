// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

/**
 * Android passes clipboard text through a binder transaction of about 1 MB, so a long
 * diagnostics report fails to copy. Keep its start, where the summary is, and its end,
 * where the latest events are.
 */
internal const val MAX_CLIPBOARD_DIAGNOSTIC_CHARS = 200_000

internal data class ClipboardDiagnostics(val text: String, val trimmed: Boolean)

internal fun clipboardDiagnostics(report: String, limit: Int = MAX_CLIPBOARD_DIAGNOSTIC_CHARS): ClipboardDiagnostics {
    if (report.length <= limit) return ClipboardDiagnostics(report, false)
    val head = limit * 2 / 5
    val tail = limit - head
    val omitted = report.length - head - tail
    val note = "\n\n--- $omitted characters omitted to fit the clipboard. " +
        "The full report is saved on this device and can be shared as a file. ---\n\n"
    return ClipboardDiagnostics(report.take(head) + note + report.takeLast(tail), true)
}

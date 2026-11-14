// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

/**
 * The Sync status vocabulary shared by Thoughts on the phone, Thoughts Desktop and the
 * Thoughts Manual (section "Syncing"). Every surface that names an overall Sync state uses
 * exactly these words; string resources that show them are checked by SyncStatusLabelsTest.
 * Progress phases ("Building PDFs", "Waiting for a connection") may appear as details only.
 */
internal object SyncStatusLabels {
    const val SYNCING = "Syncing…"
    const val UP_TO_DATE = "Up to date"
    const val CHANGES_PENDING = "Changes pending"
    const val PDF_BUILD_FAILED = "PDF build failed"
    const val SYNC_FAILED = "Sync failed"
    const val SHOW_LOGS = "Show logs"

    /**
     * A source file changed here and on the server in overlapping places. Outranks every other
     * failure: nothing else syncs until it is merged. SHOW_MERGE is its only action.
     */
    const val MERGE_NEEDED = "Merge needed"
    const val SHOW_MERGE = "Show merge"

    fun sourceFilesNeedMerge(files: Int): String =
        if (files == 1) "1 source file needs a merge" else "$files source files need a merge"

    /** Detail under PDF_BUILD_FAILED when the changes themselves reached the server. */
    const val CHANGES_SYNCED_DETAIL = "Your changes are synced"

    /**
     * The server refused a change of this device, which is kept until the user retries or discards
     * it. Syncs themselves succeed, so this is not SYNC_FAILED.
     */
    const val CHANGE_REFUSED = "Change refused"

    fun changesRefused(changes: Int): String =
        if (changes == 1) "1 change was refused by the server" else "$changes changes were refused by the server"
}

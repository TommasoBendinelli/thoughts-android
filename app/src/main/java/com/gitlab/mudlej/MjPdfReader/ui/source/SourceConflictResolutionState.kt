// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.content.res.Resources
import com.gitlab.mudlej.MjPdfReader.R

/** Why "Save resolution" cannot be used right now; [NONE] means it can. */
internal enum class ConflictSaveBlocker {
    NONE,
    LOADING,
    SAVING,
    OTHER_VERSION_SHOWN,
    MALFORMED,
    UNRESOLVED,
    SAVED,
    UNCHANGED,
}

internal data class ConflictSaveStatus(
    val enabled: Boolean,
    val blocker: ConflictSaveBlocker,
    val remainingConflicts: Int,
) {
    /** The sentence under the Save button; every state explains itself. */
    fun helperText(resources: Resources): String = when (blocker) {
        ConflictSaveBlocker.NONE -> resources.getString(R.string.source_conflict_save_ready)
        ConflictSaveBlocker.LOADING -> resources.getString(R.string.source_conflict_save_loading)
        ConflictSaveBlocker.SAVING -> resources.getString(R.string.source_conflict_save_saving)
        ConflictSaveBlocker.OTHER_VERSION_SHOWN ->
            resources.getString(R.string.source_conflict_save_other_version)
        ConflictSaveBlocker.MALFORMED -> resources.getString(R.string.source_conflict_save_malformed)
        ConflictSaveBlocker.UNRESOLVED -> resources.getQuantityString(
            R.plurals.source_conflict_save_unresolved,
            remainingConflicts,
            remainingConflicts,
        )
        ConflictSaveBlocker.SAVED -> resources.getString(R.string.source_conflict_save_saved)
        ConflictSaveBlocker.UNCHANGED -> resources.getString(R.string.source_conflict_save_unchanged)
    }
}

/**
 * Explains [canSave], the result of [conflictResolutionCanSave]. [remainingConflicts] and
 * [malformed] come from the latest conflict presentation, which may lag the text by one debounce;
 * when it does, the exact marker scan behind [canSave] still decides, and the explanation falls
 * back to "resolve the remaining conflict".
 */
internal fun conflictSaveStatus(
    canSave: Boolean,
    editorLoaded: Boolean,
    saving: Boolean,
    mergedViewSelected: Boolean,
    dirty: Boolean,
    remainingConflicts: Int,
    malformed: Boolean,
    savedThisSession: Boolean,
): ConflictSaveStatus {
    if (canSave) return ConflictSaveStatus(true, ConflictSaveBlocker.NONE, 0)
    val blocker = when {
        !editorLoaded -> ConflictSaveBlocker.LOADING
        saving -> ConflictSaveBlocker.SAVING
        !mergedViewSelected -> ConflictSaveBlocker.OTHER_VERSION_SHOWN
        malformed -> ConflictSaveBlocker.MALFORMED
        remainingConflicts > 0 -> ConflictSaveBlocker.UNRESOLVED
        !dirty && savedThisSession -> ConflictSaveBlocker.SAVED
        !dirty -> ConflictSaveBlocker.UNCHANGED
        // Dirty, in the merged view, yet not saveable: markers remain that the presentation
        // has not caught up with yet.
        else -> ConflictSaveBlocker.UNRESOLVED
    }
    return ConflictSaveStatus(false, blocker, remainingConflicts.coerceAtLeast(1))
}

internal data class ConflictProgress(val resolved: Int, val total: Int) {
    val complete: Boolean get() = total > 0 && resolved >= total
}

/** Typing new markers can only add conflicts, so the total never drops below what remains. */
internal fun conflictProgress(initialTotal: Int, remaining: Int): ConflictProgress {
    val total = maxOf(initialTotal, remaining, 0)
    return ConflictProgress(resolved = (total - remaining).coerceIn(0, total), total = total)
}

/** "Keep both": Fedora's lines first, then the phone's, without the markers. */
internal fun SourceConflictBlock.bothSides(source: CharSequence): String? {
    val fedora = selectedSide(source, ConflictTextRole.FEDORA_CURRENT) ?: return null
    val phone = selectedSide(source, ConflictTextRole.PHONE_INCOMING) ?: return null
    if (fedora.isEmpty() || phone.isEmpty() || fedora.endsWith('\n')) return fedora + phone
    return fedora + "\n" + phone
}

/** The line break that ends a conflict block, so a manual replacement keeps the next line apart. */
internal fun SourceConflictBlock.trailingLineBreak(source: CharSequence): String {
    val end = range.endExclusive
    return when {
        end >= 2 && source[end - 2] == '\r' && source[end - 1] == '\n' -> "\r\n"
        end >= 1 && source[end - 1] == '\n' -> "\n"
        else -> ""
    }
}

internal fun manualConflictReplacement(edited: String, trailingLineBreak: String): String = when {
    edited.isEmpty() || trailingLineBreak.isEmpty() -> edited
    edited.endsWith('\n') -> edited
    else -> edited + trailingLineBreak
}

/** A side's lines for display: its final line break is structure, not content. */
internal fun conflictSideDisplayText(side: String): String = side.removeSuffix("\n").removeSuffix("\r")

/** Zero-based source line of [offset]. Runs only when the shown conflict changes. */
internal fun CharSequence.lineIndexOf(offset: Int): Int = countLineBreaks(0, offset.coerceIn(0, length))

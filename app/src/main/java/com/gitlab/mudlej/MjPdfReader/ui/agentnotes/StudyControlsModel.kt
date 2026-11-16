// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import androidx.annotation.StringRes
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.PushOffReason
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.PushStatus

/** What the Home menu and the reader's Study session panel show about receiving notes. */
internal data class StudyControls(
    /** The "Instant notes" switch (Study mode's listener), only while push does not deliver notes. */
    val showInstantNotesSwitch: Boolean,
    /** A phone that does not receive notes cannot listen for them; it can still turn the switch off. */
    val instantNotesSwitchEnabled: Boolean,
    /** "Notes arrive instantly": push delivers them, no switch needed. */
    val showNotesArriveInstantly: Boolean,
)

internal fun studyControls(pushActive: Boolean, receiving: Boolean, instantNotesOn: Boolean) = StudyControls(
    showInstantNotesSwitch = !pushActive,
    instantNotesSwitchEnabled = receiving || instantNotesOn,
    showNotesArriveInstantly = pushActive,
)

/** The reason push is off, in plain words, for "Off: <reason>" in Settings. */
@StringRes
internal fun pushOffReasonText(reason: PushOffReason): Int = when (reason) {
    PushOffReason.NOT_BUILT -> R.string.agent_notes_push_off_not_built
    PushOffReason.NO_PLAY_SERVICES -> R.string.agent_notes_push_off_no_play_services
    PushOffReason.NOT_RECEIVING -> R.string.agent_notes_push_off_not_receiving
    PushOffReason.NO_SERVER -> R.string.agent_notes_push_off_no_server
    PushOffReason.CHECKING -> R.string.agent_notes_push_off_checking
    PushOffReason.SERVER_WITHOUT_PUSH -> R.string.agent_notes_push_off_server_without_push
    PushOffReason.REGISTERING -> R.string.agent_notes_push_off_registering
    PushOffReason.REFUSED -> R.string.agent_notes_push_off_refused
    PushOffReason.NO_TOKEN -> R.string.agent_notes_push_off_no_token
    PushOffReason.UNREACHABLE -> R.string.agent_notes_push_off_unreachable
}

/** Settings' Push row: "Active: …" or "Off: <reason>". */
internal fun pushStatusText(status: PushStatus, getString: (Int) -> String, format: (Int, String) -> String): String =
    if (status.active) getString(R.string.agent_notes_push_active)
    else format(R.string.agent_notes_push_off, getString(pushOffReasonText(status.offReason ?: PushOffReason.CHECKING)))

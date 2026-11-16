// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesPush
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesReceiving
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentPageTools
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentStudyMode
import com.gitlab.mudlej.MjPdfReader.databinding.DialogStudyModeBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.AgentPromptTemplates
import com.gitlab.mudlej.MjPdfReader.ui.reader.copyAgentText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Receiving agent notes, shared by the Home menu and the reader menu's Study session panel ([studyControls]):
 * while push delivers notes, the line "Notes arrive instantly"; otherwise the "Instant notes" switch
 * (Study mode's listener). Both offer "Start a new study session", which copies the prompt.
 */
internal object StudyModeControls {

    /** The study-session prompt as edited in Settings > Agent notes and prompts. */
    private fun studyModePrompt(pref: Preferences) = AgentPromptTemplates.studyModePrompt(
        pref.getAgentPromptTemplate(AgentPromptTemplates.Kind.STUDY_MODE), pref.getMentraPhotoFolder())

    /**
     * Binds the switch (and its [detail] line, if any), the push line and the session row as the
     * state is now; [dismiss] closes the menu before a dialog follows.
     */
    fun bind(
        activity: AppCompatActivity,
        pref: Preferences,
        instantNotes: MaterialSwitch,
        detail: View?,
        pushLine: View,
        startSession: View,
        dismiss: () -> Unit,
    ) {
        val pushActive = AgentNotesPush.isActive(activity)
        // Push may have become active since Study mode was turned on: it is not needed any more.
        if (pushActive) AgentStudyMode.retireForPush(activity)
        val controls = studyControls(pushActive, AgentNotesReceiving.isOn(activity), AgentStudyMode.isOn(activity))
        val switchVisibility = if (controls.showInstantNotesSwitch) View.VISIBLE else View.GONE
        instantNotes.visibility = switchVisibility
        detail?.visibility = switchVisibility
        pushLine.visibility = if (controls.showNotesArriveInstantly) View.VISIBLE else View.GONE
        instantNotes.isChecked = AgentStudyMode.isOn(activity)
        instantNotes.isEnabled = controls.instantNotesSwitchEnabled
        instantNotes.contentDescription =
            activity.getString(R.string.agent_notes_study_mode) + ". " + activity.getString(R.string.agent_notes_study_mode_detail)
        instantNotes.setOnCheckedChangeListener { _, checked ->
            AgentStudyMode.set(activity, checked)
            if (checked) {
                dismiss()
                showStudyModeOn(activity)
            }
        }
        startSession.setOnClickListener {
            dismiss()
            startStudySession(activity, pref)
        }
    }

    /** The reader menu's Study session tile: as on Home, plus the agent tools under the page and the notes list. */
    fun showPanel(activity: AppCompatActivity, pref: Preferences) {
        val binding = DialogStudyModeBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.agent_notes_study_session)
            .setView(binding.root)
            .setPositiveButton(R.string.close, null)
            .create()
        bind(activity, pref, binding.studyModeSwitch, binding.studyModeDetail, binding.pushActiveLine, binding.copyPromptRow, dialog::dismiss)
        binding.pageToolsSwitch.isChecked = AgentPageTools.isOn(activity)
        binding.pageToolsSwitch.setOnCheckedChangeListener { _, checked -> AgentPageTools.set(activity, checked) }
        binding.agentNotesRow.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(AgentNotesListActivity.intent(activity))
        }
        dialog.show()
    }

    /**
     * "Start a new study session": copies the prompt that tells a new agent conversation to watch
     * the glasses photos and answer with notes, and says what to do with it.
     */
    fun startStudySession(activity: AppCompatActivity, pref: Preferences) {
        copyAgentText(activity, studyModePrompt(pref))
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.agent_notes_start_study_session)
            .setMessage(R.string.agent_notes_study_mode_prompt_copied)
            .setNeutralButton(R.string.agent_notes_study_mode_copy_again) { _, _ -> copyAgentText(activity, studyModePrompt(pref)) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Instant notes just turned on: what is ready on the phone, with a way to fix what is not. Copies nothing. */
    fun showStudyModeOn(activity: AppCompatActivity) {
        val notifications = NotificationManagerCompat.from(activity).areNotificationsEnabled()
        fun line(ok: Boolean, text: Int) = (if (ok) "✓ " else "✗ ") + activity.getString(text)
        val message = listOf(
            "✓ " + activity.getString(R.string.agent_notes_study_mode_ready_listening),
            line(notifications, if (notifications) R.string.agent_notes_study_mode_ready_notifications else R.string.agent_notes_study_mode_missing_notifications),
        ).joinToString("\n")
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.agent_notes_study_mode_on_title)
            .setMessage(message)
        when {
            !notifications -> builder
                .setPositiveButton(R.string.agent_notes_study_mode_open_notification_settings) { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
                        )
                    }
                }
                .setNegativeButton(R.string.agent_notes_overlay_not_now, null)
            else -> builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }
}

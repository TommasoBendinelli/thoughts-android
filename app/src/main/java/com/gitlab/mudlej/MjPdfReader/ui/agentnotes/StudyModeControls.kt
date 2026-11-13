// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesReceiving
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentStudyMode
import com.gitlab.mudlej.MjPdfReader.databinding.DialogStudyModeBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.AgentPromptTemplates
import com.gitlab.mudlej.MjPdfReader.ui.reader.copyAgentText
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Study mode's controls, shared by the Home menu and the reader menu's Study mode tile. */
internal object StudyModeControls {

    /** The study-mode prompt as edited in Settings > Agent notes and prompts. */
    private fun studyModePrompt(pref: Preferences) = AgentPromptTemplates.studyModePrompt(
        pref.getAgentPromptTemplate(AgentPromptTemplates.Kind.STUDY_MODE), pref.getMentraPhotoFolder())

    /** The reader menu's Study mode tile: the switch, the agent notes list and the prompt, as on Home. */
    fun showPanel(activity: AppCompatActivity, pref: Preferences) {
        val binding = DialogStudyModeBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.agent_notes_study_mode)
            .setView(binding.root)
            .setPositiveButton(R.string.close, null)
            .create()
        binding.studyModeSwitch.isChecked = AgentStudyMode.isOn(activity)
        // A phone that does not receive agent notes cannot listen for them (it can still turn study mode off).
        binding.studyModeSwitch.isEnabled = AgentNotesReceiving.isOn(activity) || binding.studyModeSwitch.isChecked
        binding.studyModeSwitch.contentDescription =
            activity.getString(R.string.agent_notes_study_mode) + ". " + activity.getString(R.string.agent_notes_study_mode_detail)
        binding.studyModeSwitch.setOnCheckedChangeListener { _, checked ->
            AgentStudyMode.set(activity, checked)
            if (checked) {
                dialog.dismiss()
                showStudyModeOn(activity, pref)
            }
        }
        binding.agentNotesRow.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(AgentNotesListActivity.intent(activity))
        }
        binding.copyPromptRow.setOnClickListener {
            copyAgentText(activity, studyModePrompt(pref))
        }
        dialog.show()
    }

    /**
     * Study mode just turned on: copies the prompt that tells the agent to watch the photo folder and
     * answer with notes, and lists what is ready on the phone, with a way to fix what is not.
     */
    fun showStudyModeOn(activity: AppCompatActivity, pref: Preferences) {
        copyAgentText(activity, studyModePrompt(pref))
        val notifications = NotificationManagerCompat.from(activity).areNotificationsEnabled()
        fun line(ok: Boolean, text: Int) = (if (ok) "✓ " else "✗ ") + activity.getString(text)
        val message = listOf(
            "✓ " + activity.getString(R.string.agent_notes_study_mode_ready_listening),
            line(notifications, if (notifications) R.string.agent_notes_study_mode_ready_notifications else R.string.agent_notes_study_mode_missing_notifications),
            "",
            activity.getString(R.string.agent_notes_study_mode_prompt_copied),
        ).joinToString("\n")
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.agent_notes_study_mode_on_title)
            .setMessage(message)
            .setNeutralButton(R.string.agent_notes_study_mode_copy_again) { _, _ ->
                copyAgentText(activity, studyModePrompt(pref))
            }
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

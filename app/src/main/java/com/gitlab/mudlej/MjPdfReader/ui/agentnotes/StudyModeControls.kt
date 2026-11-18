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
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteReplySources
import com.gitlab.mudlej.MjPdfReader.databinding.DialogNewSessionBinding
import com.gitlab.mudlej.MjPdfReader.databinding.DialogStudyModeBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.AgentPromptTemplates
import com.gitlab.mudlej.MjPdfReader.ui.reader.copyAgentText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Receiving agent notes, shared by the Home menu and the reader menu's Study session panel ([studyControls]):
 * while push delivers notes, the panel's line "Notes arrive instantly"; otherwise the "Instant notes" switch
 * (Study mode's listener). "Start a new study session" copies the prompt: from the panel and from New session
 * at the top right of Agent notes (the Home menu has no row for it).
 */
internal object StudyModeControls {

    /**
     * The study-session prompt as edited in Settings > Agent notes and prompts, then the line that says
     * what the session may use (none for the default, everything, as before the choice).
     */
    private fun studyModePrompt(pref: Preferences): String {
        val prompt = AgentPromptTemplates.studyModePrompt(
            pref.getAgentPromptTemplate(AgentPromptTemplates.Kind.STUDY_MODE), pref.getMentraPhotoFolder())
        val sources = sessionSourcesLine(pref.getAgentNoteReplySources()) ?: return prompt
        return prompt.trimEnd() + "\n" + sources + "\n"
    }

    /**
     * Binds the switch (and its [detail] line, if any), the push line and the session row (where there are
     * such) as the state is now; [dismiss] closes the menu before a dialog follows.
     */
    fun bind(
        activity: AppCompatActivity,
        pref: Preferences,
        instantNotes: MaterialSwitch,
        detail: View?,
        pushLine: View?,
        startSession: View?,
        dismiss: () -> Unit,
    ) {
        val pushActive = AgentNotesPush.isActive(activity)
        // Push may have become active since Study mode was turned on: it is not needed any more.
        if (pushActive) AgentStudyMode.retireForPush(activity)
        val controls = studyControls(pushActive, AgentNotesReceiving.isOn(activity), AgentStudyMode.isOn(activity))
        val switchVisibility = if (controls.showInstantNotesSwitch) View.VISIBLE else View.GONE
        instantNotes.visibility = switchVisibility
        detail?.visibility = switchVisibility
        pushLine?.visibility = if (controls.showNotesArriveInstantly) View.VISIBLE else View.GONE
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
        startSession?.setOnClickListener {
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
     * the glasses photos and answer with notes, and says what to do with it. Its "Sources" chip
     * chooses what the session may use: the choice goes into the prompt (copied again at once) and
     * is remembered for the next session. A session keeps what it was started with.
     */
    fun startStudySession(activity: AppCompatActivity, pref: Preferences) {
        copyAgentText(activity, studyModePrompt(pref))
        val view = DialogNewSessionBinding.inflate(activity.layoutInflater)
        val string: (Int, Array<out Any>) -> String = { id, args -> activity.getString(id, *args) }
        fun showSources() {
            // Named as in the choice ("Studied document only"), not as on a reply.
            val title = sessionSourcesChoices(pref.getAgentNoteReplySources(), string).first { it.checked }.title
            view.newSessionSources.text = activity.getString(R.string.agent_note_sources_chip, title)
            view.newSessionSourcesRow.contentDescription = activity.getString(R.string.agent_note_sources_chip_description, title)
        }
        showSources()
        view.newSessionSources.setOnCloseIconClickListener { view.newSessionSourcesRow.performClick() }
        view.newSessionSourcesRow.setOnClickListener {
            val current = pref.getAgentNoteReplySources()
            val choices = sessionSourcesChoices(current, string)
            val entries = choices.map { choice ->
                android.text.SpannableStringBuilder(choice.title).apply {
                    append("\n")
                    val start = length
                    append(choice.detail)
                    setSpan(android.text.style.RelativeSizeSpan(0.8f), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.agent_note_sources_choice_title)
                .setSingleChoiceItems(entries.toTypedArray<CharSequence>(), choices.indexOfFirst { it.checked }) { dialog, index ->
                    dialog.dismiss()
                    val chosen = choices[index].sources
                    if (chosen == current) return@setSingleChoiceItems
                    pref.setAgentNoteReplySources(chosen)
                    showSources()
                    copyAgentText(activity, studyModePrompt(pref))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.agent_notes_start_study_session)
            .setView(view.root)
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

/**
 * The line a new study session's prompt ends with for [sources]: what it may use, which replaces the
 * prompt's Library rules where they say more. Null for the default (Library and Mental Model, as before).
 */
internal fun sessionSourcesLine(sources: AgentNoteReplySources): String? = when (sources) {
    AgentNoteReplySources.NONE ->
        "- Sources for this session: do not use my Thoughts Library or its Mental Model (no knowledge_profile, no " +
            "Library documents or notes). Answer from my captures, my replies and your own knowledge. This replaces the " +
            "Library rules above."
    AgentNoteReplySources.LINKED_PDF ->
        "- Sources for this session: only the document we study (the one you pass to own_topic). Do not read other " +
            "Library documents or the Mental Model, and skip knowledge_profile. This replaces the Library rules above."
    AgentNoteReplySources.LIBRARY ->
        "- Sources for this session: my Library documents, but not the Mental Model: skip knowledge_profile and the " +
            "Mental Model note."
    AgentNoteReplySources.LIBRARY_AND_MENTAL_MODEL -> null
}

/** The New session choice: the reply choices, worded for a session (the studied document, not a note's PDF). */
internal fun sessionSourcesChoices(
    current: AgentNoteReplySources,
    string: (Int, Array<out Any>) -> String,
): List<AgentNoteReplySourcesChoice> = agentNoteReplySourcesChoices(current, null, string).map { choice ->
    when (choice.sources) {
        AgentNoteReplySources.LINKED_PDF -> choice.copy(
            title = string(R.string.agent_notes_session_sources_linked, emptyArray()),
            detail = string(R.string.agent_notes_session_sources_linked_detail, emptyArray()),
        )
        AgentNoteReplySources.NONE -> choice.copy(detail = string(R.string.agent_notes_session_sources_none_detail, emptyArray()))
        else -> choice
    }
}

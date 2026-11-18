// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.DialogHomeMenuBinding
import com.gitlab.mudlej.MjPdfReader.ui.about.AboutActivity
import com.gitlab.mudlej.MjPdfReader.ui.agentnotes.StudyModeControls
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HomeMenuDialog(
    private val activity: AppCompatActivity,
    private val pref: Preferences,
    private val currentTab: () -> HomeTab,
    private val onLibraryDetailsChanged: () -> Unit,
    private val onShowStats: () -> Unit,
    private val onSyncNow: () -> Unit,
    private val onShowOperations: () -> Unit,
) {

    fun show() {
        val binding = DialogHomeMenuBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .create()

        val tab = currentTab()
        binding.librarySection.visibility =
            if (tab == HomeTab.LIBRARY) View.VISIBLE else View.GONE
        binding.menuDivider.visibility =
            if (tab == HomeTab.LIBRARY) View.VISIBLE else View.GONE

        bindLibraryDetails(binding)

        com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNotesDot.showNow(activity, binding.agentNotesRowDot)
        binding.agentNotesRow.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNotesListActivity.intent(activity))
        }
        // "Instant notes" while push does not deliver notes; a new study session starts from Agent notes.
        StudyModeControls.bind(
            activity, pref, binding.studyModeSwitch, detail = null, pushLine = null, startSession = null, dismiss = dialog::dismiss,
        )
        binding.settingsRow.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
        binding.aboutRow.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, AboutActivity::class.java))
        }
        binding.statsRow.setOnClickListener {
            dialog.dismiss()
            onShowStats()
        }
        binding.syncNowRow.setOnClickListener {
            dialog.dismiss()
            onSyncNow()
        }
        binding.operationsRow.setOnClickListener {
            dialog.dismiss()
            onShowOperations()
        }

        dialog.show()
    }

    private fun bindLibraryDetails(binding: DialogHomeMenuBinding) {
        binding.showReviewCountsSwitch.isChecked = pref.getLibraryShowReviewCounts()
        binding.showReviewCountsSwitch.setOnCheckedChangeListener { _, checked ->
            pref.setLibraryShowReviewCounts(checked)
            onLibraryDetailsChanged()
        }
        binding.showPageCountsSwitch.isChecked = pref.getLibraryShowPageCounts()
        binding.showPageCountsSwitch.setOnCheckedChangeListener { _, checked ->
            pref.setLibraryShowPageCounts(checked)
            onLibraryDetailsChanged()
        }
    }
}

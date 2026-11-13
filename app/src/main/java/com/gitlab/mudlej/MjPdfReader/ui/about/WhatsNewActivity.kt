// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.about

import android.os.Bundle
import android.view.MenuItem
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityWhatsNewBinding
import com.gitlab.mudlej.MjPdfReader.databinding.WhatsNewRowItemBinding
import com.gitlab.mudlej.MjPdfReader.databinding.WhatsNewSectionBinding

class WhatsNewActivity : AppCompatActivity() {

    private data class Change(
        @DrawableRes val iconRes: Int,
        @StringRes val titleRes: Int,
        @StringRes val bodyRes: Int,
    )

    private data class Section(
        @StringRes val titleRes: Int,
        val changes: List<Change>,
    )

    private lateinit var binding: ActivityWhatsNewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhatsNewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        binding.versionChip.text = "Version ${BuildConfig.VERSION_NAME}"
        bindSections()
    }

    private fun bindSections() {
        for (section in sections()) {
            val sectionBinding =
                WhatsNewSectionBinding.inflate(layoutInflater, binding.sectionsContainer, true)
            sectionBinding.sectionTitle.setText(section.titleRes)
            for (change in section.changes) {
                val rowBinding =
                    WhatsNewRowItemBinding.inflate(layoutInflater, sectionBinding.sectionRows, true)
                rowBinding.rowIcon.setImageResource(change.iconRes)
                rowBinding.rowTitle.setText(change.titleRes)
                rowBinding.rowBody.setText(change.bodyRes)
            }
        }
    }

    private fun sections(): List<Section> = listOf(
        Section(
            R.string.whats_new_thoughts_section_diary,
            listOf(
                Change(R.drawable.ic_edit, R.string.whats_new_thoughts_diary_title, R.string.whats_new_thoughts_diary_body),
            ),
        ),
        Section(
            R.string.whats_new_thoughts_section_sync,
            listOf(
                Change(R.drawable.ic_sync_done, R.string.whats_new_thoughts_rebuild_title, R.string.whats_new_thoughts_rebuild_body),
                Change(R.drawable.ic_refresh, R.string.whats_new_thoughts_large_sync_title, R.string.whats_new_thoughts_large_sync_body),
                Change(R.drawable.ic_edit, R.string.whats_new_thoughts_synced_edits_title, R.string.whats_new_thoughts_synced_edits_body),
            ),
        ),
        Section(
            R.string.whats_new_thoughts_section_comments,
            listOf(
                Change(R.drawable.ic_comment, R.string.whats_new_thoughts_comment_load_title, R.string.whats_new_thoughts_comment_load_body),
            ),
        ),
        Section(
            R.string.whats_new_thoughts_section_review,
            listOf(
                Change(R.drawable.ic_schedule_small, R.string.whats_new_thoughts_tags_title, R.string.whats_new_thoughts_tags_body),
                Change(R.drawable.ic_review_add, R.string.whats_new_thoughts_areas_title, R.string.whats_new_thoughts_areas_body),
            ),
        ),
    )

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

}

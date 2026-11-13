// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import androidx.appcompat.R as AppCompatR
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeFedoraStatusBinding
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

internal fun bindLibrarySyncStatus(
    binding: ItemHomeFedoraStatusBinding,
    presentation: LibrarySyncStatusPresentation,
    onClick: () -> Unit,
) {
    val statusColorAttr = if (presentation.hasSyncFailure) {
        AppCompatR.attr.colorError
    } else {
        MaterialR.attr.colorOnSurfaceVariant
    }
    val iconColorAttr = if (presentation.hasSyncFailure) {
        AppCompatR.attr.colorError
    } else {
        AppCompatR.attr.colorPrimary
    }
    binding.fedoraStatusLabel.text = presentation.label
    binding.fedoraStatusTimestamp.text = presentation.timestamp
    binding.fedoraStatusIcon.setImageResource(
        if (presentation.hasSyncFailure) R.drawable.ic_sync_problem else R.drawable.ic_refresh,
    )
    binding.fedoraStatusIcon.setColorFilter(
        MaterialColors.getColor(binding.root, iconColorAttr),
    )
    binding.fedoraStatusTimestamp.setTextColor(
        MaterialColors.getColor(binding.root, statusColorAttr),
    )
    binding.root.contentDescription = if (presentation.hasSyncFailure) {
        binding.root.context.getString(
            R.string.home_sync_failure_content_description,
            presentation.label,
            presentation.timestamp,
        )
    } else {
        "${presentation.label}. ${presentation.timestamp}"
    }
    binding.root.setOnClickListener { onClick() }
}

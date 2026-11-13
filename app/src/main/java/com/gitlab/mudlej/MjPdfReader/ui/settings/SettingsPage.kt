// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.gitlab.mudlej.MjPdfReader.R

enum class SettingsPage(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    @DrawableRes val iconRes: Int,
) {
    HOME(
        R.string.settings_home,
        R.string.settings_home_summary,
        R.drawable.ic_grid_view,
    ),
    SYNCHRONIZATION(
        R.string.settings_synchronization,
        R.string.settings_synchronization_summary,
        R.drawable.ic_refresh,
    ),
    APPEARANCE(
        R.string.settings_appearance,
        R.string.settings_appearance_summary,
        R.drawable.ic_color_palate,
    ),
    READING(
        R.string.settings_reading,
        R.string.settings_reading_summary,
        R.drawable.ic_book_bookmark,
    ),
    CUSTOMIZE_CONTROLS(
        R.string.customize_controls,
        R.string.customize_controls_summary,
        R.drawable.ic_display_settings,
    ),
    AGENT_PROMPTS(
        R.string.settings_agent_prompts,
        R.string.settings_agent_prompts_summary,
        R.drawable.ic_copy,
    ),
}

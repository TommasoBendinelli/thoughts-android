// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import com.gitlab.mudlej.MjPdfReader.R

sealed class HomeSection {

    data class PermissionCard(
        @StringRes val messageRes: Int = R.string.home_permission_card_message,
    ) : HomeSection()

    data class Hero(val items: List<HomeItem>) : HomeSection()

    data class Heading(
        @StringRes val titleRes: Int,
        @DrawableRes val iconRes: Int,
        val count: Int,
        val showDivider: Boolean = false,
        val expanded: Boolean? = null,
    ) : HomeSection()

    data class EmptyState(
        @StringRes val titleRes: Int,
        @StringRes val messageRes: Int,
    ) : HomeSection()

    data class FedoraStatus(
        val label: String,
        val timestamp: String,
        val hasSyncFailure: Boolean = false,
    ) : HomeSection()
}

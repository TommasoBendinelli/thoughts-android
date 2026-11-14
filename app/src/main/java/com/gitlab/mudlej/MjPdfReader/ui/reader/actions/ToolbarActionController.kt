// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.actions

import android.view.Menu
import android.view.MenuItem
import com.gitlab.mudlej.MjPdfReader.R

class ToolbarActionController(
    private val actionResolver: ConfigurableActionResolver,
    private val primaryActionId: () -> String,
    private val secondaryActionId: () -> String,
) {

    fun update(menu: Menu) {
        val navigation = toolbarNavigationPresentation(
            hasDocument = actionResolver.hasDocument(),
            canGoBack = actionResolver.action(ConfigurableAction.NAV_BACK)?.visible == true,
            canGoForward = actionResolver.action(ConfigurableAction.NAV_FORWARD)?.visible == true,
        )
        configureNavigationButton(
            menu.findItem(R.id.toolbarNavigationBackOption),
            ConfigurableAction.NAV_BACK,
            navigation.visible,
            navigation.backEnabled,
        )
        configureNavigationButton(
            menu.findItem(R.id.toolbarNavigationForwardOption),
            ConfigurableAction.NAV_FORWARD,
            navigation.visible,
            navigation.forwardEnabled,
        )
        configureButton(menu.findItem(R.id.toolbarPrimaryActionOption), primaryActionId())
        configureButton(menu.findItem(R.id.toolbarSecondaryActionOption), secondaryActionId())
        menu.findItem(R.id.readerActionsOption)?.isVisible = true
    }

    fun handle(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.toolbarNavigationBackOption -> actionResolver.perform(ConfigurableAction.NAV_BACK.id)
            R.id.toolbarNavigationForwardOption -> actionResolver.perform(ConfigurableAction.NAV_FORWARD.id)
            R.id.toolbarPrimaryActionOption -> actionResolver.perform(primaryActionId())
            R.id.toolbarSecondaryActionOption -> actionResolver.perform(secondaryActionId())
            else -> false
        }
    }

    private fun configureButton(menuItem: MenuItem?, actionId: String) {
        if (actionId == ConfigurableAction.NAV_BACK.id ||
            actionId == ConfigurableAction.NAV_FORWARD.id ||
            actionId == ConfigurableAction.FULLSCREEN.id
        ) {
            menuItem?.isVisible = false
            return
        }
        val action = actionResolver.action(actionId)
        if (menuItem == null || action == null || !action.visible) {
            menuItem?.isVisible = false
            return
        }

        menuItem.isVisible = true
        menuItem.setTitle(action.titleRes)
        menuItem.setIcon(action.iconRes)
    }

    private fun configureNavigationButton(
        menuItem: MenuItem?,
        actionId: ConfigurableAction,
        visible: Boolean,
        enabled: Boolean,
    ) {
        menuItem ?: return
        menuItem.isVisible = visible
        menuItem.isEnabled = enabled
        menuItem.setTitle(actionId.titleRes)
        menuItem.setIcon(actionId.iconRes)
        // A tinted icon looks the same when disabled: dim an arrow that leads nowhere.
        menuItem.icon?.mutate()?.alpha = navigationIconAlpha(enabled)
    }
}

/** Back and Forward are drawn dimmed while they lead nowhere, as the agent note's arrows are. */
internal fun navigationIconAlpha(enabled: Boolean): Int = if (enabled) 255 else 97

internal data class ToolbarNavigationPresentation(
    val visible: Boolean,
    val backEnabled: Boolean,
    val forwardEnabled: Boolean,
)

internal fun toolbarNavigationPresentation(
    hasDocument: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
) = ToolbarNavigationPresentation(
    visible = hasDocument,
    backEnabled = hasDocument && canGoBack,
    forwardEnabled = hasDocument && canGoForward,
)

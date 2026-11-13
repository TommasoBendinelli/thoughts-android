// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.settings

import androidx.annotation.StringRes
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.reader.AgentPromptTemplates
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ConfigurableAction

internal class SettingsEntryProvider(private val preferences: Preferences) {

    fun entries(): List<SettingEntry> {
        return listOf(
            homeEntries(),
            synchronizationEntries(),
            appearanceEntries(),
            readingEntries(),
            controlEntries(),
            textEntries(),
            readAloudEntries(),
            highlightingEntries(),
            agentPromptEntries(),
        ).flatten()
    }

    private fun agentPromptEntries(): List<SettingEntry> {
        val keywords = listOf("agent", "claude", "codex", "prompt", "copy", "template", "review", "card", "placeholder")
        return listOf(
            SettingEntry(
                page = SettingsPage.AGENT_PROMPTS,
                titleRes = R.string.agent_notes_receive_title,
                summaryRes = R.string.agent_notes_receive_summary_off,
                keywords = listOf("agent", "notes", "receive", "phone", "device", "inbox", "study", "mac", "other"),
            ) { breadcrumb ->
                receiveAgentNotesPreference(breadcrumb)
            },
            SettingEntry(
                page = SettingsPage.AGENT_PROMPTS,
                titleRes = R.string.copy_document_for_agent,
                keywords = keywords,
            ) { breadcrumb ->
                agentPromptTemplatePreference(AgentPromptTemplates.Kind.AGENT, breadcrumb)
            },
            SettingEntry(
                page = SettingsPage.AGENT_PROMPTS,
                titleRes = R.string.agent_prompt_study_mode,
                keywords = keywords + listOf("study", "mode", "physical", "paper", "mentra", "glasses", "photo"),
            ) { breadcrumb ->
                agentPromptTemplatePreference(AgentPromptTemplates.Kind.STUDY_MODE, breadcrumb)
            },
            SettingEntry(
                page = SettingsPage.AGENT_PROMPTS,
                titleRes = R.string.agent_prompt_mentra_folder,
                summaryRes = R.string.agent_prompt_mentra_folder_help,
                keywords = listOf("mentra", "glasses", "photo", "folder", "mac", "physical"),
            ) { breadcrumb ->
                mentraPhotoFolderPreference(breadcrumb)
            },
            SettingEntry(
                page = SettingsPage.AGENT_PROMPTS,
                titleRes = R.string.glasses_camera_title,
                summaryRes = R.string.glasses_camera_help,
                keywords = listOf("mentra", "glasses", "camera", "photo", "picture", "send", "reply", "agent"),
            ) { breadcrumb ->
                glassesCameraPreference(breadcrumb)
            },
        )
    }

    private fun synchronizationEntries(): List<SettingEntry> {
        return listOfNotNull(
            SettingEntry(
                page = SettingsPage.SYNCHRONIZATION,
                titleRes = R.string.thoughts_server_title,
                summaryRes = R.string.thoughts_server_not_configured,
                keywords = listOf("server", "tailscale", "sync", "okular", "comments", "build"),
            ) { breadcrumb ->
                thoughtsServerPreference(breadcrumb)
            },
            SettingEntry(
                page = SettingsPage.SYNCHRONIZATION,
                titleRes = R.string.comment_author_title,
                summaryRes = R.string.comment_author_message,
                keywords = listOf("author", "name", "comment", "note", "reply", "annotation"),
            ) { breadcrumb ->
                commentAuthorPreference(breadcrumb)
            },
            SettingEntry(
                page = SettingsPage.SYNCHRONIZATION,
                titleRes = R.string.source_shared_folder_title,
                summaryRes = R.string.source_shared_folder_summary,
                keywords = listOf("sources", "shared", "folder", "documents", "private", "storage", "latex", "markdown", "files"),
            ) { breadcrumb ->
                sharedSourceFolderPreference(breadcrumb)
            }.takeUnless { com.gitlab.mudlej.MjPdfReader.BuildConfig.IS_TEST_ENVIRONMENT }, // Thoughts Test stays private.
            SettingEntry(
                page = SettingsPage.SYNCHRONIZATION,
                titleRes = R.string.sync_battery_settings_title,
                summaryRes = R.string.sync_battery_restricted,
                keywords = listOf("battery", "background", "sync", "optimization", "exemption", "power"),
            ) { breadcrumb ->
                syncBatteryPreference(breadcrumb)
            },
            SettingEntry(
                page = SettingsPage.SYNCHRONIZATION,
                titleRes = R.string.synchronization_threshold_title,
                summaryRes = R.string.synchronization_threshold_summary,
                keywords = listOf("sync", "mobile", "data", "threshold", "wifi", "upload", "download"),
            ) { breadcrumb ->
                synchronizationThresholdPreference(breadcrumb)
            },
        )
    }

    private fun homeEntries(): List<SettingEntry> {
        return listOf(
            switchEntry(
                page = SettingsPage.HOME,
                titleRes = R.string.home_show_pdf_title_title,
                key = Preferences.homeShowPdfTitleKey,
                defaultValue = Preferences.homeShowPdfTitleDefault,
                summaryRes = R.string.home_show_pdf_title_summary,
                keywords = listOf("home", "title", "metadata", "name"),
            ),
            SettingEntry(
                page = SettingsPage.HOME,
                titleRes = R.string.home_list_title_lines_title,
                summaryRes = R.string.home_list_title_lines_summary,
                keywords = listOf("home", "title", "name", "lines", "list", "rows", "wrap", "truncate"),
            ) { breadcrumb ->
                homeListTitleLinesPreference(breadcrumb)
            },
        )
    }

    private fun appearanceEntries(): List<SettingEntry> {
        return listOf(
            appThemeEntry(),
            SettingEntry(
                page = SettingsPage.APPEARANCE,
                titleRes = R.string.dark_theme_for_pdf,
                keywords = listOf("pdf", "theme", "dark", "night", "system"),
                sectionRes = R.string.appearance_section_theme,
            ) { breadcrumb ->
                pdfPagesThemePreference(breadcrumb = breadcrumb)
            },

            switchEntry(
                page = SettingsPage.APPEARANCE,
                titleRes = R.string.quality,
                key = Preferences.highQualityKey,
                defaultValue = Preferences.highQualityDefault,
                summaryRes = R.string.quality_summary,
                keywords = listOf("rendering", "quality"),
                sectionRes = R.string.appearance_section_rendering,
            ),
            switchEntry(
                page = SettingsPage.APPEARANCE,
                titleRes = R.string.always_hide_margins,
                key = Preferences.alwaysHideMarginsKey,
                defaultValue = Preferences.alwaysHideMarginsDefault,
                summaryRes = R.string.always_hide_margins_summary,
                keywords = listOf("crop", "margin", "margins"),
                sectionRes = R.string.appearance_section_page,
            ),
            switchEntry(
                page = SettingsPage.APPEARANCE,
                titleRes = R.string.show_app_bar_page_count_title,
                key = Preferences.showAppBarPageCountKey,
                defaultValue = Preferences.showAppBarPageCountDefault,
                summaryRes = R.string.show_app_bar_page_count_summary,
                keywords = listOf("app bar", "toolbar", "title", "page count"),
                sectionRes = R.string.appearance_section_page,
            ),
        )
    }

    private fun readingEntries(): List<SettingEntry> {
        return listOf(

            switchEntry(
                page = SettingsPage.READING,
                titleRes = R.string.keep_screen_on,
                key = Preferences.screenOnKey,
                defaultValue = Preferences.screenOnDefault,
                summaryRes = R.string.keep_screen_on_summary,
                keywords = listOf("display", "sleep", "screen"),
                sectionRes = R.string.reading_section_session,
            ),
        )
    }

    private fun controlEntries(): List<SettingEntry> {
        return listOf(
            actionEntry(
                page = SettingsPage.CUSTOMIZE_CONTROLS,
                titleRes = R.string.primary_button_action,
                key = Preferences.primaryButtonActionKey,
                defaultValue = Preferences.primaryButtonActionDefault,
                currentValue = preferences::getPrimaryButtonAction,
                actions = ConfigurableAction.toolbarActions,
                keywords = listOf("toolbar", "app bar", "button", "action"),
                onActionSelected = preferences::setPrimaryButtonAction,
            ),
            actionEntry(
                page = SettingsPage.CUSTOMIZE_CONTROLS,
                titleRes = R.string.secondary_button_action,
                key = Preferences.secondaryButtonActionKey,
                defaultValue = Preferences.secondaryButtonActionDefault,
                currentValue = preferences::getSecondaryButtonAction,
                actions = ConfigurableAction.toolbarActions,
                keywords = listOf("toolbar", "app bar", "button", "action"),
                onActionSelected = preferences::setSecondaryButtonAction,
            ),
            shortcutBarEntry(),
            fullScreenButtonsEntry(),
            switchEntry(
                page = SettingsPage.READING,
                titleRes = R.string.turn_page_by_volume_buttons_title,
                key = Preferences.turnPageByVolumeButtonsKey,
                defaultValue = Preferences.turnPageByVolumeButtonsDefault,
                summaryRes = R.string.turn_page_by_volume_buttons_summary,
                keywords = listOf("volume", "buttons", "page turn"),
            ),
        )
    }

    private fun textEntries(): List<SettingEntry> {
        return listOf(
            switchEntry(
                page = SettingsPage.READING,
                titleRes = R.string.inline_text_selection_title,
                key = Preferences.inlineTextSelectionKey,
                defaultValue = Preferences.inlineTextSelectionDefault,
                summaryRes = R.string.inline_text_selection_summary,
                keywords = listOf("inline", "selection", "copy", "text"),
            ),
            switchEntry(
                page = SettingsPage.READING,
                titleRes = R.string.search_ignore_accents_title,
                key = Preferences.searchIgnoreAccentsKey,
                defaultValue = Preferences.searchIgnoreAccentsDefault,
                summaryRes = R.string.search_ignore_accents_summary,
                keywords = listOf("search", "accents", "diacritics"),
            ),
            switchEntry(
                page = SettingsPage.READING,
                titleRes = R.string.search_zoom_to_result_title,
                key = Preferences.searchZoomToResultKey,
                defaultValue = Preferences.searchZoomToResultDefault,
                summaryRes = R.string.search_zoom_to_result_summary,
                keywords = listOf("search", "zoom", "result", "navigate"),
            ),
        )
    }

    private fun readAloudEntries(): List<SettingEntry> {
        val keywords = listOf("read aloud", "voice", "speech", "tts", "openrouter", "fish", "audio", "key", "api")
        return listOf(
            SettingEntry(
                page = SettingsPage.READING,
                titleRes = R.string.read_aloud_key_title,
                keywords = keywords,
                sectionRes = R.string.read_aloud_voice_section,
            ) { breadcrumb ->
                readAloudKeyPreference(breadcrumb)
            },
            SettingEntry(
                page = SettingsPage.READING,
                titleRes = R.string.read_aloud_test_title,
                summaryRes = R.string.read_aloud_test_summary,
                keywords = keywords + listOf("test"),
                sectionRes = R.string.read_aloud_voice_section,
            ) { breadcrumb ->
                readAloudTestPreference(breadcrumb)
            },
        )
    }

    private fun highlightingEntries(): List<SettingEntry> {
        return listOf(
            SettingEntry(
                page = SettingsPage.APPEARANCE,
                titleRes = R.string.highlight_colors,
                summaryRes = R.string.highlight_colors_summary,
                keywords = listOf("highlight", "color", "colors", "palette", "strip"),
            ) { breadcrumb ->
                highlightColorsPreference(breadcrumb = breadcrumb)
            },
        )
    }

    private fun appThemeEntry(): SettingEntry {
        return SettingEntry(
            page = SettingsPage.APPEARANCE,
            titleRes = R.string.dark_theme_for_app,
            keywords = listOf("ui", "theme", "dark", "night", "system"),
            sectionRes = R.string.appearance_section_theme,
        ) { breadcrumb ->
            interfaceThemePreference(breadcrumb = breadcrumb)
        }
    }

    private fun shortcutBarEntry(): SettingEntry {
        return SettingEntry(
            page = SettingsPage.CUSTOMIZE_CONTROLS,
            titleRes = R.string.shortcut_bar_buttons,
            summaryRes = R.string.shortcut_bar_buttons_summary,
            keywords = listOf("shortcut", "buttons", "actions"),
        ) { breadcrumb ->
            shortcutBarButtonsPreference(breadcrumb = breadcrumb)
        }
    }

    private fun fullScreenButtonsEntry(): SettingEntry {
        return SettingEntry(
            page = SettingsPage.CUSTOMIZE_CONTROLS,
            titleRes = R.string.fullscreen_buttons,
            summaryRes = R.string.fullscreen_buttons_summary,
            keywords = listOf("fullscreen", "buttons", "actions"),
        ) { breadcrumb ->
            fullScreenButtonsPreference(breadcrumb = breadcrumb)
        }
    }

    private fun scrollingInfoCardEntry(): SettingEntry {
        return SettingEntry(
            page = SettingsPage.APPEARANCE,
            titleRes = R.string.scrolling_info_card,
            summaryRes = R.string.scrolling_info_card_summary,
            keywords = listOf("scrolling", "fullscreen", "info", "card", "time", "clock", "page", "percent", "progress", "title", "file"),
            sectionRes = R.string.appearance_section_page,
        ) { breadcrumb ->
            scrollingInfoCardPreference(breadcrumb = breadcrumb)
        }
    }
}

private fun switchEntry(
    page: SettingsPage,
    @StringRes titleRes: Int,
    key: String,
    defaultValue: Boolean,
    @StringRes summaryRes: Int? = null,
    keywords: List<String> = emptyList(),
    @StringRes sectionRes: Int? = null,
): SettingEntry {
    return SettingEntry(
        page = page,
        titleRes = titleRes,
        summaryRes = summaryRes,
        keywords = keywords,
        sectionRes = sectionRes,
    ) { breadcrumb ->
        switchPreference(
            titleRes = titleRes,
            key = key,
            defaultValue = defaultValue,
            summaryRes = summaryRes,
            breadcrumb = breadcrumb,
        )
    }
}

private fun actionEntry(
    page: SettingsPage,
    @StringRes titleRes: Int,
    key: String,
    defaultValue: String,
    currentValue: () -> String,
    actions: List<ConfigurableAction>,
    keywords: List<String>,
    onActionSelected: (String) -> Unit,
): SettingEntry {
    return SettingEntry(
        page = page,
        titleRes = titleRes,
        keywords = keywords,
    ) { breadcrumb ->
        actionPreference(
            titleRes = titleRes,
            key = key,
            defaultValue = defaultValue,
            currentValue = currentValue(),
            actions = actions,
            breadcrumb = breadcrumb,
            onActionSelected = onActionSelected,
        )
    }
}

private fun floatPreferenceEntry(
    page: SettingsPage,
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int,
    key: String,
    currentValue: () -> Float,
    defaultValue: Float,
    minValue: Float,
    maxValue: Float,
    keywords: List<String>,
    onValueSelected: (Float) -> Unit,
): SettingEntry {
    return SettingEntry(
        page = page,
        titleRes = titleRes,
        summaryRes = summaryRes,
        keywords = keywords,
    ) { breadcrumb ->
        floatPreference(
            titleRes = titleRes,
            summaryRes = summaryRes,
            key = key,
            currentValue = currentValue(),
            defaultValue = defaultValue,
            minValue = minValue,
            maxValue = maxValue,
            breadcrumb = breadcrumb,
            onValueSelected = onValueSelected,
        )
    }
}

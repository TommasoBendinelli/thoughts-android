// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import android.content.SharedPreferences
import com.gitlab.mudlej.MjPdfReader.ui.reader.AgentPromptTemplates
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.HighlightPalette
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ConfigurableAction
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeProgressStyle
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTab
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTitleEllipsize
import com.gitlab.mudlej.MjPdfReader.ui.home.ListFilter
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewAnchorFilter
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewPdfFilter
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewPriorityFilter
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewLocationFilter
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewSortOrder

class Preferences(private val prefMan: SharedPreferences) {

    init {
        migrateLegacyKey("spaceBetweenPagesKey", spaceBetweenPagesKey)
        migrateLegacyKey("alwaysHorizontalKey", alwaysHorizontalKey)
        migrateRemovedHomeTabs()
        migrateRemovedSettings()
    }

    private fun migrateLegacyKey(legacyKey: String, key: String) {
        if (!prefMan.contains(legacyKey)) {
            return
        }
        val editor = prefMan.edit()
        if (!prefMan.contains(key)) {
            editor.putBoolean(key, safeGetBoolean(legacyKey, false))
        }
        editor.remove(legacyKey).apply()
    }

    private fun migrateRemovedHomeTabs() {
        when (prefMan.getString(homeTabKey, null)) {
            // The Comments tab ("OVERVIEW") and the older Recent tab gave their place to the Diary.
            "RECENT", "OVERVIEW" -> prefMan.edit().putString(homeTabKey, HomeTab.DIARY.name).apply()
            "FOLDERS" -> prefMan.edit()
                .putString(homeTabKey, HomeTab.REVIEW.name)
                .remove("homeFolderFlat")
                .apply()
        }
    }

    private fun migrateRemovedSettings() {
        val removedKeys = listOf(
            "scanMode", "scanLocations", "workflowScanDefaultApplied",
            // The "Save Reading History" switch; reading history is always on now.
            "historyEnabled",
            // Settings > Backup and Restore, removed 2026-10-22.
            "backupFolderTreeUri", "autoBackupEnabled", "autoBackupHour", "autoBackupMinute",
            "autoBackupLastRun", "autoBackupLastError", "autoBackupErrorAcknowledgedRun",
            "autoBackupEnabledAt", "importResultPending",
        )
            .filter(prefMan::contains)
        if (removedKeys.isEmpty()) {
            return
        }
        val editor = prefMan.edit()
        removedKeys.forEach(editor::remove)
        editor.apply()
    }

    private fun safeGetBoolean(key: String, default: Boolean): Boolean {
        return try {
            prefMan.getBoolean(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private fun safeGetInt(key: String, default: Int): Int {
        return try {
            prefMan.getInt(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private fun safeGetFloat(key: String, default: Float): Float {
        return try {
            prefMan.getFloat(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private fun safeGetString(key: String, default: String?): String? {
        return try {
            prefMan.getString(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private fun safeGetStringSet(key: String, default: Set<String>?): Set<String>? {
        return try {
            prefMan.getStringSet(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private inline fun <reified T : Enum<T>> safeGetEnum(key: String, defaultName: String): T {
        val stored = safeGetString(key, defaultName) ?: defaultName
        return runCatching { enumValueOf<T>(stored) }.getOrElse {
            prefMan.edit().remove(key).apply()
            enumValueOf(defaultName)
        }
    }

    companion object {
        // Preferences keys
        const val firstInstallKey = "firstInstall"
        const val showFeaturesDialogKey = "showFeaturesDialog"
        const val lastSeenVersionCodeKey = "lastSeenVersionCode"
        const val highQualityKey = "highQuality"
        const val antiAliasingKey = "antiAliasing"
        const val horizontalScrollKey = "horizontalScroll"
        const val dualPageModeKey = "dualPageMode"
        const val dualPageFirstPageAloneKey = "dualPageFirstPageAlone"
        const val pageSnapKey = "pageSnap"
        const val pageFlingKey = "pageFling"
        const val browserScrollModeKey = "browserScrollMode"
        const val turnPageByMouseButtonsKey = "turnPageByMouseButtons"
        const val pdfDarkThemeKey = "pdfDarkTheme"
        const val appFollowSystemThemeKey = "appFollowSystemTheme"
        const val pdfFollowSystemThemeKey = "pdfFollowSystemTheme"
        const val interfaceThemeKey = "interfaceTheme"
        const val pdfPagesThemeKey = "pdfPagesTheme"
        const val enableReloadButtonKey = "enableReloadButton"
        const val primaryButtonActionKey = "primaryButtonAction"
        const val secondaryButtonActionKey = "secondaryButtonAction"
        const val fullScreenOverlayActionsKey = "fullScreenOverlayActions"
        const val fullScreenOverlayActionOrderKey = "fullScreenOverlayActionOrder"
        const val shortcutBarActionsKey = "shortcutBarActions"
        const val shortcutBarActionOrderKey = "shortcutBarActionOrder"
        const val screenOnKey = "screenOn"
        const val spaceBetweenPagesKey = "spaceBetweenPages"
        const val hideDelayKey = "hideDelay"
        const val partSizeKey = "partSize"
        const val maxZoomKey = "maxZoom"
        const val inlineTextSelectionKey = "inlineTextSelection"
        const val detectExistingHighlightsKey = "detectExistingHighlights"
        const val highlightColorsKey = "highlightColors"
        const val searchIgnoreAccentsKey = "searchIgnoreAccents"
        const val searchZoomToResultKey = "searchZoomToResult"
        const val defaultTextModeKey = "defaultTextMode"
        const val turnPageByVolumeButtonsKey = "turnPageByVolumeButtons"
        const val showScrollHandlePageCountKey = "showScrollHandlePageCount"
        const val showAppBarPageCountKey = "showAppBarPageCount"
        const val alwaysHideMarginsKey = "alwaysHideMargins"
        const val secondBarEnabledKey = "secondBarEnabled"
        const val hideButtonsLabelsKey = "hideButtonsLabels"
        const val fullScreenInfoShowTimeKey = "fullScreenInfoShowTime"
        const val fullScreenInfoShowPdfNameKey = "fullScreenInfoShowPdfName"
        const val fullScreenInfoShowPageNumberKey = "fullScreenInfoShowPageNumber"
        const val fullScreenInfoShowReadingPercentageKey = "fullScreenInfoShowReadingPercentage"
        const val doubleTapToExitEnabledKey = "doubleTapToExitEnabled"
        const val alwaysOpenAtFirstPageKey = "alwaysOpenAtFirstPage"
        const val autoFullScreenKey = "autoFullScreenSwitch"
        const val alwaysHorizontalKey = "alwaysHorizontal"
        const val scrollSpeedKey = "scrollSpeed"
        const val listFilterKey = "listFilter"
        const val homeDisabledKey = "homeDisabled"
        const val homeShowPdfTitleKey = "homeShowPdfTitle"
        const val homeTabKey = "homeTab"
        const val homeProgressStyleKey = "homeProgressStyle"
        const val homeListTitleLinesKey = "homeListTitleLines"
        const val homeTitleEllipsizeKey = "homeTitleEllipsize"
        const val homeBadgePagesKey = "homeBadgePages"
        const val homeBadgeProgressKey = "homeBadgeProgress"
        const val homeBadgeLastOpenedKey = "homeBadgeLastOpened"
        const val homeBadgeFileSizeKey = "homeBadgeFileSize"
        const val homeBadgeStatusKey = "homeBadgeStatus"
        const val goToPageGridColumnsKey = "goToPageGridColumns"
        const val libraryShowReviewCountsKey = "libraryShowReviewCounts"
        const val libraryShowPageCountsKey = "libraryShowPageCounts"
        const val overviewSortKey = "overviewSort"
        const val overviewCommentTypeFilterKey = "overviewCommentTypeFilter"
        const val overviewDocumentFilterKey = "overviewDocumentFilter"
        const val overviewProjectFilterKey = "overviewProjectFilter"
        const val overviewProjectDocumentFilterKey = "overviewProjectDocumentFilter"
        const val overviewAnchorFilterKey = "overviewAnchorFilter"
        const val overviewPdfFilterKey = "overviewPdfFilter"
        const val overviewPriorityFilterKey = "overviewPriorityFilter"
        const val overviewLocationLabelKey = "overviewLocationLabel"
        const val overviewLocationLatitudeKey = "overviewLocationLatitude"
        const val overviewLocationLongitudeKey = "overviewLocationLongitude"
        const val overviewLocationPlaceIdKey = "overviewLocationPlaceId"
        const val overviewLocationRadiusKey = "overviewLocationRadius"
        const val overviewLastAddDocumentKey = "overviewLastAddDocument"
        const val keepSharedCopiesKey = "keepSharedCopies"
        const val synchronizationThresholdMegabytesKey = "synchronizationThresholdMegabytes"
        const val translationModeKey = "translationMode"
        const val translationEngineKey = "translationEngine"
        const val translationTargetLanguageKey = "translationTargetLanguage"
        const val translationCustomUrlKey = "translationCustomUrl"
        const val agentPromptTemplateKey = "agentPromptTemplate"

        /**
         * The "Copy for agent" default until 2026-10-22, which ended with an "I'm studying this card" line.
         * A phone that saved it unchanged gets the current default instead.
         */
        private const val previousDefaultAgentPromptTemplate =
            "Thoughts document: \"{document_name}\"\n" +
                "Project: {project}\n" +
                "Source: {source}  ({source_format})\n" +
                "To change {source_name}, create proposals with the Thoughts create_proposals tool (several edits at once) or " +
                "`thoughtsctl create-proposals`; don't edit the source directly unless asked, and never edit the PDF.\n" +
                "Built PDF: {pdf}  ({pdf_pages} pages)\n" +
                "Project file: {project_file}\n" +
                "Document ID: {document_id}\n" +
                "Review card: {card_id}\n" +
                "Question: \"{question}\"\n" +
                "Note: \"{note}\"\n" +
                "Answer pages: {answer_pages}\n" +
                "Answer source: {answer_source}\n" +
                "{answer_feedback}\n" +
                "I'm studying this card. Don't reveal, quote or hint at the answer until I have given my own answer or ask for it; " +
                "the answer pages and source lines above are for looking it up then.\n"
        const val studyModePromptTemplateKey = "studyModePromptTemplate"
        const val mentraPhotoFolderKey = "mentraPhotoFolder"
        /** The glasses' camera server, e.g. http://<glasses address>:8089 ("Send with picture"); empty when not set up. */
        const val glassesCameraUrlKey = "glassesCameraUrl"
        /** Whether an agent note's message shows one line (true) or all of it; the last choice holds for every note. */
        const val agentNoteMessageCollapsedKey = "agentNoteMessageCollapsed"
        const val agentNoteMessageCollapsedDefault = true
        const val dictionaryDefineWordsKey = "dictionaryDefineWords"

        // Default values
        const val firstInstallDefault = true
        const val showFeaturesDialogDefault = true
        const val highQualityDefault = false
        const val antiAliasingDefault = true
        const val horizontalScrollDefault = false
        const val dualPageModeDefault = false
        const val dualPageFirstPageAloneDefault = false
        const val pageSnapDefault = false
        const val pageFlingDefault = false
        const val browserScrollModeDefault = false
        const val turnPageByMouseButtonsDefault = true
        const val pdfDarkThemeDefault = false
        const val appFollowSystemThemeDefault = true    // NEW: for version v2.1 M3 Theme
        const val pdfFollowSystemThemeDefault = false
        const val enableReloadButtonDefault = false
        const val primaryButtonActionDefault = "fullscreen"
        const val secondaryButtonActionDefault = "none"
        const val annotationRenderingDefault = true
        const val screenOnDefault = false
        const val spaceBetweenPagesDefault = true
        const val hideDelayDefault = 3000
        const val spacingDefault = 10           // in dp
        const val minZoomDefault = 0.5f         //0.5f
        const val midZoomDefault = 2.0f
        const val maxZoomDefault = 10.0f
        const val partSizeDefault = 512f
        const val goToPageGridColumnsDefault = 3
        const val inlineTextSelectionDefault = true
        const val detectExistingHighlightsDefault = true
        const val searchIgnoreAccentsDefault = false
        const val searchZoomToResultDefault = false
        const val defaultTextModeDefault = false
        const val turnPageByVolumeButtonsDefault = false
        const val showScrollHandlePageCountDefault = false
        const val showAppBarPageCountDefault = false
        const val alwaysHideMarginsDefault = false
        const val secondBarEnabledDefault = false
        const val hideButtonsLabelsDefault = false
        const val fullScreenInfoShowTimeDefault = false
        const val fullScreenInfoShowPdfNameDefault = false
        const val fullScreenInfoShowPageNumberDefault = true
        const val fullScreenInfoShowReadingPercentageDefault = true
        const val doubleTapToExitEnabledDefault = false
        const val alwaysOpenAtFirstPageDefault = false
        const val autoFullScreenDefault = false
        const val alwaysHorizontalDefault = false
        const val scrollSpeedDefault = 3
        const val listFilterDefault = "RECENT"  // ListFilter.RECENT.name
        const val homeDisabledDefault = false
        const val homeShowPdfTitleDefault = true
        const val homeTabDefault = "DIARY"
        const val homeProgressStyleDefault = "RING"
        const val homeListTitleLinesDefault = 2
        const val homeTitleEllipsizeDefault = "END"
        const val homeBadgeDefault = true
        const val overviewSortDefault = "NEWEST"
        const val overviewDocumentFilterDefault = ""
        const val overviewAnchorFilterDefault = "ALL"
        const val overviewPdfFilterDefault = "ALL"
        const val overviewPriorityFilterDefault = "ALL"
        const val overviewLocationRadiusDefault = 10
        const val overviewLastAddDocumentDefault = ""
        const val keepSharedCopiesDefault = true
        const val synchronizationThresholdMegabytesDefault = 5
        const val synchronizationThresholdMegabytesMin = 1
        const val synchronizationThresholdMegabytesMax = 10_000
        const val themeSystem = "system"
        const val themeLight = "light"
        const val themeDark = "dark"
        const val translationModeApps = "apps"
        const val translationModeWeb = "web"
        const val translationModeDefault = translationModeApps
        const val translationEngineDefault = "google"
        const val translationTargetLanguageDefault = ""
        const val translationCustomUrlDefault = ""
        const val dictionaryDefineWordsDefault = true
        val fullScreenOverlayActionsDefault = ConfigurableAction.defaultFullScreenOverlayActionIds
        val shortcutBarActionsDefault = ConfigurableAction.defaultShortcutBarActionIds

        // Colors
        const val pdfDarkBackgroundColor = -0x313132          // -0x313132 = 0xffcecece
        const val pdfLightBackgroundColor = -0xcdcdce         // 0xff323232 = -0xcdcdce

        // Constants
        const val highlightColorsCount = 6
        const val minMaxZoom = 1f
        const val maxMaxZoom = 100f
        const val minPartSize = 5f
        const val maxPartSize = 1000f
        const val AUTO_SCROLL_UNIT = 0.1
    }

    // get values saved in Shared Preferences or return the default values
    fun getFirstInstall() = safeGetBoolean(firstInstallKey, firstInstallDefault)
    fun getShowFeaturesDialog() = safeGetBoolean(showFeaturesDialogKey, showFeaturesDialogDefault)
    fun getLastSeenVersionCode() = safeGetInt(lastSeenVersionCodeKey, 0)
    fun getHighQuality() = safeGetBoolean(highQualityKey, highQualityDefault)
    fun getAntiAliasing() = antiAliasingDefault
    fun getHorizontalScroll() = horizontalScrollDefault
    fun getDualPageMode() = safeGetBoolean(dualPageModeKey, dualPageModeDefault)
    fun getDualPageFirstPageAlone() = dualPageFirstPageAloneDefault
    fun getPageSnap() = pageSnapDefault
    fun getPageFling() = pageFlingDefault
    fun getBrowserScrollMode() = browserScrollModeDefault
    fun getTurnPageByMouseButtons() = turnPageByMouseButtonsDefault
    fun getPdfDarkTheme() = safeGetBoolean(pdfDarkThemeKey, pdfDarkThemeDefault)
    fun getAppFollowSystemTheme() = safeGetBoolean(appFollowSystemThemeKey, appFollowSystemThemeDefault)
    fun getPdfFollowSystemTheme() = safeGetBoolean(pdfFollowSystemThemeKey, pdfFollowSystemThemeDefault)
    fun getInterfaceTheme(): String {
        return safeGetString(interfaceThemeKey, null)
            ?: if (getAppFollowSystemTheme()) themeSystem else themeLight
    }
    fun getPdfPagesTheme(): String {
        return safeGetString(pdfPagesThemeKey, null)
            ?: if (getPdfFollowSystemTheme()) themeSystem else if (getPdfDarkTheme()) themeDark else themeLight
    }
    fun getScreenOn() = safeGetBoolean(screenOnKey, screenOnDefault)
    fun getSpaceBetweenPages() = spaceBetweenPagesDefault
    fun getHideDelay() = safeGetInt(hideDelayKey, hideDelayDefault)
    fun getPartSize() = partSizeDefault

    fun getGoToPageGridColumns() = safeGetInt(goToPageGridColumnsKey, goToPageGridColumnsDefault)

    fun getTranslationMode() = translationModeDefault

    fun getTranslationEngine() = translationEngineDefault

    fun getTranslationTargetLanguage() = translationTargetLanguageDefault

    fun getTranslationCustomUrl() = translationCustomUrlDefault

    fun getDictionaryDefineWords() = dictionaryDefineWordsDefault
    fun getMaxZoom() = maxZoomDefault
    fun getInlineTextSelection() = safeGetBoolean(inlineTextSelectionKey, inlineTextSelectionDefault)
    fun getDetectExistingHighlights() = detectExistingHighlightsDefault
    fun getHighlightColors(): List<Int> {
        val stored = safeGetString(highlightColorsKey, null)
            ?.split(",")
            ?.mapNotNull(HighlightPalette::fromName)
            ?: HighlightPalette.defaultSelection
        return (stored + HighlightPalette.defaultSelection)
            .filter { it in HighlightPalette.selectable }
            .distinct()
            .take(highlightColorsCount)
            .map { it.colorValue }
    }
    fun getSearchIgnoreAccents() = safeGetBoolean(searchIgnoreAccentsKey, searchIgnoreAccentsDefault)
    fun getSearchZoomToResult() = safeGetBoolean(searchZoomToResultKey, searchZoomToResultDefault)
    fun getDefaultTextMode() = defaultTextModeDefault
    fun getTurnPageByVolumeButtons() = safeGetBoolean(turnPageByVolumeButtonsKey, turnPageByVolumeButtonsDefault)
    fun getShowScrollHandlePageCount() = showScrollHandlePageCountDefault
    fun getShowAppBarPageCount() = safeGetBoolean(showAppBarPageCountKey, showAppBarPageCountDefault)
    fun getAlwaysHideMargins() = safeGetBoolean(alwaysHideMarginsKey, alwaysHideMarginsDefault)
    fun getSecondBarEnabled() = safeGetBoolean(secondBarEnabledKey, secondBarEnabledDefault)
    fun getHideButtonsLabels() = safeGetBoolean(hideButtonsLabelsKey, hideButtonsLabelsDefault)
    fun getFullScreenInfoShowTime() = fullScreenInfoShowTimeDefault
    fun getFullScreenInfoShowPdfName() = fullScreenInfoShowPdfNameDefault
    fun getFullScreenInfoShowPageNumber() = fullScreenInfoShowPageNumberDefault
    fun getFullScreenInfoShowReadingPercentage() = fullScreenInfoShowReadingPercentageDefault
    fun getDoubleTapToExitEnabled() = doubleTapToExitEnabledDefault

    fun getAlwaysOpenAtFirstPage() = alwaysOpenAtFirstPageDefault
    fun getAutoFullScreen() = autoFullScreenDefault
    fun getAlwaysHorizontal() = alwaysHorizontalDefault
    fun getEnableReloadButton() = safeGetBoolean(enableReloadButtonKey, enableReloadButtonDefault)
    fun getPrimaryButtonAction() = safeGetString(
        primaryButtonActionKey,
        primaryButtonActionDefault,
    ) ?: primaryButtonActionDefault
    fun getSecondaryButtonAction(): String {
        if (prefMan.contains(secondaryButtonActionKey)) {
            return safeGetString(secondaryButtonActionKey, secondaryButtonActionDefault) ?: secondaryButtonActionDefault
        }
        return if (getEnableReloadButton()) ConfigurableAction.RELOAD.id else secondaryButtonActionDefault
    }
    fun getFullScreenOverlayActions(): Set<String> {
        return safeGetStringSet(fullScreenOverlayActionsKey, fullScreenOverlayActionsDefault)?.toSet()
            ?: fullScreenOverlayActionsDefault
    }
    fun getFullScreenOverlayActionOrder(): List<String> {
        return safeGetString(fullScreenOverlayActionOrderKey, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: ConfigurableAction.defaultFullScreenOverlayOrder.map { it.id }
    }
    fun getShortcutBarActions(): Set<String> {
        return safeGetStringSet(shortcutBarActionsKey, shortcutBarActionsDefault)?.toSet()
            ?: shortcutBarActionsDefault
    }
    fun getShortcutBarActionOrder(): List<String> {
        return safeGetString(shortcutBarActionOrderKey, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: ConfigurableAction.defaultShortcutBarOrder.map { it.id }
    }

    fun removeObsoleteActionIds(obsoleteIds: Set<String>) {
        if (obsoleteIds.isEmpty()) return
        val editor = prefMan.edit()
        if (getPrimaryButtonAction() in obsoleteIds) {
            editor.putString(primaryButtonActionKey, primaryButtonActionDefault)
        }
        if (getSecondaryButtonAction() in obsoleteIds) {
            editor.putString(secondaryButtonActionKey, secondaryButtonActionDefault)
        }
        editor.putStringSet(
            fullScreenOverlayActionsKey,
            getFullScreenOverlayActions().filterNot(obsoleteIds::contains).toSet(),
        )
        editor.putString(
            fullScreenOverlayActionOrderKey,
            getFullScreenOverlayActionOrder().filterNot(obsoleteIds::contains).joinToString(","),
        )
        editor.putStringSet(
            shortcutBarActionsKey,
            getShortcutBarActions().filterNot(obsoleteIds::contains).toSet(),
        )
        editor.putString(
            shortcutBarActionOrderKey,
            getShortcutBarActionOrder().filterNot(obsoleteIds::contains).joinToString(","),
        )
        editor.apply()
    }

    fun getScrollSpeed() = safeGetInt(scrollSpeedKey, scrollSpeedDefault)
    fun getListFilter() = safeGetEnum<ListFilter>(listFilterKey, listFilterDefault)
    fun getHomeDisabled() = homeDisabledDefault
    fun getHomeShowPdfTitle() = safeGetBoolean(homeShowPdfTitleKey, homeShowPdfTitleDefault)
    fun getHomeTab() = safeGetEnum<HomeTab>(homeTabKey, homeTabDefault)
    fun getHomeProgressStyle() = enumValueOf<HomeProgressStyle>(homeProgressStyleDefault)
    fun getHomeListTitleLines() = safeGetInt(homeListTitleLinesKey, homeListTitleLinesDefault)
    fun getHomeTitleEllipsize() = enumValueOf<HomeTitleEllipsize>(homeTitleEllipsizeDefault)
    fun getHomeBadgePages() = homeBadgeDefault
    fun getHomeBadgeProgress() = homeBadgeDefault
    fun getHomeBadgeLastOpened() = homeBadgeDefault
    fun getHomeBadgeFileSize() = homeBadgeDefault
    fun getHomeBadgeStatus() = homeBadgeDefault
    fun getLibraryShowReviewCounts() = safeGetBoolean(libraryShowReviewCountsKey, true)
    fun getLibraryShowPageCounts() = safeGetBoolean(libraryShowPageCountsKey, true)
    fun getOverviewSort() = safeGetEnum<OverviewSortOrder>(overviewSortKey, overviewSortDefault)
    fun getOverviewCommentTypeFilter() = safeGetString(overviewCommentTypeFilterKey, "all") ?: "all"
    fun getOverviewDocumentFilter() =
        safeGetString(overviewDocumentFilterKey, overviewDocumentFilterDefault)
            ?: overviewDocumentFilterDefault
    fun getOverviewAnchorFilter() =
        safeGetEnum<OverviewAnchorFilter>(overviewAnchorFilterKey, overviewAnchorFilterDefault)
    fun getOverviewPdfFilter() =
        safeGetEnum<OverviewPdfFilter>(overviewPdfFilterKey, overviewPdfFilterDefault)
    fun getOverviewPriorityFilter() =
        safeGetEnum<OverviewPriorityFilter>(
            overviewPriorityFilterKey,
            overviewPriorityFilterDefault,
        )
    fun getOverviewLocationFilter(): OverviewLocationFilter? {
        val label = safeGetString(overviewLocationLabelKey, null)?.takeIf(String::isNotBlank)
            ?: return null
        val latitude = safeGetString(overviewLocationLatitudeKey, null)?.toDoubleOrNull()
            ?: return null
        val longitude = safeGetString(overviewLocationLongitudeKey, null)?.toDoubleOrNull()
            ?: return null
        val allowedRadii = setOf(1, 5, 10, 25, 50, 100)
        return OverviewLocationFilter(
            label = label,
            latitude = latitude,
            longitude = longitude,
            googlePlaceId = safeGetString(overviewLocationPlaceIdKey, "").orEmpty(),
            radiusKm = safeGetInt(overviewLocationRadiusKey, overviewLocationRadiusDefault)
                .takeIf(allowedRadii::contains)
                ?: overviewLocationRadiusDefault,
        )
    }
    fun getOverviewLastAddDocument() =
        safeGetString(overviewLastAddDocumentKey, overviewLastAddDocumentDefault)
            ?: overviewLastAddDocumentDefault
    fun getSynchronizationThresholdMegabytes(): Int =
        safeGetInt(
            synchronizationThresholdMegabytesKey,
            synchronizationThresholdMegabytesDefault,
        ).coerceIn(
            synchronizationThresholdMegabytesMin,
            synchronizationThresholdMegabytesMax,
        )
    fun getSynchronizationThresholdBytes(): Long =
        getSynchronizationThresholdMegabytes().toLong() * 1_000_000L

    fun getKeepSharedCopies() = keepSharedCopiesDefault

    // put values in Shared Preferences
    fun setFirstInstall(value: Boolean) = prefMan.edit().putBoolean(firstInstallKey, value).apply()
    fun setShowFeaturesDialog(value: Boolean) = prefMan.edit().putBoolean(showFeaturesDialogKey, value).apply()
    fun setLastSeenVersionCode(value: Int) = prefMan.edit().putInt(lastSeenVersionCodeKey, value).apply()
    fun setHighQuality(value: Boolean) = prefMan.edit().putBoolean(highQualityKey, value).apply()
    fun setAntiAliasing(value: Boolean) = prefMan.edit().putBoolean(antiAliasingKey, value).apply()
    fun setHorizontalScroll(value: Boolean) = prefMan.edit().putBoolean(horizontalScrollKey, value).apply()
    fun setDualPageMode(value: Boolean) = prefMan.edit().putBoolean(dualPageModeKey, value).apply()
    fun setDualPageFirstPageAlone(value: Boolean) = prefMan.edit().putBoolean(dualPageFirstPageAloneKey, value).apply()
    fun setPageSnap(value: Boolean) = prefMan.edit().putBoolean(pageSnapKey, value).apply()
    fun setPageFling(value: Boolean) = prefMan.edit().putBoolean(pageFlingKey, value).apply()
    fun setBrowserScrollMode(value: Boolean) = prefMan.edit().putBoolean(browserScrollModeKey, value).apply()
    fun setTurnPageByMouseButtons(value: Boolean) = prefMan.edit().putBoolean(turnPageByMouseButtonsKey, value).apply()
    fun setPdfDarkTheme(value: Boolean) = prefMan.edit().putBoolean(pdfDarkThemeKey, value).apply()
    fun setAppFollowSystemTheme(value: Boolean) = prefMan.edit().putBoolean(appFollowSystemThemeKey, value).apply()
    fun setPdfFollowSystemTheme(value: Boolean) = prefMan.edit().putBoolean(pdfFollowSystemThemeKey, value).apply()
    fun setInterfaceTheme(value: String) = prefMan.edit()
        .putString(interfaceThemeKey, value)
        .putBoolean(appFollowSystemThemeKey, value == themeSystem)
        .apply()
    fun setPdfPagesTheme(value: String) = prefMan.edit()
        .putString(pdfPagesThemeKey, value)
        .putBoolean(pdfFollowSystemThemeKey, value == themeSystem)
        .putBoolean(pdfDarkThemeKey, value == themeDark)
        .apply()
    fun setScreenOn(value: Boolean) = prefMan.edit().putBoolean(screenOnKey, value).apply()
    fun setSpaceBetweenPages(value: Boolean) = prefMan.edit().putBoolean(spaceBetweenPagesKey, value).apply()
    fun setHideDelay(value: Int) = prefMan.edit().putInt(hideDelayKey, value).apply()
    fun setPartSize(value: Float) = prefMan.edit().putFloat(partSizeKey, value).apply()

    fun setGoToPageGridColumns(value: Int) = prefMan.edit().putInt(goToPageGridColumnsKey, value).apply()

    fun setTranslationMode(value: String) = prefMan.edit().putString(translationModeKey, value).apply()

    fun setTranslationEngine(value: String) = prefMan.edit().putString(translationEngineKey, value).apply()

    fun setTranslationTargetLanguage(value: String) = prefMan.edit().putString(translationTargetLanguageKey, value).apply()

    fun setTranslationCustomUrl(value: String) = prefMan.edit().putString(translationCustomUrlKey, value).apply()

    fun setMaxZoom(value: Float) = prefMan.edit().putFloat(maxZoomKey, value).apply()
    fun setInlineTextSelection(value: Boolean) = prefMan.edit().putBoolean(inlineTextSelectionKey, value).apply()
    fun setDetectExistingHighlights(value: Boolean) = prefMan.edit().putBoolean(detectExistingHighlightsKey, value).apply()
    fun setHighlightColors(value: List<HighlightPalette>) = prefMan.edit()
        .putString(highlightColorsKey, value.joinToString(",") { it.name })
        .apply()
    fun setSearchIgnoreAccents(value: Boolean) = prefMan.edit().putBoolean(searchIgnoreAccentsKey, value).apply()
    fun setDefaultTextMode(value: Boolean) = prefMan.edit().putBoolean(defaultTextModeKey, value).apply()
    fun setTurnPageByVolumeButtons(value: Boolean) = prefMan.edit().putBoolean(turnPageByVolumeButtonsKey, value).apply()
    fun setShowScrollHandlePageCount(value: Boolean) = prefMan.edit().putBoolean(showScrollHandlePageCountKey, value).apply()
    fun setShowAppBarPageCount(value: Boolean) = prefMan.edit().putBoolean(showAppBarPageCountKey, value).apply()
    fun setAlwaysHideMargins(value: Boolean) = prefMan.edit().putBoolean(alwaysHideMarginsKey, value).apply()
    fun setSecondBarEnabled(value: Boolean) = prefMan.edit().putBoolean(secondBarEnabledKey, value).apply()
    fun setDoubleTapToExitEnabled(value: Boolean) = prefMan.edit().putBoolean(doubleTapToExitEnabledKey, value).apply()
    fun setAutoFullScreen(value: Boolean) = prefMan.edit().putBoolean(autoFullScreenKey, value).apply()
    fun setAlwaysHorizontal(value: Boolean) = prefMan.edit().putBoolean(alwaysHorizontalKey, value).apply()
    fun setHideButtonsLabels(value: Boolean) = prefMan.edit().putBoolean(hideButtonsLabelsKey, value).apply()
    fun setFullScreenInfoShowTime(value: Boolean) = prefMan.edit().putBoolean(fullScreenInfoShowTimeKey, value).apply()
    fun setFullScreenInfoShowPdfName(value: Boolean) = prefMan.edit().putBoolean(fullScreenInfoShowPdfNameKey, value).apply()
    fun setFullScreenInfoShowPageNumber(value: Boolean) = prefMan.edit().putBoolean(fullScreenInfoShowPageNumberKey, value).apply()
    fun setFullScreenInfoShowReadingPercentage(value: Boolean) = prefMan.edit().putBoolean(fullScreenInfoShowReadingPercentageKey, value).apply()
    fun setEnableReloadButton(value: Boolean) = prefMan.edit().putBoolean(enableReloadButtonKey, value).apply()
    fun setPrimaryButtonAction(value: String) = prefMan.edit().putString(primaryButtonActionKey, value).apply()
    fun setSecondaryButtonAction(value: String) = prefMan.edit().putString(secondaryButtonActionKey, value).apply()
    fun setFullScreenOverlayActions(value: Set<String>) = prefMan.edit().putStringSet(fullScreenOverlayActionsKey, value).apply()
    fun setFullScreenOverlayActionOrder(value: List<String>) = prefMan.edit()
        .putString(fullScreenOverlayActionOrderKey, value.joinToString(","))
        .apply()
    fun setShortcutBarActions(value: Set<String>) = prefMan.edit().putStringSet(shortcutBarActionsKey, value).apply()
    fun setShortcutBarActionOrder(value: List<String>) = prefMan.edit()
        .putString(shortcutBarActionOrderKey, value.joinToString(","))
        .apply()
    fun setScrollSpeed(value: Int) = prefMan.edit().putInt(scrollSpeedKey, value).apply()
    fun setListFilter(value: ListFilter) = prefMan.edit().putString(listFilterKey, value.name).apply()
    fun setHomeDisabled(value: Boolean) = prefMan.edit().putBoolean(homeDisabledKey, value).apply()
    fun setHomeShowPdfTitle(value: Boolean) = prefMan.edit().putBoolean(homeShowPdfTitleKey, value).apply()
    fun setHomeTab(value: HomeTab) = prefMan.edit().putString(homeTabKey, value.name).apply()
    fun setHomeProgressStyle(value: HomeProgressStyle) = prefMan.edit().putString(homeProgressStyleKey, value.name).apply()
    fun setHomeListTitleLines(value: Int) = prefMan.edit().putInt(homeListTitleLinesKey, value).apply()
    fun setHomeTitleEllipsize(value: HomeTitleEllipsize) = prefMan.edit().putString(homeTitleEllipsizeKey, value.name).apply()
    fun setHomeBadgePages(value: Boolean) = prefMan.edit().putBoolean(homeBadgePagesKey, value).apply()
    fun setHomeBadgeProgress(value: Boolean) = prefMan.edit().putBoolean(homeBadgeProgressKey, value).apply()
    fun setHomeBadgeLastOpened(value: Boolean) = prefMan.edit().putBoolean(homeBadgeLastOpenedKey, value).apply()
    fun setHomeBadgeFileSize(value: Boolean) = prefMan.edit().putBoolean(homeBadgeFileSizeKey, value).apply()
    fun setHomeBadgeStatus(value: Boolean) = prefMan.edit().putBoolean(homeBadgeStatusKey, value).apply()
    fun setLibraryShowReviewCounts(value: Boolean) = prefMan.edit().putBoolean(libraryShowReviewCountsKey, value).apply()
    fun setLibraryShowPageCounts(value: Boolean) = prefMan.edit().putBoolean(libraryShowPageCountsKey, value).apply()
    fun setOverviewSort(value: OverviewSortOrder) =
        prefMan.edit().putString(overviewSortKey, value.name).apply()
    fun setOverviewCommentTypeFilter(value: String) =
        prefMan.edit().putString(overviewCommentTypeFilterKey, value).apply()
    fun setOverviewDocumentFilter(value: String) =
        prefMan.edit().putString(overviewDocumentFilterKey, value).apply()
    fun getOverviewProjectFilter() = safeGetString(overviewProjectFilterKey, "") ?: ""
    fun setOverviewProjectFilter(value: String) =
        prefMan.edit().putString(overviewProjectFilterKey, value).apply()
    fun getOverviewProjectDocumentFilter() = safeGetString(overviewProjectDocumentFilterKey, "") ?: ""
    fun setOverviewProjectDocumentFilter(value: String) =
        prefMan.edit().putString(overviewProjectDocumentFilterKey, value).apply()
    fun setOverviewAnchorFilter(value: OverviewAnchorFilter) =
        prefMan.edit().putString(overviewAnchorFilterKey, value.name).apply()
    fun setOverviewPdfFilter(value: OverviewPdfFilter) =
        prefMan.edit().putString(overviewPdfFilterKey, value.name).apply()
    fun setOverviewPriorityFilter(value: OverviewPriorityFilter) =
        prefMan.edit().putString(overviewPriorityFilterKey, value.name).apply()
    fun setOverviewLocationFilter(value: OverviewLocationFilter?) {
        prefMan.edit().apply {
            if (value == null) {
                remove(overviewLocationLabelKey)
                remove(overviewLocationLatitudeKey)
                remove(overviewLocationLongitudeKey)
                remove(overviewLocationPlaceIdKey)
                remove(overviewLocationRadiusKey)
            } else {
                putString(overviewLocationLabelKey, value.label)
                putString(overviewLocationLatitudeKey, value.latitude.toString())
                putString(overviewLocationLongitudeKey, value.longitude.toString())
                putString(overviewLocationPlaceIdKey, value.googlePlaceId)
                putInt(overviewLocationRadiusKey, value.radiusKm)
            }
        }.apply()
    }
    fun setOverviewLastAddDocument(value: String) =
        prefMan.edit().putString(overviewLastAddDocumentKey, value).apply()
    fun setSynchronizationThresholdMegabytes(value: Int) = prefMan.edit()
        .putInt(
            synchronizationThresholdMegabytesKey,
            value.coerceIn(
                synchronizationThresholdMegabytesMin,
                synchronizationThresholdMegabytesMax,
            ),
        )
        .apply()

    fun setKeepSharedCopies(value: Boolean) = prefMan.edit().putBoolean(keepSharedCopiesKey, value).apply()

    private fun agentPromptKey(kind: AgentPromptTemplates.Kind) = when (kind) {
        AgentPromptTemplates.Kind.AGENT -> agentPromptTemplateKey
        AgentPromptTemplates.Kind.STUDY_MODE -> studyModePromptTemplateKey
    }

    private fun isDefaultAgentPromptTemplate(kind: AgentPromptTemplates.Kind, value: String) =
        value.isBlank() || value == AgentPromptTemplates.defaultTemplate(kind) ||
            (kind == AgentPromptTemplates.Kind.AGENT && value == previousDefaultAgentPromptTemplate)

    /**
     * The review card "Copy for agent" template or the study-mode prompt; blank, unset or a previous
     * default means the current default.
     */
    internal fun getAgentPromptTemplate(kind: AgentPromptTemplates.Kind): String =
        safeGetString(agentPromptKey(kind), null)?.takeUnless { isDefaultAgentPromptTemplate(kind, it) }
            ?: AgentPromptTemplates.defaultTemplate(kind)

    /** Saving a default (or nothing) removes the entry, so a later default applies. */
    internal fun setAgentPromptTemplate(kind: AgentPromptTemplates.Kind, value: String) = prefMan.edit().apply {
        if (isDefaultAgentPromptTemplate(kind, value)) remove(agentPromptKey(kind))
        else putString(agentPromptKey(kind), value)
    }.apply()

    /** Where the Mentra glasses' captures arrive on the agent's computer, for {mentra_folder}. */
    fun getMentraPhotoFolder(): String =
        safeGetString(mentraPhotoFolderKey, null)?.trim()?.takeUnless(String::isEmpty) ?: AgentPromptTemplates.DEFAULT_MENTRA_FOLDER

    fun setMentraPhotoFolder(value: String) = prefMan.edit().apply {
        val folder = value.trim()
        if (folder.isEmpty() || folder == AgentPromptTemplates.DEFAULT_MENTRA_FOLDER) remove(mentraPhotoFolderKey)
        else putString(mentraPhotoFolderKey, folder)
    }.apply()

    /** The glasses camera server for "Send with picture", or null when not set up. */
    fun getGlassesCameraUrl(): String? = safeGetString(glassesCameraUrlKey, null)?.trim()?.takeUnless(String::isEmpty)

    fun setGlassesCameraUrl(value: String?) = prefMan.edit().apply {
        val url = value?.trim().orEmpty()
        if (url.isEmpty()) remove(glassesCameraUrlKey) else putString(glassesCameraUrlKey, url)
    }.apply()

    fun getAgentNoteMessageCollapsed(): Boolean = safeGetBoolean(agentNoteMessageCollapsedKey, agentNoteMessageCollapsedDefault)

    fun setAgentNoteMessageCollapsed(collapsed: Boolean) =
        prefMan.edit().putBoolean(agentNoteMessageCollapsedKey, collapsed).apply()

}

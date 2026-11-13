// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import com.gitlab.mudlej.MjPdfReader.ui.reader.AgentPromptTemplates
import android.content.SharedPreferences
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTab
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewAnchorFilter
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewPdfFilter
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewPriorityFilter
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewLocationFilter
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesTest {

    @Test
    fun recentHomePreferenceMigratesToDiary() {
        val sharedPreferences = InMemorySharedPreferences(
            mapOf(Preferences.homeTabKey to "RECENT")
        )

        assertEquals(HomeTab.DIARY, Preferences(sharedPreferences).getHomeTab())
        assertEquals(HomeTab.DIARY.name, sharedPreferences.getString(Preferences.homeTabKey, null))
    }

    @Test
    fun removedCommentsTabMigratesToDiaryInItsPlace() {
        val sharedPreferences = InMemorySharedPreferences(
            mapOf(Preferences.homeTabKey to "OVERVIEW")
        )

        assertEquals(HomeTab.DIARY, Preferences(sharedPreferences).getHomeTab())
        assertEquals(0, HomeTab.DIARY.ordinal)
        assertEquals(HomeTab.DIARY.name, sharedPreferences.getString(Preferences.homeTabKey, null))
    }

    @Test
    fun freshInstallOpensTheDiary() {
        assertEquals(HomeTab.DIARY, Preferences(InMemorySharedPreferences(emptyMap())).getHomeTab())
    }

    @Test
    fun foldersHomePreferenceMigratesToReviewAndDropsFolderViewMode() {
        val sharedPreferences = InMemorySharedPreferences(
            mapOf(
                Preferences.homeTabKey to "FOLDERS",
                "homeFolderFlat" to true,
            )
        )

        assertEquals(HomeTab.REVIEW, Preferences(sharedPreferences).getHomeTab())
        assertEquals(HomeTab.REVIEW.name, sharedPreferences.getString(Preferences.homeTabKey, null))
        assertFalse(sharedPreferences.contains("homeFolderFlat"))
    }

    @Test
    fun removedScanSettingsAreDropped() {
        val sharedPreferences = InMemorySharedPreferences(
            mapOf(
                "scanMode" to "WHOLE_DEVICE",
                "scanLocations" to setOf("/storage/emulated/0/Documents/docs-clean-pdfs"),
                "workflowScanDefaultApplied" to true,
            )
        )

        Preferences(sharedPreferences)

        assertFalse(sharedPreferences.contains("scanMode"))
        assertFalse(sharedPreferences.contains("scanLocations"))
        assertFalse(sharedPreferences.contains("workflowScanDefaultApplied"))
    }

    @Test
    fun overviewPreferencesDefaultAndRoundTrip() {
        val preferences = Preferences(InMemorySharedPreferences())

        assertEquals(OverviewSortOrder.NEWEST, preferences.getOverviewSort())
        assertEquals("", preferences.getOverviewDocumentFilter())
        assertEquals(OverviewAnchorFilter.ALL, preferences.getOverviewAnchorFilter())
        assertEquals(OverviewPdfFilter.ALL, preferences.getOverviewPdfFilter())
        assertEquals(OverviewPriorityFilter.ALL, preferences.getOverviewPriorityFilter())
        assertEquals(null, preferences.getOverviewLocationFilter())
        assertEquals("", preferences.getOverviewLastAddDocument())

        preferences.setOverviewSort(OverviewSortOrder.PAGE)
        preferences.setOverviewDocumentFilter("A document")
        preferences.setOverviewAnchorFilter(OverviewAnchorFilter.NEEDS_ANCHOR)
        preferences.setOverviewPdfFilter(OverviewPdfFilter.TEX_LINKED)
        preferences.setOverviewPriorityFilter(OverviewPriorityFilter.HIGH)
        val locationFilter = OverviewLocationFilter(
            "Paris",
            48.8566,
            2.3522,
            "place-id",
            25,
        )
        preferences.setOverviewLocationFilter(locationFilter)
        preferences.setOverviewLastAddDocument("Another document")

        assertEquals(OverviewSortOrder.PAGE, preferences.getOverviewSort())
        assertEquals("A document", preferences.getOverviewDocumentFilter())
        assertEquals(OverviewAnchorFilter.NEEDS_ANCHOR, preferences.getOverviewAnchorFilter())
        assertEquals(OverviewPdfFilter.TEX_LINKED, preferences.getOverviewPdfFilter())
        assertEquals(OverviewPriorityFilter.HIGH, preferences.getOverviewPriorityFilter())
        assertEquals(locationFilter, preferences.getOverviewLocationFilter())
        assertEquals("Another document", preferences.getOverviewLastAddDocument())
    }

    @Test
    fun removedActionIdsAreMigratedOutOfEveryConfigurableSlot() {
        val removed = "incognito"
        val sharedPreferences = InMemorySharedPreferences(
            mapOf(
                Preferences.primaryButtonActionKey to removed,
                Preferences.secondaryButtonActionKey to removed,
                Preferences.fullScreenOverlayActionsKey to setOf(removed, "rotate"),
                Preferences.fullScreenOverlayActionOrderKey to "$removed,rotate",
                Preferences.shortcutBarActionsKey to setOf(removed, "search"),
                Preferences.shortcutBarActionOrderKey to "$removed,search",
            )
        )
        val preferences = Preferences(sharedPreferences)

        preferences.removeObsoleteActionIds(setOf(removed))

        assertEquals(Preferences.primaryButtonActionDefault, preferences.getPrimaryButtonAction())
        assertEquals(Preferences.secondaryButtonActionDefault, preferences.getSecondaryButtonAction())
        assertFalse(removed in preferences.getFullScreenOverlayActions())
        assertFalse(removed in preferences.getFullScreenOverlayActionOrder())
        assertFalse(removed in preferences.getShortcutBarActions())
        assertFalse(removed in preferences.getShortcutBarActionOrder())
        assertTrue("rotate" in preferences.getFullScreenOverlayActions())
        assertTrue("search" in preferences.getShortcutBarActions())
    }

    @Test
    fun removedBackupSettingsAreDropped() {
        val keys = listOf(
            "backupFolderTreeUri", "autoBackupEnabled", "autoBackupHour", "autoBackupMinute",
            "autoBackupLastRun", "autoBackupLastError", "autoBackupErrorAcknowledgedRun",
            "autoBackupEnabledAt", "importResultPending",
        )
        val sharedPreferences = InMemorySharedPreferences(
            mapOf(
                "backupFolderTreeUri" to "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                "autoBackupEnabled" to true,
                "autoBackupHour" to 2,
                "autoBackupMinute" to 0,
                "autoBackupLastRun" to 1L,
                "autoBackupLastError" to "Folder unavailable",
                "autoBackupErrorAcknowledgedRun" to 0L,
                "autoBackupEnabledAt" to 1L,
                "importResultPending" to "Imported",
                "highQuality" to true,
            )
        )

        Preferences(sharedPreferences)

        keys.forEach { assertFalse(it, sharedPreferences.contains(it)) }
        assertTrue(sharedPreferences.contains("highQuality"))
    }

    @Test
    fun readingHistoryIsAlwaysRecordedEvenIfTheOldSwitchWasOff() {
        val sharedPreferences = InMemorySharedPreferences(
            mapOf("historyEnabled" to false),
        )

        Preferences(sharedPreferences)

        assertFalse(sharedPreferences.contains("historyEnabled"))
        assertTrue(HistoryPolicy().canRecord())
    }

    @Test
    fun synchronizationThresholdDefaultsToFiveMegabytesAndIsBounded() {
        val preferences = Preferences(InMemorySharedPreferences())

        assertEquals(5, preferences.getSynchronizationThresholdMegabytes())
        assertEquals(5_000_000L, preferences.getSynchronizationThresholdBytes())
        preferences.setSynchronizationThresholdMegabytes(17)
        assertEquals(17_000_000L, preferences.getSynchronizationThresholdBytes())
        preferences.setSynchronizationThresholdMegabytes(Int.MAX_VALUE)
        assertEquals(
            Preferences.synchronizationThresholdMegabytesMax,
            preferences.getSynchronizationThresholdMegabytes(),
        )
    }

    @Test
    fun agentPromptTemplatesPersistAndResetToTheDefault() {
        val shared = InMemorySharedPreferences()
        val preferences = Preferences(shared)
        val agent = AgentPromptTemplates.Kind.AGENT
        val study = AgentPromptTemplates.Kind.STUDY_MODE

        assertEquals(AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE, preferences.getAgentPromptTemplate(agent))
        assertEquals(AgentPromptTemplates.DEFAULT_STUDY_MODE_TEMPLATE, preferences.getAgentPromptTemplate(study))
        // The build's default (thoughtsStudyCaptureFolder, ~/glasses unless set).
        assertEquals(AgentPromptTemplates.DEFAULT_MENTRA_FOLDER, preferences.getMentraPhotoFolder())
        assertTrue(AgentPromptTemplates.DEFAULT_MENTRA_FOLDER.isNotBlank())

        preferences.setAgentPromptTemplate(agent, "Line one\n\nLine {card_id}\n")
        preferences.setAgentPromptTemplate(study, "Watch {mentra_folder}\n")
        preferences.setMentraPhotoFolder("  /tmp/photos  ")
        // A new instance reads what was saved.
        val reread = Preferences(shared)
        assertEquals("Line one\n\nLine {card_id}\n", reread.getAgentPromptTemplate(agent))
        assertEquals("Watch {mentra_folder}\n", reread.getAgentPromptTemplate(study))
        assertEquals("/tmp/photos", reread.getMentraPhotoFolder())

        // Blank or the default: back to the default, with no entry left behind.
        preferences.setAgentPromptTemplate(agent, "  \n")
        preferences.setAgentPromptTemplate(study, AgentPromptTemplates.DEFAULT_STUDY_MODE_TEMPLATE)
        preferences.setMentraPhotoFolder("")
        assertFalse(shared.contains(Preferences.agentPromptTemplateKey))
        assertFalse(shared.contains(Preferences.studyModePromptTemplateKey))
        assertFalse(shared.contains(Preferences.mentraPhotoFolderKey))
        assertEquals(AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE, preferences.getAgentPromptTemplate(agent))
        assertEquals(AgentPromptTemplates.DEFAULT_MENTRA_FOLDER, preferences.getMentraPhotoFolder())
    }

    @Test
    fun theCopyForAgentDefaultBefore20261022CountsAsTheDefault() {
        val agent = AgentPromptTemplates.Kind.AGENT
        val previousDefault = AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE +
            "I'm studying this card. Don't reveal, quote or hint at the answer until I have given my own answer or ask for it; " +
            "the answer pages and source lines above are for looking it up then.\n"
        // Saved on a phone while it was the default: the new default applies.
        val shared = InMemorySharedPreferences(mapOf(Preferences.agentPromptTemplateKey to previousDefault))
        val preferences = Preferences(shared)
        assertEquals(AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE, preferences.getAgentPromptTemplate(agent))
        assertFalse(AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE.contains("I'm studying this card"))
        assertTrue(AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE.endsWith("Answer source: {answer_source}\n{answer_feedback}\n"))

        // Saving it again leaves no entry behind; an edited copy stays.
        preferences.setAgentPromptTemplate(agent, previousDefault)
        assertFalse(shared.contains(Preferences.agentPromptTemplateKey))
        preferences.setAgentPromptTemplate(agent, previousDefault + "More\n")
        assertEquals(previousDefault + "More\n", preferences.getAgentPromptTemplate(agent))
        // Only the agent template had that line.
        val study = AgentPromptTemplates.Kind.STUDY_MODE
        preferences.setAgentPromptTemplate(study, previousDefault)
        assertEquals(previousDefault, preferences.getAgentPromptTemplate(study))
    }

    @Test
    fun agentNoteMessageStartsCollapsedAndKeepsTheLastChoice() {
        val shared = InMemorySharedPreferences(emptyMap())
        assertTrue(Preferences(shared).getAgentNoteMessageCollapsed())

        Preferences(shared).setAgentNoteMessageCollapsed(false)
        // The next note (a new Preferences over the same store) opens with the whole message.
        assertFalse(Preferences(shared).getAgentNoteMessageCollapsed())
        Preferences(shared).setAgentNoteMessageCollapsed(true)
        assertTrue(Preferences(shared).getAgentNoteMessageCollapsed())
    }
}

private class InMemorySharedPreferences(
    initialValues: Map<String, Any> = emptyMap(),
) : SharedPreferences {
    private val values = initialValues.toMutableMap()

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String?, defaultValue: String?): String? {
        return value(key, defaultValue)
    }

    override fun getStringSet(key: String?, defaultValues: Set<String>?): Set<String>? {
        return value(key, defaultValues)?.toSet()
    }

    override fun getInt(key: String?, defaultValue: Int): Int = value(key, defaultValue)

    override fun getLong(key: String?, defaultValue: Long): Long = value(key, defaultValue)

    override fun getFloat(key: String?, defaultValue: Float): Float = value(key, defaultValue)

    override fun getBoolean(key: String?, defaultValue: Boolean): Boolean = value(key, defaultValue)

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    @Suppress("UNCHECKED_CAST")
    private fun <T> value(key: String?, defaultValue: T): T {
        val stored = values[key] ?: return defaultValue
        return stored as? T ?: throw ClassCastException("$key has the wrong type")
    }

    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            update(key, value)

        override fun putStringSet(
            key: String?,
            values: Set<String>?,
        ): SharedPreferences.Editor = update(key, values?.toSet())

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = update(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = update(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = update(key, value)

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = update(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let(removals::add)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            if (clearRequested) {
                values.clear()
            }
            removals.forEach(values::remove)
            updates.forEach { (key, value) ->
                if (value == null) {
                    values.remove(key)
                } else {
                    values[key] = value
                }
            }
            return true
        }

        override fun apply() {
            commit()
        }

        private fun update(key: String?, value: Any?): SharedPreferences.Editor {
            key?.let { updates[it] = value }
            return this
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SyncBatteryPromptStateTest {
    @Test fun newInstallOffersOnceAndDecliningSurvivesRestart() {
        val root = Files.createTempDirectory("battery-setup").toFile()
        try {
            val marker = File(root, "no-backup/thoughts-sync/battery-prompt-seen-v1")
            val state = SyncBatteryPromptState(marker)
            assertTrue(state.shouldOffer(exempt = false))
            state.markShown()
            assertFalse(SyncBatteryPromptState(marker).shouldOffer(exempt = false))
            assertTrue(SyncBatteryPromptState(File(root, "another-install/seen")).shouldOffer(exempt = false))
        } finally { root.deleteRecursively() }
    }

    @Test fun alreadyExemptInstallDoesNotNagOrRecordAFakeDecision() {
        val root = Files.createTempDirectory("battery-setup").toFile()
        try {
            val marker = File(root, "seen")
            val state = SyncBatteryPromptState(marker)
            assertFalse(state.shouldOffer(exempt = true))
            assertFalse(marker.exists())
            assertTrue(state.shouldOffer(exempt = false))
        } finally { root.deleteRecursively() }
    }

    @Test fun waitsUntilAServerIsConfiguredWithoutConsumingTheOffer() {
        val root = Files.createTempDirectory("battery-setup").toFile()
        try {
            val marker = File(root, "seen")
            val state = SyncBatteryPromptState(marker)
            assertFalse(state.shouldOffer(exempt = false, serverConfigured = false))
            assertFalse(marker.exists())
            assertTrue(state.shouldOffer(exempt = false, serverConfigured = true))
        } finally { root.deleteRecursively() }
    }
}

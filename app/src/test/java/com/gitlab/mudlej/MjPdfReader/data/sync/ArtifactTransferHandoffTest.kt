// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArtifactTransferHandoffTest {
    @Test
    fun downloadIsScheduledOnlyAfterItsExactPlanIsReady() = runBlocking {
        val calls = mutableListOf<String>()
        handoffArtifactTransfer(
            prepare = { calls += "prepare" },
            publish = { calls += "download" },
            defer = { calls += "defer" },
        )
        assertEquals(listOf("prepare", "download"), calls)
    }

    @Test
    fun aConcurrentPublicationDefersWithoutLaunchingAnObsoleteDownload() = runBlocking {
        val calls = mutableListOf<String>()
        handoffArtifactTransfer(
            prepare = { throw ArtifactPlanSupersededException() },
            publish = { calls += "download" },
            defer = { calls += "defer" },
        )
        assertEquals(listOf("defer"), calls)
    }

    @Test
    fun networkFailureDoesNotScheduleAStaleDownload() {
        val calls = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            runBlocking {
                handoffArtifactTransfer(
                    prepare = { throw IOException("offline") },
                    publish = { calls += "download" },
                    defer = { calls += "defer" },
                )
            }
        }
        assertEquals(emptyList<String>(), calls)
    }
}

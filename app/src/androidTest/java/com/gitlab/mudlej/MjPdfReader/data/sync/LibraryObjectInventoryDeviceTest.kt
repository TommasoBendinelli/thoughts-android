// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryObjectInventoryDeviceTest {
    @Test fun scopedInventoryMatchesBroadScanAndRechecksChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = InstrumentationRegistry.getArguments().getString("isolation_run_id")!!
        val root = File(context.filesDir, "inventory-$runId").apply { mkdirs() }
        fun hash(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        try {
            val expected = mutableSetOf<String>()
            repeat(600) { index ->
                val bytes = "Object $index in $runId\n".repeat(100).toByteArray()
                val digest = hash(bytes)
                expected += digest
                File(root, "${LibraryObjectInventory.OBJECT_STORES[index % 4]}/$digest")
                    .apply { parentFile.mkdirs(); writeBytes(bytes) }
                if (index % 2 == 0) {
                    File(root, "${LibraryObjectInventory.OBJECT_STORES[(index + 1) % 4]}/$digest")
                        .apply { parentFile.mkdirs(); writeBytes(bytes) }
                }
            }
            repeat(5000) { index ->
                File(root, "unrelated/group-${index / 25}/entry-$index.json")
                    .apply { parentFile.mkdirs(); writeText("fixture $runId $index") }
            }
            val allHashes = Regex("^[0-9a-f]{64}$")
            val broadCache = FileVerificationCache()
            val scopedCache = FileVerificationCache()
            fun broad() = root.walkTopDown().filter {
                it.isFile && allHashes.matches(it.name) && broadCache.sha256(it) == it.name
            }.map { it.name }.toSet()
            val scoped = LibraryObjectInventory(root, scopedCache::sha256)
            val broadTimes = mutableListOf<Long>()
            val scopedTimes = mutableListOf<Long>()
            fun measure(block: () -> Set<String>): Long {
                val start = SystemClock.elapsedRealtimeNanos()
                assertEquals(expected, block())
                return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
            }
            // First pair is cold verification; subsequent pairs reuse process-local identities.
            repeat(4) { iteration ->
                if (iteration % 2 == 0) {
                    broadTimes += measure(::broad)
                    scopedTimes += measure(scoped::verifiedHashes)
                } else {
                    scopedTimes += measure(scoped::verifiedHashes)
                    broadTimes += measure(::broad)
                }
            }
            Log.i("LibraryInventoryBenchmark", "unrelated=5000 objects=600 copies=900 broad_ms=$broadTimes scoped_ms=$scopedTimes")

            // Same-size edits with restored mtime must still invalidate the inode/ctime receipt.
            val extraBytes = "good".toByteArray()
            val extraDigest = hash(extraBytes)
            val extra = File(root, "${LibraryObjectInventory.OBJECT_STORES.first()}/$extraDigest")
            extra.writeBytes(extraBytes)
            assertTrue(extraDigest in scoped.verifiedHashes())
            val modified = extra.lastModified()
            extra.writeText("evil")
            assertTrue(extra.setLastModified(modified))
            assertFalse(extraDigest in scoped.verifiedHashes())
            extra.writeBytes(extraBytes)
            assertTrue(extraDigest in scoped.verifiedHashes())
            assertTrue(extra.delete())
            assertFalse(extraDigest in scoped.verifiedHashes())
            // A fresh cache after app restart still verifies the complete object set.
            assertEquals(expected, LibraryObjectInventory(root, FileVerificationCache()::sha256).verifiedHashes())
            val boot = syncBootCount(context)
            assertTrue(boot >= 0)
            val start = SystemClock.elapsedRealtimeNanos()
            assertNotNull(syncStartupElapsedNanos(start, SystemClock.elapsedRealtimeNanos(), boot, syncBootCount(context)))
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }
}

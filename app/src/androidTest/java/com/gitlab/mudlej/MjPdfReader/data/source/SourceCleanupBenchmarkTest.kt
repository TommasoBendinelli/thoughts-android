// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnostics
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable file-tree benchmark; the runner restricts execution to Thoughts Test. */
@RunWith(AndroidJUnit4::class)
class SourceCleanupBenchmarkTest {
    @Test
    fun restartedUnchangedCleanupReusesReceiptAndPreservesDrafts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = InstrumentationRegistry.getArguments().getString("isolation_run_id")!!
        val root = File(context.filesDir, "cleanup-$runId").apply { mkdirs() }
        val staging = File(root, "staging")
        val working = File(root, "working")
        val privateDirectory = File(root, "private")
        fun store() = OfflineSourceBundleStore(staging, working, privateDirectory)
        fun hash(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        try {
            val objects = linkedMapOf<String, ByteArray>()
            val sources = JsonArray()
            val inputs = JsonArray()
            repeat(600) { index ->
                val path = "docs-test/tex/source/chapter-$index.tex"
                val raw = "Source $index for isolated run $runId.\n".repeat(20).toByteArray()
                val digest = hash(raw)
                objects[digest] = raw
                sources.add(JsonObject().apply {
                    addProperty("path", path); addProperty("sha256", digest)
                    addProperty("object", digest); addProperty("bytes", raw.size)
                })
                inputs.add(JsonObject().apply {
                    addProperty("source_file", path); addProperty("source_sha256", digest)
                    addProperty("synctex_path", "/workspace/$path")
                })
            }
            val lookup = "isolated lookup $runId".toByteArray()
            val lookupHash = hash(lookup)
            objects[lookupHash] = lookup
            val manifest = JsonObject().apply {
                addProperty("schema_version", 3)
                add("sources", sources)
                add("pdfs", JsonArray().apply { add(JsonObject().apply {
                    addProperty("filename", "test.pdf"); addProperty("pdf_sha256", "a".repeat(64))
                    addProperty("page_count", 1)
                    add("segments", JsonArray().apply { add(JsonObject().apply {
                        addProperty("start_page", 1); addProperty("page_count", 1)
                        addProperty("source_available", true)
                        addProperty("primary_source_file", "docs-test/tex/source/chapter-0.tex")
                        addProperty("lookup_object", lookupHash); addProperty("lookup_sha256", lookupHash)
                        add("inputs", inputs)
                    }) })
                }) })
            }.toString().toByteArray()
            val bundleId = hash(manifest)
            objects[bundleId] = manifest
            val index = JsonObject().apply {
                addProperty("schema_version", 3); addProperty("bundle_id", bundleId)
                addProperty("manifest_object", bundleId)
            }.toString().toByteArray()
            assertNotNull(store().installDownloaded(index, objects))
            var reason = ""
            val diagnostics = SyncDiagnostics(write = { reason = it.values["reason"].toString() }, memory = { emptyMap() })
            val full = mutableListOf<Long>()
            val reused = mutableListOf<Long>()
            repeat(3) {
                File(privateDirectory, "cleanup-receipt.json").delete()
                var start = SystemClock.elapsedRealtimeNanos()
                assertTrue(store().pruneActiveBundle(diagnostics = diagnostics))
                full.add((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
                assertEquals("prune_active_bundle", reason)
                start = SystemClock.elapsedRealtimeNanos()
                assertTrue(store().pruneActiveBundle(diagnostics = diagnostics))
                reused.add((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
                assertEquals("unchanged_cleanup_inventory", reason)
            }
            val draft = File(working, "docs-test/tex/source/chapter-0.tex")
            assertTrue(draft.isFile)
            draft.writeText("Unsynced draft $runId")
            assertTrue(store().pruneActiveBundle(diagnostics = diagnostics))
            assertEquals("prune_active_bundle", reason)
            assertEquals("Unsynced draft $runId", draft.readText())
            Log.i("SourceCleanupBenchmark", "files=600 full_cleanup_ms=$full unchanged_cleanup_ms=$reused")
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.os.Build
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompilerCommentPersistenceInstrumentedTest {
    @Test fun sourceCommentsSaveLocallyAndSurviveDatabaseReopen() = runBlocking {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "compiler-comment-test-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var database = open()
        try {
            val artifact = instrumentation.context.assets.open("compiler-comments/artifact.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            val template = artifact.getAsJsonArray("logical_comments").first().asJsonObject
            var repository = FedoraRepository(context, database)
            val timings = mutableListOf<Double>()
            fun payload(id: String, revision: Int, body: String) = template.deepCopy().apply {
                addProperty("comment_id", id); addProperty("thread_root_id", id)
                add("parent_id", JsonNull.INSTANCE); add("origin", JsonNull.INSTANCE)
                addProperty("revision", revision); addProperty("contents", body)
                addProperty("updated_at", "2026-09-07T20:00:00Z")
                add("scope", JsonObject().apply { addProperty("state", "source_linked") })
            }
            // Separate root IDs exercise durable creation; repeated revisions
            // exercise latest-state projection independently of PDF geometry.
            for (i in 0 until 30) {
                val started = SystemClock.elapsedRealtimeNanos()
                repository.enqueueLogicalComment(payload("compiler-root-$i", 1, "Initial $i"))
                timings += (SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0
            }
            repository.enqueueLogicalComment(payload("compiler-root-0", 2, "Newest body"))
            repository.enqueueLogicalComment(payload("compiler-root-1", 2, "Deleted").apply { addProperty("deleted", true) })
            repository.enqueueLogicalComment(payload("compiler-reply", 1, "Reply without captured PDF origin").apply {
                addProperty("thread_root_id", "compiler-root-0"); addProperty("parent_id", "compiler-root-0")
                add("scope", JsonObject().apply { addProperty("state", "pdf_only"); addProperty("reason", "inherits_thread_locations") })
            })
            assertEquals(33, database.thoughtsSyncDao().undelivered().size)
            database.close()
            database = open(); repository = FedoraRepository(context, database)
            val reopened = repository.effectiveLogicalCommentPayloads().associateBy { it.get("comment_id").asString }
            assertEquals(31, reopened.size)
            assertEquals("Newest body", reopened.getValue("compiler-root-0").get("contents").asString)
            assertTrue(reopened.getValue("compiler-root-1").get("deleted").asBoolean)
            assertEquals("compiler-root-0", reopened.getValue("compiler-reply").get("parent_id").asString)
            val reads = (0 until 30).map {
                val started = SystemClock.elapsedRealtimeNanos()
                repository.effectiveLogicalCommentPayloads()
                (SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0
            }
            // Historical delivery rows remain available for diagnostics, but cannot
            // make reader work grow with every old keystroke.
            database.withTransaction {
                repeat(10000) { index ->
                    database.thoughtsSyncDao().insertOutbox(ThoughtsOutboxEntity(
                        operationId = "history-$index", deviceSequence = (1000+index).toLong(),
                        entityType = "logical_comment", entityId = "compiler-root-0", collection = "comments",
                        baseRevision = 0, clientTimestamp = "history", payloadJson = payload("compiler-root-0", 1, "Old history").toString(),
                        createdAtEpochMillis = 0, deliveryState = "accepted",
                    ))
                }
            }
            val readsWithHistory = (0 until 30).map {
                val started = SystemClock.elapsedRealtimeNanos()
                val state = repository.effectiveLogicalCommentPayloads()
                assertEquals(31, state.size)
                assertEquals("Newest body", state.single { it.get("comment_id").asString == "compiler-root-0" }.get("contents").asString)
                (SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0
            }
            fun quantile(values: List<Double>, q: Double) = values.sorted()[((values.size-1)*q).toInt()]
            val report = JsonObject().apply {
                addProperty("device", Build.MODEL); addProperty("isolated_emulator", true)
                addProperty("durable_save_p50_ms", quantile(timings, .5)); addProperty("durable_save_p95_ms", quantile(timings, .95))
                addProperty("latest_state_read_p50_ms", quantile(reads, .5)); addProperty("latest_state_read_p95_ms", quantile(reads, .95))
                addProperty("queued_operations_after_reopen", database.thoughtsSyncDao().undelivered().size)
                addProperty("historical_operations", 10000)
                addProperty("latest_state_read_with_history_p50_ms", quantile(readsWithHistory, .5))
                addProperty("latest_state_read_with_history_p95_ms", quantile(readsWithHistory, .95))
            }
            File(context.getExternalFilesDir(null), "compiler-comment-local-performance.json").writeText(report.toString())
        } finally {
            database.close()
            context.deleteDatabase(name) // Only this unique test database.
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a separate disposable database/preferences; never starts sync or modifies user artifacts. */
@RunWith(AndroidJUnit4::class)
class SyncDiagnosticsPersistenceTest {
    @Test fun durableBoundedHiddenAndMeasured() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val name = "diagnostics-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$name-$key", mode)
        }
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var database = open()
        try {
            var store = SyncOperationStore(context, database)
            val id = store.begin(null, false)
            val original = store.operation(id)!!
            val batcher = SyncDiagnosticBatcher { store.appendDiagnostics(id, it) }
            val diagnostics = SyncDiagnostics(batcher::append)
            val start = System.nanoTime()
            repeat(50) { diagnostics.measure("probe") { 42 } }
            val enqueueMillis = (System.nanoTime() - start) / 1_000_000.0
            SyncDiagnosticBatcher.flushAll()
            val millis = (System.nanoTime() - start) / 1_000_000.0
            println("SYNC_DIAGNOSTICS_BENCH spans=50 events=100 elapsed_ms=$millis enqueue_ms=$enqueueMillis ms_per_event=${millis / 100}")
            diagnostics.begin("interrupted_probe")
            SyncDiagnosticBatcher.flushAll()
            assertEquals(original, store.operation(id))
            assertTrue(database.thoughtsSyncDao().observeSyncEvents(id).first().none { it.origin == "diagnostic" })
            database.close()
            database = open()
            store = SyncOperationStore(context, database)
            val events = store.operationWithEvents(id)!!.journalEvents.filter { it.origin == "diagnostic" }
            assertEquals(101, events.size)
            assertEquals("interrupted_probe", events.last().message)
            assertEquals("started", events.last().state)
            assertTrue(events.any { it.metadataJson.contains("java_heap_used_bytes") })
            // Fill near the limit cheaply, then exercise the persistent bound across store instances.
            val dao = database.thoughtsSyncDao()
            dao.appendSyncEvents(List(895) { ThoughtsSyncEventEntity(runId = id, phase = "diagnostic",
                state = "counters", attempt = 0, message = "fixture", createdAtEpochMillis = 1, origin = "diagnostic") })
            repeat(10) { SyncOperationStore(context, database).appendDiagnostic(id,
                SyncDiagnosticEvent("bounded", "counters", mapOf("bytes" to 42L))) }
            assertEquals(1000, dao.diagnosticEventCount(id))
            assertEquals(1, dao.syncEvents(id).count { it.state == "limit_reached" })
            assertEquals(original, store.operation(id))
        } finally {
            database.close()
            base.deleteDatabase(name)
            base.deleteSharedPreferences("$name-thoughts-sync-operations-v1")
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration25Test {
    private val databaseName = "migration-24-25"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesHistoricalRunsAndMarksTransferMetricsUnavailable() {
        helper.createDatabase(databaseName, 24).apply {
            execSQL(
                """
                INSERT INTO sync_runs (
                    id, traceId, retryOf, target, kind, state, phase, displayStatus,
                    startedAtEpochMillis, updatedAtEpochMillis, displayMessage, eventsJson,
                    eventTimestampsJson, buildProgressJson, buildCheckedAtEpochMillis, attempt,
                    planJson, transferredBytes, totalBytes, schedulerKind, schedulerId,
                    serverState, serverTerminal, lastServerSequence, streamState,
                    legacyEventsImported
                ) VALUES (
                    'run-1', 'trace-1', NULL, NULL, 'sync', 'succeeded', 'succeeded', 'Synced',
                    10, 20, '', '["Queued","Completed"]', '[10,20]', '[]', 0, 0,
                    '{}', 24, 24, '', '', 'succeeded', 1, 2, 'offline', 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO sync_events (
                    runId, phase, state, attempt, message, errorCode, createdAtEpochMillis,
                    origin, serverSequence, serverTimestampEpochMillis,
                    receivedTimestampEpochMillis, component, buildId, completed, total,
                    bytesCompleted, bytesTotal, metadataJson
                ) VALUES (
                    'run-1', 'succeeded', 'succeeded', 0, 'Completed', '', 20,
                    'phone', NULL, 0, 0, 'phone', '', NULL, NULL, NULL, NULL, '{}'
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            25,
            true,
            AppDatabaseMigrations.MIGRATION_24_TO_25,
        ).use { database ->
            database.query(
                """
                SELECT transferMetricsAvailable, uploadBytesCompleted, uploadBytesTotal,
                       downloadBytesCompleted, downloadBytesTotal, transferPlanId
                FROM sync_runs WHERE id = 'run-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals(0L, cursor.getLong(2))
                assertEquals(0L, cursor.getLong(3))
                assertEquals(0L, cursor.getLong(4))
                assertEquals("", cursor.getString(5))
            }
            database.query("SELECT message FROM sync_events WHERE runId = 'run-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Completed", cursor.getString(0))
            }
        }
    }
}

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
class AppDatabaseMigration24Test {
    private val databaseName = "migration-23-24"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesRunsAndAddsDurableServerCursor() {
        helper.createDatabase(databaseName, 23).apply {
            execSQL(
                """
                INSERT INTO sync_runs (
                    id, traceId, retryOf, target, kind, state, phase, displayStatus,
                    startedAtEpochMillis, updatedAtEpochMillis, displayMessage, eventsJson,
                    eventTimestampsJson, buildProgressJson, buildCheckedAtEpochMillis, attempt,
                    planJson, transferredBytes, totalBytes, schedulerKind, schedulerId
                ) VALUES (
                    'run-1', 'trace-1', NULL, 'paper.pdf', 'build', 'building', 'building', 'Building',
                    10, 20, 'Publishing sources', '["Queued"]', '[10]', '[]', 20, 1,
                    '{}', 0, 0, '', ''
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            24,
            true,
            AppDatabaseMigrations.MIGRATION_23_TO_24,
        ).use { database ->
            database.query(
                "SELECT serverState, serverTerminal, lastServerSequence, streamState, legacyEventsImported FROM sync_runs WHERE id = 'run-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0L, cursor.getLong(2))
                assertEquals("offline", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
            }
        }
    }
}

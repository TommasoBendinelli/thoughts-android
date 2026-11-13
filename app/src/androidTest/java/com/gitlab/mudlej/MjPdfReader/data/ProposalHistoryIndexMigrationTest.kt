// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProposalHistoryIndexMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())

    private fun payload(path: String) = JsonObject().apply {
        addProperty("contents", "Unrelated source_file and snapshot strings.")
        add("snapshot", JsonObject().apply { addProperty("source_file", path) })
    }.toString()

    @Test fun migrationPreservesHistoryAndScopesSubsequentWrites() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-index-${UUID.randomUUID()}"
        val wanted = "docs/a_100%/quote\"é.tex"
        val other = "docs/other.tex"
        val original = payload(wanted)
        try {
            helper.createDatabase(name, 29).apply {
                for ((id, path) in listOf("wanted" to wanted, "other" to other)) {
                    execSQL("INSERT INTO ThoughtsProjection (entityType,collection,entityId,revision,deleted,payloadJson,serverSequence) VALUES ('proposal_event','proposals',?,1,0,?,1)", arrayOf(id, payload(path)))
                    execSQL("INSERT INTO ThoughtsOutbox (operationId,deviceSequence,entityType,entityId,collection,baseRevision,clientTimestamp,payloadJson,createdAtEpochMillis) VALUES (?,?,'proposal_event',?,'proposals',0,'time',?,0)",
                        arrayOf<Any>(id, if (id == "wanted") 1 else 2, id, payload(path)))
                }
                close()
            }
            helper.runMigrationsAndValidate(name, 30, true, AppDatabaseMigrations.MIGRATION_29_TO_30).use { database ->
                for (table in listOf("ThoughtsProjection", "ThoughtsOutbox")) {
                    database.query("SELECT payloadJson FROM $table WHERE proposalSourceFile = ?", arrayOf(wanted)).use {
                        assertTrue(it.moveToFirst()); assertEquals(original, it.getString(0)); assertFalse(it.moveToNext())
                    }
                    database.query("SELECT COUNT(*) FROM $table").use {
                        assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
                    }
                    database.query("EXPLAIN QUERY PLAN SELECT * FROM $table WHERE entityType='proposal_event' AND collection='proposals' AND proposalSourceFile=?", arrayOf(wanted)).use {
                        assertTrue(it.moveToFirst())
                        assertTrue(it.getString(3), it.getString(3).contains("index_${table}_entityType_collection_proposalSourceFile"))
                    }
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun retiredHistoryIsDeletedAndNeverStoredAgain() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-retired-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 34).apply {
                execSQL("INSERT INTO ThoughtsProjection (entityType,collection,entityId,revision,deleted,payloadJson,serverSequence,proposalSourceFile) VALUES ('proposal_event','proposals','old',1,0,?,1,'')", arrayOf(payload("docs/a.tex")))
                execSQL("INSERT INTO ThoughtsProjection (entityType,collection,entityId,revision,deleted,payloadJson,serverSequence,proposalSourceFile) VALUES ('proposal','proposals','current',1,0,'{}',2,'')")
                execSQL("INSERT INTO ThoughtsOutbox (operationId,deviceSequence,entityType,entityId,collection,baseRevision,clientTimestamp,payloadJson,createdAtEpochMillis,proposalSourceFile) VALUES ('queued',1,'proposal_event','queued','proposals',0,'time',?,0,'')", arrayOf<Any>(payload("docs/a.tex")))
                close()
            }
            helper.runMigrationsAndValidate(name, 35, true, AppDatabaseMigrations.MIGRATION_34_TO_35).use { database ->
                database.query("SELECT entityType FROM ThoughtsProjection").use {
                    assertTrue(it.moveToFirst()); assertEquals("proposal", it.getString(0)); assertFalse(it.moveToNext())
                }
                database.query("SELECT COUNT(*) FROM ThoughtsOutbox").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
            val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                val dao = database.thoughtsSyncDao()
                dao.insertOutbox(ThoughtsOutboxEntity("new", 2, "proposal_event", "new", "proposals", 0, "time", payload("docs/a.tex"), 0))
                assertNull(dao.operation("new"))
                assertTrue(dao.projections().none { it.entityType == "proposal_event" })
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}

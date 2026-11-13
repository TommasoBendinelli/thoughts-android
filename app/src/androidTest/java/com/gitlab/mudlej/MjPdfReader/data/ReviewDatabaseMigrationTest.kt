// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReviewDatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun addsReviewTablesWithoutChangingExistingOutbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val name = "review-migration-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 28).apply {
                execSQL("INSERT INTO ThoughtsOutbox (operationId,deviceSequence,entityType,entityId,collection,baseRevision,clientTimestamp,payloadJson,createdAtEpochMillis,deliveryState,serverSequence,failureCode,failureDetail,lastAttemptEpochMillis,acceptedAtEpochMillis) VALUES ('pending-review',1,'review_event','event','events',NULL,'time','{}',0,'pending',NULL,'','',0,0)")
                close()
            }
            helper.runMigrationsAndValidate(name, 29, true, AppDatabaseMigrations.MIGRATION_28_TO_29).use { db ->
                db.query("SELECT operationId,deliveryState FROM ThoughtsOutbox").use { assertTrue(it.moveToFirst()); assertEquals("pending-review", it.getString(0)); assertEquals("pending", it.getString(1)); assertFalse(it.moveToNext()) }
                db.query("SELECT COUNT(*) FROM ReviewMeta").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                db.query("EXPLAIN QUERY PLAN SELECT cardId FROM ReviewState WHERE active=1 AND eligibleAt<=100").use {
                    assertTrue(it.moveToFirst()); assertTrue(it.getString(3).contains("index_ReviewState_active_eligibleAt_pdfStem"))
                }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun addsRebuildableSnapshotsWithoutChangingReviewAuthority() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val name = "review-snapshot-migration-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 30).apply {
                execSQL("INSERT INTO ReviewRecord VALUES ('event-path','events',2,'event','card','{payload}')")
                execSQL("INSERT INTO ReviewState VALUES ('card','fixture',1,123,0,'document',123,7,'correct',0,0)")
                close()
            }
            helper.runMigrationsAndValidate(name, 31, true, AppDatabaseMigrations.MIGRATION_30_TO_31).use { db ->
                db.query("SELECT payload FROM ReviewRecord").use { assertTrue(it.moveToFirst()); assertEquals("{payload}", it.getString(0)) }
                db.query("SELECT reviewCount,currentMark FROM ReviewState").use { assertTrue(it.moveToFirst()); assertEquals(7, it.getInt(0)); assertEquals("correct", it.getString(1)) }
                db.query("SELECT COUNT(*) FROM ReviewSnapshot").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun addsDashboardWithoutChangingHistoryOrFullSnapshots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val name = "review-dashboard-migration-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 31).apply {
                execSQL("INSERT INTO ReviewRecord VALUES ('event-path','events',2,'event','card','history')")
                execSQL("INSERT INTO ReviewSnapshot VALUES ('card',1,'full snapshot')")
                close()
            }
            helper.runMigrationsAndValidate(name, 32, true, AppDatabaseMigrations.MIGRATION_31_TO_32).use { db ->
                db.query("SELECT payload FROM ReviewRecord").use { assertTrue(it.moveToFirst()); assertEquals("history", it.getString(0)) }
                db.query("SELECT payload FROM ReviewSnapshot").use { assertTrue(it.moveToFirst()); assertEquals("full snapshot", it.getString(0)) }
                db.query("SELECT COUNT(*) FROM ReviewDashboard").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }

}

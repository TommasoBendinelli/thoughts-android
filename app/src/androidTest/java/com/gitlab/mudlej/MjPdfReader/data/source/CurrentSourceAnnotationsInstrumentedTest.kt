// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.AppDatabaseMigrations
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.util.UUID

class CurrentSourceAnnotationsInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun migrationPreservesExistingAnnotationRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val name = "current-migration-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 32).apply {
                execSQL("INSERT INTO ThoughtsProjection(entityType,collection,entityId,revision,deleted,payloadJson,serverSequence,proposalSourceFile) VALUES ('logical_comment','comments','retained-note',1,0,'{}',1,'')")
                close()
            }
            helper.runMigrationsAndValidate(name, 33, true, AppDatabaseMigrations.MIGRATION_32_TO_33).use { db ->
                db.query("SELECT entityId FROM ThoughtsProjection").use {
                    assertTrue(it.moveToFirst()); assertEquals("retained-note", it.getString(0)); assertFalse(it.moveToNext())
                }
                db.query("SELECT COUNT(*) FROM CurrentSourceAnnotation").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun currentRecordsSurviveRestartAndRejectOtherGenerations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val dao = AppDatabase.getInstance(context).currentSourceAnnotationDao()
        val partition = "test-${UUID.randomUUID()}"
        val generation = "a".repeat(64)
        val proposal = OfflineAgentProposal(generation, "b".repeat(64), "proposal-test", "edit", "Reason.",
            "docs-test/tex/source/draft.tex", "b".repeat(64), 1, "New.", "Old.", "New.", null, false, emptyList(), emptyList())
        val snapshot = OfflineOverviewSourceSnapshot(bundleId = generation, proposals = listOf(proposal))
        try {
            CurrentSourceAnnotations(dao).write(partition, snapshot)
            val reopened = CurrentSourceAnnotations(dao)
            assertEquals(snapshot, reopened.read(partition, generation))
            assertNull(reopened.read(partition, "c".repeat(64)))
            val rows = runBlocking { dao.records(partition) }
            assertEquals(setOf("proposal", "metadata-v2"), rows.map { it.kind }.toSet())
            assertEquals("proposal-test", rows.single { it.kind == "proposal" }.id)
            reopened.write(partition, snapshot.copy(proposals = emptyList()))
            assertTrue(reopened.read(partition, generation)!!.proposals.isEmpty())
        } finally {
            runBlocking { dao.removePartition(partition) }
        }
    }

    @Test fun recordsLargerThanTheCursorWindowAreRead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val dao = AppDatabase.getInstance(context).currentSourceAnnotationDao()
        val partition = "test-${UUID.randomUUID()}"
        val generation = "a".repeat(64)
        // Multi-byte text: parts are cut by characters, the cursor limit counts bytes.
        val text = "Proposal prose \u00fc\u2713 ".repeat(200_000)
        val proposal = OfflineAgentProposal(generation, "b".repeat(64), "large-proposal", "edit", "Reason.",
            "docs-test/tex/source/draft.tex", "b".repeat(64), 1, text, "Old.", text, null, false, emptyList(), emptyList())
        val snapshot = OfflineOverviewSourceSnapshot(bundleId = generation, proposals = listOf(proposal))
        try {
            CurrentSourceAnnotations(dao).write(partition, snapshot)
            assertEquals(snapshot, CurrentSourceAnnotations(dao).read(partition, generation))
        } finally {
            runBlocking { dao.removePartition(partition) }
        }
    }

    @Test fun catalogueQuerySkipsLargeProposalCorpus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.getInstance(context).currentSourceAnnotationDao()
        val partition = "test-${com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity.id}-catalogue-benchmark"
        val generation = "a".repeat(64)
        val text = "Repeated proposal prose. ".repeat(500)
        val proposals = (0 until 500).map { number -> OfflineAgentProposal(
            generation, "b".repeat(64), "proposal-$number", "edit", "Reason.",
            "docs-test/tex/source/draft.tex", "b".repeat(64), 1, text, text, text,
            null, false, emptyList(), emptyList()) }
        val comments = (0 until 100).map { number -> OfflineLogicalComment(
            "comment-$number", "comment-$number", null, 1, false, "pdf_only", "", 0,
            "document", 0, "{\"contents\":\"Saved comment $number\"}") }
        try {
            CurrentSourceAnnotations(dao).write(partition,
                OfflineOverviewSourceSnapshot(bundleId = generation, proposals = proposals, logicalComments = comments))
            val fullTimes = mutableListOf<Long>()
            val catalogueTimes = mutableListOf<Long>()
            repeat(3) {
                val reopened = CurrentSourceAnnotations(dao)
                val start = android.os.SystemClock.elapsedRealtime()
                assertEquals(100, reopened.readComments(partition, generation)!!.logicalComments.size)
                catalogueTimes += android.os.SystemClock.elapsedRealtime() - start
                val fullStart = android.os.SystemClock.elapsedRealtime()
                assertEquals(500, reopened.read(partition, generation)!!.proposals.size)
                fullTimes += android.os.SystemClock.elapsedRealtime() - fullStart
            }
            android.util.Log.i("StartupIndexBenchmark", "Generated corpus: 500 proposals, 100 comments; " +
                "catalogue_ms=$catalogueTimes full_projection_ms=$fullTimes")
        } finally { runBlocking { dao.removePartition(partition) } }
    }

    @Test fun ambiguousReconciliationPreservesCurrentRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val dao = AppDatabase.getInstance(context).currentSourceAnnotationDao()
        val partition = "test-${UUID.randomUUID()}"
        val store = CurrentSourceAnnotations(dao)
        val original = OfflineOverviewSourceSnapshot(bundleId = "a".repeat(64))
        val comment = OfflineSourceReviewComment("duplicate", "Text.", "docs-test/tex/source/draft.tex", "b".repeat(64), 1, emptyList())
        try {
            store.write(partition, original)
            assertTrue(runCatching { store.write(partition, original.copy(reviewComments = listOf(comment, comment))) }.isFailure)
            assertEquals(original, store.read(partition, original.bundleId!!))
        } finally {
            runBlocking { dao.removePartition(partition) }
        }
    }
}

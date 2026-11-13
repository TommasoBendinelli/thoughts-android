// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceReviewComment
import com.gitlab.mudlej.MjPdfReader.data.source.logicalReadScope
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectLogicalCommentIsolationTest {
    @Test fun scopedJournalKeepsRepliesAndTombstonesWithoutReadingOtherProjectPayloads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.thoughtsSyncDao()
            fun payload(id: String, root: String, source: String = "") = JsonObject().apply {
                addProperty("comment_id", id); addProperty("thread_root_id", root); addProperty("revision", 1)
                addProperty("source_file", source)
                addProperty("text", "Mention alpha.pdf and docs-alpha/tex/source/a.tex without owning them")
            }.toString()
            for ((id, raw) in listOf(
                "alpha-root" to payload("alpha-root", "alpha-root", "docs-alpha/tex/source/a.tex"),
                "alpha-reply" to payload("alpha-reply", "alpha-root"),
                "alpha-new" to payload("alpha-new", "alpha-new", "docs-alpha/tex/source/new.tex"),
                "beta-root" to payload("beta-root", "beta-root", "docs-beta/tex/source/b.tex"),
            )) dao.putProjection(ThoughtsProjectionEntity("logical_comment", "comments", id, 1, false, raw, 1))
            val scope = OfflineOverviewSourceSnapshot(reviewComments = listOf(
                OfflineSourceReviewComment("alpha-root", "root", "docs-alpha/tex/source/a.tex", "a".repeat(64), 1, listOf("alpha.pdf")),
            )).logicalReadScope("alpha.pdf")
            val rows = scope.queries("SELECT * FROM ThoughtsProjection WHERE entityType = 'logical_comment'", "entityId")
                .flatMap { dao.scopedLogicalCommentProjections(it) }
            assertEquals(setOf("alpha-root", "alpha-reply", "alpha-new"), rows.map { it.entityId }.toSet())
            val deleted = JsonObject().apply {
                addProperty("comment_id", "alpha-root"); addProperty("revision", 2); addProperty("deleted", true)
            }.toString()
            dao.insertOutbox(ThoughtsOutboxEntity("delete-alpha", 1, "logical_comment", "alpha-root", "comments", 1,
                "2026-09-11T00:00:00Z", deleted, 0, deliveryState = "accepted"))
            dao.putLocalCommentState(ThoughtsLocalCommentStateEntity("alpha-root", "delete-alpha", 2, 1, true, deleted))
            val effective = FedoraRepository(context, db).effectiveLogicalCommentPayloads(scope).associateBy { it.get("comment_id").asString }
            assertEquals(setOf("alpha-root", "alpha-reply", "alpha-new"), effective.keys)
            assertEquals(true, effective.getValue("alpha-root").get("deleted").asBoolean)
        } finally { db.close() }
    }
}

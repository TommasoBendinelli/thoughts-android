// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Accepted local changes stay visible until their server copy is pulled, then yield to it. */
class AcceptedCommentVisibilityTest {
    @Test fun acceptedChangesShowUntilTheServerCopyArrives() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.thoughtsSyncDao()
            fun payload(id: String, revision: Int, contents: String) = JsonObject().apply {
                addProperty("comment_id", id); addProperty("thread_root_id", id)
                addProperty("revision", revision); addProperty("contents", contents)
            }.toString()
            var sequence = 0L
            suspend fun accepted(id: String, revision: Int, contents: String) {
                sequence++
                dao.insertOutbox(ThoughtsOutboxEntity("op-$id-$revision", sequence, "logical_comment", id, "comments",
                    (revision - 1).toLong(), "2026-09-30T00:00:00Z", payload(id, revision, contents), 0, deliveryState = "accepted"))
            }
            suspend fun projection(id: String, revision: Int, contents: String) =
                dao.putProjection(ThoughtsProjectionEntity("logical_comment", "comments", id, revision.toLong(), false,
                    payload(id, revision, contents), revision.toLong()))
            suspend fun contents() = FedoraRepository(context, db).effectiveLogicalCommentPayloads(includeSourceJournals = false)
                .associate { it.get("comment_id").asString to it.get("contents").asString }

            accepted("fresh", 1, "Created on the phone")
            projection("edited", 1, "Before")
            accepted("edited", 2, "After")
            projection("remote", 1, "Original")
            accepted("remote", 2, "Phone edit")
            projection("remote", 3, "Later edit from another device")
            assertEquals(mapOf(
                "fresh" to "Created on the phone",
                "edited" to "After",
                "remote" to "Later edit from another device",
            ), contents())

            projection("fresh", 1, "Server copy")
            projection("edited", 2, "Server copy of the edit")
            assertEquals("Server copy", contents().getValue("fresh"))
            assertEquals("Server copy of the edit", contents().getValue("edited"))
        } finally { db.close() }
    }
}

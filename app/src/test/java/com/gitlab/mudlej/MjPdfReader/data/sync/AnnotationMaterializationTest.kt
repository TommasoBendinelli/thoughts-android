// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentDeliveryRecord
import com.gitlab.mudlej.MjPdfReader.data.render.PendingCommentDelivery
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnnotationMaterializationTest {
    @Test
    fun pendingDeliverySurvivesServerProjectionMaterialization() {
        val server = projection("server-id", 2, payload("server-id", 2, "server"))
        val pending = PendingCommentDelivery(
            batchId = "11111111-1111-1111-1111-111111111111",
            target = "thoughts.pdf",
            records = listOf(
                CommentDeliveryRecord("pending-id", 1, payload("pending-id", 1, "pending")),
            ),
            createdAtEpochMillis = 1,
        )

        val records = records(materializedAnnotationSidecars(listOf(server), listOf(pending), emptyList()))

        assertEquals(setOf("server-id", "pending-id"), records.keys)
        assertEquals("pending", records.getValue("pending-id").get("contents").asString)
    }

    @Test
    fun pendingThoughtsOperationOverridesItsOlderServerProjection() {
        val server = projection("same-id", 2, payload("same-id", 2, "server"))
        val pendingPayload = JsonParser.parseString(payload("same-id", 3, "local")).asJsonObject.apply {
            addProperty("sidecar_schema_version", 5)
            addProperty("schema_version", 1)
        }.toString()
        val operation = ThoughtsOutboxEntity(
            operationId = "operation",
            deviceSequence = 1,
            entityType = "annotation",
            entityId = "same-id",
            collection = "thoughts",
            baseRevision = 2,
            clientTimestamp = "2026-08-15T00:00:00Z",
            payloadJson = pendingPayload,
            createdAtEpochMillis = 1,
        )

        val records = records(materializedAnnotationSidecars(listOf(server), emptyList(), listOf(operation)))

        assertEquals("local", records.getValue("same-id").get("contents").asString)
        assertEquals(5, records.getValue("same-id").get("schema_version").asInt)
        assertFalse(records.getValue("same-id").has("sidecar_schema_version"))
    }

    @Test
    fun serverTombstoneIsNotWrittenIntoTheLiveSidecar() {
        val tombstone = JsonParser.parseString(payload("deleted-id", 4, "deleted")).asJsonObject.apply {
            addProperty("deleted", true)
        }.toString()
        val live = projection("live-id", 3, payload("live-id", 3, "live"))

        val records = records(
            materializedAnnotationSidecars(
                listOf(live, projection("deleted-id", 4, tombstone, deleted = true)),
                emptyList(),
                emptyList(),
            ),
        )

        assertEquals(setOf("live-id"), records.keys)
    }

    private fun records(sidecars: Map<String, String>) = sidecars.getValue("thoughts")
        .lineSequence()
        .filter(String::isNotBlank)
        .map { JsonParser.parseString(it).asJsonObject }
        .associateBy { it.get("id").asString }

    private fun projection(
        id: String,
        revision: Long,
        payloadJson: String,
        deleted: Boolean = false,
    ) = ThoughtsProjectionEntity(
        entityType = "annotation",
        collection = "thoughts",
        entityId = id,
        revision = revision,
        deleted = deleted,
        payloadJson = payloadJson,
        serverSequence = revision,
    )

    private fun payload(id: String, revision: Int, contents: String): String =
        """{"schema_version":5,"id":"$id","revision":$revision,"contents":"$contents"}"""
}

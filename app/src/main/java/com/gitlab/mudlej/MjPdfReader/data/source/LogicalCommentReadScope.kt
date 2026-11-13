// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** Membership is indexed on record writes. Opening a project never searches other payloads. */
internal class LogicalCommentReadScope(private val keys: Set<String>) {
    fun accepts(payload: com.google.gson.JsonObject): Boolean {
        fun text(key: String) = payload.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val source = payload.get("scope")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("source_file")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val collection = payload.get("origin")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("collection")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val placement = payload.get("pdf_placement")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("collection")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        return "pdf:$placement" in keys || "comment:${text("comment_id")}" in keys || "comment:${text("thread_root_id")}" in keys ||
            "pdf:$collection" in keys || SourceOwner.of(source)?.let { "project:$it" in keys } == true
    }
    fun queries(select: String, entityColumn: String): List<SupportSQLiteQuery> = keys.chunked(200).map { batch ->
        SimpleSQLiteQuery("$select AND $entityColumn IN (SELECT entityId FROM ThoughtsCommentScope WHERE scopeKey IN (${batch.joinToString { "?" }}))", batch.toTypedArray())
    }
}

internal fun OfflineOverviewSourceSnapshot.logicalReadScope(pdfFilename: String): LogicalCommentReadScope {
    val identities = logicalComments.flatMap { listOf(it.id, it.threadRootId) } +
        reviewComments.map { it.id } + proposals.map { it.id }
    val paths = logicalComments.map { it.sourceFile } + reviewComments.map { it.sourceFile } + proposals.map { it.sourceFile }
    val keys = identities.filter(String::isNotBlank).mapTo(linkedSetOf()) { "comment:$it" }
    keys += "pdf:${pdfFilename.removeSuffix(".pdf")}"
    paths.mapNotNull(SourceOwner::of).distinct().forEach {
        keys += "project:$it"
    }
    return LogicalCommentReadScope(keys)
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCreationMetadata
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentPriority
import com.gitlab.mudlej.MjPdfReader.data.annotation.firstPrimaryOrNull
import com.gitlab.mudlej.MjPdfReader.data.annotation.logicalCommentColor
import com.gitlab.mudlej.MjPdfReader.data.annotation.logicalCommentMutationPayload
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.gitlab.mudlej.MjPdfReader.data.source.anchorRemoved
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorValue
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

internal data class LogicalCommentMessage(
    val id: String,
    val threadRootId: String,
    val parentId: String?,
    val contents: String,
    val author: String,
    val createdAt: String,
    val updatedAt: String,
    val chatGptUrl: String,
    val editorValue: CommentEditorValue,
    val payload: JsonObject,
)

internal data class LogicalCommentThread(
    val root: LogicalCommentMessage,
    val replies: List<LogicalCommentMessage>,
    val pageIndex: Int,
)

internal data class LogicalCommentSheetSnapshot(
    val threads: List<LogicalCommentThread>,
    val selectedThreadIndex: Int,
)

internal fun logicalCommentMarkerOpenTarget(
    commentId: String?,
    pdfThreadOpened: Boolean,
): LogicalCommentMarkerOpenTarget = when {
    pdfThreadOpened -> LogicalCommentMarkerOpenTarget.PDF_THREAD
    !commentId.isNullOrBlank() -> LogicalCommentMarkerOpenTarget.LOGICAL_THREAD
    else -> LogicalCommentMarkerOpenTarget.UNAVAILABLE
}

internal enum class LogicalCommentMarkerOpenTarget {
    PDF_THREAD,
    LOGICAL_THREAD,
    UNAVAILABLE,
}

internal fun logicalCommentEditorValue(payload: JsonObject): CommentEditorValue? {
    if (payload.string("comment_id").isBlank() || payload.boolean("deleted")) return null
    val contents = payload.string("contents")
    val location = payload.get("location")
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?.let(CommentLocation::fromJson)
    return CommentEditorValue(
        text = contents,
        color = logicalCommentColor(payload),
        priority = CommentPriority.fromWireValue(payload.string("priority")),
        location = location,
        needsAction = payload.boolean("needs_action"),
        linking = payload.get("linking")?.takeIf { it.isJsonObject }?.asJsonObject,
    )
}

internal fun logicalCommentSheetSnapshot(
    comments: List<OfflineLogicalComment>,
    selectedCommentId: String,
    pdfFilename: String,
    pdfSha256: String,
    selectFirstIfMissing: Boolean = false,
): LogicalCommentSheetSnapshot? {
    val active = comments.filterNot(OfflineLogicalComment::deleted)
    val byId = active.associateBy(OfflineLogicalComment::id)
    val selected = byId[selectedCommentId]
    if (selected == null && !selectFirstIfMissing) return null
    val selectedRootId = selected?.threadRootId
    val roots = active.asSequence()
        .filter { it.parentId == null && it.id == it.threadRootId }
        // "Anchor removed" roots stay out of the reader's comment order unless opened directly.
        .filter { !it.anchorRemoved || it.id == selectedRootId }
        .mapNotNull { root ->
            val location = root.locations.firstPrimaryOrNull { candidate ->
                candidate.status == "exact" &&
                    candidate.pdfFilename.equals(pdfFilename, ignoreCase = true) &&
                    candidate.pdfSha256.equals(pdfSha256, ignoreCase = true)
            }
            if (location == null && root.originCollection != pdfFilename.removeSuffix(".pdf")) return@mapNotNull null
            val rootMessage = root.toLogicalCommentMessage() ?: return@mapNotNull null
            val replies = active.asSequence()
                .filter { it.parentId != null && it.threadRootId == root.id }
                .mapNotNull(OfflineLogicalComment::toLogicalCommentMessage)
                .sortedWith(compareBy({ it.createdAt }, { it.updatedAt }, { it.id }))
                .toList()
            LogicalCommentThread(rootMessage, replies, location?.pageIndex ?: root.originPageIndex)
        }
        .sortedWith(compareBy({ it.pageIndex }, { it.root.createdAt }, { it.root.id }))
        .toList()
    if (roots.isEmpty()) return null
    val selectedIndex = roots.indexOfFirst { it.root.id == selectedRootId }.let { if (it < 0 && selectFirstIfMissing) 0 else it }
    if (selectedIndex < 0) return null
    return LogicalCommentSheetSnapshot(roots, selectedIndex)
}

internal fun logicalCommentReplyPayload(
    rootPayload: JsonObject,
    replyId: String,
    contents: String,
    author: String,
    timestamp: String,
): JsonObject? {
    val rootId = rootPayload.string("comment_id").takeIf(String::isNotBlank) ?: return null
    if (rootPayload.int("schema_version") != 1 || rootPayload.string("parent_id").isNotBlank()) {
        return null
    }
    val origin = rootPayload.get("origin")?.takeIf { it.isJsonObject }?.deepCopy() ?: JsonNull.INSTANCE
    return JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("comment_id", replyId)
        addProperty("thread_root_id", rootId)
        addProperty("parent_id", rootId)
        addProperty("revision", 1)
        addProperty("created_at", timestamp)
        addProperty("updated_at", timestamp)
        addProperty("origin_id", "thoughts-android")
        addProperty("deleted", false)
        addProperty("contents", contents)
        addProperty("author", author)
        addProperty("subtype", "Text")
        addProperty("selected_text", "")
        add("color", CommentCreationMetadata.canonicalPopupColorJson())
        addProperty("priority", CommentPriority.NORMAL.wireValue)
        add("location", JsonNull.INSTANCE)
        addProperty("chatgpt_url", "")
        add("origin", origin)
        add("scope", JsonObject().apply {
            addProperty("state", "pdf_only")
            addProperty("reason", "inherits_thread_locations")
        })
    }
}

internal class LogicalCommentThreadStore(context: Context, private val documentFilename: () -> String) {
    private val appContext = context.applicationContext

    suspend fun load(
        selectedCommentId: String,
        pdfFilename: String,
        pdfSha256: String,
        selectFirstIfMissing: Boolean = false,
    ): LogicalCommentSheetSnapshot? = logicalCommentSheetSnapshot(
        mergedComments(pdfFilename),
        selectedCommentId,
        pdfFilename,
        pdfSha256,
        selectFirstIfMissing,
    )

    suspend fun update(commentId: String, value: CommentEditorValue): Boolean = runCatching {
        com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(appContext).update(commentId, documentFilename()) { current ->
            requireNotNull(logicalCommentMutationPayload(current, commentId, deleted = false, contents = value.text,
                color = value.color, priority = value.priority, location = value.location, replaceLocation = true,
                needsAction = value.needsAction.takeIf { current.string("parent_id").isBlank() }))
        }
        true
    }.getOrDefault(false)

    suspend fun addReply(rootId: String, contents: String, author: String): Boolean = runCatching {
        com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(appContext).update(rootId, documentFilename()) { current ->
            requireNotNull(logicalCommentReplyPayload(current, CommentCreationMetadata.newThoughtsId(), contents, author, Instant.now().toString()))
        }
        true
    }.getOrDefault(false)

    private suspend fun mergedComments(pdfFilename: String = documentFilename()): List<OfflineLogicalComment> =
        com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(appContext).snapshot(pdfFilename).logicalComments


}

private fun OfflineLogicalComment.toLogicalCommentMessage(): LogicalCommentMessage? {
    val value = runCatching { JsonParser.parseString(payloadJson).asJsonObject }.getOrNull()
        ?: return null
    val editorValue = logicalCommentEditorValue(value) ?: return null
    return LogicalCommentMessage(
        id = id,
        threadRootId = threadRootId,
        parentId = parentId,
        contents = editorValue.text,
        author = value.string("author"),
        createdAt = value.string("created_at"),
        updatedAt = value.string("updated_at"),
        chatGptUrl = value.string("chatgpt_url").ifBlank { value.string("chat_gpt_url") },
        editorValue = editorValue,
        payload = value,
    )
}

private fun JsonObject.string(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

private fun JsonObject.int(name: String): Int =
    runCatching { get(name)?.asInt }.getOrNull() ?: 0

private fun JsonObject.boolean(name: String): Boolean =
    runCatching { get(name)?.asBoolean }.getOrNull() ?: false

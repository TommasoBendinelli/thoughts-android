// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.gitlab.mudlej.MjPdfReader.data.source.AgentProposals
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * A reply record as the server returns it (protocol/agent-notes.md "Reply record"), or as the
 * phone keeps it in its outbox before the server has confirmed it.
 */
data class AgentNoteReply(
    val replyId: String,
    val noteId: String,
    val sequence: Long = 0,
    val deviceId: String = "",
    val createdAt: String = "",
    val mediaType: String = "image/png",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val imageBytes: Long = 0,
    val imageSha256: String = "",
    val imageUrl: String = "",
    val strokesUrl: String? = null,
    val strokeCount: Int = 0,
    val message: String? = null,
    /** Whether an agent heard it when it was stored (the answer to the post only; never kept on disk). */
    val listening: AgentNoteListening = AgentNoteListening.UNKNOWN,
    /** Who answers it, from the same answer (null when nobody listens or the server does not say). */
    val coverer: AgentNoteCoverer? = null,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("reply_id", replyId)
        addProperty("sequence", sequence)
        addProperty("note_id", noteId)
        addProperty("device_id", deviceId)
        addProperty("created_at", createdAt)
        addProperty("media_type", mediaType)
        addProperty("image_width", imageWidth)
        addProperty("image_height", imageHeight)
        addProperty("image_bytes", imageBytes)
        addProperty("image_sha256", imageSha256)
        addProperty("image_url", imageUrl)
        addProperty("strokes_url", strokesUrl)
        addProperty("stroke_count", strokeCount)
        addProperty("message", message)
    }

    companion object {
        fun fromJson(json: JsonObject, noteId: String? = null): AgentNoteReply = AgentNoteReply(
            replyId = json.string("reply_id") ?: throw IllegalArgumentException("Reply without reply_id"),
            noteId = json.string("note_id") ?: noteId ?: json.getAsJsonObjectOrNull("note")?.string("note_id").orEmpty(),
            sequence = json.long("sequence"),
            deviceId = json.string("device_id").orEmpty(),
            createdAt = json.string("created_at").orEmpty(),
            mediaType = json.string("media_type") ?: "image/png",
            imageWidth = json.long("image_width").toInt(),
            imageHeight = json.long("image_height").toInt(),
            imageBytes = json.long("image_bytes"),
            imageSha256 = json.string("image_sha256").orEmpty(),
            imageUrl = json.string("image_url").orEmpty(),
            strokesUrl = json.string("strokes_url"),
            strokeCount = json.long("stroke_count").toInt(),
            message = json.string("message"),
            listening = AgentNoteListening.fromJson(json.getAsJsonObjectOrNull("listening")),
            coverer = AgentNoteCoverer.fromListening(json.getAsJsonObjectOrNull("listening")),
        )
    }
}

/** A photo sent with a reply ("Send with picture": from the glasses camera); [capture] names its source. */
class AgentNoteReplyPhotoUpload(val bytes: ByteArray, val mediaType: String, val capture: String?)

/**
 * Whether an agent waits to act on the user's reply to a note (protocol/agent-notes.md "Who hears
 * a reply"): the agent that sent it runs its reply monitor or waits for this note right now.
 */
enum class AgentNoteListening {
    LISTENING,
    NOT_LISTENING,
    /** The sender's agents never say (older servers, agents that do not announce it): say nothing. */
    UNKNOWN;

    companion object {
        fun fromState(state: String?): AgentNoteListening = when (state) {
            "listening" -> LISTENING
            "not_listening" -> NOT_LISTENING
            else -> UNKNOWN
        }

        /** `{"state": ..., "listeners": [...]}`, or UNKNOWN when absent. */
        fun fromJson(json: JsonObject?): AgentNoteListening = fromState(json?.string("state"))
    }
}

/**
 * The agent that answers the user's reply to a note while it is listening (protocol/agent-notes.md
 * "Who answers"): an agent of the device that sent it ([SENDER]), a topic owner ([OWNER], with the
 * topic's [label]) or the Thoughts reply agent ([RESPONDER]).
 */
data class AgentNoteCoverer(val role: String, val label: String? = null) {
    companion object {
        const val SENDER = "sender"
        const val OWNER = "owner"
        const val RESPONDER = "responder"

        /**
         * The live listener that answers first, from `GET /v1/agent-notes/{id}/listening` (or a
         * reply's `listening`): the sender's agent, then a topic owner, then the reply agent, which
         * waits for both. Null when nobody listens. A listener from a server before topic owners
         * has no `role`: `responder: true` is the reply agent, anything else the sender's agent.
         */
        fun fromListening(json: JsonObject?): AgentNoteCoverer? {
            if (json?.string("state") != "listening") return null
            val listeners = json.get("listeners")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return null
            val coverers = listeners.mapNotNull { element ->
                val listener = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
                val responder = listener.get("responder")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean == true
                when (listener.string("role") ?: if (responder) RESPONDER else SENDER) {
                    RESPONDER -> AgentNoteCoverer(RESPONDER)
                    OWNER -> AgentNoteCoverer(OWNER, listener.string("label")?.trim()?.takeIf(String::isNotEmpty)?.take(120))
                    SENDER -> AgentNoteCoverer(SENDER)
                    else -> null
                }
            }
            return listOf(SENDER, OWNER, RESPONDER).firstNotNullOfOrNull { role -> coverers.firstOrNull { it.role == role } }
        }
    }
}

/**
 * An agent that can answer the user's reply to a note (server `agent_note_answer_by_v1`): the
 * sender's agent, a topic owner or the Thoughts reply agent ([role], as [AgentNoteCoverer]), on
 * [deviceId], in [conversationId] or for [ownerId]. [live]: it is listening right now.
 */
data class AgentNoteAgent(
    val role: String,
    val deviceId: String,
    val conversationId: String? = null,
    val ownerId: String? = null,
    val label: String? = null,
    val live: Boolean = false,
) {
    /** The same agent as [other], whatever its label or whether it is live says now. */
    fun isSameAgent(other: AgentNoteAgent?): Boolean = other != null && role == other.role &&
        deviceId == other.deviceId && conversationId == other.conversationId && ownerId == other.ownerId

    /** How the screen names it. */
    val coverer: AgentNoteCoverer get() = AgentNoteCoverer(role, label)

    /** The agent as `PUT /v1/agent-notes/{id}/answer-by` names it: its identity only. */
    fun toChoiceJson(): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("device_id", deviceId)
        addProperty("conversation_id", conversationId)
        addProperty("owner_id", ownerId)
    }

    companion object {
        /** Null for anything that is not an agent the screen can name (an unknown role, no device). */
        fun fromJson(json: JsonObject?): AgentNoteAgent? {
            val role = json?.string("role")?.takeIf { it in setOf(AgentNoteCoverer.SENDER, AgentNoteCoverer.OWNER, AgentNoteCoverer.RESPONDER) }
                ?: return null
            val deviceId = json.string("device_id")?.takeIf(String::isNotBlank) ?: return null
            return AgentNoteAgent(
                role = role,
                deviceId = deviceId,
                conversationId = json.string("conversation_id"),
                ownerId = json.string("owner_id"),
                label = json.string("label")?.trim()?.takeIf(String::isNotEmpty)?.take(120),
                live = json.get("live")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true,
            )
        }
    }
}

/**
 * A note's listening state with who answers it. With `agent_note_answer_by_v1` the server also
 * says which agent the user chose to answer ([answerBy]; null is automatic: the agent that sent
 * the note first, then the Thoughts reply agent) and which agents could answer now
 * ([candidates]); with a choice set, [state] is listening exactly while the chosen agent is live.
 */
data class AgentNoteListeningAnswer(
    val state: AgentNoteListening,
    val coverer: AgentNoteCoverer? = null,
    val answerBy: AgentNoteAgent? = null,
    val candidates: List<AgentNoteAgent> = emptyList(),
) {
    companion object {
        val UNKNOWN = AgentNoteListeningAnswer(AgentNoteListening.UNKNOWN)

        fun fromJson(json: JsonObject?): AgentNoteListeningAnswer = AgentNoteListeningAnswer(
            state = AgentNoteListening.fromJson(json),
            coverer = AgentNoteCoverer.fromListening(json),
            answerBy = AgentNoteAgent.fromJson(json?.getAsJsonObjectOrNull("answer_by")),
            candidates = json?.get("candidates")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                ?.mapNotNull { AgentNoteAgent.fromJson(it.takeIf(JsonElement::isJsonObject)?.asJsonObject) }
                .orEmpty(),
        )
    }
}

/** The server capability for choosing who answers a note (`answer_by`, `candidates`, the PUT). */
const val AGENT_NOTE_ANSWER_BY_CAPABILITY = "agent_note_answer_by_v1"

/** The `error_code` of the 409 for a chosen agent the server does not know (any more). */
const val AGENT_NOTE_ANSWERER_UNKNOWN = "agent_note_answerer_unknown"

/**
 * A tappable rectangle of a note's image (protocol/agent-notes.md "Links"): [left], [top], [width]
 * and [height] are fractions of the image; [href] is a web address or
 * `thoughts-pdf-link:<Library filename>.pdf?page=<0-based>`.
 */
data class AgentNoteLink(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val href: String,
    val label: String? = null,
) {
    /** Whether the point ([x], [y], fractions of the image) is inside, with [slackX]/[slackY] around it. */
    fun contains(x: Float, y: Float, slackX: Float = 0f, slackY: Float = 0f): Boolean =
        x >= left - slackX && x <= left + width + slackX && y >= top - slackY && y <= top + height + slackY

    fun toJson(): JsonObject = JsonObject().apply {
        add("rect", com.google.gson.JsonArray().apply { add(left); add(top); add(width); add(height) })
        addProperty("href", href)
        if (label != null) addProperty("label", label)
    }

    companion object {
        /** A link of a note record, or null when it is not one this phone can open. */
        fun fromJson(json: JsonObject): AgentNoteLink? {
            val rect = json.get("rect")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return null
            if (rect.size() != 4) return null
            val values = rect.map { element -> element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat ?: return null }
            if (values.any { !it.isFinite() } || values[2] <= 0f || values[3] <= 0f) return null
            val href = json.string("href")?.trim()?.takeIf(::openable) ?: return null
            return AgentNoteLink(values[0], values[1], values[2], values[3], href, json.string("label")?.takeIf(String::isNotBlank))
        }

        /** A web address, or a page of a Library PDF as PDF links in the reader write it. */
        fun openable(href: String): Boolean =
            href.startsWith("https://", ignoreCase = true) || href.startsWith("http://", ignoreCase = true) ||
                href.startsWith("thoughts-pdf-link:")

        /** The link of [links] at the point ([x], [y], fractions of the image): the smallest that holds it, else the nearest within the slack. */
        fun at(links: List<AgentNoteLink>, x: Float, y: Float, slackX: Float, slackY: Float): AgentNoteLink? =
            links.filter { it.contains(x, y) }.minByOrNull { it.width * it.height }
                ?: links.filter { it.contains(x, y, slackX, slackY) }.minByOrNull { link ->
                    val dx = maxOf(link.left - x, 0f, x - (link.left + link.width)) / maxOf(slackX, 1e-6f)
                    val dy = maxOf(link.top - y, 0f, y - (link.top + link.height)) / maxOf(slackY, 1e-6f)
                    dx * dx + dy * dy
                }
    }
}

/** A note record (protocol/agent-notes.md "Note record"). */
data class AgentNote(
    val noteId: String,
    val sequence: Long = 0,
    val createdAt: String = "",
    val senderDeviceId: String = "",
    val title: String = "",
    val message: String? = null,
    val mediaType: String = "image/png",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val imageBytes: Long = 0,
    val imageSha256: String = "",
    val imageUrl: String = "",
    val documentId: String? = null,
    val documentFilename: String? = null,
    val reviewCardId: String? = null,
    val reviewQuestion: String? = null,
    val origin: JsonObject? = null,
    val deliveredAt: String? = null,
    val deliveredDeviceId: String? = null,
    val openedAt: String? = null,
    val deleted: Boolean = false,
    val replies: List<AgentNoteReply> = emptyList(),
    /** Rectangles of the image that open a source when tapped (servers before agent_note_links_v1: none). */
    val links: List<AgentNoteLink> = emptyList(),
) {
    /** "claude" or "codex": the conversation that sent the note (`origin.conversation_agent`), or null. */
    val conversationAgent: String? get() = origin?.string("conversation_agent")?.takeIf(String::isNotBlank)

    /** Link of the conversation that sent the note (`origin.conversation_url`, spec 3.6 form only), or null. */
    val conversationUrl: String? get() = origin?.string("conversation_url")?.takeIf(AgentProposals::validConversationUrl)

    /** True when the note names the conversation that sent it, with or without a link. */
    val hasConversation: Boolean get() = conversationUrl != null || !origin?.string("conversation_id").isNullOrBlank()

    /** The conversation that sent the note (`origin.conversation_id`, else its link), or null when it names none. */
    val conversationKey: String? get() = origin?.string("conversation_id")?.takeIf(String::isNotBlank) ?: conversationUrl

    /** A page the user sent from the reader in study mode (`origin.kind` "phone_page"), not a note from an agent. */
    val isPhonePage: Boolean get() = origin?.string("kind") == PHONE_PAGE
    /** A page note still only on this phone: Send has not created it on the server yet. */
    val isUnsentPhonePage: Boolean get() = isPhonePage && imageUrl.isBlank() && sequence == 0L
    /** "png" or "jpg": the extension of the stored image file. */
    val imageExtension: String get() = if (mediaType.equals("image/jpeg", ignoreCase = true)) "jpg" else "png"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("note_id", noteId)
        addProperty("sequence", sequence)
        addProperty("created_at", createdAt)
        addProperty("sender_device_id", senderDeviceId)
        addProperty("title", title)
        addProperty("message", message)
        addProperty("media_type", mediaType)
        addProperty("image_width", imageWidth)
        addProperty("image_height", imageHeight)
        addProperty("image_bytes", imageBytes)
        addProperty("image_sha256", imageSha256)
        addProperty("image_url", imageUrl)
        addProperty("document_id", documentId)
        addProperty("document_filename", documentFilename)
        addProperty("review_card_id", reviewCardId)
        addProperty("review_question", reviewQuestion)
        add("origin", origin)
        addProperty("delivered_at", deliveredAt)
        addProperty("delivered_device_id", deliveredDeviceId)
        addProperty("opened_at", openedAt)
        addProperty("deleted", deleted)
        add("replies", com.google.gson.JsonArray().apply { replies.forEach { add(it.toJson()) } })
        add("links", com.google.gson.JsonArray().apply { links.forEach { add(it.toJson()) } })
    }

    companion object {
        /** `origin.kind` of a page the user sends from the reader to their agent. */
        const val PHONE_PAGE = "phone_page"

        fun fromJson(json: JsonObject): AgentNote {
            val id = json.string("note_id") ?: throw IllegalArgumentException("Note without note_id")
            return AgentNote(
                noteId = id,
                sequence = json.long("sequence"),
                createdAt = json.string("created_at").orEmpty(),
                senderDeviceId = json.string("sender_device_id").orEmpty(),
                title = json.string("title").orEmpty(),
                message = json.string("message"),
                mediaType = json.string("media_type") ?: "image/png",
                imageWidth = json.long("image_width").toInt(),
                imageHeight = json.long("image_height").toInt(),
                imageBytes = json.long("image_bytes"),
                imageSha256 = json.string("image_sha256").orEmpty(),
                imageUrl = json.string("image_url").orEmpty(),
                documentId = json.string("document_id"),
                documentFilename = json.string("document_filename"),
                reviewCardId = json.string("review_card_id"),
                reviewQuestion = json.string("review_question"),
                origin = json.getAsJsonObjectOrNull("origin"),
                deliveredAt = json.string("delivered_at"),
                deliveredDeviceId = json.string("delivered_device_id"),
                openedAt = json.string("opened_at"),
                deleted = json.get("deleted")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                replies = json.get("replies")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                    ?.mapNotNull { element ->
                        element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                            ?.let { runCatching { AgentNoteReply.fromJson(it, id) }.getOrNull() }
                    }.orEmpty(),
                links = json.get("links")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                    ?.mapNotNull { element -> element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let(AgentNoteLink::fromJson) }
                    .orEmpty(),
            )
        }
    }
}

/** One answer of `GET /v1/agent-notes/inbox`. */
data class AgentNoteInboxPage(val notes: List<AgentNote>, val cursor: Long?)

/** The review card and document the phone is studying, reported with each inbox request. */
data class AgentStudyContext(val reviewCardId: String?, val documentId: String?)

internal fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.long(key: String): Long =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L

internal fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
    get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

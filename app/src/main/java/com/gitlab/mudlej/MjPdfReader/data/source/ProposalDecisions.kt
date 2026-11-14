// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.security.MessageDigest

/**
 * Decision claims of thoughts-server protocol/agent-proposals.md section 10.4.2. A claim says that
 * a wrapper was removed by an explicit decision in the phone's source editor; it travels with the
 * source upload that carries the decided text (`proposal_decisions` on POST /v1/sources/apply and
 * on the conflict resolution) and expires when the server answers that upload.
 */
internal data class ProposalDecisionClaim(
    val decisionId: String,
    val proposalId: String,
    /** accept | reject | partial (the phone source editor never withdraws). */
    val action: String,
    val wrapperSha256: String,
    val sourceFile: String,
    val decidedAt: String,
    val origin: String = ORIGIN_PHONE_EDITOR,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("decision_id", decisionId)
        addProperty("proposal_id", proposalId)
        addProperty("action", action)
        addProperty("wrapper_sha256", wrapperSha256)
        addProperty("source_file", sourceFile)
        addProperty("decided_at", decidedAt)
        addProperty("origin", origin)
    }

    companion object {
        const val ORIGIN_PHONE_EDITOR = "phone_editor"
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val ID = Regex("[a-z0-9][a-z0-9._:-]{0,127}")

        fun fromJson(element: JsonElement?): ProposalDecisionClaim? = runCatching {
            val value = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            fun text(key: String) = value.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            ProposalDecisionClaim(text("decision_id"), text("proposal_id"), text("action"), text("wrapper_sha256"),
                text("source_file"), text("decided_at"), text("origin")).takeIf {
                it.decisionId.matches(UUID) && it.proposalId.matches(ID) && it.action in ACTIONS &&
                    it.wrapperSha256.matches(SHA256) && it.sourceFile.isNotBlank() && it.decidedAt.isNotBlank() &&
                    it.origin.isNotBlank()
            }
        }.getOrNull()

        val ACTIONS = setOf("accept", "reject", "partial", "withdraw")
    }
}

internal object ProposalDecisionClaims {
    /** The server reads claims, writes removed_outside rows and accepts Dismiss (section 10.7.1). */
    const val CAPABILITY = "proposal_decisions_v1"
    const val REQUEST_MEMBER = "proposal_decisions"
    /** Journal member: decision (or redo) events whose claim was already recorded with a save. */
    const val CLAIMED_EVENTS = "claimedDecisionEvents"
    const val MAX_CLAIMS = 500

    data class Derived(val claim: ProposalDecisionClaim, val activationEventId: String)

    /** The claims an upload of [contents] to [path] carries, and the ones it drops (section 10.4.2). */
    data class Upload(val send: List<ProposalDecisionClaim>, val dropped: List<ProposalDecisionClaim>)

    fun read(array: JsonElement?): List<ProposalDecisionClaim> =
        array?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull(ProposalDecisionClaim::fromJson).orEmpty()

    fun toJson(claims: List<ProposalDecisionClaim>): JsonArray = JsonArray().apply { claims.forEach { add(it.toJson()) } }

    /** One claim per proposal: a later decision replaces an earlier one for the same ID. */
    fun merge(existing: List<ProposalDecisionClaim>, added: List<ProposalDecisionClaim>): List<ProposalDecisionClaim> {
        val ids = added.mapTo(hashSetOf()) { it.proposalId }
        return (existing.filter { it.proposalId !in ids } + added).takeLast(MAX_CLAIMS)
    }

    fun wrapperSha256(text: String, proposal: AgentProposals.Proposal): String =
        sha256(text.substring(proposal.start, proposal.end))

    /** IDs that the text still carries as a valid wrapper or as an issue (section 6). */
    fun presentIds(path: String, text: String): Set<String> {
        val parsed = AgentProposals.parse(path, text)
        return parsed.proposals.mapTo(hashSetOf()) { it.id } + parsed.issues.mapNotNull { it.id }
    }

    /**
     * Claims travel only with an upload that includes their source. A claim whose wrapper is back
     * in the uploaded content (or broken: an issue carries its ID) was undone locally and is dropped.
     */
    fun forUpload(claims: List<ProposalDecisionClaim>, path: String, contents: String?): Upload {
        val mine = claims.filter { it.sourceFile == path }
        if (contents == null) return Upload(mine, emptyList())
        val present = presentIds(path, contents)
        val (dropped, send) = mine.partition { it.proposalId in present }
        return Upload(send, dropped)
    }

    /**
     * Claims of one save of the phone source editor (section 10.5): every ID that is a valid
     * wrapper in [base] (the text the save replaces), is absent from [saved], and whose last
     * not-undone journal decision removed the wrapper. A removal step that follows partial steps
     * of the same proposal is `partial`. Decisions already claimed by an earlier save ([claimed])
     * do not claim again, so removing a restored wrapper by hand is not taken for a decision.
     */
    fun derive(
        sourceFile: String,
        base: String,
        saved: String,
        events: List<JsonObject>,
        claimed: Set<String>,
        newDecisionId: () -> String,
    ): List<Derived> {
        val parsedBase = AgentProposals.parse(sourceFile, base)
        if (parsedBase.proposals.isEmpty()) return emptyList()
        val present = presentIds(sourceFile, saved)
        val ordered = events.sortedWith(compareBy<JsonObject>({ it.text("observed_at") }, { it.text("event_id") }))
        return parsedBase.proposals.filter { parsedBase.refusalCode(it.id) == null && it.id !in present }.mapNotNull { proposal ->
            val history = ordered.filter { it.text("proposal_id") == proposal.id }
            val undone = hashSetOf<String>()
            val activation = hashMapOf<String, JsonObject>()
            for (event in history) {
                val related = event.text("related_event_id")
                when (event.text("kind")) {
                    "review_undone" -> undone += related
                    "review_redone" -> { undone -= related; activation[related] = event }
                }
            }
            val active = history.filter { it.has("review") && it.text("event_id") !in undone }
            val last = active.lastOrNull() ?: return@mapNotNull null
            // A partial step keeps a wrapper around the rest; a later removal was not a decision.
            val action = when (last.text("kind")) { "accepted" -> "accept"; "rejected" -> "reject"; else -> return@mapNotNull null }
            val activatedBy = activation[last.text("event_id")] ?: last
            val activationId = activatedBy.text("event_id")
            if (activationId.isBlank() || activationId in claimed) return@mapNotNull null
            val previous = active.getOrNull(active.size - 2)
            val partial = previous?.text("kind")?.startsWith("partially_") == true
            val decidedAt = if (activatedBy === last) last.text("decision_at").ifBlank { last.text("observed_at") }
                else activatedBy.text("observed_at")
            Derived(
                ProposalDecisionClaim(newDecisionId(), proposal.id, if (partial) "partial" else action,
                    wrapperSha256(base, proposal), sourceFile, decidedAt.ifBlank { java.time.Instant.now().toString() }),
                activationId,
            )
        }
    }

    /**
     * IDs that a plain text edit removes without a decision: a valid wrapper in [before] that
     * leaves no wrapper and no issue in [after]. Saving lists them as removed outside Thoughts.
     */
    fun removedWithoutDecision(path: String, before: String, after: String): List<String> {
        val parsed = AgentProposals.parse(path, before)
        if (parsed.proposals.isEmpty()) return emptyList()
        val present = presentIds(path, after)
        return parsed.proposals.filter { parsed.refusalCode(it.id) == null && it.id !in present }.map { it.id }
    }

    private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    internal fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * Restore Proposal (section 10.6): puts the markup of a proposal removed outside Thoughts back with
 * an ordinary source edit. Every client computes the same edit (fixtures: decisions.json, restore).
 */
internal object ProposalRestore {
    /** The row's last verified proposal (section 10.3 `snapshot`). */
    data class Snapshot(
        val proposalId: String,
        val sourceFile: String,
        val comment: String,
        val before: String,
        val after: String,
        val createdAt: String = "",
        val conversationId: String = "",
        val conversationUrl: String = "",
    ) {
        companion object {
            fun fromJson(value: JsonObject?): Snapshot? {
                value ?: return null
                fun text(key: String): String? = value.get(key)?.takeIf { it.isJsonPrimitive }?.asString
                return Snapshot(
                    text("proposal_id") ?: return null, text("source_file") ?: return null,
                    text("comment") ?: return null, text("before") ?: return null, text("after") ?: return null,
                    text("created_at").orEmpty(), text("conversation_id").orEmpty(), text("conversation_url").orEmpty(),
                )
            }
        }
    }

    sealed class Result {
        /** [line] is the 1-based line of the restored wrapper; [matched] is after, before or empty. */
        data class Restored(val output: String, val line: Int, val matched: String) : Result()
        data class AlreadyPresent(val line: Int) : Result()
        /** source_missing | id_in_source | ambiguous | text_changed | not_restorable */
        data class Refused(val code: String, val line: Int = 0) : Result()
    }

    fun restore(path: String, text: String?, snapshot: Snapshot, line: Int): Result {
        val id = snapshot.proposalId
        text ?: return Result.Refused("source_missing")
        val parsed = AgentProposals.parse(path, text)
        parsed.proposals.firstOrNull { it.id == id && parsed.refusalCode(id) == null }?.let {
            return Result.AlreadyPresent(lineAt(text, it.start))
        }
        text.indexOf(id).takeIf { it >= 0 }?.let { return Result.Refused("id_in_source", lineAt(text, it)) }
        val format = AgentProposals.format(path) ?: return Result.Refused("not_restorable")
        val blocked = parsed.proposals.map { it.start until it.end } + parsed.issues.map { it.start until it.end }
        data class Candidate(val start: Int, val end: Int, val matched: String)
        val candidates = linkedMapOf<Pair<Int, Int>, Candidate>()
        for ((matched, needle) in listOf("after" to snapshot.after, "before" to snapshot.before)) {
            if (needle.isEmpty()) continue
            var from = text.indexOf(needle)
            while (from >= 0) {
                val end = from + needle.length
                // Whole lines; beside a card marker (section 8.1) a text may also start with the LF
                // that ends the line before it, or end right before its last LF.
                val startsLine = from == 0 || text[from - 1] == '\n' || needle.startsWith('\n')
                val endsLine = end == text.length || text[end - 1] == '\n' || text[end] == '\n'
                val free = blocked.none { from < it.last + 1 && it.first < end }
                if (startsLine && endsLine && free) candidates.getOrPut(from to end) { Candidate(from, end, matched) }
                from = text.indexOf(needle, from + 1)
            }
        }
        if (candidates.isEmpty() && (snapshot.before.isEmpty() || snapshot.after.isEmpty())) {
            // The start of line L (the end of T when T has fewer lines); the end of line L, before its
            // LF, when the other text starts with LF: that wrapper started there (section 8.1).
            var offset = lineStart(text, line)
            if (snapshot.after.ifEmpty { snapshot.before }.startsWith('\n') && line in 1..lineCount(text)) {
                offset = text.indexOf('\n', offset).takeIf { it >= 0 } ?: text.length
            }
            candidates[offset to offset] = Candidate(offset, offset, "empty")
        }
        if (candidates.isEmpty()) return Result.Refused("text_changed")
        val target = line.coerceAtLeast(1)
        val best = candidates.values.groupBy { kotlin.math.abs(lineAt(text, it.start) - target) }.minByOrNull { it.key }!!.value
        if (best.size > 1) return Result.Refused("ambiguous")
        val chosen = best.single()
        val wrapper = write(format, snapshot) ?: return Result.Refused("not_restorable")
        val output = text.substring(0, chosen.start) + wrapper + text.substring(chosen.end)
        val after = AgentProposals.parse(path, output)
        val restored = after.proposals.filter { it.id == id }
        val p = restored.singleOrNull()
        if (p == null || after.refusalCode(id) != null || p.explanation != snapshot.comment || p.original != snapshot.before ||
            p.replacement != snapshot.after || p.createdAt != snapshot.createdAt || p.conversationId != snapshot.conversationId ||
            p.conversationUrl.orEmpty() != snapshot.conversationUrl ||
            introducedIssues(parsed.issues, after.issues)) {
            return Result.Refused("not_restorable")
        }
        return Result.Restored(output, lineAt(output, p.start), chosen.matched)
    }

    /** Section 8 writers, from stored fields: the LaTeX explanation is TeX already (section 3.4). */
    internal fun write(format: AgentProposals.Format, snapshot: Snapshot): String? = when (format) {
        AgentProposals.Format.TEX -> buildString {
            append(AgentProposals.COMMAND)
            if (snapshot.createdAt.isNotEmpty()) {
                append("[created=").append(snapshot.createdAt)
                if (snapshot.conversationId.isNotEmpty()) append(",conversation_id=").append(snapshot.conversationId)
                if (snapshot.conversationUrl.isNotEmpty()) append(",conversation_url=").append(snapshot.conversationUrl)
                append(']')
            }
            append('{').append(snapshot.proposalId).append("}{").append(snapshot.comment).append("}{")
                .append(snapshot.before).append("}{").append(snapshot.after).append('}')
        }
        AgentProposals.Format.MARKDOWN -> if (snapshot.createdAt.isEmpty() || snapshot.conversationId.isEmpty()) null else buildString {
            val replacement = if (snapshot.after.isNotEmpty() && !snapshot.after.endsWith("\n")) snapshot.after + "\n" else snapshot.after
            append("<!-- thoughts:agent-proposal:v2:start\n")
            append("id: ").append(snapshot.proposalId).append('\n')
            append("created_at: ").append(snapshot.createdAt).append('\n')
            append("conversation_id: ").append(snapshot.conversationId).append('\n')
            if (snapshot.conversationUrl.isNotEmpty()) append("conversation_url: ").append(snapshot.conversationUrl).append('\n')
            append("explanation:\n").append(markdownBlock(snapshot.comment))
            append("original_text:\n").append(markdownBlock(snapshot.before))
            append("-->\n").append(replacement)
            append("<!-- thoughts:agent-proposal:v2:end ").append(snapshot.proposalId).append(" -->\n")
        }
    }

    private fun markdownBlock(value: String): String = if (value.isEmpty()) "" else
        value.split('\n').joinToString("") { "| " + it.replace("&", "&amp;").replace("-->", "--&gt;") + "\n" }

    private fun introducedIssues(before: List<AgentProposals.Issue>, after: List<AgentProposals.Issue>): Boolean {
        val earlier = before.groupingBy { it.code to it.id }.eachCount().toMutableMap()
        return after.any { issue ->
            val left = earlier[issue.code to issue.id] ?: 0
            if (left > 0) { earlier[issue.code to issue.id] = left - 1; false } else true
        }
    }

    /** 1-based line of [offset]. */
    fun lineAt(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }

    /** Offset of the start of 1-based [line], or the end of [text] when it has fewer lines. */
    /** Lines of [text]; a final LF does not start another one. */
    private fun lineCount(text: String): Int =
        1 + text.indices.count { text[it] == '\n' && it + 1 < text.length }

    fun lineStart(text: String, line: Int): Int {
        var remaining = line.coerceAtLeast(1) - 1
        if (remaining == 0) return 0
        for (i in text.indices) if (text[i] == '\n' && --remaining == 0) return i + 1
        return text.length
    }
}

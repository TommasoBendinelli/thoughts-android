// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.source.ProposalDecisionClaim
import com.gitlab.mudlej.MjPdfReader.data.source.ProposalDecisionClaims
import com.google.gson.Gson
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.JsonSerializer
import org.tbend.thoughts.sdk.ConflictResolveRequest
import org.tbend.thoughts.sdk.SourceApplyRequest
import org.tbend.thoughts.sdk.SourceApplyResult

/**
 * Carries decision claims (agent-proposal spec section 10.4.2) on one source upload: the
 * `proposal_decisions` member of POST /v1/sources/apply or of the conflict resolution, and the
 * per-claim outcomes of the answer. The SDK request models predate the member, so a client built
 * with [gson] adds it while serializing and reads the outcomes while parsing; everything else
 * (authentication, errors, retries) stays the SDK's.
 */
internal class ProposalDecisionExchange(private val base: Gson, claims: List<ProposalDecisionClaim>) {
    data class Outcome(val decisionId: String, val proposalId: String, val outcome: String)

    private val wire = ProposalDecisionClaims.toJson(claims)

    /** Outcomes of the last parsed answer; empty when the server did not report any. */
    @Volatile var outcomes: List<Outcome> = emptyList()
        private set

    val gson: Gson = base.newBuilder()
        .registerTypeAdapter(SourceApplyRequest::class.java, JsonSerializer<SourceApplyRequest> { value, _, _ -> withClaims(value) })
        .registerTypeAdapter(ConflictResolveRequest::class.java, JsonSerializer<ConflictResolveRequest> { value, _, _ -> withClaims(value) })
        .registerTypeAdapter(SourceApplyResult::class.java, JsonDeserializer { json, _, _ ->
            outcomes = parseOutcomes(json.takeIf { it.isJsonObject }?.asJsonObject)
            base.fromJson(json, SourceApplyResult::class.java)
        })
        .create()

    private fun withClaims(value: Any): JsonObject = base.toJsonTree(value).asJsonObject.apply {
        if (wire.size() > 0) add(ProposalDecisionClaims.REQUEST_MEMBER, wire.deepCopy())
    }

    companion object {
        fun parseOutcomes(answer: JsonObject?): List<Outcome> =
            answer?.get(ProposalDecisionClaims.REQUEST_MEMBER)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
                val value = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                fun text(key: String) = value.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                Outcome(text("decision_id"), text("proposal_id"), text("outcome")).takeIf { it.decisionId.isNotBlank() }
            }.orEmpty()
    }
}

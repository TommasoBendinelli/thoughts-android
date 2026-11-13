// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject

/** The authoritative correspondence already contains the proposal's complete placement. */
internal fun discardRedundantProposalGeometry(artifact: JsonObject) {
    if (artifact.get("review_correspondence_schema_version")?.asInt != 2) return
    val authoritativeKeys = artifact.getAsJsonArray("review_correspondences")
        ?.map { it.asJsonObject.get("review_key")?.asString }.orEmpty().toSet()
    artifact.getAsJsonArray("agent_proposals")?.forEach { element ->
        val proposal = element.asJsonObject
        val id = proposal.get("id")?.asString ?: return@forEach
        // Invalid authoritative records still fail normal validation; never fall
        // back to unverified legacy geometry in place of a rejected proof.
        if ("proposal:$id" in authoritativeKeys) proposal.remove("placement")
    }
}

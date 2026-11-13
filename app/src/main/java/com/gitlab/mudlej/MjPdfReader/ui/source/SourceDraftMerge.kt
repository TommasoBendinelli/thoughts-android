// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import com.gitlab.mudlej.MjPdfReader.data.source.SourceTextMerge
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant

/** Three-way line merge. Ambiguous or excessively large diffs are kept as conflicts, never dropped. */
internal object SourceDraftMerge {
    fun merge(baseText: String, localText: String, currentText: String): String =
        SourceTextMerge.merge(baseText, localText, currentText)

    /** Remove legacy archives; retain only a minimal, temporary recovery for the active merge. */
    fun cleanRecoveries(state: JsonObject, syncedSource: String? = null): Boolean {
        val before = state.get("mergeRecoveries")?.toString()
        val recovery = state.getAsJsonArray("mergeRecoveries")?.lastOrNull()?.asJsonObject
        val keep = recovery?.get("temporaryRecoveryVersion")?.asInt == 1 &&
            !(syncedSource != null && state.get("source").asString == syncedSource &&
                state.get("savedSource").asString == syncedSource &&
                !com.gitlab.mudlej.MjPdfReader.data.source.containsConflictMarkers(syncedSource))
        state.add("mergeRecoveries", JsonArray().apply { if (keep) add(recovery) })
        return before != state.get("mergeRecoveries").toString()
    }

    /** Preserve proposal events while rebasing undo onto the new source. */
    fun reconcile(state: JsonObject, sha256: String, current: String): Boolean {
        val base = state.get("savedSource").asString
        if (state.get("baseSha256").asString == sha256 && base == current) return false
        val local = state.get("source").asString
        val merged = merge(base, local, current)
        cleanRecoveries(state)
        if (local != base && local != current && base != current) {
            val archive = JsonObject().apply {
                addProperty("temporaryRecoveryVersion", 1)
                addProperty("recoveredAt", Instant.now().toString())
                addProperty("source", local)
                addProperty("savedSource", base)
            }
            state.add("mergeRecoveries", JsonArray().apply { add(archive) })
        }
        state.addProperty("baseSha256", sha256)
        state.addProperty("savedSource", current)
        state.addProperty("source", merged)
        state.remove("savingSource")
        state.add("undo", JsonArray().apply {
            if (merged != current) add(ReviewTextEdit.of(current, merged).toJson())
        })
        state.add("redo", JsonArray())
        return true
    }
}

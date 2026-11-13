// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context

/** Prevents a corrupt or unusable delta from being selected again during the same sync re-plan. */
internal class PdfDeltaFallbackStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val legacyPreferences = context.getSharedPreferences("thoughts-pdf-delta-fallback-v1", Context.MODE_PRIVATE)

    /** Old rejections also included network errors, so reconsider each once after upgrading. */
    fun reconsiderLegacyRejection(operationId: String?): Boolean {
        if (operationId == null || isRejected(operationId)) return false
        val rejected = legacyPreferences.getStringSet(REJECTED_OPERATIONS, emptySet()).orEmpty().toMutableSet()
        if (!rejected.remove(operationId)) return false
        legacyPreferences.edit().putStringSet(REJECTED_OPERATIONS, rejected).apply()
        return true
    }

    fun isRejected(operationId: String?): Boolean =
        operationId.orEmpty() in preferences.getStringSet(REJECTED_OPERATIONS, emptySet()).orEmpty()

    fun reject(operationId: String?) {
        val rejected = preferences.getStringSet(REJECTED_OPERATIONS, emptySet()).orEmpty().toMutableSet()
        rejected += operationId.orEmpty()
        preferences.edit().putStringSet(REJECTED_OPERATIONS, rejected).apply()
    }

    fun clear(operationId: String?) {
        val rejected = preferences.getStringSet(REJECTED_OPERATIONS, emptySet()).orEmpty().toMutableSet()
        if (rejected.remove(operationId.orEmpty())) {
            preferences.edit().putStringSet(REJECTED_OPERATIONS, rejected).apply()
        }
    }

    private companion object {
        const val PREFERENCES = "thoughts-pdf-delta-fallback-v2"
        const val REJECTED_OPERATIONS = "rejected_operation_ids"
    }
}

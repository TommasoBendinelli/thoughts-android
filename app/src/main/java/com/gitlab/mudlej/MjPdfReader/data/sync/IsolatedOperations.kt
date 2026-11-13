// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context

/**
 * Outbox operations that are uploaded in a batch of their own, such as the Restore of a "Comment
 * missing" comment: the server answers a refused operation with 409 for its whole batch, and an
 * isolated operation then blocks nothing else. IDs are kept only while their operation is undelivered.
 */
internal class IsolatedOperations(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun ids(): Set<String> = preferences.getStringSet(KEY, emptySet()).orEmpty().toSet()

    /** Adds [operationId] and forgets IDs whose operation is no longer undelivered. */
    @Synchronized
    fun add(operationId: String, undelivered: Set<String>) {
        val kept = ids().filter { it in undelivered }.toMutableSet()
        kept += operationId
        preferences.edit().putStringSet(KEY, kept).commit()
    }

    private companion object {
        const val PREFERENCES = "thoughts-isolated-operations"
        const val KEY = "operation_ids"
    }
}

/**
 * Splits an upload batch so that each isolated operation is sent alone. The order of the device
 * sequence is kept: the operations before an isolated one go first, then it, then the rest.
 */
internal fun <T> isolatedOperationBatches(rows: List<T>, isolated: Set<String>, id: (T) -> String): List<List<T>> {
    if (rows.size <= 1 || rows.none { id(it) in isolated }) return listOf(rows)
    val batches = mutableListOf<List<T>>()
    var current = mutableListOf<T>()
    for (row in rows) {
        if (id(row) in isolated) {
            if (current.isNotEmpty()) batches += current
            batches += listOf(row)
            current = mutableListOf()
        } else {
            current += row
        }
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

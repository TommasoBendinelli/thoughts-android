// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context

/** Conditional-request state for document-scoped Fedora snapshots. */
class DocumentSyncStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun etag(target: String): String? = preferences.getString(key(target), null)
        ?.takeIf { it.matches(SHA256) }

    fun save(target: String, etag: String) {
        require(target.isSafeTarget())
        require(etag.matches(SHA256))
        preferences.edit().putString(key(target), etag).apply()
    }

    private fun key(target: String): String {
        require(target.isSafeTarget())
        return "etag:$target"
    }

    private companion object {
        const val PREFERENCES = "thoughts-document-sync-v1"
        val SHA256 = Regex("^[0-9a-f]{64}$")

        fun String.isSafeTarget(): Boolean =
            endsWith(".pdf", ignoreCase = true) &&
                length > 4 &&
                this == java.io.File(this).name &&
                '/' !in this &&
                '\\' !in this
    }
}

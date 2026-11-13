// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import com.google.gson.JsonParser

internal const val REVIEW_SOURCE_DELETE_CAPABILITY = "review_source_delete_v1"
/** Servers that accept `review_tag_merge` entities; earlier ones would reject the whole batch. */
internal const val REVIEW_TAG_MERGE_CAPABILITY = "review_tag_merge_v1"
/** Servers that accept the `review_tag_groups` record; until then a local edit stays pending. */
internal const val REVIEW_TAG_GROUPS_CAPABILITY = "review_tag_groups_v1"
/**
 * Servers that accept a card update whose source change moves the card's existing markers
 * (Edit card and "Place this card again" on a source-linked card).
 */
internal const val REVIEW_SOURCE_REPLACEMENT_CAPABILITY = "client_review_source_replacement_v1"
internal const val REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED = "review_source_replacement_unsupported"
internal const val REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED_MESSAGE = "Update the server to edit the areas of linked cards"
internal const val REVIEW_SOURCE_UNSUPPORTED = "review_source_delete_unsupported"
internal const val REVIEW_SOURCE_UNSUPPORTED_MESSAGE =
    "Enclosed-text deletion is waiting for a Thoughts server update. Other changes can still sync."

internal fun ThoughtsOutboxEntity.deletesReviewSource(): Boolean =
    runCatching { JsonParser.parseString(payloadJson).asJsonObject.has("delete_source") }.getOrDefault(false)

/** Cache only capabilities learned during an explicit sync; opening a dialog stays offline. */
internal class ReviewSourceCapabilityStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("review-source-capability", Context.MODE_PRIVATE)

    fun record(baseUrl: String, supported: Boolean) {
        preferences.edit().putBoolean(baseUrl.trimEnd('/'), supported).apply()
    }

    /** Carries what was learned about [from] over to [to], the same server at a new address. */
    fun copy(from: String, to: String) {
        val key = from.trimEnd('/')
        if (!preferences.contains(key)) return
        preferences.edit().putBoolean(to.trimEnd('/'), preferences.getBoolean(key, false)).apply()
    }

    fun supported(): Boolean {
        val config = ThoughtsServerSecretStore(appContext).config() ?: return false
        return preferences.getBoolean(config.baseUrl.trimEnd('/'), false)
    }

    fun recordReplacement(baseUrl: String, supported: Boolean) {
        preferences.edit().putBoolean(REPLACEMENT_PREFIX + baseUrl.trimEnd('/'), supported).apply()
    }

    /**
     * Whether the server can move a linked card's markers: false only when the last Sync found it
     * cannot, so editing stays possible before the first Sync (the upload holds the change back).
     */
    fun replacementSupported(): Boolean {
        val config = ThoughtsServerSecretStore(appContext).config() ?: return true
        return preferences.getBoolean(REPLACEMENT_PREFIX + config.baseUrl.trimEnd('/'), true)
    }

    private companion object {
        const val REPLACEMENT_PREFIX = "replacement:"
    }
}

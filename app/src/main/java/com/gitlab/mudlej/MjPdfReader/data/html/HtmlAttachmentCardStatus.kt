// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

import android.content.Context
import android.content.SharedPreferences

/**
 * What this phone last saw of an attachment card's answer: found in the page, or not found in the
 * version of the page shown then ("Answer not found in the current page" in the card list and
 * the card's details). Recorded by a review, after the page state was restored and the page
 * settled; a highlight pass does not decide it (a card on another tab is simply not shown yet).
 */
class HtmlAttachmentCardStatus(private val preferences: SharedPreferences) {
    /** The page version ([attachmentSha256]) in which [cardId]'s answer was not found, or null. */
    fun notFoundIn(cardId: String): String? = preferences.getString(KEY + cardId, null)

    fun isNotFound(cardId: String, currentAttachmentSha256: String? = null): Boolean {
        val sha = notFoundIn(cardId) ?: return false
        return currentAttachmentSha256 == null || sha.equals(currentAttachmentSha256, ignoreCase = true)
    }

    fun record(cardId: String, attachmentSha256: String, found: Boolean) {
        preferences.edit().apply {
            if (found) remove(KEY + cardId) else putString(KEY + cardId, attachmentSha256.lowercase())
        }.apply()
    }

    /**
     * Why the server refused [cardId] (`html_attachment_unknown`: the page version it was made on
     * is not one its PDF links to), or null. The card stays on this phone, unsent.
     */
    fun notSent(cardId: String): String? = preferences.getString(NOT_SENT + cardId, null)

    fun recordNotSent(cardId: String, reason: String) {
        preferences.edit().putString(NOT_SENT + cardId, reason).apply()
    }

    companion object {
        private const val PREFERENCES = "html-attachment-cards"
        private const val KEY = "not-found:"
        private const val NOT_SENT = "not-sent:"

        fun of(context: Context) = HtmlAttachmentCardStatus(
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))
    }
}

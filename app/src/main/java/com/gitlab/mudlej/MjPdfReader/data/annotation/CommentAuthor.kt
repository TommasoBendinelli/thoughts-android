// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerAddress
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore

/**
 * The author name written into new comments, replies and notes (PDF annotations and server payloads).
 *
 * A name saved in Settings always wins. Without one, a phone connected to this build's own server
 * (its public or a legacy host) keeps the name all its earlier comments carry, when the build names
 * one ([OWNER_DEFAULT]); any other phone gets a neutral name instead of somebody else's.
 */
object CommentAuthor {
    const val GENERIC_DEFAULT = "Thoughts user"
    const val MAX_LENGTH = 80

    /**
     * The name earlier comments of this build's own server carry (Gradle property
     * `thoughtsOwnerCommentAuthor`), or null when the build names nobody.
     */
    val OWNER_DEFAULT: String? = normalized(BuildConfig.OWNER_COMMENT_AUTHOR)

    private const val PREFERENCES_NAME = "thoughts_comment_author"
    private const val KEY_NAME = "name"

    private val ownerServerHosts: Set<String> by lazy {
        setOf(ThoughtsServerAddress.PUBLIC_HOST) + ThoughtsServerAddress.LEGACY_HOSTS
    }

    /** The name to write, from the saved name and the configured server address (both optional). */
    fun resolve(
        savedName: String?,
        serverBaseUrl: String?,
        ownerName: String? = OWNER_DEFAULT,
        ownerHosts: Set<String> = ownerServerHosts,
    ): String {
        normalized(savedName)?.let { return it }
        val owner = normalized(ownerName)
        return if (owner != null && ThoughtsServerAddress.hostOf(serverBaseUrl) in ownerHosts) owner else GENERIC_DEFAULT
    }

    /** Trimmed, single-line and length-limited, or null when nothing usable remains. */
    fun normalized(name: String?): String? = name
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_LENGTH)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    fun current(context: Context): String =
        resolve(saved(context), ThoughtsServerSecretStore(context.applicationContext).baseUrl())

    fun saved(context: Context): String? = normalized(
        preferences(context).getString(KEY_NAME, null),
    )

    /** Saves [name]; a blank name returns to the automatic default. */
    fun save(context: Context, name: String?) {
        val value = normalized(name)
        preferences(context).edit().apply {
            if (value == null) remove(KEY_NAME) else putString(KEY_NAME, value)
        }.apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

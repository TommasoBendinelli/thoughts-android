// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.content.Context

/**
 * A build without push (app/build.gradle, thoughtsPush off): no Google library is compiled in, push
 * stays off, and agent notes reach the phone as before push (the inbox checks and Study mode's
 * listener).
 */
internal object PushProvider {
    /** This build has no Firebase Cloud Messaging. */
    const val compiledIn = false

    fun playServicesUsable(context: Context): Boolean = false

    fun ensureFirebase(context: Context, config: PushFirebaseConfig): Boolean = false

    fun token(context: Context, config: PushFirebaseConfig): String = throw IllegalStateException("This build has no push")
}

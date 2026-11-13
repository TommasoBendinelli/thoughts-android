// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.gitlab.mudlej.MjPdfReader.data.source.SourceConflictBundle
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository

/** What the TeX conflict screen loads, saves and discards; called off the main thread. */
internal interface SourceConflictBackend {
    suspend fun load(context: Context, conflictId: String): SourceConflictBundle

    fun saveResolution(context: Context, bundle: SourceConflictBundle, contents: String)

    suspend fun discard(context: Context, conflictId: String, expectedSha256: String): Boolean

    companion object {
        /** Lets UI tests show a fixture conflict without Fedora or a queued phone draft. */
        @VisibleForTesting
        @Volatile
        var testOverride: SourceConflictBackend? = null

        val current: SourceConflictBackend
            get() = testOverride ?: Default
    }

    object Default : SourceConflictBackend {
        override suspend fun load(context: Context, conflictId: String): SourceConflictBundle =
            FedoraRepository(context.applicationContext).loadSourceConflict(conflictId)

        override fun saveResolution(context: Context, bundle: SourceConflictBundle, contents: String) {
            SourceEditQueue(context.applicationContext, scheduleRequests = false)
                .saveConflictResolution(bundle, contents)
        }

        override suspend fun discard(context: Context, conflictId: String, expectedSha256: String): Boolean =
            FedoraRepository(context.applicationContext).discardSourceConflict(conflictId, expectedSha256)
    }
}

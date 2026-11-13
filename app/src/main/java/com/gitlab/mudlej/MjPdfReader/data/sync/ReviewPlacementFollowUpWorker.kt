// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import java.util.concurrent.TimeUnit

/** Fetches card positions the server computes after a Sync switched PDFs. Never uploads. */
class ReviewPlacementFollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { FedoraRepository(applicationContext).followReviewPlacements() }
            .onFailure { Log.w("ThoughtsSync", "Review placement follow-up stopped", it) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "review-placement-follow-up"

        /** PDFs this device activated; right after a switch the library generation may still be settling. */
        internal fun shownPdfSha256ByFilename(): Map<String, String> = SourceLinkedPdfIndex().snapshot().let { index ->
            index.activatedPdfSha256ByFilename.ifEmpty { if (index.libraryAvailable) index.libraryPdfSha256ByFilename else emptyMap() }
        }

        /** Starts only when cards are waiting for positions on PDFs this device already shows. */
        fun enqueueIfWaiting(context: Context) {
            val shown = shownPdfSha256ByFilename()
            val waiting = if (shown.isEmpty()) emptySet() else ReviewStore(context).cardsAwaitingPlacement(shown)
            Log.i("ThoughtsSync", "Review placement follow-up: ${waiting.size} cards waiting on ${shown.size} PDFs")
            if (waiting.isEmpty()) return
            val request = OneTimeWorkRequestBuilder<ReviewPlacementFollowUpWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

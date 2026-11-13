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
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Fetches the PDF Fedora rebuilds after a Sync that only accepted proposals. That Sync ends as soon
 * as the Accepts are delivered (the kept PDF already shows the accepted text); this background Sync
 * starts once the rebuild has had time to run, follows it, and installs the rebuilt PDF.
 */
class AcceptRebuildFollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching {
            ThoughtsSyncScheduler.enqueueExplicitContinuation(applicationContext, inputData.getString(TARGET_PDF))
        }.onFailure { Log.w("ThoughtsSync", "Accept rebuild follow-up could not start", it) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "accept-rebuild-follow-up"
        private const val TARGET_PDF = "target_pdf"
        /** Long enough for the Sync that delivered the Accepts to finish and the rebuild to run. */
        private const val DELAY_SECONDS = 15L

        fun enqueue(context: Context, targetPdfFilename: String?) {
            val request = OneTimeWorkRequestBuilder<AcceptRebuildFollowUpWorker>()
                .setInitialDelay(DELAY_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(TARGET_PDF to targetPdfFilename))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

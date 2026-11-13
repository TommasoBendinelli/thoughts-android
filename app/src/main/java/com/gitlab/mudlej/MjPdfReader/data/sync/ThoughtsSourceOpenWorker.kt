// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal fun sourceOpenWorkName(requestId: String): String {
    require(runCatching { UUID.fromString(requestId) }.isSuccess) {
        "The source request identity is invalid"
    }
    return "thoughts-source-open-$requestId"
}

class ThoughtsSourceOpenWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(INPUT_REQUEST_ID)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: return Result.failure()
        return try {
            FedoraRepository(applicationContext).resolveSourceOpenRequest(requestId)
            SourceEditQueue(applicationContext, scheduleRequests = false).recoverDeliveredRequests()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Focused source-open recovery will retry", error)
            Result.retry()
        }
    }

    companion object {
        private const val INPUT_REQUEST_ID = "request_id"
        private const val TAG = "ThoughtsSourceOpen"

        fun enqueue(context: Context, requestId: String) {
            val workName = sourceOpenWorkName(requestId)
            val request = OneTimeWorkRequestBuilder<ThoughtsSourceOpenWorker>()
                .setInputData(workDataOf(INPUT_REQUEST_ID to requestId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

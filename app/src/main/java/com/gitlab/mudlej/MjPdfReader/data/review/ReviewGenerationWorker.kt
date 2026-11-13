// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Completes persisted work from installations that supported automatic card generation. */
class ReviewGenerationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = Result.success()
}

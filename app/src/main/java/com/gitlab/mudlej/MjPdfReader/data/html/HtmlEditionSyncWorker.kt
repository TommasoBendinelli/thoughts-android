// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.JsonParser
import com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsNetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Brings the HTML editions of the Library's PDFs up to date after a Sync ([HtmlEditionSync]), then
 * tells an open reader ([ACTION_EDITIONS_UPDATED]). Runs on its own, so Sync never waits for HTML.
 *
 * On a metered network only the editions of documents read in the HTML view are downloaded
 * ([HtmlEditionDownloadPolicy]); when others were left, one more run waits for an unmetered network.
 *
 * The stored Library listing's edition states can be older than the server's (an edition finishing
 * does not change the Library generation), so versions still being built are asked for again by
 * follow-up runs with a bounded backoff ([followUpDelaySeconds]), only for editions that would be
 * downloaded then.
 */
class HtmlEditionSyncWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val remote = runCatching { FedoraRepository(applicationContext).htmlEditionRemote() }.getOrNull()
            ?: return@withContext Result.success()
        val index = SourceLinkedPdfIndex(ThoughtsLocalStore.currentPdfs()).snapshot()
        if (!index.libraryAvailable) return@withContext Result.success()
        val listed = listedEditionStates(
            File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), SourceLinkedPdfIndex.GENERATION_FILENAME))
        val documents = index.libraryPdfSha256ByFilename.map { (filename, sha256) ->
            HtmlEditionSync.Document(
                filename, sha256, index.libraryKindsByFilename[filename] != LibraryPdfKind.UNLINKED,
                listed?.get(filename to sha256.lowercase()),
            )
        }
        val htmlKeys = HtmlEditionDownloadPolicy.htmlDocumentKeys(
            applicationContext.getSharedPreferences(HtmlEditionDownloadPolicy.PREFERENCES, Context.MODE_PRIVATE).all)
        val documentIds by lazy { DocumentIdentityStore().documentIdsByFilename() }
        val download = { document: HtmlEditionSync.Document ->
            HtmlEditionDownloadPolicy.downloadAfterSync(
                unmetered = ThoughtsNetworkPolicy.isUnmetered(applicationContext),
                openedInHtml = HtmlEditionDownloadPolicy.openedInHtml(document.filename, documentIds[document.filename], htmlKeys),
            )
        }
        val attempt = inputData.getInt(KEY_FOLLOW_UP, 0)
        val summary = runCatching {
            HtmlEditionSync(HtmlEditionStore.default(applicationContext), remote)
                .run(documents, { isStopped }, download, followUp = attempt > 0)
        }.getOrElse { error ->
            Log.w(TAG, "HTML editions not updated", error)
            return@withContext if (runAttemptCount < 2) Result.retry() else Result.success()
        }
        Log.i(TAG, summary.logLine())
        summary.errors.take(5).forEach { Log.w(TAG, it) }
        if (summary.deferred > 0) runCatching { enqueueOnUnmetered(applicationContext) }
        val delay = followUpDelaySeconds(attempt + 1)
        if (summary.pollable > 0 && delay != null && !isStopped) {
            runCatching { enqueueFollowUp(applicationContext, attempt + 1, delay, replace = attempt == 0) }
        }
        applicationContext.sendBroadcast(Intent(ACTION_EDITIONS_UPDATED).setPackage(applicationContext.packageName))
        Result.success()
    }

    companion object {
        private const val TAG = "ThoughtsHtmlEditions"
        private const val WORK_NAME = "thoughts-html-editions"
        private const val UNMETERED_WORK_NAME = "thoughts-html-editions-unmetered"
        private const val FOLLOW_UP_WORK_NAME = "thoughts-html-editions-follow-up"
        private const val KEY_FOLLOW_UP = "follow_up"
        const val ACTION_EDITIONS_UPDATED = "org.tbend.thoughts.HTML_EDITIONS_UPDATED"

        /** Delays of the follow-up runs after a Sync: 20 s doubling to 10 min, 8 runs (about 40 min). */
        internal fun followUpDelaySeconds(attempt: Int): Long? =
            if (attempt < 1 || attempt > MAX_FOLLOW_UPS) null else minOf(20L shl (attempt - 1), 600L)

        internal const val MAX_FOLLOW_UPS = 8

        /**
         * The edition states the stored Library listing carries per (filename, PDF SHA-256), or null
         * when the listing was stored without them (written before the app knew the fields).
         */
        internal fun listedEditionStates(generation: File): Map<Pair<String, String>, HtmlEditionState>? = runCatching {
            val root = JsonParser.parseString(generation.readText()).asJsonObject
            if (root.get(FedoraRepository.GENERATION_HTML_FIELDS)?.asInt != 1) return@runCatching null
            root.getAsJsonArray("publications").mapNotNull { value ->
                val entry = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val filename = entry.get("filename")?.asString ?: return@mapNotNull null
                val sha = entry.get("sha256")?.asString?.lowercase() ?: return@mapNotNull null
                (filename to sha) to HtmlEditionState.fromJson(entry)
            }.toMap()
        }.getOrNull()

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HtmlEditionSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        /**
         * Asks again for versions still being built. The run after a Sync replaces any older chain; a
         * follow-up run appends its successor (it is still running, so replacing would stop it).
         */
        private fun enqueueFollowUp(context: Context, attempt: Int, delaySeconds: Long, replace: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                FOLLOW_UP_WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<HtmlEditionSyncWorker>()
                    .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                    .setInputData(workDataOf(KEY_FOLLOW_UP to attempt))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        /** The editions a metered run left: downloaded once the phone is on an unmetered network. */
        private fun enqueueOnUnmetered(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNMETERED_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HtmlEditionSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .build(),
            )
        }
    }
}

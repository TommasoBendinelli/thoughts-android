// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object LibraryImportScheduler {
    /** Where "Put in Library" copies a PDF before adding it to the Library on this phone. */
    fun stagingRoot(context: Context): File = File(context.filesDir, "library-imports")

    internal const val INPUT_ID = "library_import_id"
    internal const val INPUT_FILENAME = "library_import_filename"
    internal const val INPUT_SHA256 = "library_import_sha256"
}

/**
 * Earlier versions uploaded an imported PDF with this worker, waiting for Wi-Fi or mobile-data
 * approval. Imports are now added to the Library on the phone and uploaded by the next Sync, so a
 * job left by an earlier version only moves its staged PDF into that queue: no network, no Sync.
 */
class LibraryImportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(LibraryImportScheduler.INPUT_ID)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: return Result.failure()
        val filename = inputData.getString(LibraryImportScheduler.INPUT_FILENAME)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*\\.pdf")) }
            ?: return Result.failure()
        val sha256 = inputData.getString(LibraryImportScheduler.INPUT_SHA256)
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            ?: return Result.failure()
        val payload = File(LibraryImportScheduler.stagingRoot(applicationContext), "$id.pdf")
        if (!payload.isFile) return Result.success()
        return runCatching {
            check(sha256(payload) == sha256) { "The staged PDF failed verification" }
            LibraryMutationStore(applicationContext).importPdf(payload, filename, sha256)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                // Keep the staged PDF: nothing was queued, and it is the only copy of the import.
                Log.e("LibraryImportWorker", "Could not queue an earlier Library import", error)
                Result.failure()
            },
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

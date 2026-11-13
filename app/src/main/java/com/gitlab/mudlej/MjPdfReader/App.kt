// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.WorkManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.usage.AppUsageTracker
import com.gitlab.mudlej.MjPdfReader.data.render.CommentDeliveryScheduler
import com.gitlab.mudlej.MjPdfReader.data.render.PdfWarmCacheWorker
import com.gitlab.mudlej.MjPdfReader.data.render.VerifiedPdfActivationStore
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncTrigger
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.reconcileInterruptedLibraryActivationLocally
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.PrintController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.PdfThemeController
import com.google.android.material.color.DynamicColors
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.dialog
import org.acra.config.httpSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import kotlinx.coroutines.runBlocking

class App : Application() {
    lateinit var usageTracker: AppUsageTracker
        private set

    override fun onCreate() {
        com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log("application onCreate")
        // Sync downloads use 8 parallel HTTPS connections, but each Network's pool kept only the
        // default 5 idle ones, so every batch redid TLS handshakes. Read when a pool is created.
        if (System.getProperty("http.maxConnections") == null) System.setProperty("http.maxConnections", "16")
        DynamicColors.applyToActivitiesIfAvailable(this)
        super.onCreate()
        if (!ACRA.isACRASenderServiceProcess()) {
            ThoughtsLocalStore.install(this)
            com.gitlab.mudlej.MjPdfReader.data.sync.DefaultFolderMigration.install(this)
            com.gitlab.mudlej.MjPdfReader.data.sync.SourceFolderNamesMigration.install(this)
            com.shockwave.pdfium.PdfiumCore.setRemoteDocumentResolver { filename ->
                com.gitlab.mudlej.MjPdfReader.ui.links.installedRemotePdf(ThoughtsLocalStore.currentPdfs(), filename)
            }
            com.gitlab.mudlej.MjPdfReader.data.sync.verifiedFileCache.persistTo(
                java.io.File(noBackupFilesDir, "verified-file-hashes.tsv"))
            ThoughtsSyncScheduler.cancelLegacyReadOnlyRefresh(this)
            // Once: move the production Tailscale address to the public one, keeping the token.
            com.gitlab.mudlej.MjPdfReader.data.sync.PublicServerMigration.startInBackground(this, waitForStartup = true)
            reconcileInterruptedLibraryActivationLocally()
            VerifiedPdfActivationStore(this).pruneInactiveVersions()
            Thread(
                {
                    com.gitlab.mudlej.MjPdfReader.data.StartupReadiness.awaitMaintenanceWindow()
                    runCatching(ThoughtsLocalStore::migrateLegacyProjectionOnce)
                    runCatching(ThoughtsLocalStore::quarantineLegacyPdfCaches)
                    runCatching { runBlocking { com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDrafts.flush(this@App) } }
                    runCatching { com.gitlab.mudlej.MjPdfReader.data.DatabaseCompaction.compactOnce(this@App) }
                    runCatching { com.gitlab.mudlej.MjPdfReader.data.render.PdfWarmCache.deleteLegacyPreviewDirectory(this@App) }
                },
                "mj-private-projection-migration",
            ).start()
            PdfWarmCacheWorker.enqueue(this)
            usageTracker = AppUsageTracker(this).also(AppUsageTracker::start)
            ThoughtsSyncTrigger.install(this)
            registerActivityLifecycleCallbacks(
                com.gitlab.mudlej.MjPdfReader.data.sync.SyncForegroundRecovery(),
            )
            // Agent notes: the live long-poll runs while an activity is started (no work until then).
            com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteInbox.install(this)
            // New proposals and PDFs on Fedora: checked every 20 s while an activity is started.
            com.gitlab.mudlej.MjPdfReader.data.sync.RemoteWorkWatch.install(this)
            Thread(
                {
                    val sourceQueue = SourceEditQueue(this@App, scheduleRequests = false)
                    com.gitlab.mudlej.MjPdfReader.data.StartupReadiness.awaitMaintenanceWindow()
                    runCatching {
                        runBlocking { com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository.resumeInterruptedDefaultFolderMigration(this@App) }
                    }
                    runCatching {
                        runBlocking { com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository.resumeInterruptedSourceFolderNamesMigration(this@App) }
                    }
                    sourceQueue.recoverDeliveredRequests()
                    com.gitlab.mudlej.MjPdfReader.ui.source.ProposalReviewJournal.cleanLegacyJournals(
                        java.io.File(filesDir, "proposal-review"),
                    )
                },
                "thoughts-source-request-recovery",
            ).start()
            val preferences = Preferences(PreferenceManager.getDefaultSharedPreferences(this))
            preferences.removeObsoleteActionIds(setOf(LEGACY_PRIVATE_ACTION_ID))
            AppCompatDelegate.setDefaultNightMode(PdfThemeController.interfaceNightMode(preferences))
            OnlineDocumentStore.removeLegacyPrivateCache(this)
            PrintController.sweepStaging(this)
            CommentDeliveryScheduler.resume(this)
            Thread(
                {
                    androidx.work.WorkManager.getInstance(this@App).cancelAllWorkByTag(
                        com.gitlab.mudlej.MjPdfReader.data.review.ReviewGenerationWorker::class.java.name,
                    )
                    // Settings > Backup and Restore was removed on 2026-10-22: stop its daily job and
                    // delete the pre-import snapshots it kept (they could hold saved PDF passwords).
                    androidx.work.WorkManager.getInstance(this@App).apply {
                        cancelUniqueWork("autoBackupWork")
                        cancelAllWorkByTag("autoBackupWork")
                    }
                    java.io.File(filesDir, "backup-safety").deleteRecursively()
                    runCatching { com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesWorker.schedule(this@App) }
                        .onFailure { Log.w("ThoughtsAgentNotes", "Unable to schedule the agent notes check", it) }
                    runCatching { ThoughtsSyncScheduler.resume(this@App) }
                        .onFailure { error ->
                            Log.e(
                                "ThoughtsSync",
                                "Unable to resume durable Thoughts artifact work",
                                error,
                            )
                        }
                    runCatching {
                        ThoughtsSyncScheduler.reconcileInterruptedOperations(this@App)
                    }.onFailure { error ->
                        Log.e(
                            "ThoughtsSync",
                            "Unable to reconcile interrupted Thoughts sync operations",
                            error,
                        )
                    }
                },
                "thoughts-sync-reconciliation",
            ).start()
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    Thread(
                        { ThoughtsSyncScheduler.reconcileInterruptedOperations(this@App) },
                        "thoughts-sync-reconciliation-delayed",
                    ).start()
                },
                SyncOperationStore.INTERRUPTION_GRACE_MILLIS + 1_000L,
            )
            WorkManager.getInstance(this).cancelUniqueWork("source-working-copy-immediate")
            WorkManager.getInstance(this).cancelUniqueWork("source-working-copy-periodic")
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Agent API (org.tbend.agent): content://<applicationId>.agent and AgentActivity. Registered
        // here, not in onCreate, because Android publishes AgentProvider before onCreate runs.
        com.gitlab.mudlej.MjPdfReader.agent.AgentCommands.register(this)

        // Without a crash-report endpoint (the default: local.properties leaves AU blank) there is
        // nowhere to send a report, so do not offer to send one; Android's own crash dialog applies.
        if (!crashReportingConfigured(getString(R.string.CRASH_REPORT_API_URI))) return

        initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.JSON
            reportContent = listOf(
                ReportField.STACK_TRACE,
                ReportField.APP_VERSION_CODE,
                ReportField.APP_VERSION_NAME,
                ReportField.USER_CRASH_DATE,
                ReportField.PACKAGE_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.PHONE_MODEL,
                ReportField.LOGCAT,
                ReportField.AVAILABLE_MEM_SIZE,
            )
            httpSender {
                uri = getString(R.string.CRASH_REPORT_API_URI)
                httpHeaders = mapOf(
                    "X-Parse-Application-Id" to getString(R.string.CRASH_REPORT_APP_ID),
                    "X-Parse-REST-API-Key" to getString(R.string.CRASH_REPORT_API_KEY)
                )
            }
            dialog {
                title = getString(R.string.send_crash_report_title)
                text = "${getString(R.string.send_crash_report_title)}\n\n${getString(R.string.send_crash_report_message)}"
                positiveButtonText = getString(R.string.send)
                negativeButtonText = getString(R.string.no)
            }
        }
    }

    internal companion object {
        private const val LEGACY_PRIVATE_ACTION_ID = "incognito"

        /** A blank or missing (`null`, from an absent local.properties key) endpoint disables ACRA. */
        fun crashReportingConfigured(uri: String?): Boolean =
            !uri.isNullOrBlank() && uri.trim() != "null"
    }
}

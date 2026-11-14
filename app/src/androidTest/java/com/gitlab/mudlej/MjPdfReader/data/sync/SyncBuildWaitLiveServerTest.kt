// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatteryPromptState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tbend.thoughts.sdk.SyncClient

/**
 * How long a Sync takes when its upload makes Fedora rebuild a PDF, against a live local server on
 * the emulator's host: thoughts-server's Live helper (tests/test_html_edition_phone_e2e.py, as
 * tools/html_edition_live_server.py starts it) with the Project's docs-e2e/tex/source/paper.tex,
 * provisioned like tools/run-isolated-tests.py --server-config; build with -PthoughtsTestServerUrl.
 * A latexmk wrapper on the server's PATH that sleeps first makes the build as slow as Fedora's.
 *
 * The phone edits the LaTeX source and Syncs. Since 13 November 2026 that Sync ends without waiting
 * for the build and RemoteWorkWatch, running while Thoughts is on screen, fetches the rebuilt PDF;
 * before, the Sync waited for the build. Both report "SyncBuildWait" lines with the times: from the
 * start of the upload Sync to its end, and to the new PDF being active on the phone. Screenshots
 * (/data/local/tmp/sync-build-wait-*.png) show the Library and the Sync menu meanwhile.
 */
@RunWith(AndroidJUnit4::class)
class SyncBuildWaitLiveServerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(message: String) = android.util.Log.i("SyncBuildWait", message)

    @Before fun dismissFirstRunScreens() {
        val preferences = com.gitlab.mudlej.MjPdfReader.data.Preferences(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
        preferences.setFirstInstall(false)
        preferences.setShowFeaturesDialog(false)
        preferences.setLastSeenVersionCode(BuildConfig.VERSION_CODE)
        preferences.setHomeTab(com.gitlab.mudlej.MjPdfReader.ui.home.HomeTab.LIBRARY)
        SyncBatteryPromptState(java.io.File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1")).markShown()
    }

    /** One Sync as the Sync button starts it; returns it once final. */
    private fun sync(label: String, timeoutMs: Long = 240_000): SyncOperationRecord {
        val operations = SyncOperationStore(context)
        val operationId = operations.begin(null, false)
        val started = SystemClock.uptimeMillis()
        ThoughtsSyncScheduler.enqueue(context, preparedOperationId = operationId, origin = ExplicitSyncOrigin.BACKGROUND_COMMAND)
        while (SystemClock.uptimeMillis() - started < timeoutMs) {
            val record = operations.operation(operationId)
            if (record != null && isTerminalSyncOperationStatus(record.status)) {
                log("sync $label: ${record.status} in ${SystemClock.uptimeMillis() - started} ms; last event ${record.events.lastOrNull()}")
                assertEquals("Sync $label ended with $record", SyncOperationStore.STATUS_SYNCED, record.status)
                return record
            }
            SystemClock.sleep(250)
        }
        fail("Sync $label not finished after ${timeoutMs / 1000} s")
        error("unreachable")
    }

    private fun client(): SyncClient {
        val config = checkNotNull(ThoughtsServerSecretStore(context).config()) { "no test server provisioned" }
        assertEquals(BuildConfig.TEST_SERVER_URL, config.baseUrl)
        assertTrue("a local test server", config.baseUrl.startsWith("http://10.0.2.2:"))
        return SyncClient(config.baseUrl, config.token)
    }

    private fun activePdf(filename: String): String? {
        val index = SourceLinkedPdfIndex()
        index.invalidate()
        return index.snapshot().activatedPdfSha256ByFilename[filename]
    }

    @Test fun anUploadThatRebuildsAPdf() {
        val client = client()
        sync("first")
        val paper = checkNotNull(client.library())!!.publications.single { it.filename.contains("paper") }
        assertEquals(paper.sha256, activePdf(paper.filename))

        // The phone edits the LaTeX source: its Sync makes Fedora rebuild the PDF.
        val queue = SourceEditQueue(context, scheduleRequests = false)
        val snapshot = checkNotNull(queue.openOfflineSource("docs-e2e/tex/source/paper.tex", 1)?.cachedSnapshot) {
            "paper.tex not offline"
        }
        val edited = snapshot.contents.replace("\\end{document}",
            "An edit made on the phone at ${System.currentTimeMillis()}.\n\\end{document}")
        queue.saveForNextSync(snapshot, paper.filename, paper.sha256, edited)

        // Thoughts on screen, as when the user taps Sync: RemoteWorkWatch runs.
        val home = ActivityScenario.launch<HomeActivity>(Intent(context, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val started = SystemClock.uptimeMillis()
            val upload = sync("upload")
            val uploadMs = SystemClock.uptimeMillis() - started
            log("pending builds after the upload Sync: ${PendingFedoraBuildStore(context).builds()}")
            SystemClock.sleep(2_000)
            // Open the project so its document's status shows ("Updating…" while Fedora builds).
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input tap 96 502").close()
            SystemClock.sleep(1_500)
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("screencap -p /data/local/tmp/sync-build-wait-after-upload.png").close()
            // The Sync menu says what is still building.
            home.onActivity { it.findViewById<android.view.View>(com.gitlab.mudlej.MjPdfReader.R.id.syncStatusButton).performClick() }
            SystemClock.sleep(1_500)
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("screencap -p /data/local/tmp/sync-build-wait-menu.png").close()
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent KEYCODE_BACK").close()
            var active = activePdf(paper.filename)
            while (active == paper.sha256 && SystemClock.uptimeMillis() - started < 240_000) {
                SystemClock.sleep(500)
                active = activePdf(paper.filename)
            }
            val newPdfMs = SystemClock.uptimeMillis() - started
            assertNotEquals("the rebuilt PDF never became active", paper.sha256, active)
            // The Sync the watch started may still be finishing its other steps.
            while (SyncOperationStore(context).pendingOperations().isNotEmpty() && SystemClock.uptimeMillis() - started < 300_000) {
                SystemClock.sleep(250)
            }
            val rebuilt = checkNotNull(client.library())!!.publications.single { it.filename == paper.filename }
            assertEquals(rebuilt.sha256, activePdf(paper.filename))
            log("RESULT upload Sync ${uploadMs} ms (${upload.events.lastOrNull()}); rebuilt PDF active after ${newPdfMs} ms; " +
                "all Syncs done after ${SystemClock.uptimeMillis() - started} ms")
            SystemClock.sleep(1_500)
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("screencap -p /data/local/tmp/sync-build-wait-done.png").close()
        } finally {
            home.close()
        }
    }
}

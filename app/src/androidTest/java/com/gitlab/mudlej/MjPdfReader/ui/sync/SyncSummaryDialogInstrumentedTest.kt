// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import android.content.ClipboardManager
import android.os.SystemClock
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.sync.*
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Private sandbox fixtures, with no server credentials or network requests. */
@RunWith(AndroidJUnit4::class)
class SyncSummaryDialogInstrumentedTest {
    @Before fun dismissFirstRunScreens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = com.gitlab.mudlej.MjPdfReader.data.Preferences(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
        preferences.setFirstInstall(false)
        preferences.setShowFeaturesDialog(false)
        preferences.setLastSeenVersionCode(com.gitlab.mudlej.MjPdfReader.BuildConfig.VERSION_CODE)
        SyncBatteryPromptState(
            File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1"),
        ).markShown()
    }

    @Test fun conciseSummaryCopiesFullOfflineDiagnosticsAndRetryHistoryAfterRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SyncOperationStore(context)
        val marker = "[TEST ${TestRunIdentity.id}] full log tail"
        val fullLog = "LaTeX diagnostic line\n".repeat(800) + marker
        val hash = MessageDigest.getInstance("SHA-256").digest(fullLog.toByteArray()).joinToString("") { "%02x".format(it) }
        val reference = "server-object:$hash"
        LocalBuildLogStore().cache(reference, fullLog.toByteArray())
        val original = store.begin(null, false)
        store.fail(original, "Previous attempt failed", "original-attempt-private-details")
        val id = store.beginRetry(original)
        store.recordBuildProgress(id, listOf(SyncBuildProgress(
            UUID.randomUUID().toString(), "all", "failed", "failed", 0, 2, "PDFs", "renderer exited 1",
            failedDocuments = listOf(SyncProjectFailure("paper.pdf", "latex_error /private/main.tex:246")),
            logReference = reference,
        )), false)
        SyncDiagnosticArchive(context).append(id, "test", "raw diagnostic ".repeat(1500) + marker)
        store.fail(id, SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED, "renderer exited 1 /private/main.tex:246")

        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            fun openSaved() {
                val record = SyncOperationStore(context).operationWithEvents(id)!!
                scenario.onActivity { SyncOperationsDialog(it).showRecord(record) }
                await { assertNotNull(node("Copy diagnostics")) }
                assertTrue(texts().any { it.contains("paper\nA formatting error") })
                assertTrue(texts().any { it.contains("Started: ") && it.contains("\nElapsed: ") &&
                    it.contains("\nUploaded: ") && it.contains("\nDownloaded: ") })
                assertFalse(texts().any { it.contains("renderer exited") })
                assertNull(node("Copy"))
                assertNull(node("View build log"))
                assertNull(node("Close"))
                await { assertNotNull(node("Sync")) }
            }
            openSaved()
            tapOutside()
            await { assertNull(node("Copy diagnostics")) }
            scenario.recreate()
            openSaved()
            click("Copy diagnostics")
            var copied = ""
            await {
                scenario.onActivity {
                    copied = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()
                }
                assertTrue(copied.contains(marker))
            }
            assertTrue(copied.contains(fullLog))
            assertTrue(copied.contains("original-attempt-private-details"))
            assertTrue(copied.contains(original))
            assertTrue(copied.contains(id))
            assertEquals(copied, File(context.filesDir, "sync-diagnostics/$id.txt").readText())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
    }

    @Test fun savedSingleDocumentFailureUsesPdfCountWithoutKnownOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SyncOperationStore(context)
        val id = store.begin(null, false)
        store.recordBuildProgress(id, listOf(SyncBuildProgress(
            UUID.randomUUID().toString(), "all", "failed", "failed", 0, 1, "pdfs",
            "strict review correspondence gate failed: proposal:test-${TestRunIdentity.id}",
            documents = listOf("course-notes.pdf"), failedDocuments = emptyList(),
        )), false)
        store.fail(id, SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED, "pdf_build_failed")
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { SyncOperationsDialog(it).showRecord(store.operationWithEvents(id)!!) }
            await {
                assertNotNull(node("PDF build failed"))
                assertTrue(texts().any { it.contains("Your changes are synced · 1 PDF failed to build.") })
                assertTrue(texts().any { it.contains("course-notes\nA proposed edit could not be matched") })
            }
            assertFalse(texts().any { it.contains("affected project was not recorded") })
            assertFalse(texts().any { it.contains("proposal:test-") })
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
    }

    @Test fun importedFailureNamesOwningProjectAfterRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectId = "test-${TestRunIdentity.id}"
        val state = File(context.noBackupFilesDir, "thoughts-sync/projects/state.json")
        state.parentFile!!.mkdirs()
        state.writeText("""{"pending":[],"projects":[{"id":"$projectId","name":"Models","documents":[
            {"document_id":"doc-test","filename":"import-test.pdf","name":"OLMo 3"}],
            "project_configuration":{"schema_version":5,"kind":"thoughts-project","id":"$projectId",
            "name":"Models","source_folders":[],"documents":[{"document_id":"doc-test","included":true}]}}]}""")
        val store = SyncOperationStore(context)
        val id = store.begin(null, false)
        store.recordBuildProgress(id, listOf(SyncBuildProgress(
            UUID.randomUUID().toString(), "all", "failed", "failed", 0, 1, "pdfs", "error",
            failedDocuments = listOf(SyncProjectFailure("import-test.pdf", "unresolved_tex_link: private source")),
        )), false)
        store.fail(id, SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED, "pdf_build_failed")
        try {
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                scenario.recreate()
                scenario.onActivity { SyncOperationsDialog(it).showRecord(store.operationWithEvents(id)!!) }
                await {
                    assertNotNull(node("PDF build failed"))
                    assertTrue(texts().any { it.contains("Your changes are synced · 1 project failed to build.") })
                    assertTrue(texts().any { it.contains("Models\nDocuments: OLMo 3") })
                }
                assertFalse(texts().any { it.contains("import-test") || it.contains("private source") })
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
        } finally { state.delete() }
    }

    @Test fun syncFollowsAllCurrentRunsWhileViewingAnOlderFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SyncOperationStore(context)
        val target = "test-${TestRunIdentity.id}.pdf"
        val old = store.begin(target, false)
        store.fail(old, "Failed", "Old fixture failure")
        val other = store.begin(null, false)
        // A newer completed run must not hide the older pending run from the button's check.
        val newer = store.begin("test-${TestRunIdentity.id}-newer.pdf", false)
        store.append(newer, SyncOperationStore.STATUS_SYNCED, "Fixture complete")
        val requests = java.util.concurrent.CopyOnWriteArrayList<SyncOperationRecord>()
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            val record = store.operationWithEvents(old)!!
            scenario.onActivity { SyncOperationsDialog(it) { requested -> requests += requested }.showRecord(record) }
            await { assertNotNull(node("Copy diagnostics")) }
            assertNull(node("Close"))
            assertNull(node("Sync"))
            store.append(other, SyncOperationStore.STATUS_SYNCING, "Syncing fixture")
            await { assertNull(node("Sync")) }
            store.append(other, SyncOperationStore.STATUS_SYNCED, "Fixture complete")
            await { assertNotNull(node("Sync")) }
            val startedElsewhere = store.begin(target, false)
            await { assertNull(node("Sync")) }
            store.fail(startedElsewhere, "Failed", "Fixture finished")
            await { assertNotNull(node("Sync")) }
            click("Sync")
            await { assertEquals(1, requests.size) }
            assertEquals(old, requests.single().id)
            assertEquals(target, requests.single().target)
            await { assertNull(node("Copy diagnostics")) }
        }
    }

    @Test fun metricsUpdateWithoutNewEventsAndStopAtCompletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SyncOperationStore(context)
        val id = store.begin("test-${TestRunIdentity.id}.pdf", false)
        val planId = "test-${TestRunIdentity.id}"
        store.recordTransferPlan(id, SyncTransferPlan(
            contractVersion = 1, sourceProfile = "test", includeVoiceMedia = false,
            planId = planId, serverPlanId = planId, serverLibraryRevision = "test",
            remoteObjects = emptyList(), operationId = id, targetPdfFilename = null,
            building = false, uploadBytes = 256, downloadBytes = 2048, thresholdBytes = 4096,
        ))
        store.recordTransferProgress(id, planId, 128, 1024)
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { SyncOperationsDialog(it).showRecord(store.operationWithEvents(id)!!) }
            fun metrics() = texts().firstOrNull { it.startsWith("Started: ") }
            fun elapsed() = metrics()?.lineSequence()?.firstOrNull { it.startsWith("Elapsed: ") }
            fun bytes(value: Long) = Formatter.formatFileSize(context, value)
            await {
                assertTrue(metrics().orEmpty().contains("Uploaded: ${bytes(128)} of ${bytes(256)}"))
                assertTrue(metrics().orEmpty().contains("Downloaded: ${bytes(1024)} of ${bytes(2048)}"))
            }
            val initialElapsed = requireNotNull(elapsed())
            val started = metrics()!!.lineSequence().first()
            await { assertNotEquals(initialElapsed, elapsed()) }
            assertEquals(started, metrics()!!.lineSequence().first())
            store.recordTransferProgress(id, planId, 256, 2048)
            store.append(id, SyncOperationStore.STATUS_SYNCED, "Completed")
            await {
                assertTrue(metrics().orEmpty().contains("Uploaded: ${bytes(256)}\nDownloaded: ${bytes(2048)}"))
                assertNotNull(node("Sync"))
            }
            val finished = requireNotNull(metrics())
            Thread.sleep(2100)
            assertEquals(finished, metrics())
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
    }

    @Test fun manyFailedDocumentsScrollWhileIssueLinksAndButtonsStayReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SyncOperationStore(context)
        val id = store.begin(null, false)
        val last = "last-failed-${TestRunIdentity.id}"
        val failures = (1..24).map { SyncProjectFailure("failed-document-$it.pdf", "latex_error main.tex:$it") } +
            SyncProjectFailure("$last.pdf", "latex_error main.tex:99")
        store.recordBuildProgress(id, listOf(SyncBuildProgress(
            UUID.randomUUID().toString(), "all", "failed", "failed", 0, failures.size, "pdfs", "renderer exited 1",
            failedDocuments = failures,
        )), false)
        store.fail(id, SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED, "pdf_build_failed")
        val requests = java.util.concurrent.CopyOnWriteArrayList<SyncOperationRecord>()
        // A tall phone screen, with a larger font.
        val fontScale = shell("settings get system font_scale").trim()
        shell("wm size 1080x2392")
        shell("wm density 480")
        shell("settings put system font_scale 1.3")
        try {
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                scenario.onActivity {
                    SyncOperationsDialog(it, requestSync = { requested -> requests += requested },
                        loadIssueCounts = { SyncIssueCounts(comments = 3, reviewCards = 1, sourceMerges = 0) },
                    ).showRecord(store.operationWithEvents(id)!!)
                }
                val actions = listOf("Copy diagnostics", "Sync", "3 comments have issues", "1 review card has an issue")
                await {
                    assertNotNull(node("PDF build failed"))
                    assertTrue(texts().any { it.contains(last) })
                    actions.forEach { assertNotNull(it, node(it)) }
                }
                val window = android.graphics.Rect().also {
                    requireNotNull(instrumentation.uiAutomation.rootInActiveWindow).getBoundsInScreen(it)
                }
                actions.forEach { text ->
                    val action = requireNotNull(node(text))
                    val bounds = android.graphics.Rect().also(action::getBoundsInScreen)
                    assertTrue("$text is visible", action.isVisibleToUser)
                    assertTrue("$text $bounds lies inside the dialog $window", window.contains(bounds) && bounds.height() > 0)
                    assertTrue("$text is clickable", action.isClickable && action.isEnabled)
                }
                // The message scrolls instead of growing past the screen.
                val message = requireNotNull(nodes().firstOrNull { it.text?.contains(last) == true })
                val messageBounds = android.graphics.Rect().also(message::getBoundsInScreen)
                val buttonTop = actions.minOf { android.graphics.Rect().also(requireNotNull(node(it))::getBoundsInScreen).top }
                assertTrue("message $messageBounds ends above the actions at $buttonTop", messageBounds.bottom <= buttonTop)
                val scroller = requireNotNull(nodes().firstOrNull { it.isScrollable })
                assertTrue(scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                click("Sync")
                await { assertEquals(1, requests.size) }
                assertEquals(id, requests.single().id)
            }
        } finally {
            shell("wm size reset")
            shell("wm density reset")
            shell("settings put system font_scale ${fontScale.toFloatOrNull() ?: 1f}")
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun tapOutside() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, now, action, 10f, 150f, 0)
            try { ui.injectInputEvent(event, true) } finally { event.recycle() }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { i -> node.getChild(i)?.let(::descendants).orEmpty() }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::descendants).orEmpty()
    }
    private fun node(text: String) = nodes().firstOrNull { it.text?.toString().equals(text, ignoreCase = true) }
    private fun texts() = nodes().mapNotNull { it.text?.toString() }
    private fun click(text: String) {
        assertTrue(requireNotNull(node(text)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun await(check: () -> Unit) {
        var failure: Throwable? = null
        repeat(60) {
            try { check(); return } catch (error: AssertionError) { failure = error }
            Thread.sleep(100)
        }
        throw requireNotNull(failure)
    }
}

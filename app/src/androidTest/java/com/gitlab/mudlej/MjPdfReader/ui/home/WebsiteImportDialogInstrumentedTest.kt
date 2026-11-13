// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.graphics.pdf.PdfDocument
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.importing.PaperWebsite
import com.gitlab.mudlej.MjPdfReader.data.importing.PreparedPaperImport
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatteryPromptState
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebsiteImportDialogInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val title = "Critique-GRPO: Advancing LLM Reasoning with Natural Language and Numerical Feedback"

    @Before fun setup() {
        Preferences(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)).apply {
            setFirstInstall(false); setShowFeaturesDialog(false); setLastSeenVersionCode(BuildConfig.VERSION_CODE)
        }
        SyncBatteryPromptState(File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1")).markShown()
    }

    private fun fixture(paper: PaperWebsite, name: String? = title): PreparedPaperImport {
        val file = File.createTempFile("website-${TestRunIdentity.id}", ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(200, 200, 1).create())
            pdf.finishPage(page)
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return PreparedPaperImport(paper, file, name, 1, file.length())
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun edits(dialog: AlertDialog) = views(dialog.window!!.decorView).filterIsInstance<EditText>()
    private fun texts(dialog: AlertDialog) = views(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun await(check: () -> Unit) {
        var last: Throwable? = null
        repeat(100) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { check() } catch (error: Throwable) { failure = error } }
            if (failure == null) return
            last = failure
            Thread.sleep(50)
        }
        throw AssertionError("Dialog did not reach expected state", last)
    }

    @Test fun urlOnlyThenConfirmationSavesEditedTitleOnceWithoutSync() {
        val root = File(context.cacheDir, "website-store-${TestRunIdentity.id}")
        val store = LibraryProjectStore(context, root)
        val project = JsonObject().apply { addProperty("id", "new:website-${TestRunIdentity.id}"); addProperty("revision", 0) }
        store.enqueue("create", project, JsonObject().apply { addProperty("name", "Website fixture") })
        var file: File? = null
        var preparations = 0
        var saves = 0
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                dialog = WebsiteImportDialog(activity, activity.lifecycleScope, "Website fixture", import = { ready, name ->
                    saves++; store.stageImport(store.projects().single().asJsonObject, ready.file, name)
                }, onImported = {}, prepare = { paper, _ ->
                    preparations++; fixture(paper).also { file = it.file }
                }).show()
                assertEquals(1, edits(dialog).size)
                edits(dialog).single().setText("https://arxiv.org/abs/2506.03106")
                dialog.getButton(-1).performClick()
            }
            await {
                assertEquals("Import", dialog.getButton(-1).text.toString())
                assertTrue(texts(dialog).contains("Website: arXiv"))
                assertEquals(title, edits(dialog).single().text.toString())
                assertTrue(texts(dialog).any { it.contains("File size:") && it.contains("Pages: 1") && it.contains("Destination: Website fixture") })
                assertEquals(1, store.pendingCount())
                edits(dialog).single().setText("Edited paper title")
                dialog.getButton(-1).performClick()
                assertFalse(dialog.getButton(-1).isEnabled)
            }
            await { assertFalse(dialog.isShowing); assertFalse(file!!.exists()) }
            assertEquals(1, preparations); assertEquals(1, saves)
            assertEquals(2, store.pendingCount())
            assertEquals("Edited paper title", store.projects().single().asJsonObject.getAsJsonArray("documents")[0].asJsonObject.get("name").asString)
        }
        root.deleteRecursively()
    }

    @Test fun backAndCancelCleanPreparedDownloadsWithoutSaving() {
        val files = mutableListOf<File>()
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                dialog = WebsiteImportDialog(activity, activity.lifecycleScope, "Fixture", import = { _, _ -> fail("Must not save") },
                    onImported = {}, prepare = { paper, _ -> fixture(paper).also { files.add(it.file) } }).show()
                edits(dialog).single().setText("https://openreview.net/forum?id=abc")
                dialog.getButton(-1).performClick()
            }
            await { assertTrue(texts(dialog).contains("Website: OpenReview")); dialog.getButton(-3).performClick() }
            await {
                assertEquals("Next", dialog.getButton(-1).text.toString())
                assertFalse(files.single().exists())
                assertEquals("https://openreview.net/forum?id=abc", edits(dialog).single().text.toString())
                dialog.getButton(-1).performClick()
            }
            await { assertEquals("Import", dialog.getButton(-1).text.toString()); dialog.getButton(-2).performClick() }
            await { assertFalse(dialog.isShowing); assertEquals(2, files.size); assertTrue(files.none { it.exists() }) }
        }
    }

    @Test fun missingTitleRequiresNameAndFailedSaveCanRetryWithoutDownloadingAgain() {
        var saves = 0
        var preparations = 0
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                dialog = WebsiteImportDialog(activity, activity.lifecycleScope, "Fixture", import = { _, _ ->
                    if (++saves == 1) error("Save fixture failed")
                }, onImported = {}, prepare = { paper, _ -> preparations++; fixture(paper, null) }).show()
                edits(dialog).single().setText("https://arxiv.org/abs/2506.03106")
                dialog.getButton(-1).performClick()
            }
            await {
                assertEquals("Import", dialog.getButton(-1).text.toString())
                assertEquals("", edits(dialog).single().text.toString())
                dialog.getButton(-1).performClick()
                assertNotNull(edits(dialog).single().error); assertEquals(0, saves)
                edits(dialog).single().setText("Manual title"); dialog.getButton(-1).performClick()
            }
            await {
                assertTrue(texts(dialog).contains("Save fixture failed"))
                assertEquals("Manual title", edits(dialog).single().text.toString())
                dialog.getButton(-1).performClick()
            }
            await { assertFalse(dialog.isShowing) }
            assertEquals(1, preparations); assertEquals(2, saves)
        }
    }

    @Test fun cancellationDuringPreparationCannotLeakReturnedFile() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var file: File? = null
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                dialog = WebsiteImportDialog(activity, activity.lifecycleScope, "Fixture", import = { _, _ -> fail("Must not save") },
                    onImported = {}, prepare = { paper, _ ->
                        val ready = fixture(paper).also { file = it.file }
                        entered.countDown(); release.await(10, TimeUnit.SECONDS)
                        ready
                    }).show()
                edits(dialog).single().setText("https://arxiv.org/abs/2506.03106")
                dialog.getButton(-1).performClick()
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                scenario.onActivity { dialog.getButton(-2).performClick() }
            } finally { release.countDown() }
            await { assertFalse(file!!.exists()); assertFalse(dialog.isShowing) }
        }
    }
}

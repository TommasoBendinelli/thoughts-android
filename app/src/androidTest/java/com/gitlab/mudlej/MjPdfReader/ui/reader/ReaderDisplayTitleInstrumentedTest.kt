// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal
import com.gitlab.mudlej.MjPdfReader.data.sync.emptyProjectConfiguration
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardReaderContract
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ReaderDisplayTitleInstrumentedTest {
    @Test fun sharedPdfTitleRefreshesWithoutReloadAndProjectRenameUpdatesOpenTab() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = InstrumentationRegistry.getArguments().getString("isolation_run_id")!!
        File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1").apply {
            parentFile!!.mkdirs(); writeText("shown\n")
        }
        val pdf = File(context.cacheDir, "import-$runId.pdf")
        val document = PdfDocument()
        try {
            for (number in 1..3) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, number).create())
                page.canvas.drawText("Display title fixture $number", 40f, 70f, Paint().apply { textSize = 20f })
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val olmoId = UUID.randomUUID().toString()
        val qwenId = UUID.randomUUID().toString()
        val configuration = emptyProjectConfiguration("titles-$runId", "Models")
        val entries = JsonArray()
        for ((id, name) in listOf(olmoId to "olmo3", qwenId to "qwen3")) {
            configuration.getAsJsonArray("documents").add(JsonObject().apply {
                addProperty("document_id", id); addProperty("included", true); addProperty("format", "latex")
                add("source", JsonObject().apply { add("folder_id", configuration.get("default_folder_id")); addProperty("path", "$name.tex") })
            })
            configuration.getAsJsonObject("document_names").addProperty(id, name)
            entries.add(JsonObject().apply {
                addProperty("document_id", id); addProperty("id", id); addProperty("name", name)
                addProperty("filename", pdf.name); addProperty("local_path", pdf.path); addProperty("start_page", 1)
            })
        }
        val project = JsonObject().apply {
            add("id", configuration.get("id")); addProperty("name", "Models"); addProperty("revision", 1)
            add("project_configuration", configuration); add("documents", entries); add("sources", JsonArray())
        }
        fun installCatalogue() {
            val state = JsonObject().apply {
                add("projects", JsonArray().apply { add(project) }); add("pending", JsonArray())
            }
            val file = File(context.noBackupFilesDir, "thoughts-sync/projects/state.json").apply { parentFile!!.mkdirs() }
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            output.write(state.toString().toByteArray()); atomic.finishWrite(output)
            PendingChangesSignal.notifyChanged()
        }
        installCatalogue()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW; setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_DISPLAY_DOCUMENT_ID, olmoId); putExtra(EXTRA_DISPLAY_TITLE, "olmo3")
            putExtra(ReviewCardReaderContract.EXTRA_PAGE_INDEX, 1)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            fun awaitTitle(expected: String) {
                val deadline = SystemClock.uptimeMillis() + 20000
                var actual: String? = null
                do {
                    scenario.onActivity { activity ->
                        activity.setShowWhenLocked(true); activity.setTurnScreenOn(true)
                        if (activity.findViewById<PDFView>(R.id.pdfView).pageCount > 0 &&
                            activity.findViewById<PDFView>(R.id.pdfView).currentPage == 1)
                            actual = activity.findViewById<TextView>(R.id.openDocumentTabTitle)?.text?.toString()
                    }
                    if (actual == expected) return
                    SystemClock.sleep(50)
                } while (SystemClock.uptimeMillis() < deadline)
                assertEquals(expected, actual)
            }
            awaitTitle("olmo3")
            var token = -1L
            var page = -1
            scenario.onActivity { activity ->
                token = ViewModelProvider(activity)[ReaderViewModel::class.java].currentLoadToken
                val viewer = activity.findViewById<PDFView>(R.id.pdfView)
                viewer.zoomTo(1.4f); page = viewer.currentPage
            }
            configuration.getAsJsonObject("document_names").addProperty(olmoId, "OLMo 3.1")
            installCatalogue() // The same durable catalogue update and signal used after Sync.
            awaitTitle("OLMo 3.1")
            scenario.onActivity { activity ->
                assertEquals(token, ViewModelProvider(activity)[ReaderViewModel::class.java].currentLoadToken)
                val viewer = activity.findViewById<PDFView>(R.id.pdfView)
                assertEquals(page, viewer.currentPage); assertEquals(1.4f, viewer.zoom, 0.01f)
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, Intent(intent).apply {
                    removeExtra(EXTRA_DISPLAY_DOCUMENT_ID)
                    putExtra(EXTRA_DISPLAY_PROJECT_ID, project.get("id").asString)
                    putExtra(EXTRA_DISPLAY_TITLE, "Models")
                })
            }
            awaitTitle("Models")
            LibraryProjectStore(context).enqueue("rename", project, JsonObject().apply { addProperty("name", "Language models") })
            awaitTitle("Language models")
            scenario.onActivity { activity ->
                assertEquals(token, ViewModelProvider(activity)[ReaderViewModel::class.java].currentLoadToken)
            }
        }
    }
}

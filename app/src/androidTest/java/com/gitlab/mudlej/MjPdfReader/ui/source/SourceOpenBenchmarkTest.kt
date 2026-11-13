// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.os.SystemClock
import android.util.Log
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.SourceSelection
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderComposition
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in measurement against an existing offline document; never saves or syncs source edits. */
@RunWith(AndroidJUnit4::class)
class SourceOpenBenchmarkTest {
    @Test
    fun actualReaderActionDisplaysSourceAndCoalescesRepeatedTaps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Phone must be unlocked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val pdf = File(context.filesDir, "thoughts/current-pdfs/course-notes.pdf")
        assertTrue(pdf.isFile)
        val drawn = CountDownLatch(1)
        val opens = AtomicInteger()
        var started = 0L
        var ready = 0L
        var editorActivity: ProposalSourceActivity? = null
        val lifecycle = ActivityLifecycleCallback { activity, stage ->
            if (activity is ProposalSourceActivity && stage == Stage.CREATED) {
                editorActivity = activity
                opens.incrementAndGet()
                val editor = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as WebView
                var checking = false
                editor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (!checking) {
                            checking = true
                            editor.evaluateJavascript("Boolean(window.reviewApp && document.activeElement.value && document.activeElement.value.slice(document.activeElement.selectionStart,document.activeElement.selectionEnd)==='Task definition')") { result ->
                                checking = false
                                if (result == "true") {
                                    ready = SystemClock.elapsedRealtime()
                                    editor.viewTreeObserver.removeOnPreDrawListener(this)
                                    drawn.countDown()
                                }
                            }
                        }
                        return true
                    }
                })
            }
        }
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle) }
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(Uri.fromFile(pdf), "application/pdf")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var reader: ReaderComposition
                lateinit var request: PDFView.HighlightRequest
                val canOpen = ReaderComposition::class.java.getDeclaredMethod("canOpenSource", PDFView.HighlightRequest::class.java)
                    .apply { isAccessible = true }
                val open = ReaderComposition::class.java.getDeclaredMethod("openSourceEditor", PDFView.HighlightRequest::class.java)
                    .apply { isAccessible = true }
                var loaded = false
                val deadline = SystemClock.elapsedRealtime() + 30000
                while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        reader = MainActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }.get(activity) as ReaderComposition
                        val view = activity.findViewById<PDFView>(R.id.pdfView)
                        if (view.pageCount > 0) {
                            request = currentFormulaRequest(view)
                            loaded = canOpen.invoke(reader, request) as Boolean
                            if (loaded) view.jumpTo(request.pageIndex, false)
                        }
                    }
                    if (!loaded) SystemClock.sleep(100)
                }
                assertTrue("Real PDF and source identity are ready", loaded)
                scenario.onActivity {
                    started = SystemClock.elapsedRealtime()
                    open.invoke(reader, request)
                    open.invoke(reader, request)
                }
                assertTrue("Editor text laid out", drawn.await(15, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val editor = checkNotNull(editorActivity).findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as WebView
                    assertTrue(editor.isShown)
                    assertEquals("Rapid taps open only one editor", 1, opens.get())
                }
                Log.i("SourceOpenBenchmark", "action_to_text_layout_ms=${ready - started} editor_count=${opens.get()}")
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "source-open-editor.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                instrumentation.runOnMainSync { editorActivity?.finish() }
            }
        } finally {
            instrumentation.runOnMainSync {
                editorActivity?.finish()
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
            }
        }
    }

    @Test
    fun resolveExistingFormulaSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = File(context.filesDir, "thoughts/current-pdfs/course-notes.pdf")
        val hash = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes()).joinToString("") { "%02x".format(it) }
        lateinit var selection: SourceSelection
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(pdf), "application/pdf")).use { scenario ->
            var ready = false
            repeat(120) {
                if (!ready) {
                    scenario.onActivity { activity ->
                        val view = activity.findViewById<PDFView>(R.id.pdfView)
                        if (view.pageCount > 0) {
                            val request = currentFormulaRequest(view)
                            val rect = request.pdfRects.first()
                            val height = view.getPagePointSize(request.pageIndex)!!.height
                            selection = SourceSelection(pdf.name, hash, request.pageIndex + 1, rect.centerX(), height - rect.centerY(), "Task definition")
                            ready = true
                        }
                    }
                    if (!ready) Thread.sleep(100)
                }
            }
            assertTrue("Current formula selection loaded", ready)
        }
        repeat(2) { iteration ->
            // A fresh store exercises persistence instead of the previous instance's memory cache.
            val store = OfflineSourceBundleStore(context)
            val started = SystemClock.elapsedRealtime()
            val snapshot = checkNotNull(store.resolve(selection))
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue(snapshot.sourceFile.endsWith("simplifications_of_formulas_task.tex"))
            assertTrue(snapshot.contents.contains("Task definition"))
            val digest = MessageDigest.getInstance("SHA-256").digest(snapshot.contents.toByteArray())
                .joinToString("") { "%02x".format(it) }
            Log.i("SourceOpenBenchmark", "iteration=$iteration resolve_ms=$elapsed " +
                "file=${snapshot.sourceFile} line=${snapshot.sourceLine} contents_sha256=$digest")
        }
    }
    private fun currentFormulaRequest(view: PDFView): PDFView.HighlightRequest {
        for (page in 0 until view.pageCount) {
            val index = view.getPageRawText(page + 1).indexOf("Task definition")
            if (index >= 0) {
                val bounds = view.getSearchTextBounds(page, index, "Task definition".length)
                if (bounds.isNotEmpty()) return PDFView.HighlightRequest(page, bounds, "Task definition")
            }
        }
        error("Current PDF must contain Task definition")
    }

}

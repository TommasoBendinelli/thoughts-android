// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LibraryLoadingInstrumentedTest {
    /** Opt-in read-only verification against the installed catalogue and its actual source bundle. */
    @Test fun repeatedLibraryRefreshesAndPdfOpening() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyExistingLibrary") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val samples = mutableListOf<Int>()
        fun sample() { samples.add(Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss) }
        fun screenshot(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), name).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner)
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            lateinit var pane: LibraryProjectPane
            lateinit var index: SourceLinkedPdfIndexSnapshot
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.findViewById<ViewPager2>(R.id.homePager).setCurrentItem(HomeTab.LIBRARY.ordinal, false)
                pane = field(field(activity, "libraryTab")!!, "projectPane") as LibraryProjectPane
            }
            val deadline = SystemClock.uptimeMillis() + 30_000
            var loaded = false
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    loaded = pane.itemCount > 1
                    index = field(activity, "sourceIndexSnapshot") as SourceLinkedPdfIndexSnapshot
                }
                if (!loaded) SystemClock.sleep(100)
            }
            assertTrue("Existing Library must display projects", loaded)
            sample()
            repeat(5) {
                var job: Job? = null
                scenario.onActivity {
                    @Suppress("UNCHECKED_CAST")
                    val items = field(pane, "items") as List<HomeItem>
                    repeat(10) { pane.render(items, index) }
                    job = field(pane, "renderJob") as Job?
                }
                runBlocking { withTimeout(15_000) { job?.join() } }
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertTrue(pane.itemCount > 1) }
                sample()
            }
            SystemClock.sleep(500)
            screenshot("library-crash-fix.png")
        }
        val pdfs = PhoneRenderQueue.defaultCanonicalPdfDirectory().listFiles().orEmpty()
            .filter { it.extension == "pdf" }
        val pdf = pdfs.firstOrNull { it.name == "concepts.pdf" } ?: pdfs.first()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        repeat(3) {
          ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var pages = 0
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (pages == 0 && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    pages = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content)
                        .getChildAt(0)).pdfView.pageCount
                }
                if (pages == 0) SystemClock.sleep(100)
            }
            assertTrue("Real PDF opens after repeated Library refreshes", pages > 0)
            SystemClock.sleep(500)
            sample()
          }
          Runtime.getRuntime().gc()
          SystemClock.sleep(250)
          sample()
        }
        File(context.getExternalFilesDir(null), "library-crash-fix-memory.txt")
            .writeText("PSS KiB: initial Library, five batches of ten refreshes, then three PDF open/close pairs: $samples\n")
    }
}

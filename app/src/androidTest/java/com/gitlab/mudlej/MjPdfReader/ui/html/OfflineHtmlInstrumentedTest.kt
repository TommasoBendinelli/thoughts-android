// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.html

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class OfflineHtmlInstrumentedTest {
    @Test fun downloadedAttachmentOpensInBrowserAndRunsJavascript() = verifyBrowser(false)
    @Test fun desktopFileLinkUsesChosenOfflineCopyAndRunsJavascript() = verifyBrowser(true)

    @Test fun sourcePageOpensInBrowserWithoutPdfBinding() = verifyBrowser(false, true)

    private fun verifyBrowser(localCopy: Boolean, fromSources: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(BuildConfig.IS_TEST_ENVIRONMENT)
        val name = "html-${TestRunIdentity.id}.pdf"
        val path = if (fromSources) "docs-test/tex/source/tools/plot.html" else "docs-test/tex/support/html/plot.html"
        val label = "Calculate ${TestRunIdentity.id}"
        val result = "Result ${TestRunIdentity.id}: 42"
        val ready = "Ready ${TestRunIdentity.id}"
        val html = "<!doctype html><html><meta name=\"viewport\" content=\"width=device-width\"><body><button onclick=\"this.textContent='$result'\">$label</button><p id=\"ready\"></p><script>document.getElementById('ready').textContent='$ready';</script></body></html>".toByteArray()
        val staging = File(context.filesDir, "source-editor/bundle-staging")
        val objects = File(staging, "objects").apply { mkdirs() }
        fun store(raw: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
            File(objects, digest).writeBytes(raw)
            return digest
        }
        val objectId = store(html)
        val pdf = JsonObject().apply {
            addProperty("filename", name); addProperty("pdf_sha256", "a".repeat(64)); addProperty("page_count", 1)
            add("segments", JsonArray().apply { add(JsonObject().apply {
                addProperty("start_page", 1); addProperty("page_count", 1); addProperty("source_available", false)
            }) })
        }
        val manifest = JsonObject().apply {
            addProperty("schema_version", 3); add("sources", JsonArray()); add("pdfs", JsonArray().apply { add(pdf) })
            add("resources", JsonArray().apply { add(JsonObject().apply {
                addProperty("kind", "html_attachment"); addProperty("path", path); addProperty("sha256", objectId)
                addProperty("object", objectId); addProperty("bytes", html.size)
                add("pdf_filenames", JsonArray().apply { add(name) })
            }) })
        }
        val id = store(manifest.toString().toByteArray())
        File(staging, "index.json").writeText("{\"schema_version\":3,\"bundle_id\":\"$id\",\"manifest_object\":\"$id\"}")
        assertNotNull(OfflineSourceBundleStore(context).importLatest())
        staging.deleteRecursively()
        assertArrayEquals(html, OfflineSourceBundleStore(context).htmlAttachment(path, name)?.readBytes())
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
            return null
        }
        fun awaitNode(text: String): AccessibilityNodeInfo? {
            val deadline = SystemClock.uptimeMillis() + 15000
            do {
                find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
                SystemClock.sleep(100)
            } while (SystemClock.uptimeMillis() < deadline)
            return null
        }
        @Suppress("DEPRECATION")
        val wake = (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "ThoughtsTest:OfflineHtml")
        wake.acquire(90000)
        var scenario: androidx.test.core.app.ActivityScenario<com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity>? = null
        fun tap(x: Float, y: Float) {
            val down = SystemClock.uptimeMillis()
            for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to down, android.view.MotionEvent.ACTION_UP to down + 70)) {
                val event = android.view.MotionEvent.obtain(down, time, action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
        }
        try {
            val link = if (localCopy) "file:///Users/example-other/plot.html" else "thoughts-html:$path"
            if (localCopy) LocalHtmlAttachments(File(context.filesDir, "local-html-attachments"))
                .save(name, link, html.inputStream())
            val file = File(context.cacheDir, name).apply { writeBytes(linkedPdf(link)) }
            scenario = androidx.test.core.app.ActivityScenario.launch(
                Intent(context, com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.fromFile(file), "application/pdf")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            var pdfLink: com.shockwave.pdfium.PdfDocument.Link? = null
            val loadDeadline = SystemClock.uptimeMillis() + 15000
            while (pdfLink == null && SystemClock.uptimeMillis() < loadDeadline) {
                scenario!!.onActivity { activity ->
                    val binding = com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding.bind(
                        activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0),
                    )
                    pdfLink = binding.pdfView.getLinks(0).firstOrNull()
                }
                if (pdfLink == null) SystemClock.sleep(100)
            }
            assertEquals(link, pdfLink?.uri)
            scenario!!.onActivity { activity ->
                if (fromSources) {
                    HtmlAttachmentActivity.openSource(activity, path)
                    return@onActivity
                }
                val binding = com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding.bind(
                    activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0),
                )
                val rect = requireNotNull(binding.pdfView.focusOnPdfRect(0, requireNotNull(pdfLink).bounds, binding.pdfView.zoom))
                val down = SystemClock.uptimeMillis()
                for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to down, android.view.MotionEvent.ACTION_UP to down + 70)) {
                    val event = android.view.MotionEvent.obtain(down, time, action, rect.centerX(), rect.centerY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    binding.pdfView.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            val button = awaitNode(label)
            assertNotNull("The default browser must display the downloaded HTML", button)
            assertNotEquals(context.packageName, button!!.packageName.toString())
            assertNotNull("The browser must execute the inline script", awaitNode(ready))
            val bounds = android.graphics.Rect()
            button.getBoundsInScreen(bounds)
            tap(bounds.exactCenterX(), bounds.exactCenterY())
            assertNotNull("Inline JavaScript must run offline", awaitNode(result))
        } finally {
            scenario?.close()
            if (wake.isHeld) wake.release()
            context.stopService(Intent(context, OfflineHtmlService::class.java))
        }
    }
    private fun linkedPdf(uri: String): ByteArray {
        val stream = "BT /F1 18 Tf 30 280 Td (Open offline plot) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R /Annots [6 0 R] >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
            "<< /Type /Annot /Subtype /Link /Rect [25 265 275 305] /Border [0 0 0] /A << /S /URI /URI ($uri) >> >>",
        )
        val out = java.io.ByteArrayOutputStream()
        fun write(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
        write("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body -> val offset = out.size(); write("${index + 1} 0 obj\n$body\nendobj\n"); offset }
        val xref = out.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(java.util.Locale.US, it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

}

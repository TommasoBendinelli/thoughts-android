// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shockwave.pdfium.PdfiumCore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReferenceSyncTexTest {
    @Test fun referenceLookupUsesTextGeometryAcrossCropAndRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "reference-synctex").apply { mkdirs() }
        val assets = instrumentation.context.assets
        val data = File(directory, "fixture.synctex.gz")
        assets.open("source-navigation/fixture.synctex.bin").use { input -> data.outputStream().use(input::copyTo) }
        val output = File(directory, "fixture.pdf").absolutePath
        val core = PdfiumCore(context)
        var cold = 0L
        val warm = mutableListOf<Long>()
        for (name in listOf("fixture.pdf", "crop-0.pdf", "crop-90.pdf", "crop-180.pdf", "crop-270.pdf")) {
            val document = core.newDocument(assets.open("source-navigation/$name").use { it.readBytes() })
            try {
                for (page in 0..1) {
                    core.openPage(document, page)
                    core.openTextPage(document, page)
                    val count = core.textCountChars(document, page)
                    val index = (0 until count).first { core.textRange(document, page, it, 8) == "accuracy" }
                    val box = FloatArray(4)
                    assertTrue(core.tightCharBox(document, page, index, box))
                    val point = core.sourcePoint(document, page, (box[0]+box[2])/2, (box[1]+box[3])/2)!!
                    if (name == "fixture.pdf" && page == 0) {
                        val loaded = java.util.concurrent.CountDownLatch(1)
                        lateinit var view: com.github.barteksc.pdfviewer.PDFView
                        instrumentation.runOnMainSync {
                            view = com.github.barteksc.pdfviewer.PDFView(context, null)
                            view.layout(0,0,720,1280)
                            view.fromBytes(assets.open("source-navigation/fixture.pdf").use { it.readBytes() })
                                .onLoad { loaded.countDown() }.load()
                        }
                        try {
                            assertTrue(loaded.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            instrumentation.runOnMainSync {
                                val bounds = android.graphics.RectF(box[0],box[3],box[2],box[1])
                                val actual = view.getSourcePoint(0,bounds)!!
                                assertEquals(point[0],actual[0],0.01f)
                                assertEquals(point[1],actual[1],0.01f)
                                val text = view.getSourceContext(0,bounds)
                                assertTrue("PDF context: $text",text.contains("accuracy") && text.contains("Diagnosis"))
                                assertFalse(text.contains("Report"))
                            }
                        } finally { instrumentation.runOnMainSync { view.recycle() } }
                    }
                    repeat(20) {
                        val start = SystemClock.elapsedRealtimeNanos()
                        val result = PdfiumCore.referenceSyncTex(output, page+1, point[0], point[1])
                        val elapsed = SystemClock.elapsedRealtimeNanos()-start
                        if (cold == 0L) cold = elapsed else warm.add(elapsed)
                        assertNotNull("$name page $page", result)
                        val first = result.filterNotNull().first()
                        assertEquals("$name page $page: $first", if (page == 0) 3 else 6, first.substringBefore('\t').toInt())
                        assertTrue(first.endsWith("fixture.tex"))
                    }
                }
            } finally { core.closeDocument(document) }
        }
        warm.sort()
        Log.i("ReferenceSyncTexTest", "cold_ms=${cold/1e6} warm_p95_ms=${warm[(warm.size*0.95).toInt()]/1e6}")
        assertTrue("Cached native lookup should stay below 100ms", warm[(warm.size*0.95).toInt()] < 100_000_000)
    }
    @Test fun offlineBundleRetainsNativeMapAndExactBuildRevisionAcrossReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val root = File(context.cacheDir, "native-bundle").apply { mkdirs() }
        val staging = File(root,"staging").apply { mkdirs() }
        val objects = File(staging,"objects").apply { mkdirs() }
        val working = File(root,"working")
        val privateDir = File(root,"private")
        fun put(raw: ByteArray): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
            File(objects,hash).writeBytes(raw)
            return hash
        }
        val pdf = assets.open("source-navigation/fixture.pdf").use { it.readBytes() }
        val source = assets.open("source-navigation/fixture.tex").bufferedReader().use { it.readText() }
        val sourceHash = put(source.toByteArray())
        val rawHash = put(assets.open("source-navigation/fixture.synctex.bin").use { it.readBytes() })
        val pdfHash = put(pdf)
        val sourcePath = "docs-test/tex/source/fixture.tex"
        val manifest = org.json.JSONObject().put("schema_version",3)
            .put("sources", org.json.JSONArray().put(org.json.JSONObject()
                .put("path",sourcePath).put("sha256",sourceHash).put("object",sourceHash).put("bytes",source.toByteArray().size)))
            .put("pdfs",org.json.JSONArray().put(org.json.JSONObject().put("filename","fixture.pdf")
                .put("pdf_sha256",pdfHash).put("page_count",2)
                .put("segments",org.json.JSONArray().put(org.json.JSONObject()
                    .put("start_page",1).put("page_count",2).put("source_available",true)
                    .put("primary_source_file",sourcePath).put("synctex_object",rawHash).put("synctex_sha256",rawHash)
                    .put("inputs",org.json.JSONArray().put(org.json.JSONObject()
                        .put("source_file",sourcePath).put("source_sha256",sourceHash).put("synctex_path","fixture.tex")))))))
        val id = put(manifest.toString().toByteArray())
        File(staging,"index.json").writeText(org.json.JSONObject().put("schema_version",3)
            .put("bundle_id",id).put("manifest_object",id).toString())
        val core = PdfiumCore(context)
        val doc = core.newDocument(pdf)
        val point = try {
            core.openPage(doc,0);core.openTextPage(doc,0)
            val index = (0 until core.textCountChars(doc,0)).first { core.textRange(doc,0,it,8)=="accuracy" }
            val box = FloatArray(4);assertTrue(core.tightCharBox(doc,0,index,box))
            core.sourcePoint(doc,0,(box[0]+box[2])/2,(box[1]+box[3])/2)!!
        } finally { core.closeDocument(doc) }
        val selection = com.gitlab.mudlej.MjPdfReader.data.source.SourceSelection(
            "fixture.pdf",pdfHash,1,point[0],point[1],"accuracy",
            "Diagnosis-only baseline: accuracy and absolute tool counts")
        val store = com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore(staging,working,privateDir)
        assertNotNull(store.importLatest())
        val first = store.resolve(selection)!!
        assertEquals(3,first.sourceLine)
        assertEquals(source,first.navigationBaseContents)
        assertEquals(selection.contextText,first.navigationContext)
        assertTrue(store.writeWorkingContents(sourcePath,"Inserted source line.\n"+source))
        staging.deleteRecursively()
        val reopened = com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore(staging,working,privateDir)
            .resolve(selection)!!
        assertEquals(3,reopened.sourceLine)
        assertEquals(source,reopened.navigationBaseContents)
        assertTrue(reopened.contents.startsWith("Inserted source line."))
    }

}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.webkit.WebView
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProposalSourceReviewInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun js(scenario: ActivityScenario<ProposalSourceActivity>, code: String): String {
        val done=CountDownLatch(1); var result=""
        scenario.onActivity { activity ->
            val web=(activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as WebView
            web.evaluateJavascript(code) { result=it; done.countDown() }
        }
        assertTrue("JavaScript response",done.await(15,TimeUnit.SECONDS))
        return result
    }
    @Test fun realSourceOpensAndFixtureSupportsPartialReviewHistoryAndUndo() {
        val context=instrumentation.targetContext
        val proposal=OfflineSourceBundleStore(context).overviewSourceSnapshot("course-notes.pdf").proposals
            .firstOrNull { it.sourceFile.endsWith("distillation_idea.tex") } ?: error("Real SDPO proposal must be cached")
        ActivityScenario.launch<ProposalSourceActivity>(ProposalSourceActivity.intent(context,proposal.sourceFile,proposal.line,proposal.id)).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            var loaded=false
            repeat(60) {
                if(!loaded) { Thread.sleep(250); loaded=js(scenario,"Boolean(window.reviewApp && reviewApp.getState())")=="true" }
            }
            assertTrue("Real source editor loaded",loaded)
            assertEquals(js(scenario,"document.body.innerText.slice(0,5000)"), "true", js(scenario,"Boolean(document.querySelector('.reviewActions'))"))
            assertEquals("true",js(scenario,"reviewApp.getState().filename.endsWith('distillation_idea.tex')"))
            assertEquals("true",js(scenario,"document.documentElement.scrollWidth <= innerWidth + 1"))
            var positioned = false
            repeat(80) {
                if (!positioned) {
                    positioned = js(scenario,"Boolean(document.querySelector('.proposal') && document.querySelector('.proposal').getBoundingClientRect().top >= 0 && document.querySelector('.proposal').getBoundingClientRect().top < innerHeight)") == "true"
                    if (!positioned) Thread.sleep(100)
                }
            }
            assertTrue("Requested proposal is visible", positioned)
            Thread.sleep(300) // Allow the Android compositor to display the completed WebView frame.
            renderScreenshot(scenario,"proposal-source-real-render.png")
            screenshot("proposal-source-real.png")
            // Fixture interactions use the exact bundled UI, but cannot publish or mutate real TeX.
            js(scenario,"""window.originalNative=Native; window.Native={
                sha256:t=>originalNative.sha256(t), uuid:()=>originalNative.uuid(),
                persist:s=>'{}', close:()=>{},
                save:s=>{const state=JSON.parse(s);state.savedSource=state.source;return JSON.stringify({state});}
            }; reviewApp.initialize({proposalId:'phone-review-test',state:{filename:'review-fixture.tex',baseSha256:'fixture',
                source:'Header.\n\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{phone-review-test}{Clarify the result.}{}{Takes 4 minutes.\nNext sentence.}\nFooter.',
                savedSource:'', events:[], undo:[], redo:[]}}); true""")
            assertEquals("true",js(scenario,"""(()=>{const t=document.querySelector('textarea[data-kind="new"]');
                t.value=t.value.replace('4 minutes','3 minutes');t.dispatchEvent(new Event('input'));
                t.setSelectionRange(0,16);t.dispatchEvent(new Event('select'));
                [...document.querySelectorAll('button')].find(b=>b.textContent==='Accept').click();
                return reviewApp.getState().source.includes('Header.\nTakes 3 minutes.\n\\agentProposedEdit');})()"""))
            assertEquals("true",js(scenario,"""(()=>{const e=reviewApp.getState().events.find(e=>e.kind==='partially_accepted');
                return e.review.differs_from_first_recorded===true && e.review.original_corresponding_text==='Takes 4 minutes.\n' && e.review.remaining.after==='Next sentence.';})()"""))
            js(scenario,"document.getElementById('undo').click()")
            assertEquals("true",js(scenario,"reviewApp.getState().source.includes('{}{Takes 3 minutes.')"))
            js(scenario,"document.getElementById('redo').click()")
            assertEquals("true",js(scenario,"reviewApp.getState().events.at(-1).kind==='review_redone'"))
            js(scenario,"document.getElementById('history').click()")
            assertEquals("true",js(scenario,"document.getElementById('historyPanel').innerText.includes('Edited before review.')"))
            screenshot("proposal-source-history.png")
            js(scenario,"reviewApp.render()")
            renderScreenshot(scenario,"proposal-source-partial-render.png")
            screenshot("proposal-source-partial.png")
            // Avoid flushing the fixture through the real bridge during Activity teardown.
        }
    }
    @Test fun realPdfShowsExactTexAndSourceEntryWithoutMakingDecisions() {
        val context = instrumentation.targetContext
        val file = File(context.filesDir, "thoughts/current-pdfs/course-notes.pdf")
        assertTrue(file.isFile)
        val proposal = OfflineSourceBundleStore(context).overviewSourceSnapshot(file.name).proposals
            .first { it.sourceFile.endsWith("distillation_idea.tex") }
        val intent = android.content.Intent(context, com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity::class.java)
            .setAction(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.fromFile(file), "application/pdf")
        ActivityScenario.launch<com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity>(intent).use { scenario ->
            var loaded = false
            repeat(120) {
                if (!loaded) {
                    scenario.onActivity { loaded = it.findViewById<com.github.barteksc.pdfviewer.PDFView>(com.gitlab.mudlej.MjPdfReader.R.id.pdfView).pageCount > 0 }
                    if (!loaded) Thread.sleep(100)
                }
            }
            assertTrue("Real PDF pages loaded", loaded)
            scenario.onActivity { activity ->
                val reader = activity.javaClass.getDeclaredField("reader").apply { isAccessible = true }.get(activity)
                    as com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderComposition
                reader.readerNavigationController.showAgentProposal(proposal.id)
            }
            var raw = ""
            repeat(120) {
                if (!raw.contains("− ")) {
                    scenario.onActivity { raw = it.findViewById<android.widget.TextView>(com.gitlab.mudlej.MjPdfReader.R.id.commentsTexDiff)?.text?.toString().orEmpty() }
                    if (!raw.contains("− ")) Thread.sleep(100)
                }
            }
            assertTrue("Exact local TeX loaded: $raw", raw.contains("− "))
            scenario.onActivity { activity ->
                val button = activity.findViewById<android.widget.Button>(com.gitlab.mudlej.MjPdfReader.R.id.commentsEditSource)
                assertTrue(button.isEnabled)
                assertEquals("Edit source ↗", button.text.toString())
            }
        }
    }

    private fun renderScreenshot(scenario: ActivityScenario<ProposalSourceActivity>, name: String) {
        scenario.onActivity { activity ->
            val web=(activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as WebView
            val bitmap=android.graphics.Bitmap.createBitmap(web.width,web.height,android.graphics.Bitmap.Config.ARGB_8888)
            web.draw(android.graphics.Canvas(bitmap))
            File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            };bitmap.recycle()
        }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            };bitmap.recycle()
        }
    }
    @Test fun journalPersistsUndoAndRecoversInterruptedSourceSaveWithoutRewritingHistory() {
        val root=File(instrumentation.targetContext.cacheDir,"proposal-journal-test-${System.nanoTime()}")
        try {
            val journal=ProposalReviewJournal(root,"fixture.tex")
            val state=JsonParser.parseString("""{"filename":"fixture.tex","baseSha256":"base","source":"Accepted.","savedSource":"Before.","events":[],"undo":[{"before":"Before.","after":"Accepted.","event":null}],"redo":[]}""").asJsonObject
            journal.prepareSave(state)
            val reopened=journal.read()!!
            journal.recoverSave(reopened,"Accepted.")
            assertEquals("Accepted.",journal.read()!!.get("savedSource").asString)
            assertEquals(1,journal.read()!!.getAsJsonArray("undo").size())
            assertFalse(journal.read()!!.has("savingSource"))
        } finally {root.deleteRecursively()}
    }
}

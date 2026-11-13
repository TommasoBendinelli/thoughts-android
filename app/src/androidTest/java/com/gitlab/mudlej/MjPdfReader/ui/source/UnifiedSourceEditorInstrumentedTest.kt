// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.os.Bundle
import android.os.Parcel
import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.WebView
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.SourceSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Disposable sources only. No Sync, server mutation, or real document decision. */
@RunWith(AndroidJUnit4::class)
class UnifiedSourceEditorInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun js(scenario: ActivityScenario<ProposalSourceActivity>, code: String): String {
        val latch = CountDownLatch(1); var result = ""
        scenario.onActivity { activity ->
            val child = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            check(child is WebView) { "Editor failed to load: $child" }
            child.evaluateJavascript(code) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS)); return result
    }
    private fun loaded(scenario: ActivityScenario<ProposalSourceActivity>) {
        for (i in 0..100) {
            if (js(scenario, "Boolean(window.reviewApp && reviewApp.getState() && reviewApp.isReady())") == "true") return
            Thread.sleep(100)
        }
        fail("Unified editor did not load")
    }
    private fun fixture(contents: String = "Before source.\nAfter source.", selected: String = "", run: (ActivityScenario<ProposalSourceActivity>, SourceEditQueue, SourceSnapshot) -> Unit) {
        val context = instrumentation.targetContext
        val name = "unified-test-${UUID.randomUUID()}"
        val key = ProposalSourceActivity.digest(name)
        val source = "$name.tex"
        val queue = SourceEditQueue(context, scheduleRequests = false)
        val snapshot = SourceSnapshot(UUID.randomUUID().toString(), source, 1, ProposalSourceActivity.digest(contents), contents, navigationBaseContents = contents)
        queue.cacheSnapshot(key, snapshot)
        val intent = SourceEditorActivity.intent(context, "", key, "$name.pdf", "a".repeat(64), selected)
        assertEquals(ProposalSourceActivity::class.java.name, intent.component!!.className)
        try {
            ActivityScenario.launch<ProposalSourceActivity>(intent).use { scenario -> loaded(scenario); run(scenario, queue, snapshot) }
            instrumentation.waitForIdleSync()
        } finally {
            queue.discardLocalChanges(snapshot)
            File(context.filesDir, "source-editor/snapshots/$key.json").delete()
            File(context.filesDir, "proposal-review/${ProposalSourceActivity.digest(source)}.json").delete()
        }
    }
    @Test fun syncReconcilesDirtyDraftAndOpensConflictsInEditableSource() = fixture("one\ntwo\nthree\n") { scenario, queue, snapshot ->
        val key = ProposalSourceActivity.digest(snapshot.sourceFile.removeSuffix(".tex"))
        js(scenario, "reviewApp.engine().edit('LOCAL\\ntwo\\nthree\\n');reviewApp.flush();true")
        val synced = snapshot.copy(contents = "one\ntwo\nSYNC\n", sourceSha256 = ProposalSourceActivity.digest("one\ntwo\nSYNC\n"))
        queue.cacheSnapshot(key, synced)
        scenario.recreate(); loaded(scenario)
        assertEquals("true", js(scenario, "reviewApp.getState().source==='LOCAL\\ntwo\\nSYNC\\n'"))
        assertEquals("true", js(scenario, "reviewApp.getState().mergeRecoveries.length===1"))
        scenario.recreate(); loaded(scenario)
        assertEquals("true", js(scenario, "reviewApp.getState().mergeRecoveries.length===1"))
        val competing = synced.copy(contents = "REMOTE\ntwo\nSYNC\n", sourceSha256 = ProposalSourceActivity.digest("REMOTE\ntwo\nSYNC\n"))
        queue.cacheSnapshot(key, competing)
        scenario.recreate(); loaded(scenario)
        assertEquals("true", js(scenario, "reviewApp.getState().source.includes('<<<<<<< Your draft')"))
        assertEquals("true", js(scenario, "!document.getElementById('mergeMessage').hidden && document.getElementById('save').disabled"))
        assertEquals("true", js(scenario, "[...document.querySelectorAll('textarea')].some(t=>!t.readOnly&&t.value.includes('<<<<<<< Your draft'))"))
        assertEquals("true", js(scenario, "JSON.parse(Native.save(JSON.stringify(reviewApp.getState()))).error.includes('Resolve the conflict')"))
        js(scenario, "reviewApp.engine().edit('RESOLVED\\ntwo\\nSYNC\\n');reviewApp.render();reviewApp.save(false);true")
        assertEquals("RESOLVED\ntwo\nSYNC\n", queue.localDraftContents(competing))
        assertEquals("true", js(scenario, "reviewApp.getState().source===reviewApp.getState().savedSource"))
        // Recovery records survive saving and reopening.
        scenario.recreate(); loaded(scenario)
        assertEquals("true", js(scenario, "reviewApp.getState().mergeRecoveries.length===1"))
    }

    @Test fun syncDuringOpenEditorReconcilesOnSaveWithoutClosingOrPublishingConflict() = fixture("one\ntwo\n") { scenario, queue, snapshot ->
        val key = ProposalSourceActivity.digest(snapshot.sourceFile.removeSuffix(".tex"))
        js(scenario, "reviewApp.engine().edit('LOCAL\\ntwo\\n');reviewApp.flush();true")
        val current = snapshot.copy(contents="REMOTE\ntwo\n",sourceSha256=ProposalSourceActivity.digest("REMOTE\ntwo\n"))
        queue.cacheSnapshot(key,current)
        js(scenario,"reviewApp.save(true);true")
        assertEquals("true",js(scenario,"reviewApp.getState().source.includes('<<<<<<< Your draft')"))
        assertNull(queue.localDraftContents(current))
        assertEquals("true",js(scenario,"document.getElementById('save').disabled"))
    }

    private fun reviewFixture(scenario: ActivityScenario<ProposalSourceActivity>) {
        js(scenario, """window.realBridge=Native;window.Native={
          sha256:t=>realBridge.sha256(t),uuid:()=>realBridge.uuid(),persist:s=>'{}',close:()=>{},requestClose:()=>{},
          save:s=>{const state=JSON.parse(s);state.savedSource=state.source;return JSON.stringify({state});}
        };reviewApp.initialize({proposalId:'unified-review',state:{filename:'fixture.tex',baseSha256:'fixture',
          source:'Before.\n\\agentProposedEdit[created=2026-09-12T12:00:00Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{unified-review}{Clarify.}{Old first.\nOld rest.}{New first.\nNew rest.}\nAfter.',
          savedSource:'',events:[],undo:[],redo:[]}});true""")
        instrumentation.waitForIdleSync()
    }
    private fun assertProposalGlyphsVisible(scenario: ActivityScenario<ProposalSourceActivity>, label: String) {
        val painted = CountDownLatch(1)
        scenario.onActivity { activity ->
            val web = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as WebView
            web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { web.invalidate(); painted.countDown() }
            })
        }
        assertTrue("WebView presented the current document", painted.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        Thread.sleep(150) // Allow WebView's compositor to present the focus/selection change.
        val rect = JSONObject(js(scenario, """(()=>{const t=document.querySelector('textarea[data-kind="new"]'),r=t.getBoundingClientRect();
          return {x:r.left+12,y:r.top+9,width:r.width-24,height:20,viewport:innerWidth,
            ink:getComputedStyle(document.body).color.match(/\d+/g).map(Number)};})()"""))
        var left = 0; var top = 0; var scale = 1.0
        scenario.onActivity { activity ->
            val web = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as WebView
            val location = IntArray(2); web.getLocationOnScreen(location)
            left = location[0] + web.paddingLeft; top = location[1] + web.paddingTop
            scale = (web.width - web.paddingLeft - web.paddingRight) / rect.getDouble("viewport")
        }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val x = left + (rect.getDouble("x") * scale).toInt()
        val y = top + (rect.getDouble("y") * scale).toInt()
        val width = (rect.getDouble("width") * scale).toInt()
        val height = (rect.getDouble("height") * scale).toInt()
        assertTrue("Proposal is on screen: $label", x >= 0 && y >= 0 && x + width <= screenshot.width && y + height <= screenshot.height)
        val ink = rect.getJSONArray("ink")
        var letterPixels = 0
        for (py in y until y + height) for (px in x until x + width) {
            val color = screenshot.getPixel(px, py)
            if (kotlin.math.abs(Color.red(color) - ink.getInt(0)) < 25 &&
                kotlin.math.abs(Color.green(color) - ink.getInt(1)) < 25 &&
                kotlin.math.abs(Color.blue(color) - ink.getInt(2)) < 25) letterPixels++
        }
        // Disposable fixture screenshots are removed with the sandbox by the test runner.
        File(instrumentation.targetContext.getExternalFilesDir(null), "source-visibility-$label.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        assertTrue("Letters must be painted, not only diff backgrounds ($label): $letterPixels", letterPixels > 60)
    }
    @Test fun proposalTextSurvivesSelectionKeyboardDismissalAndBlur() = fixture { scenario, _, _ ->
        reviewFixture(scenario)
        assertProposalGlyphsVisible(scenario, "unfocused")
        js(scenario, """window.proposed=document.querySelector('textarea[data-kind="new"]');proposed.focus();proposed.setSelectionRange(0,10);proposed.dispatchEvent(new Event('select'));true""")
        assertEquals("true", js(scenario, "document.querySelector('.reviewActions').classList.contains('floating')"))
        assertProposalGlyphsVisible(scenario, "selected")
        scenario.onActivity { activity ->
            val web = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as WebView
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        assertProposalGlyphsVisible(scenario, "keyboard-shown")
        scenario.onActivity { activity ->
            val web = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as WebView
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(web.windowToken, 0)
        }
        assertProposalGlyphsVisible(scenario, "keyboard-hidden")
        js(scenario, "document.querySelector('button[aria-label=\"Why this change?\"]').focus();true")
        assertProposalGlyphsVisible(scenario, "blurred")
        assertEquals("Text rendering must not change proposal bytes", "true", js(scenario, "reviewApp.getState().source.includes('{New first.\\nNew rest.}')"))
    }
    @Test fun compactEditorPreservesInitialPdfSelectionAndWrapping() = fixture(
        "\\section{Introduction}\n" + "Readable source text with a long sentence and normal whitespace. ".repeat(80) + "\nTarget passage.", "Target passage.",
    ) { scenario, _, _ ->
        Thread.sleep(300)
        assertEquals(js(scenario, "JSON.stringify({tag:document.activeElement.tagName,start:document.activeElement.selectionStart,end:document.activeElement.selectionEnd,text:document.activeElement.value?.slice(-100)})"), "true", js(scenario, "document.activeElement.value.slice(document.activeElement.selectionStart,document.activeElement.selectionEnd)==='Target passage.'"))
        assertEquals("true", js(scenario, "document.documentElement.scrollWidth<=innerWidth+1"))
        assertEquals("true", js(scenario, "document.querySelector('header').offsetHeight<135"))
        assertEquals("true", js(scenario, "!document.getElementById('raw')&&!document.getElementById('sourceTab')"))
        assertEquals("true", js(scenario, "getComputedStyle(document.activeElement).fontFamily.includes('sans-serif')"))
        assertEquals("true", js(scenario, "document.activeElement.spellcheck"))
    }
    @Test fun pdfNavigationShowsKeyboardAndTypingReplacesSelection() = fixture(
        "Before. Target passage. After.", "Target passage.",
    ) { scenario, _, _ ->
        var visible = false
        for (attempt in 0..50) {
            scenario.onActivity { activity ->
                visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            if (visible) break
            Thread.sleep(100)
        }
        assertTrue("PDF navigation must open the IME", visible)
        instrumentation.sendStringSync("Replacement.")
        assertEquals("true", js(scenario, "document.activeElement.value==='Before. Replacement. After.'"))
        scenario.onActivity { activity ->
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        Thread.sleep(400)
        js(scenario, "Native.requestKeyboard();true")
        Thread.sleep(400)
        scenario.onActivity { activity ->
            assertFalse("Consumed request must not reopen a dismissed keyboard",
                androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
        }
    }
    @Test fun sourceEditsHideAndInlineRawPreserveProposalBytes() = fixture { scenario, _, _ ->
        reviewFixture(scenario)
        assertEquals("true", js(scenario, """(()=>{const t=document.querySelector('textarea[data-kind="context"]');t.value='Longer before.\n';t.dispatchEvent(new Event('input'));
          return reviewApp.getState().source.startsWith('Longer before.\n\\agentProposedEdit[created=');})()"""))
        js(scenario, "window.beforeHide=reviewApp.getState().source;document.getElementById('visibility').click()")
        assertEquals("true", js(scenario, "reviewApp.getState().source===beforeHide && document.querySelector('.originalHidden').textContent==='Old first.\\nOld rest.' && !document.querySelector('.proposal')"))
        js(scenario, "document.getElementById('visibility').click();document.querySelector('button[aria-label=\"Show raw proposal\"]').click()")
        assertEquals("true", js(scenario, "Boolean(document.querySelector('.rawPanel textarea')) && !document.querySelector('textarea[data-kind=\"new\"]')"))
        assertEquals("true", js(scenario, """(()=>{const t=document.querySelector('.rawPanel textarea');t.value=t.value.replace('New rest.','Revised rest.');t.dispatchEvent(new Event('input'));
          document.querySelector('button[aria-label="Show raw proposal"]').click();return reviewApp.getState().source.includes('Revised rest.')&&document.querySelector('textarea[data-kind="new"]').value.includes('Revised rest.');})()"""))
    }
    @Test fun naturalSelectionsKeepCorrespondenceHistoryAndUndo() = fixture { scenario, _, _ ->
        reviewFixture(scenario)
        assertEquals("true", js(scenario, """(()=>{for(const role of ['old','new']){const t=document.querySelector('textarea[data-kind="'+role+'"]');t.setSelectionRange(0,10);t.dispatchEvent(new Event('select'));}
          document.querySelector('.reviewActions button').click();return reviewApp.getState().source.includes('New first.\n\\agentProposedEdit[created=2026-09-12T12:00:00Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{unified-review}{Clarify.}{Old rest.}{New rest.}');})()"""))
        assertEquals("true", js(scenario, "reviewApp.getState().events.some(e=>e.kind==='partially_accepted'&&e.review.remaining.after==='New rest.')"))
        js(scenario, "document.getElementById('undo').click()")
        assertEquals("true", js(scenario, "reviewApp.getState().source.includes('{Old first.\\nOld rest.}{New first.\\nNew rest.}')"))
        js(scenario, "document.getElementById('redo').click();document.getElementById('history').click()")
        assertEquals("true", js(scenario, "document.getElementById('historyPanel').innerText.includes('New first.')"))
    }
    @Test fun largeUnsavedDraftRecoversWithoutLargeActivityBundle() = fixture("A normal source paragraph with café and TeX \\emph{commands}.\n".repeat(5000)) { scenario, _, _ ->
        js(scenario, "const t=document.querySelector('textarea');t.value='UNSAVED '+t.value;t.dispatchEvent(new Event('input'));reviewApp.flush();true")
        scenario.onActivity { activity ->
            val state = Bundle(); instrumentation.callActivityOnSaveInstanceState(activity, state)
            val parcel = Parcel.obtain()
            try { parcel.writeBundle(state); assertTrue("Activity bundle stays small", parcel.dataSize() < 32000) } finally { parcel.recycle() }
        }
        scenario.recreate(); loaded(scenario)
        assertEquals("true", js(scenario, "reviewApp.getState().source.startsWith('UNSAVED ')"))
        js(scenario, "document.getElementById('undo').click()")
        assertEquals(js(scenario, "JSON.stringify({prefix:reviewApp.getState().source.slice(0,40),undo:reviewApp.getState().undo.length,redo:reviewApp.getState().redo.length})"), "true", js(scenario, "!reviewApp.getState().source.startsWith('UNSAVED ')"))
    }
    @Test fun saveFailureIsVisibleWhileScrolledAndKeepsDraft() = fixture("Source paragraph.\n".repeat(200)) { scenario, _, snapshot ->
        js(scenario, "reviewApp.engine().edit(reviewApp.getState().source+'Unsaved edit.');reviewApp.render();reviewApp.flush();true")
        instrumentation.waitForIdleSync()
        assertEquals("true", js(scenario, "window.scrollTo(0,document.body.scrollHeight);scrollY>100"))
        val key = ProposalSourceActivity.digest(snapshot.sourceFile.removeSuffix(".tex"))
        assertTrue(File(instrumentation.targetContext.filesDir, "source-editor/snapshots/$key.json").delete())
        js(scenario, "reviewApp.save(false);true")
        assertEquals("true", js(scenario, "document.getElementById('message').textContent.includes('Source is no longer available locally')"))
        val geometry = js(scenario, "JSON.stringify({scrollY,innerHeight,rect:document.getElementById('message').getBoundingClientRect().toJSON()})")
        assertEquals(geometry, "true", js(scenario, "(()=>{const r=document.getElementById('message').getBoundingClientRect();return scrollY>100 && r.top>=-1 && r.bottom<=innerHeight;})()"))
        assertEquals("true", js(scenario, "reviewApp.getState().source.endsWith('Unsaved edit.') && reviewApp.getState().source!==reviewApp.getState().savedSource"))
    }

    @Test fun saveIsLocalAndRepeatedSaveUsesCurrentDraft() = fixture { scenario, queue, snapshot ->
        js(scenario, "let t=document.querySelector('textarea');t.value='First edit.';t.dispatchEvent(new Event('input'));reviewApp.save();true")
        assertEquals("First edit.", queue.localDraftContents(snapshot))
        js(scenario, "t=document.querySelector('textarea');t.value='Second edit.';t.dispatchEvent(new Event('input'));reviewApp.save();true")
        assertEquals("Second edit.", queue.localDraftContents(snapshot))
        assertEquals("true", js(scenario, "reviewApp.getState().source===reviewApp.getState().savedSource"))
    }
}

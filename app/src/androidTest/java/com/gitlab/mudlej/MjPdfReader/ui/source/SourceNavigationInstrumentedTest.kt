// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.webkit.WebView
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.SourceSnapshot
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** All sources, journals and cached PDF-open results belong to the disposable sandbox. */
@RunWith(AndroidJUnit4::class)
class SourceNavigationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val intro = "An introductory paragraph far above the selected passage.\n\n".repeat(120)
    private fun js(scenario: ActivityScenario<ProposalSourceActivity>, code: String): String {
        val latch = CountDownLatch(1); var result = ""
        scenario.onActivity { activity ->
            val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            check(view is WebView) { "Editor did not load: $view" }
            view.evaluateJavascript(code) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS)); return result
    }
    private fun awaitJs(scenario: ActivityScenario<ProposalSourceActivity>, condition: String) {
        for (i in 0..100) {
            if (js(scenario, condition) == "true") return
            Thread.sleep(100)
        }
        val diagnostic = js(scenario, """JSON.stringify({message:document.getElementById('message')?.innerText,
            navigation:document.getElementById('navigationMessage')?.innerText,scroll:scrollY,
            marker:document.querySelector('.prefixSelected')?.getBoundingClientRect(),
            active:document.activeElement?.tagName,proposals:document.querySelectorAll('.proposal').length})""")
        fail("Editor condition did not become true: $condition; $diagnostic")
    }
    private fun fixture(base: String?, current: String, selected: String, hidden: Boolean = false,
                        run: (ActivityScenario<ProposalSourceActivity>) -> Unit) {
        val context = instrumentation.targetContext
        val id = "navigation-${UUID.randomUUID()}"
        val key = ProposalSourceActivity.digest(id)
        val source = "$id.tex"
        val snapshot = SourceSnapshot(UUID.randomUUID().toString(), source, 1,
            ProposalSourceActivity.digest(current), current, navigationBaseContents = base)
        val queue = SourceEditQueue(context, scheduleRequests = false)
        queue.cacheSnapshot(key, snapshot)
        val journal = ProposalReviewJournal(File(context.filesDir, "proposal-review"), source)
        journal.write(JsonObject().apply {
            addProperty("filename", source); addProperty("baseSha256", snapshot.sourceSha256)
            addProperty("source", current); addProperty("savedSource", current)
            addProperty("hideProposals", hidden)
            add("events", JsonArray()); add("undo", JsonArray()); add("redo", JsonArray())
        })
        try {
            val intent = SourceEditorActivity.intent(context, "", key, "$id.pdf", "a".repeat(64), selected)
            ActivityScenario.launch<ProposalSourceActivity>(intent).use { scenario ->
                awaitJs(scenario, "Boolean(window.reviewApp && reviewApp.getState())")
                run(scenario)
                assertEquals("true", js(scenario, "reviewApp.getState().source===reviewApp.getState().savedSource && reviewApp.getState().undo.length===0"))
            }
        } finally {
            queue.discardLocalChanges(snapshot)
            File(context.filesDir, "source-editor/snapshots/$key.json").delete()
            File(context.filesDir, "proposal-review/${ProposalSourceActivity.digest(source)}.json").delete()
        }
    }
    private val targetVisible = """(()=>{const m=document.querySelector('.prefixSelected');if(!m)return false;
        const r=m.getBoundingClientRect();return r.bottom>document.querySelector('header').getBoundingClientRect().bottom && r.top<innerHeight;})()"""

    @Test fun unchangedJournalWritesDoNotInvalidateReadersButRealConflictsStillFail() {
        val root = File(instrumentation.targetContext.cacheDir, "journal-${UUID.randomUUID()}")
        val journal = ProposalReviewJournal(root, "fixture.tex")
        try {
            val state = JsonObject().apply {
                addProperty("filename", "fixture.tex"); addProperty("source", "Original text.")
                add("events", JsonArray())
            }
            journal.write(state)
            val stale = state.deepCopy()
            val firstRevision = state.get("journalRevision").asLong
            journal.write(state)
            assertEquals(firstRevision, journal.read()!!.get("journalRevision").asLong)
            state.addProperty("source", "A real edit.")
            journal.write(state)
            stale.addProperty("source", "A conflicting edit.")
            assertThrows(IllegalArgumentException::class.java) { journal.write(stale) }
            assertEquals("A real edit.", journal.read()!!.get("source").asString)
        } finally { root.deleteRecursively() }
    }

    @Test fun exactSelectionScrollsPastInsertedLines() {
        val base = "Heading.\n\nThe uniquely selected target."
        fixture(base, intro + base, "uniquely selected target") { scenario ->
            awaitJs(scenario, targetVisible)
            assertEquals("true", js(scenario, "document.activeElement.value.slice(document.activeElement.selectionStart,document.activeElement.selectionEnd)==='uniquely selected target'"))
            assertEquals("true", js(scenario, "scrollY>1000 && document.getElementById('navigationMessage').hidden"))
        }
    }
    @Test fun rewrittenCardOpensCurrentPassageWithVisibleNotice() {
        val marker = "\\ReviewCardMaskStart{stable-card}{0}%\n"
        val base = marker + "The corrected experiment trains both arms.\n\\ReviewCardMaskEnd{stable-card}{0}%\n"
        val current = intro + marker + "We compare two models.\nOnly their training loss differs.\n\nFollowing text."
        fixture(base, current, "corrected experiment") { scenario ->
            awaitJs(scenario, targetVisible)
            assertEquals("true", js(scenario, "document.activeElement.value.slice(document.activeElement.selectionStart,document.activeElement.selectionEnd).startsWith('We compare two models.')"))
            assertEquals("true", js(scenario, "document.getElementById('navigationMessage').innerText.includes('changed since the PDF') && document.getElementById('navigationMessage').getBoundingClientRect().top>=0"))
        }
    }
    @Test fun hiddenProposalIsRevealedWithoutChangingPreferenceOrSource() {
        val source = intro + "\\agentProposedEdit[created=2026-09-14T10:00:00Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{hidden-target}{Clarify.}{Original wording.}{The selected proposed wording.}"
        fixture(source, source, "selected proposed wording", hidden = true) { scenario ->
            awaitJs(scenario, targetVisible)
            assertEquals("true", js(scenario, "Boolean(document.getElementById('proposal-hidden-target')) && reviewApp.getState().hideProposals===true"))
            assertEquals("true", js(scenario, "document.activeElement.dataset.kind==='new'"))
            scenario.recreate()
            awaitJs(scenario, targetVisible)
            assertEquals("true", js(scenario, "reviewApp.getState().hideProposals===true"))
        }
    }
    @Test fun hiddenOriginalRemainsReadOnlyWhenRevealed() {
        val source = intro + "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{hidden-original}{Clarify.}{Selected original wording.}{Replacement wording.}"
        fixture(source, source, "Selected original wording", hidden = true) { scenario ->
            awaitJs(scenario, targetVisible)
            assertEquals("true", js(scenario, "document.activeElement.readOnly && document.activeElement.dataset.kind==='old'"))
        }
    }
    @Test fun deletedPassageReportsFailureWithoutSelectingOtherText() {
        val base = "Unique preceding boundary.\n\nOriginal target wording.\n\nUnique following boundary."
        fixture(base, base.replace("Original target wording.", ""), "target wording") { scenario ->
            awaitJs(scenario, "document.getElementById('navigationMessage').innerText.includes('could not be located')")
            assertEquals("true", js(scenario, "!document.querySelector('.prefixSelected') && document.activeElement.tagName!=='TEXTAREA'"))
        }
    }
    @Test fun legacyCacheWithoutBuildSourceReportsUnavailableEvidence() {
        fixture(null, "This file happens to contain the selected phrase.", "selected phrase") { scenario ->
            awaitJs(scenario, "document.getElementById('navigationMessage').innerText.includes('source version used to build this PDF is unavailable')")
            assertEquals("true", js(scenario, "!document.querySelector('.prefixSelected')"))
        }
    }
}

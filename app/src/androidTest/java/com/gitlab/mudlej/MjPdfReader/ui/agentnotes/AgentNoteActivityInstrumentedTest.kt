// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNote
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteInboxPage
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteReply
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotes
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteInbox
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesApi
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesReceiving
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentStudyListenerService
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AnnotationTool
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Draws on an agent note with injected touches, undoes and redoes, and sends to a fake server
 * (AgentNotes.apiOverride). Run only through tools/run-isolated-tests.py on the sandbox app.
 */
class AgentNoteActivityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val noteId by lazy { "test-${TestRunIdentity.id}-note" }

    private class Posted(val noteId: String, val replyId: String, val image: ByteArray, val strokes: String?, val message: String?)

    private class FakeApi : AgentNotesApi {
        val posted = CopyOnWriteArrayList<Posted>()
        val receipts = CopyOnWriteArrayList<String>()
        val inboxCalls = java.util.concurrent.atomic.AtomicInteger()
        /** When set, a reply waits for it before it reaches the server: a slow upload. */
        @Volatile var hold: java.util.concurrent.CountDownLatch? = null
        override fun inbox(after: Long?, wait: Int, reviewCardId: String?, documentId: String?): AgentNoteInboxPage {
            inboxCalls.incrementAndGet()
            // A long-poll that finds nothing.
            Thread.sleep(500)
            return AgentNoteInboxPage(emptyList(), after ?: 0)
        }
        override fun list(documentId: String?, reviewCardId: String?, limit: Int) = emptyList<AgentNote>()
        override fun note(noteId: String) = AgentNote(noteId)
        override fun downloadImage(note: AgentNote) = throw java.io.IOException("not used")
        override fun downloadReplyImage(reply: AgentNoteReply) = throw java.io.IOException("not used")
        override fun receipt(noteId: String, state: String): AgentNote {
            receipts += state
            return AgentNote(noteId, title = "Receipt")
        }
        override fun postReply(noteId: String, replyId: String, imageBytes: ByteArray, mediaType: String, strokesJson: String?, message: String?): AgentNoteReply {
            hold?.await(20, java.util.concurrent.TimeUnit.SECONDS)
            posted += Posted(noteId, replyId, imageBytes, strokesJson, message)
            return AgentNoteReply(replyId = replyId, noteId = noteId, sequence = 1)
        }
        override fun delete(noteId: String) = Unit
    }

    private val api = FakeApi()

    @Before fun setUp() {
        check(context.packageName == "org.tbend.thoughts.sandbox" && BuildConfig.IS_TEST_ENVIRONMENT)
        AgentNotes.apiOverride = api
        val image = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.WHITE)
        }
        val png = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        AgentNotes.store(context).saveNote(
            AgentNote(noteId = noteId, sequence = 1, title = "Test note ${TestRunIdentity.id}", message = "Circle the answer.",
                imageWidth = WIDTH, imageHeight = HEIGHT, mediaType = "image/png"),
            png,
        )
    }

    @After fun tearDown() {
        AgentNotesReceiving.set(context, true)
        AgentNotes.apiOverride = null
        AgentNotes.store(context).deleteNote(noteId)
        context.stopService(Intent(context, AgentStudyListenerService::class.java))
    }

    @Test fun theNoteWindowIsTitledByTheNoteAndIsASelectedDocumentTab() {
        ActivityScenario.launch<AgentNoteActivity>(AgentNoteActivity.intent(context, noteId)).use { scenario ->
            val title = "Test note ${TestRunIdentity.id}"
            awaitCondition("the note's title is in the bar and on the selected tab") {
                var shown = false
                scenario.onActivity { activity ->
                    val tabs = activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.agentNoteTabs)
                    val selected = tabs.getTabAt(tabs.selectedTabPosition)
                    shown = activity.supportActionBar?.title?.toString() == title &&
                        tabs.visibility == View.VISIBLE && selected?.contentDescription == title
                }
                shown
            }
            scenario.onActivity { activity ->
                assertTrue("the notes window is its own task, not the launcher's", activity.isTaskRoot)
            }
        }
    }

    @Test fun drawUndoRedoAndSendTheComposite() {
        ActivityScenario.launch<AgentNoteActivity>(AgentNoteActivity.intent(context, noteId)).use { scenario ->
            awaitCondition("the note is shown") {
                var ready = false
                scenario.onActivity { ready = it.canvas().bitmap != null && it.canvas().width > 0 }
                ready
            }
            scenario.onActivity { activity ->
                val canvas = activity.canvas()
                // performClick ignores isEnabled, so check that Send is really tappable.
                assertTrue("Send is enabled", activity.findViewById<View>(R.id.agentNoteSend).isEnabled)
                // A new note opens in Scroll: one finger draws nothing.
                drag(canvas, 0.2f, 0.4f, 0.8f, 0.45f)
                val document = canvas.document!!
                assertTrue(document.isEmpty)

                activity.findViewById<View>(R.id.agentNotePen).performClick()
                drag(canvas, 0.2f, 0.4f, 0.8f, 0.45f)
                assertEquals(1, document.strokes.size)
                val stroke = document.strokes.single()
                assertEquals(AnnotationTool.PEN, stroke.tool)
                // Touches are mapped into image pixels.
                assertTrue(stroke.points.indices.step(2).all { stroke.points[it] in 0f..WIDTH.toFloat() })
                assertTrue(stroke.points[0] < stroke.points[stroke.points.size - 2])
                assertTrue(stroke.pointCount >= 2)

                activity.findViewById<View>(R.id.agentNoteUndo).performClick()
                assertTrue(document.isEmpty)
                activity.findViewById<View>(R.id.agentNoteRedo).performClick()
                assertEquals(1, document.strokes.size)

                activity.findViewById<View>(R.id.agentNoteHighlighter).performClick()
                drag(canvas, 0.2f, 0.6f, 0.7f, 0.6f)
                assertEquals(AnnotationTool.HIGHLIGHTER, document.strokes.last().tool)

                // The eraser removes the highlighter it crosses.
                activity.findViewById<View>(R.id.agentNoteEraser).performClick()
                drag(canvas, 0.5f, 0.55f, 0.5f, 0.65f)
                assertEquals(listOf(AnnotationTool.PEN), document.strokes.map { it.tool })

                activity.findViewById<View>(R.id.agentNoteSend).performClick()
            }
            awaitCondition("the reply is sent") { api.posted.isNotEmpty() }
            // The note stays open after sending, with the drawing, until the agent answers.
            awaitCondition("the note shows it was sent") {
                var subtitle: CharSequence? = null
                scenario.onActivity { subtitle = it.supportActionBar?.subtitle }
                subtitle?.toString()?.startsWith("Sent") == true || subtitle?.toString()?.startsWith("Saved") == true
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { assertEquals(1, it.canvas().document!!.strokes.size) }
        }
        // The live loop may send the queued reply too; the server dedupes by reply_id.
        assertEquals(1, api.posted.map { it.replyId }.toSet().size)
        val posted = api.posted.first()
        assertEquals(noteId, posted.noteId)
        val bitmap = BitmapFactory.decodeByteArray(posted.image, 0, posted.image.size)
        assertNotNull(bitmap)
        assertEquals(WIDTH, bitmap.width)
        assertEquals(HEIGHT, bitmap.height)
        // Something was drawn on the white page.
        var dark = 0
        for (x in 0 until WIDTH step 4) for (y in 0 until HEIGHT step 4) if (Color.red(bitmap.getPixel(x, y)) < 128) dark++
        assertTrue("dark pixels: $dark", dark > 10)
        val strokes = JsonParser.parseString(posted.strokes).asJsonObject
        assertEquals(1, strokes["schema_version"].asInt)
        assertEquals(WIDTH, strokes["image_width"].asInt)
        assertEquals(HEIGHT, strokes["source_height"].asInt)
        assertEquals(1, strokes.getAsJsonArray("strokes").size())
        val store = AgentNotes.store(context)
        val reply = store.replies(noteId).single()
        assertTrue(reply.sent)
        assertFalse(store.pendingReplies().any { it.noteId == noteId })
        assertTrue("opened" in api.receipts)
    }

    @Test fun theNoteStaysUsableWhileTheReplyIsOnItsWay() {
        val release = java.util.concurrent.CountDownLatch(1)
        api.hold = release
        ActivityScenario.launch<AgentNoteActivity>(AgentNoteActivity.intent(context, noteId)).use { scenario ->
            awaitCondition("the note is shown") {
                var ready = false
                scenario.onActivity { ready = it.canvas().bitmap != null && it.canvas().width > 0 }
                ready
            }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.agentNoteCommentField).setText("First reply")
                activity.findViewById<View>(R.id.agentNotePen).performClick()
                drag(activity.canvas(), 0.2f, 0.3f, 0.8f, 0.35f)
                activity.findViewById<View>(R.id.agentNoteSend).performClick()
                // The comment went with the reply: the field is free for the next one at once.
                assertEquals("", activity.findViewById<EditText>(R.id.agentNoteCommentField).text.toString())
            }
            awaitCondition("the reply is on its way") {
                var subtitle: CharSequence? = null
                scenario.onActivity { subtitle = it.supportActionBar?.subtitle }
                subtitle?.toString()?.contains("Sending") == true
            }
            scenario.onActivity { activity ->
                // Only Send waits: no spinner over the note, the drawing and the comment stay usable.
                assertFalse("Send waits", activity.findViewById<View>(R.id.agentNoteSend).isEnabled)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.agentNoteProgress).visibility)
                assertTrue("drawing", activity.canvas().isEnabled)
                val field = activity.findViewById<EditText>(R.id.agentNoteCommentField)
                assertTrue("comment", field.isEnabled)
                field.setText("Next one")
                drag(activity.canvas(), 0.2f, 0.7f, 0.8f, 0.75f)
            }
            release.countDown()
            awaitCondition("the reply is sent") { api.posted.isNotEmpty() }
            awaitCondition("Send is back") {
                var enabled = false
                scenario.onActivity { enabled = it.findViewById<View>(R.id.agentNoteSend).isEnabled }
                enabled
            }
            scenario.onActivity { activity ->
                // What was typed and drawn meanwhile stays for the next reply.
                assertEquals("Next one", activity.findViewById<EditText>(R.id.agentNoteCommentField).text.toString())
                assertEquals(2, activity.canvas().document!!.strokes.size)
            }
        }
        // The reply is what was there when Send was pressed: its comment and its one stroke.
        assertEquals(1, api.posted.map { it.replyId }.toSet().size)
        val posted = api.posted.first()
        assertEquals("First reply", posted.message)
        assertEquals(1, JsonParser.parseString(posted.strokes).asJsonObject.getAsJsonArray("strokes").size())
    }

    @Test fun numberToolDropsNumberedMarkersThatAreSent() {
        ActivityScenario.launch<AgentNoteActivity>(AgentNoteActivity.intent(context, noteId)).use { scenario ->
            awaitCondition("the note is shown") {
                var ready = false
                scenario.onActivity { ready = it.canvas().bitmap != null && it.canvas().width > 0 }
                ready
            }
            scenario.onActivity { activity ->
                val canvas = activity.canvas()
                val document = canvas.document!!
                val number = activity.findViewById<View>(R.id.agentNoteNumber)
                assertTrue("Number is enabled", number.isEnabled)
                number.performClick()
                assertEquals(AnnotationCanvasView.Mode.NUMBER, canvas.mode)
                // The comment field sits in the tools next to undo/redo; the title bar has no menu items.
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.agentNoteCommentField).visibility)
                assertEquals(0, activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.agentNoteToolbar).menu.size())
                // Android 16 draws edge to edge whatever the theme says: the title bar starts below the status bar.
                val toolbar = activity.findViewById<View>(R.id.agentNoteToolbar)
                val barsTop = androidx.core.view.ViewCompat.getRootWindowInsets(toolbar)!!.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
                ).top
                val location = IntArray(2).also(toolbar::getLocationOnScreen)
                assertTrue("title bar top ${location[1]} under the status bar ($barsTop)", location[1] >= barsTop)

                tap(canvas, 0.3f, 0.3f)
                tap(canvas, 0.7f, 0.6f)
                assertEquals(listOf(1, 2), document.markers.map { it.number })
                assertTrue(document.markers.all { it.color == AgentNoteViewModel.INK })
                // Touches are mapped into image pixels.
                assertTrue(document.markers[0].x in 0f..WIDTH.toFloat() && document.markers[0].x < document.markers[1].x)
                assertTrue(document.markers[0].y < document.markers[1].y)
                assertTrue(document.strokes.isEmpty())

                // A drag pans instead of adding a marker.
                drag(canvas, 0.5f, 0.7f, 0.5f, 0.3f)
                assertEquals(2, document.markers.size)

                // Undo and redo cover the markers.
                activity.findViewById<View>(R.id.agentNoteUndo).performClick()
                assertEquals(listOf(1), document.markers.map { it.number })
                activity.findViewById<View>(R.id.agentNoteRedo).performClick()
                assertEquals(listOf(1, 2), document.markers.map { it.number })

                activity.findViewById<View>(R.id.agentNoteSend).performClick()
            }
            awaitCondition("the reply is sent") { api.posted.isNotEmpty() }
        }
        val posted = api.posted.first()
        val strokes = JsonParser.parseString(posted.strokes).asJsonObject
        val markers = strokes.getAsJsonArray("markers")
        assertEquals(2, markers.size())
        assertEquals(listOf(1, 2), markers.map { it.asJsonObject["number"].asInt })
        assertEquals("#000000", markers[0].asJsonObject["color"].asString)
        assertEquals(0, strokes.getAsJsonArray("strokes").size())
        // The composite shows the black markers around their positions.
        val bitmap = BitmapFactory.decodeByteArray(posted.image, 0, posted.image.size)
        assertEquals(WIDTH, bitmap.width)
        for (marker in markers) {
            val x = marker.asJsonObject["x"].asFloat.toInt()
            val y = marker.asJsonObject["y"].asFloat.toInt()
            var dark = 0
            for (dx in -10..10) for (dy in -10..10) {
                val pixel = bitmap.getPixel((x + dx).coerceIn(0, WIDTH - 1), (y + dy).coerceIn(0, HEIGHT - 1))
                if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) dark++
            }
            assertTrue("dark pixels around marker at $x,$y: $dark", dark > 100)
        }
    }

    @Test fun scrollModeDragPansAndHoldThenDragHighlights() {
        ActivityScenario.launch<AgentNoteActivity>(AgentNoteActivity.intent(context, noteId)).use { scenario ->
            awaitCondition("the note is shown") {
                var ready = false
                scenario.onActivity { ready = it.canvas().bitmap != null && it.canvas().width > 0 }
                ready
            }
            lateinit var canvas: AnnotationCanvasView
            scenario.onActivity { activity ->
                canvas = activity.canvas()
                // A new note opens in Scroll; a quick drag only moves the view.
                assertEquals(AnnotationCanvasView.Mode.SCROLL, canvas.mode)
                drag(canvas, 0.3f, 0.6f, 0.7f, 0.4f)
                assertTrue(canvas.document!!.strokes.isEmpty())
            }
            // Pressing still for a moment, then dragging, highlights from where the finger went down.
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, fx: Float, fy: Float) = scenario.onActivity {
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, fx * canvas.width, fy * canvas.height, 0).also { e ->
                    canvas.dispatchTouchEvent(e)
                    e.recycle()
                }
            }
            event(MotionEvent.ACTION_DOWN, 0.2f, 0.5f)
            Thread.sleep(600)
            for (i in 1..10) event(MotionEvent.ACTION_MOVE, 0.2f + 0.05f * i, 0.5f)
            event(MotionEvent.ACTION_UP, 0.7f, 0.5f)
            scenario.onActivity {
                val strokes = canvas.document!!.strokes
                assertEquals(1, strokes.size)
                assertEquals(AnnotationTool.HIGHLIGHTER, strokes[0].tool)
                // The tool stays Scroll, so the next quick drag scrolls again.
                assertEquals(AnnotationCanvasView.Mode.SCROLL, canvas.mode)
                drag(canvas, 0.5f, 0.7f, 0.5f, 0.3f)
                assertEquals(1, canvas.document!!.strokes.size)
            }
        }
    }

    @Test fun commentIsTypedInlineWhileDrawingAndSentAsTheMessage() {
        ActivityScenario.launch<AgentNoteActivity>(AgentNoteActivity.intent(context, noteId)).use { scenario ->
            awaitCondition("the note is shown") {
                var ready = false
                scenario.onActivity { ready = it.canvas().bitmap != null && it.canvas().width > 0 }
                ready
            }
            scenario.onActivity { activity ->
                val canvas = activity.canvas()
                val field = activity.findViewById<EditText>(R.id.agentNoteCommentField)
                // The field is part of the screen, under the note, not a dialog over it.
                assertEquals(View.VISIBLE, field.visibility)
                assertTrue(field.isShown)
                val canvasBottom = IntArray(2).also(canvas::getLocationOnScreen)[1] + canvas.height
                assertTrue("field below the note", IntArray(2).also(field::getLocationOnScreen)[1] >= canvasBottom)
                assertEquals(4, field.maxLines)

                field.requestFocus()
                field.setText("Line one\nline two")
                // Drawing and numbering while the comment is open keep the comment.
                activity.findViewById<View>(R.id.agentNotePen).performClick()
                drag(canvas, 0.2f, 0.4f, 0.8f, 0.45f)
                activity.findViewById<View>(R.id.agentNoteNumber).performClick()
                tap(canvas, 0.5f, 0.7f)
                activity.findViewById<View>(R.id.agentNoteScroll).performClick()
                assertEquals(1, canvas.document!!.strokes.size)
                assertEquals(1, canvas.document!!.markers.size)
                assertEquals("Line one\nline two", field.text.toString())

                // The keyboard pushes the tools up instead of covering them.
                val root = activity.findViewById<View>(android.R.id.content).let { (it as android.view.ViewGroup).getChildAt(0) }
                ViewCompat.dispatchApplyWindowInsets(
                    root,
                    WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 700))
                        .setVisible(WindowInsetsCompat.Type.ime(), true)
                        .build(),
                )
                assertEquals(700, root.paddingBottom)
                ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder().build())
                assertEquals(0, root.paddingBottom)

                activity.findViewById<View>(R.id.agentNoteSend).performClick()
            }
            awaitCondition("the reply is sent") { api.posted.isNotEmpty() }
            // A successful Send empties the field for the next reply.
            awaitCondition("the comment field is cleared") {
                var text: String? = null
                scenario.onActivity { text = it.findViewById<EditText>(R.id.agentNoteCommentField).text.toString() }
                text == ""
            }
        }
        assertEquals("Line one\nline two", api.posted.first().message)
    }

    @Test fun aPhoneThatDoesNotReceiveNeverAsksTheInboxButStillOpensANote() {
        instrumentation.runOnMainSync { AgentNotesReceiving.set(context, false) }
        // A request already open when the switch went off may still finish.
        SystemClock.sleep(1_000)
        api.inboxCalls.set(0)
        ActivityScenario.launch<AgentNoteActivity>(AgentNoteActivity.intent(context, noteId)).use { scenario ->
            awaitCondition("the note is shown") {
                var ready = false
                scenario.onActivity { ready = it.canvas().bitmap != null && it.canvas().width > 0 }
                ready
            }
            // Opening the note would start the listener and the loop; off, neither runs.
            SystemClock.sleep(2_000)
            assertFalse("live loop off", AgentNoteInbox.running)
            assertFalse("study listener off", listenerRunning())
            assertEquals(0, api.inboxCalls.get())
            assertTrue("opened" in api.receipts)

            // Back on: the loop starts at once while the note is open.
            instrumentation.runOnMainSync { AgentNotesReceiving.set(context, true) }
            awaitCondition("the live loop runs again") { AgentNoteInbox.running }
        }
    }

    @Test fun aNewNoteWaitsBehindTheButtonInsteadOfReplacingTheNoteOnScreen() {
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        val store = AgentNotes.store(context)
        val png = store.imageFile(noteId)!!.readBytes()
        val first = AgentNote(noteId = firstId, sequence = 2, title = "First new ${TestRunIdentity.id}", imageWidth = WIDTH, imageHeight = HEIGHT, mediaType = "image/png")
        val second = AgentNote(noteId = secondId, sequence = 3, title = "Second new ${TestRunIdentity.id}", imageWidth = WIDTH, imageHeight = HEIGHT, mediaType = "image/png")
        store.saveNote(first, png)
        store.saveNote(second, png)
        try {
            ActivityScenario.launch<AgentNoteActivity>(AgentNoteActivity.intent(context, noteId)).use { scenario ->
                awaitCondition("the note is shown") {
                    var ready = false
                    scenario.onActivity { ready = it.canvas().bitmap != null && it.canvas().width > 0 }
                    ready
                }
                val button = { var view: TextView? = null; scenario.onActivity { view = it.findViewById(R.id.agentNoteNewNote) }; view }
                val shown = { var id: String? = null; scenario.onActivity { id = it.shownNoteId }; id }
                assertNull("nothing waits yet", button())

                // Two notes arrive: the note being read stays, and the button says so.
                instrumentation.runOnMainSync { AgentNoteInbox.deliver(context, listOf(first, second)) }
                awaitCondition("the button counts both") { button()?.text?.toString() == "2 new notes" }
                assertEquals(noteId, shown())

                // A tap opens the oldest; the button counts the other.
                scenario.onActivity { button()!!.performClick() }
                awaitCondition("the first new note is on screen") { shown() == firstId }
                awaitCondition("one note still waits") { button()?.text?.toString() == "New note" }
                scenario.onActivity { button()!!.performClick() }
                awaitCondition("the second new note is on screen") { shown() == secondId }
                awaitCondition("nothing waits any more") { button() == null }
                // Back to the note it started on: the scenario knows the activity by that intent.
                scenario.onActivity { it.startActivity(AgentNoteActivity.intent(it, noteId)) }
                awaitCondition("the first note is back") { shown() == noteId }
            }
        } finally {
            AgentNoteInbox.opened(firstId)
            AgentNoteInbox.opened(secondId)
            store.deleteNote(firstId)
            store.deleteNote(secondId)
        }
    }

    @Suppress("DEPRECATION")
    private fun listenerRunning(): Boolean =
        context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == AgentStudyListenerService::class.java.name && it.service.packageName == context.packageName }

    private fun AgentNoteActivity.canvas() = findViewById<AnnotationCanvasView>(R.id.agentNoteCanvas)

    private fun drag(view: View, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, fx: Float, fy: Float, time: Long) =
            MotionEvent.obtain(down, time, action, fx * view.width, fy * view.height, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        event(MotionEvent.ACTION_DOWN, fromX, fromY, down)
        val steps = 20
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            event(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, down + i * 10L)
        }
        event(MotionEvent.ACTION_UP, toX, toY, down + (steps + 1) * 10L)
    }

    private fun tap(view: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50)) {
            MotionEvent.obtain(down, time, action, x * view.width, y * view.height, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting until $what")
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 900
    }
}

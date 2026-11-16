// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.google.gson.JsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentNoteSyncTest {
    @get:Rule val folder = TemporaryFolder()

    /** The server as the phone sees it, scripted per call. */
    class FakeApi : AgentNotesApi {
        val inboxCalls = mutableListOf<Triple<Long?, Int, AgentStudyContext>>()
        val pages = ArrayDeque<() -> AgentNoteInboxPage>()
        val images = mutableMapOf<String, () -> ByteArray>()
        val receipts = mutableListOf<Pair<String, String>>()
        val posted = mutableListOf<String>()
        var postFailure: ((String) -> Exception?)? = null
        var notesById = mutableMapOf<String, AgentNote>()

        override fun inbox(after: Long?, wait: Int, reviewCardId: String?, documentId: String?): AgentNoteInboxPage {
            inboxCalls += Triple(after, wait, AgentStudyContext(reviewCardId, documentId))
            return (pages.removeFirstOrNull() ?: { AgentNoteInboxPage(emptyList(), after) })()
        }

        override fun list(documentId: String?, reviewCardId: String?, limit: Int) = notesById.values.toList()
        override fun note(noteId: String) = notesById[noteId] ?: throw AgentNotesHttpException(404, "agent_note_not_found", "gone")
        override fun downloadImage(note: AgentNote) = images[note.noteId]?.invoke() ?: byteArrayOf(note.sequence.toByte())
        override fun downloadReplyImage(reply: AgentNoteReply) = byteArrayOf(1)
        override fun receipt(noteId: String, state: String): AgentNote {
            receipts += noteId to state
            return notesById[noteId] ?: AgentNote(noteId)
        }

        override fun postReply(noteId: String, replyId: String, imageBytes: ByteArray, mediaType: String, strokesJson: String?, message: String?): AgentNoteReply {
            postFailure?.invoke(replyId)?.let { throw it }
            posted += replyId
            return AgentNoteReply(replyId = replyId, noteId = noteId, sequence = posted.size.toLong(), message = message)
        }

        val photos = mutableMapOf<String, AgentNoteReplyPhotoUpload>()

        override fun postReply(
            noteId: String, replyId: String, imageBytes: ByteArray, mediaType: String, strokesJson: String?, message: String?,
            photo: AgentNoteReplyPhotoUpload?,
        ): AgentNoteReply {
            photo?.let { photos[replyId] = it }
            return postReply(noteId, replyId, imageBytes, mediaType, strokesJson, message)
        }

        /** What each posted reply said the answering agent may use (null: the field left out). */
        val sources = mutableMapOf<String, String?>()

        override fun postReply(
            noteId: String, replyId: String, imageBytes: ByteArray, mediaType: String, strokesJson: String?, message: String?,
            photo: AgentNoteReplyPhotoUpload?, sources: String?,
        ): AgentNoteReply {
            postFailure?.invoke(replyId)?.let { throw it }
            this.sources[replyId] = sources
            return postReply(noteId, replyId, imageBytes, mediaType, strokesJson, message, photo).copy(sources = sources)
        }

        override fun delete(noteId: String) = Unit
    }

    private fun note(id: String, sequence: Long, deleted: Boolean = false) =
        AgentNote(noteId = id, sequence = sequence, title = "Note $id", deleted = deleted)

    private val store by lazy { AgentNoteStore.under(folder.root) }
    private val api = FakeApi()
    private val sync by lazy { AgentNoteSync(api, store) }

    @Test fun storesNewNotesPostsDeliveredAndPersistsTheCursor() {
        api.pages += { AgentNoteInboxPage(listOf(note("b", 8), note("a", 7)), 8) }
        val stored = sync.pollInbox(25, AgentStudyContext("card-1", "doc-1"))

        // Oldest first, each stored with its image and acknowledged.
        assertEquals(listOf("a", "b"), stored.map { it.noteId })
        assertArrayEquals(byteArrayOf(7), store.imageFile("a")!!.readBytes())
        assertEquals(listOf("a" to "delivered", "b" to "delivered"), api.receipts)
        assertEquals(8L, store.inboxCursor())
        assertEquals(Triple(null, 25, AgentStudyContext("card-1", "doc-1")), api.inboxCalls.single())

        // The next request continues after the stored cursor.
        api.pages += { AgentNoteInboxPage(emptyList(), 8) }
        assertTrue(sync.pollInbox(25).isEmpty())
        assertEquals(8L, api.inboxCalls.last().first)
    }

    @Test fun takesTheServersCursorWhenNothingIsNew() {
        api.pages += { AgentNoteInboxPage(emptyList(), 40) }
        sync.pollInbox(0)
        assertEquals(40L, store.inboxCursor())
    }

    @Test fun skipsNotesAlreadyStored() {
        store.saveNote(note("a", 7), byteArrayOf(1))
        api.images["a"] = { fail("a stored note is not downloaded again"); byteArrayOf() }
        api.pages += { AgentNoteInboxPage(listOf(note("a", 7), note("b", 8)), 8) }
        val stored = sync.pollInbox(25)
        assertEquals(listOf("b"), stored.map { it.noteId })
        assertEquals(listOf("b" to "delivered"), api.receipts)
        assertEquals(8L, store.inboxCursor())
    }

    @Test fun aFailedDownloadIsAskedAgainFromTheLastStoredNote() {
        store.setInboxCursor(6)
        api.images["b"] = { throw SocketTimeoutException() }
        api.pages += { AgentNoteInboxPage(listOf(note("a", 7), note("b", 8), note("c", 9)), 9) }
        try {
            sync.pollInbox(25)
            fail("The download failure ends the round")
        } catch (expected: IOException) {
        }
        assertEquals(7L, store.inboxCursor())
        assertTrue(store.hasNote("a"))

        api.images.remove("b")
        api.pages += { AgentNoteInboxPage(listOf(note("b", 8), note("c", 9)), 9) }
        assertEquals(listOf("b", "c"), sync.pollInbox(25).map { it.noteId })
        assertEquals(7L, api.inboxCalls.last().first)
        assertEquals(9L, store.inboxCursor())
    }

    @Test fun skipsGoneOrDeletedNotesForGood() {
        api.images["a"] = { throw AgentNotesHttpException(404, "agent_note_not_found", "gone") }
        api.pages += { AgentNoteInboxPage(listOf(note("a", 7), note("b", 8, deleted = true), note("c", 9)), 9) }
        assertEquals(listOf("c"), sync.pollInbox(25).map { it.noteId })
        assertEquals(9L, store.inboxCursor())
        assertEquals(listOf("c" to "delivered"), api.receipts)
    }

    @Test fun aFailedReceiptDoesNotLoseTheNote() {
        val failing = object : AgentNotesApi by api {
            override fun receipt(noteId: String, state: String): AgentNote = throw IOException("offline")
        }
        api.pages += { AgentNoteInboxPage(listOf(note("a", 7)), 7) }
        assertEquals(listOf("a"), AgentNoteSync(failing, store).pollInbox(0).map { it.noteId })
        assertEquals(7L, store.inboxCursor())
    }

    @Test fun workerSkipsTheInboxWhileTheLoopIsAsking() {
        val inLoop = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        api.pages += {
            inLoop.countDown()
            release.await()
            AgentNoteInboxPage(emptyList(), 1)
        }
        val loop = Thread { sync.pollInbox(25) }.apply { start() }
        inLoop.await()
        assertNull(sync.tryPollInbox(0))
        release.countDown()
        loop.join(5_000)
        assertEquals(emptyList<AgentNote>(), sync.tryPollInbox(0))
    }

    private fun queue(id: String, created: Long) = LocalAgentNoteReply(
        replyId = id, noteId = "a", createdAtMillis = created, mediaType = "image/png",
        imageWidth = 1, imageHeight = 1, strokeCount = 0, message = null, sent = false,
    ).also { store.queueReply(it, byteArrayOf(1), "{\"schema_version\":1}") }

    @Test fun periodicCheckOnAPhoneThatDoesNotReceiveOnlySendsTheOutbox() {
        api.notesById["a"] = note("a", 1)
        queue("r1", 1)
        api.pages += { AgentNoteInboxPage(listOf(note("b", 2)), 2) }

        val (sent, fresh) = sync.periodicCheck(receiving = false)

        assertEquals(1, sent)
        assertEquals(listOf("r1"), api.posted)
        assertEquals(emptyList<AgentNote>(), fresh)
        // The inbox is never asked, so the server keeps the note for the phone that receives.
        assertTrue(api.inboxCalls.isEmpty())
        assertNull(store.inboxCursor())
        assertTrue(!store.hasNote("b"))
    }

    @Test fun periodicCheckOnAReceivingPhoneSendsTheOutboxThenAsksTheInbox() {
        api.notesById["a"] = note("a", 1)
        queue("r1", 1)
        api.pages += { AgentNoteInboxPage(listOf(note("b", 2)), 2) }

        val (sent, fresh) = sync.periodicCheck(receiving = true)

        assertEquals(1, sent)
        assertEquals(listOf("b"), fresh.map { it.noteId })
        assertEquals(1, api.inboxCalls.size)
        assertEquals(0, api.inboxCalls.single().second)
        assertEquals(2L, store.inboxCursor())
    }

    @Test fun liveLoopRunsOnlyWhileThisPhoneReceives() {
        assertTrue(agentNotesInboxShouldRun(receiving = true, startedActivities = 1, listening = false))
        assertTrue(agentNotesInboxShouldRun(receiving = true, startedActivities = 0, listening = true))
        assertTrue(!agentNotesInboxShouldRun(receiving = true, startedActivities = 0, listening = false))
        // Off: neither an open screen nor the study listener makes it ask the inbox.
        assertTrue(!agentNotesInboxShouldRun(receiving = false, startedActivities = 2, listening = false))
        assertTrue(!agentNotesInboxShouldRun(receiving = false, startedActivities = 0, listening = true))
        assertTrue(!agentNotesInboxShouldRun(receiving = false, startedActivities = 1, listening = true))
    }

    @Test fun outboxIsRetriedUntilTheServerConfirms() {
        api.notesById["a"] = note("a", 1).copy(replies = listOf(AgentNoteReply("r1", "a")))
        store.saveNote(note("a", 1), byteArrayOf(1))
        queue("r1", 1)
        queue("r2", 2)
        api.postFailure = { IOException("offline") }
        assertEquals(0, sync.flushOutbox())
        // The round stops at the first network failure.
        assertEquals(1, store.reply("r1")?.attempts)
        assertEquals(0, store.reply("r2")?.attempts)

        api.postFailure = null
        assertEquals(2, sync.flushOutbox())
        assertEquals(listOf("r1", "r2"), api.posted)
        assertTrue(store.pendingReplies().isEmpty())
        // The note's record is refreshed with its replies.
        assertEquals(listOf("r1"), store.note("a")?.replies?.map { it.replyId })
        assertEquals(0, sync.flushOutbox())
    }

    @Test fun aReplyWithAPictureIsSentWithItAndNeverWithoutIt() {
        val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2)
        val withPicture = LocalAgentNoteReply(
            replyId = "r1", noteId = "a", createdAtMillis = 1, mediaType = "image/png", imageWidth = 1, imageHeight = 1,
            strokeCount = 0, message = "A new version from this", sent = false,
            photoMediaType = "image/jpeg", photoCapture = "IMG_20261111_170138_123_ab12",
        )
        store.queueReply(withPicture, byteArrayOf(1), null, photo)
        // Kept across restarts with the outbox.
        assertEquals("IMG_20261111_170138_123_ab12", store.reply("r1")?.photoCapture)
        assertEquals(1, sync.flushOutbox())
        val sent = api.photos.getValue("r1")
        assertArrayEquals(photo, sent.bytes)
        assertEquals("image/jpeg", sent.mediaType)
        assertEquals("IMG_20261111_170138_123_ab12", sent.capture)

        // Its picture gone from disk: the reply is not sent without it.
        store.queueReply(withPicture.copy(replyId = "r2"), byteArrayOf(1), null, photo)
        store.replyPhotoFile("r2").delete()
        assertEquals(0, sync.flushOutbox())
        assertTrue(store.reply("r2")!!.rejected)
        assertEquals(listOf("r1"), api.posted)
    }

    @Test fun aQueuedReplyIsSentWithTheSourcesItWasWrittenWith() {
        // Written offline with "Linked PDF only"; the user has chosen otherwise since, which is not this reply's choice.
        val written = LocalAgentNoteReply(
            replyId = "r1", noteId = "a", createdAtMillis = 1, mediaType = "image/png", imageWidth = 1, imageHeight = 1,
            strokeCount = 0, message = "Only from the paper", sent = false, sources = AgentNoteReplySources.LINKED_PDF.apiValue,
        )
        store.queueReply(written, byteArrayOf(1), null)
        api.postFailure = { IOException("offline") }
        assertEquals(0, sync.flushOutbox())
        // After a restart the outbox still has it.
        assertEquals("linked_pdf", AgentNoteStore.under(folder.root).reply("r1")?.sources)

        api.postFailure = null
        assertEquals(1, AgentNoteSync(api, AgentNoteStore.under(folder.root)).flushOutbox())
        assertEquals(mapOf("r1" to "linked_pdf"), api.sources.toMap())
        // The server's record is kept with it.
        assertEquals(AgentNoteReplySources.LINKED_PDF, store.reply("r1")?.server?.sourcesChoice)
        assertEquals(AgentNoteReplySources.LINKED_PDF, store.reply("r1")?.sourcesChoice)

        // A reply queued before the choice existed goes without the field, as it always did.
        queue("r2", 2)
        assertEquals(1, sync.flushOutbox())
        assertTrue(api.sources.containsKey("r2"))
        assertNull(api.sources["r2"])
        assertNull(store.reply("r2")?.sourcesChoice)
    }

    @Test fun aPhotoNeedsItsMediaType() {
        val plain = LocalAgentNoteReply("r1", "a", 1, "image/png", 1, 1, 0, null, sent = false)
        try {
            store.queueReply(plain, byteArrayOf(1), null, byteArrayOf(2))
            fail("a photo without its media type must be refused")
        } catch (expected: IllegalArgumentException) {
        }
        assertNull(store.reply("r1"))
    }

    @Test fun aRefusedReplyIsNotRetriedAndDoesNotBlockOthers() {
        queue("r1", 1)
        queue("r2", 2)
        api.postFailure = { id -> if (id == "r1") AgentNotesHttpException(409, "agent_note_conflict", "conflict") else null }
        assertEquals(1, sync.flushOutbox())
        assertTrue(store.reply("r1")!!.rejected)
        assertEquals(listOf("r2"), api.posted)
        assertEquals(0, sync.flushOutbox())
    }

    @Test fun backoffDoublesFromTwoSecondsToAMinute() {
        val sequence = generateSequence(agentNotesBackoffMillis(0)) { agentNotesBackoffMillis(it) }.take(8).toList()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), sequence)
    }

    @Test fun loopBacksOffOnErrorsResetsOnSuccessAndStops() = runBlocking {
        repeat(3) { api.pages += { throw IOException("down") } }
        api.pages += { AgentNoteInboxPage(listOf(note("a", 1)), 1) }
        api.pages += { throw IOException("down again") }
        api.pages += { AgentNoteInboxPage(emptyList(), 1) }
        val delivered = mutableListOf<List<String>>()
        val slept = mutableListOf<Long>()
        var clock = 0L
        val loop = AgentNoteInboxLoop(
            sync,
            shouldRun = { api.inboxCalls.size < 6 },
            context = { AgentStudyContext("card-9", null) },
            onNewNotes = { notes -> delivered += notes.map { it.noteId } },
            sleep = { slept += it },
            // Every request takes 25 s, as a long-poll that found nothing does.
            nowMillis = { clock.also { clock += 12_500 } },
        )
        loop.run()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 2_000L), loop.backoffs)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 2_000L), slept)
        assertEquals(listOf(listOf("a")), delivered)
        assertEquals(6, api.inboxCalls.size)
        assertTrue(api.inboxCalls.all { it.second == 25 && it.third.reviewCardId == "card-9" })
    }

    @Test fun loopSendsTheOutboxEachCycle() = runBlocking {
        queue("r1", 1)
        var cycles = 0
        AgentNoteInboxLoop(sync, shouldRun = { cycles++ < 1 }, context = { null }, onNewNotes = {}, sleep = {}).run()
        assertEquals(listOf("r1"), api.posted)
    }

    @Test fun anInstantEmptyAnswerDoesNotBusyLoop() = runBlocking {
        val slept = mutableListOf<Long>()
        AgentNoteInboxLoop(sync, shouldRun = { api.inboxCalls.size < 3 }, context = { null }, onNewNotes = {},
            sleep = { slept += it }, nowMillis = { 0L }).run()
        // The last answer ends the loop without a pause.
        assertEquals(listOf(1_000L, 1_000L), slept)
    }

    @Test fun loopDoesNotRunWhenNotWanted() = runBlocking {
        AgentNoteInboxLoop(sync, shouldRun = { false }, context = { null }, onNewNotes = {}, sleep = {}).run()
        assertTrue(api.inboxCalls.isEmpty())
    }

    @Test fun aNewNoteWaitsOnTheNoteScreenAndNotifiesElsewhere() {
        val a = note("a", 1)
        val b = note("b", 2)
        // On a note's screen: the New note button, no notification over the note being read.
        val onNote = agentNoteArrival(listOf(a, b), onNoteScreen = true)
        assertEquals(listOf(a, b), onNote.waiting)
        assertTrue(onNote.notify.isEmpty())

        // Anywhere else, Thoughts in front or not: a notification, and the button later.
        val elsewhere = agentNoteArrival(listOf(a, b), onNoteScreen = false)
        assertEquals(listOf(a, b), elsewhere.notify)
        assertEquals(listOf(a, b), elsewhere.waiting)

        // The note already on screen is neither announced nor waiting.
        val shown = agentNoteArrival(listOf(b), onNoteScreen = true, shownNoteId = "b")
        assertTrue(shown.waiting.isEmpty())
        assertTrue(shown.notify.isEmpty())
    }

    /** A page the user sent to their agent from the Mac ("Send Page to Agent…"). */
    private fun macPage(id: String, sequence: Long) = note(id, sequence).copy(
        senderDeviceId = "thoughts-mac",
        title = "Paper.pdf · page 3",
        origin = JsonObject().apply {
            addProperty("kind", AgentNote.PHONE_PAGE)
            addProperty("device", "mac")
            addProperty("page", 3)
            addProperty("document_name", "Paper.pdf")
        },
    )

    @Test fun aPageTheUserSentFromAnotherDeviceIsNeverAnnounced() {
        val agent = note("a", 1)
        val page = macPage("p", 2)
        for (onNoteScreen in listOf(true, false)) {
            val arrival = agentNoteArrival(listOf(agent, page), onNoteScreen)
            // No New note button and no notification for the user's own page; the agent's note still arrives.
            assertEquals(listOf(agent), arrival.waiting)
            assertEquals(if (onNoteScreen) emptyList() else listOf(agent), arrival.notify)
        }
        val alone = agentNoteArrival(listOf(page), onNoteScreen = false)
        assertTrue(alone.waiting.isEmpty())
        assertTrue(alone.notify.isEmpty())
    }

    @Test fun aPageTheUserSentFromAnotherDeviceIsStillStoredForTheList() {
        api.pages += { AgentNoteInboxPage(listOf(macPage("p", 4)), 4) }
        val stored = sync.pollInbox(25)

        assertEquals(listOf("p"), stored.map { it.noteId })
        assertTrue(store.note("p")!!.isPhonePage)
        assertEquals(listOf("p" to "delivered"), api.receipts)
        assertEquals(4L, store.inboxCursor())
        assertTrue(agentNoteArrival(stored, onNoteScreen = false).notify.isEmpty())
    }

    @Test fun draftRoundTrip() {
        val document = AnnotationDocument(10, 10).apply {
            add(AnnotationStroke(AnnotationTool.PEN, 0xE53935, 2f, 1f, floatArrayOf(1f, 1f)))
        }
        val draft = AgentNoteDraft(document.toJson(), "my comment", "highlighter", "#E53935")
        val parsed = AgentNoteDraft.fromJson(draft.toJson())!!
        assertEquals(draft.message, parsed.message)
        assertEquals("highlighter", parsed.tool)
        assertEquals(document.toJson(), AnnotationDocument.fromJson(parsed.strokesJson).toJson())
        assertNull(AgentNoteDraft.fromJson("not json"))
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

/**
 * Whether an agent listens for replies to a note, as the server last answered, kept for
 * [keepMillis]. The note screen asked the server every time a note was shown and then every 20
 * seconds: paging through notes asked for each of them, and 45 % of the requests followed the
 * previous one within two seconds. A note now asks once when its screen comes up, and not at all
 * then while the answer kept for it is younger than a minute; while it stays on screen it asks the
 * server again every [RECHECK_MILLIS] ([follow]), so what the screen says follows an agent that
 * starts or stops. An answer that says nothing (the server cannot tell, or could not be asked) is
 * not kept, so the next visit asks again.
 */
class AgentNoteListeningCache(
    private val keepMillis: Long = KEEP_MILLIS,
    private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private data class Kept(val answer: AgentNoteListeningAnswer, val atMillis: Long)

    private val kept = object : LinkedHashMap<String, Kept>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?) = size > MAX_NOTES
    }

    /** The answer kept for [noteId] while it is younger than [keepMillis]. */
    @Synchronized
    fun fresh(noteId: String): AgentNoteListeningAnswer? {
        val entry = kept[noteId] ?: return null
        val age = nowMillis() - entry.atMillis
        if (age < 0 || age >= keepMillis) {
            kept.remove(noteId)
            return null
        }
        return entry.answer
    }

    /** Keeps what the server said about [noteId] (a `/listening` answer, or the answer to a choice). */
    @Synchronized
    fun keep(noteId: String, answer: AgentNoteListeningAnswer) {
        // An answer that names a session a waker wakes says something, whatever the listeners say.
        if (answer.state == AgentNoteListening.UNKNOWN && answer.wake == null) return
        kept[noteId] = Kept(answer, nowMillis())
    }

    /**
     * Sends the user's choice of who answers [noteId] ([put]) and keeps the server's answer to
     * it, which is the note's listening answer from then on. A refusal or a failure throws, and
     * what was kept stays.
     */
    fun choose(noteId: String, agent: AgentNoteAgent?, put: (AgentNoteAgent?) -> AgentNoteListeningAnswer): AgentNoteListeningAnswer =
        put(agent).also { keep(noteId, it) }

    /**
     * Keeps what the server said when it took a reply to [noteId]: whether an agent listens and
     * which, and who a reply waker wakes ([wake]). A reply's answer does not carry the choice or the
     * candidates, so those of the answer kept so far stay with it.
     */
    @Synchronized
    fun keepAfterReply(
        noteId: String,
        state: AgentNoteListening,
        coverer: AgentNoteCoverer?,
        wake: AgentNoteWake? = null,
    ): AgentNoteListeningAnswer? {
        if (state == AgentNoteListening.UNKNOWN && wake == null) return null
        val merged = (kept[noteId]?.answer ?: AgentNoteListeningAnswer(state)).copy(state = state, coverer = coverer, wake = wake)
        kept[noteId] = Kept(merged, nowMillis())
        return merged
    }

    /**
     * The answer for one visit of the note's screen: the kept one, or else the server's ([ask],
     * called at most once), which is then kept.
     */
    fun forVisit(noteId: String, ask: () -> AgentNoteListeningAnswer): AgentNoteListeningAnswer =
        fresh(noteId) ?: ask().also { keep(noteId, it) }

    /**
     * Follows [noteId] for as long as its screen is visible (until the caller is cancelled): the
     * kept answer or the server's at once, then the server's every [RECHECK_MILLIS], whatever is
     * kept, since only the server knows that an agent started or stopped meanwhile. Each answer
     * goes to [show] and is kept; a request that fails goes to [failed] and leaves what was shown.
     */
    suspend fun follow(
        noteId: String,
        ask: suspend () -> AgentNoteListeningAnswer,
        show: (AgentNoteListeningAnswer) -> Unit,
        failed: (Exception) -> Unit = {},
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ) {
        var visit = true
        while (true) {
            try {
                show((if (visit) fresh(noteId) else null) ?: ask().also { keep(noteId, it) })
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                failed(error)
            }
            visit = false
            sleep(RECHECK_MILLIS)
        }
    }

    companion object {
        /** How long a note's answer is kept. */
        const val KEEP_MILLIS = 60_000L
        /** How often a note that stays on screen asks the server again (it was every 20 s). */
        const val RECHECK_MILLIS = 60_000L
        private const val MAX_NOTES = 200

        /** The answers of this process, shared by every note screen. */
        val shared = AgentNoteListeningCache()
    }
}

/**
 * What the server can do, asked once and kept: for [keepMillis] while it lacks a capability (it
 * may be updated meanwhile), and for good once it has it. A request that fails keeps nothing.
 */
class AgentNoteServerCapabilities(
    private val keepMillis: Long = 10 * 60_000L,
    private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var kept: Set<String>? = null
    private var keptAtMillis = 0L

    /** Whether the server has [capability]; [fetch] asks it when nothing recent is kept. */
    fun has(capability: String, fetch: () -> Set<String>): Boolean {
        synchronized(this) {
            val known = kept
            if (known != null && (capability in known || nowMillis() - keptAtMillis in 0 until keepMillis)) {
                return capability in known
            }
        }
        val fetched = fetch()
        synchronized(this) {
            kept = fetched
            keptAtMillis = nowMillis()
        }
        return capability in fetched
    }

    companion object {
        /** The capabilities of the server this process talks to. */
        val shared = AgentNoteServerCapabilities()
    }
}

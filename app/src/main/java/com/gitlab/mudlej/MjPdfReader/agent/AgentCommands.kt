// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.agent

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteFilter
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteStore
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.mergeAgentNotes
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.review.basicPdfSha256
import com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ExplicitSyncOrigin
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncCommandCoordinator
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.syncCommandResult
import com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNoteActivity
import com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNotesListActivity
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.links.ThoughtsCitationLinkActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewAnswerTextLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.tbend.agent.Agent
import org.tbend.agent.AgentActivity
import org.tbend.agent.AgentCommand
import org.tbend.agent.AgentError
import java.io.File
import java.lang.ref.WeakReference
import java.time.Instant

/** The review card a reader shows, for the agent API. */
internal data class AgentReviewCardState(val card: ReviewCard, val answerVisible: Boolean)

/** What a reader shows, for the agent API: its PDF, document fields (agentDocumentFields), page and card. */
internal data class AgentReaderState(
    val uri: Uri?,
    val fields: Map<String, String>,
    val page: Int,
    val pages: Int,
    val review: AgentReviewCardState?,
)

/**
 * Thoughts' agent API (AGENT-API.md, org.tbend.agent): read the Library and the screen, open
 * documents, review cards and agent notes, and start the same Sync as the Sync button. Nothing here
 * rates, edits, deletes, imports, renames, archives or decides proposals: those stay a tap.
 */
object AgentCommands {
    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(Screens)
        Agent.register(
            background(
                "status", "",
                "What Thoughts shows now: the screen, the document and page in the reader, the review card " +
                    "(question, whether its answer is visible) and the last Sync.",
            ) { context, _ -> status(context) },
            background(
                "list_projects", "query?",
                "Projects of the Library on this phone, with their number of documents.",
            ) { context, arg ->
                val projects = agentProjects(LibraryProjectStore(context.applicationContext).projects())
                    .filter { agentMatches(it.name, arg.optString("query")) }
                JSONObject().put("items", JSONArray(projects.map { project ->
                    JSONObject().put("id", project.id).put("name", project.name).put("documents", project.documentIds.size)
                }))
            },
            background(
                "list_documents", "query?, project? (name or id), archived? (false), limit? (50)",
                "Library documents on this phone: id, title, project, PDF filename. query = words of the title.",
            ) { context, arg ->
                val matches = filterAgentDocuments(
                    libraryDocuments(context), arg.optString("query"), arg.optString("project"),
                    includeArchived = arg.optBoolean("archived", false),
                )
                val limit = arg.optInt("limit", 50).coerceIn(1, 500)
                JSONObject().put("count", matches.size).put("items", JSONArray(matches.take(limit).map(::documentJson)))
            },
            background(
                "open_document", "id or query, project?, page? (1-based)",
                "Opens a Library document in the reader, at a page when given.",
                needsActivity = true,
            ) { context, arg -> openDocument(context, arg) },
            background(
                "review", "id or query of a document? (default: every document), project?",
                "Opens the next due review card in the reader, like Start review on Home. Rating it stays a tap.",
                needsActivity = true,
            ) { context, arg -> startReview(context, arg) },
            background(
                "current_card", "answer? (true: include the answer text)",
                "The review card in the reader: question, note, document, pages, whether the answer is visible, " +
                    "and the answer text from the PDF.",
            ) { context, arg -> currentCard(context, arg.optBoolean("answer", true)) },
            AgentCommand(
                "reveal", "",
                "Shows the answer of the review card in the reader (the Reveal button). Records nothing.",
            ) { _, _ ->
                val reader = Screens.reader()?.takeIf { it.agentReaderState()?.review != null }
                    ?: throw AgentError(NO_CARD)
                if (!reader.agentRevealAnswer()) throw AgentError("the card is busy (saving, or an area is being edited); try again")
                reader.agentReaderState()?.review?.let { cardJson(it) } ?: JSONObject()
            },
            background(
                "list_notes", "unanswered? (false), limit? (20)",
                "Agent notes on this phone, newest first: id, title, message, document or card, answer state.",
            ) { context, arg -> listNotes(context, arg) },
            background(
                "open_note", "id or query? (none: the notes list)",
                "Opens an agent note to draw an answer on, or the list of notes.",
                needsActivity = true,
            ) { context, arg -> openNote(context, arg) },
            background(
                "sync", "",
                "Starts the same manual Sync as the Sync button (or joins the one running). Poll sync_status.",
            ) { context, _ ->
                JSONObject(SyncCommandCoordinator(context.applicationContext)
                    .start(target = null, origin = ExplicitSyncOrigin.BACKGROUND_COMMAND).toJson())
            },
            background(
                "sync_status", "operation_id? (default: the latest Sync)",
                "State of a Sync: queued, syncing, building, waiting, succeeded or failed, with its message.",
            ) { context, arg ->
                val id = arg.optString("operation_id").ifBlank {
                    SyncOperationStore(context.applicationContext).latestGlobalOperation()?.id
                        ?: throw AgentError("no Sync has run recently; use sync")
                }
                JSONObject(SyncCommandCoordinator(context.applicationContext).status(id).toJson())
            },
        )
    }

    private const val NO_CARD = "no review card is shown in the reader; use review to start one"

    /**
     * A command that runs on the caller's thread (disk and database work). Android publishes
     * AgentProvider before App.onCreate runs, so a first call can arrive while it still installs
     * the stores; one round trip through the main thread waits until App.onCreate has returned.
     */
    private fun background(
        name: String,
        args: String,
        description: String,
        needsActivity: Boolean = false,
        run: (Context, JSONObject) -> Any?,
    ) = AgentCommand(name, args, description, needsActivity = needsActivity, mainThread = false) { context, arg ->
        Agent.onMain { }
        run(context, arg)
    }

    // ---- status ----

    private fun status(context: Context): JSONObject {
        val (screen, foreground, reader) = Agent.onMain {
            val top = Screens.top()
            Triple(screenName(top), Screens.foreground(), (top as? MainActivity)?.agentReaderState())
        }
        val out = JSONObject().put("screen", screen).put("foreground", foreground)
        reader?.let { state ->
            out.put("document", readerDocumentJson(state))
            state.review?.let { out.put("review_card", cardJson(it)) }
        }
        runCatching { syncJson(context) }.getOrNull()?.let { out.put("sync", it) }
        return out
    }

    private fun screenName(activity: Activity?): String = when (activity) {
        null -> "none"
        is MainActivity -> "reader"
        is HomeActivity -> "home"
        is AgentNoteActivity -> "note"
        is AgentNotesListActivity -> "notes"
        else -> activity.javaClass.simpleName
    }

    private fun readerDocumentJson(state: AgentReaderState): JSONObject {
        val file = state.uri?.takeIf { it.scheme == "file" }?.path?.let(::File)
        val id = file?.let { runCatching { DocumentIdentityStore().forFile(it)?.get("document_id")?.asString }.getOrNull() }
        return JSONObject()
            .put("id", id)
            .put("title", state.fields["document_name"]?.ifBlank { null } ?: file?.name)
            .put("project", state.fields["project"]?.ifBlank { null })
            .put("pdf", file?.name)
            .put("source", state.fields["source"]?.ifBlank { null })
            .put("page", state.page)
            .put("pages", state.pages)
    }

    private fun syncJson(context: Context): JSONObject {
        val store = SyncOperationStore(context.applicationContext)
        val out = JSONObject()
        val latest = store.latestGlobalOperation()
        if (latest == null) out.put("state", "none") else {
            val result = syncCommandResult(latest)
            out.put("state", result.state).put("operation_id", result.operationId)
                .put("message", result.message.ifBlank { null })
                .put("started_at", iso(latest.startedAtEpochMillis)).put("updated_at", iso(latest.updatedAtEpochMillis))
        }
        val lastSuccess = runBlocking { store.observeLastSuccessfulSyncAt().first() }
        if (lastSuccess > 0) out.put("last_success_at", iso(lastSuccess))
        return out
    }

    // ---- Library ----

    private fun libraryDocuments(context: Context): List<AgentDocument> {
        val snapshot = SourceLinkedPdfIndex().snapshot()
        return agentLibraryDocuments(
            DocumentIdentityStore().documentIdsByFilename(),
            snapshot.libraryDisplayNamesByFilename,
            agentProjects(LibraryProjectStore(context.applicationContext).projects()),
            snapshot.archivedLibraryFilenames,
        )
    }

    private fun documentJson(document: AgentDocument): JSONObject = JSONObject()
        .put("id", document.id).put("title", document.title).put("project", document.project.ifEmpty { null })
        .put("filename", document.filename).put("archived", document.archived)

    private fun pickDocument(context: Context, arg: JSONObject): AgentDocument {
        val project = arg.optString("project")
        val documents = libraryDocuments(context).filter { agentProjectMatches(it, project) }
        return agentPick(
            documents, arg.optString("id"), arg.optString("query", arg.optString("title")),
            idOf = AgentDocument::id, titleOf = AgentDocument::title,
            searchText = { "${it.title} ${it.filename}" }, describe = { it.label() },
            kind = "document", listCommand = "list_documents",
        )
    }

    private fun openDocument(context: Context, arg: JSONObject): JSONObject {
        val document = pickDocument(context, arg)
        val pageIndex = agentPageIndex(if (arg.has("page")) arg.optInt("page", 0) else null)
        val file = DocumentIdentityStore().fileForId(document.id)?.takeIf(File::isFile)
            ?: throw AgentError("'${document.title}' is not on this phone yet; Sync first")
        val intent = Intent(context, MainActivity::class.java).setData(Uri.fromFile(file))
        if (pageIndex != null) {
            // The same intent a Thoughts citation link opens (ThoughtsCitationLinkActivity).
            intent.action = Intent.ACTION_VIEW
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            intent.putExtra(ThoughtsCitationLinkActivity.EXTRA_CITATION_PAGE_INDEX, pageIndex)
            intent.putExtra(ThoughtsCitationLinkActivity.EXTRA_CITATION_VERSION_CHANGED, false)
        } else {
            // The same intent a tap on Home opens.
            intent.putExtra(HomeActivity.EXTRA_FROM_HOME, true)
        }
        startScreen(context, intent)
        return documentJson(document).put("opened", true).put("page", pageIndex?.plus(1))
    }

    // ---- review ----

    private fun startReview(context: Context, arg: JSONObject): JSONObject {
        val document = if (arg.optString("id").isBlank() && arg.optString("query", arg.optString("title")).isBlank()) null
            else pickDocument(context, arg)
        val pdfStem = document?.filename?.removeSuffix(".pdf")
        val snapshot = SourceLinkedPdfIndex().snapshot()
        val hashes = snapshot.libraryPdfSha256ByFilename.takeIf { snapshot.libraryAvailable && snapshot.generationCurrent }
        val store = ReviewStore(context.applicationContext)
        val due = store.dueCards(pdfStem = pdfStem, canonicalPdfSha256ByFilename = hashes)
        val card = due.firstOrNull() ?: throw AgentError(
            if (document == null) "no review card is due now" else "no review card of '${document.title}' is due now",
        )
        val file = store.reviewPdfFile(card)?.takeIf(File::isFile)
            ?: throw AgentError("the PDF of the next due card (${card.pdfStem}.pdf) is not on this phone; Sync first")
        // The same intent as Start review on Home (HomeActivity.startPdfReview).
        val intent = Intent(context, MainActivity::class.java).setData(Uri.fromFile(file))
            .putExtra(HomeActivity.EXTRA_FROM_HOME, true)
            .putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, card.id)
        pdfStem?.let { intent.putExtra(HomeActivity.EXTRA_REVIEW_PDF_STEM, it) }
        startScreen(context, intent)
        val filename = "${card.pdfStem}.pdf"
        return JSONObject().put("card_id", card.id).put("question", card.question)
            .put("document", snapshot.libraryDisplayNamesByFilename[filename]?.ifBlank { null } ?: card.pdfStem)
            .put("pdf", filename).put("due", due.size)
    }

    private fun cardJson(state: AgentReviewCardState): JSONObject {
        val card = state.card
        return JSONObject().put("card_id", card.id).put("question", card.question)
            .put("note", card.note.ifBlank { null })
            .put("pdf", "${card.pdfStem}.pdf")
            .put("pages", JSONArray(card.regions.map { it.page }.filter { it > 0 }.distinct().ifEmpty { listOfNotNull(card.pdfPage) }))
            .put("whole_document", card.wholeDocument)
            .put("answer_visible", state.answerVisible)
    }

    private fun currentCard(context: Context, withAnswer: Boolean): JSONObject {
        val (state, onScreen) = Agent.onMain {
            val reader = Screens.reader()
            reader?.agentReaderState() to (reader != null && reader === Screens.top())
        }
        val review = state?.review ?: throw AgentError(NO_CARD)
        val out = cardJson(review).put("on_screen", onScreen)
            .put("document", state.fields["document_name"]?.ifBlank { null })
        if (withAnswer && !review.card.wholeDocument) {
            answerText(context, review.card, state.uri)?.takeIf(String::isNotBlank)?.let { out.put("answer_text", it) }
        }
        return out
    }

    /** The text in the card's answer areas, from the reader's PDF or the Library PDF of the card's version. */
    private fun answerText(context: Context, card: ReviewCard, readerUri: Uri?): String? {
        val readerPdf = readerUri?.takeIf { it.scheme == "file" }?.path?.let(::File)
        val pdf = listOfNotNull(readerPdf, File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "${card.pdfStem}.pdf"))
            .firstOrNull { file ->
                file.isFile && runCatching { basicPdfSha256(file) }.getOrNull()?.equals(card.pdfSha256, ignoreCase = true) == true
            } ?: return null
        return ReviewAnswerTextLoader(context.applicationContext).regionsText(pdf, card.regions)
    }

    // ---- agent notes ----

    private fun notes(context: Context): List<com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteEntry> {
        val store = AgentNoteStore.under(context.applicationContext.filesDir)
        val local = store.notes().filterNot { it.deleted }
        return mergeAgentNotes(local, null, store.repliesByNote(), AgentNoteFilter())
    }

    private fun listNotes(context: Context, arg: JSONObject): JSONObject {
        val entries = notes(context).filter {
            !arg.optBoolean("unanswered", false) ||
                it.state == com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteAnswerState.NOT_ANSWERED
        }
        val limit = arg.optInt("limit", 20).coerceIn(1, 200)
        return JSONObject().put("count", entries.size).put("items", JSONArray(entries.take(limit).map { entry ->
            val note = entry.note
            JSONObject().put("id", note.noteId).put("title", note.title).put("message", note.message?.ifBlank { null })
                .put("created_at", note.createdAt).put("state", entry.state.name.lowercase())
                .put("opened", note.openedAt != null).put("replies", entry.replies.size)
                .put("document", note.documentFilename?.ifBlank { null })
                .put("review_question", note.reviewQuestion?.ifBlank { null })
        }))
    }

    private fun openNote(context: Context, arg: JSONObject): JSONObject {
        val query = arg.optString("query", arg.optString("title"))
        if (arg.optString("id").isBlank() && query.isBlank()) {
            startScreen(context, AgentNotesListActivity.intent(context))
            return JSONObject().put("opened", "notes")
        }
        val entry = agentPick(
            notes(context), arg.optString("id"), query,
            idOf = { it.note.noteId }, titleOf = { it.note.title },
            searchText = { "${it.note.title} ${it.note.message.orEmpty()}" },
            kind = "note", listCommand = "list_notes", firstWhenAmbiguous = true,
        )
        startScreen(context, AgentNoteActivity.intent(context, entry.note.noteId))
        return JSONObject().put("opened", "note").put("id", entry.note.noteId).put("title", entry.note.title)
    }

    // ---- helpers ----

    /** Its own task, like notes opened by Study mode: AgentActivity's task is excluded from Recents. */
    private fun startScreen(context: Context, intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun iso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    /** The activity on top (main thread only), ignoring AgentActivity, and the last reader shown. */
    private object Screens : Application.ActivityLifecycleCallbacks {
        private var resumed: WeakReference<Activity>? = null
        private var top: WeakReference<Activity>? = null
        private var reader: WeakReference<MainActivity>? = null

        fun top(): Activity? = top?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        fun foreground(): Boolean = resumed?.get() != null
        fun reader(): MainActivity? = reader?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

        override fun onActivityResumed(activity: Activity) {
            if (activity is AgentActivity) return
            resumed = WeakReference(activity)
            top = WeakReference(activity)
            if (activity is MainActivity) reader = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (top?.get() === activity) top = null
            if (reader?.get() === activity) reader = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditingGate
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.view.ActionMode
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.source.AgentProposals
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.ProposalDecisionClaims
import com.gitlab.mudlej.MjPdfReader.data.sync.EditorDecisionLock
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.decisionsCarriedBySourceEdit
import com.gitlab.mudlej.MjPdfReader.data.sync.editorDecisionLocks
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.SourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Offline, app-owned editor. No remote pages, frames, files, or navigation reach its bridge. */
class ProposalSourceActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var queue: SourceEditQueue
    private lateinit var snapshot: SourceSnapshot
    private lateinit var journal: ProposalReviewJournal
    private lateinit var initial: JsonObject
    private lateinit var repository: FedoraRepository
    private var expectedWorkingSource = ""
    private var filename = ""
    private var pdfHash = ""
    private var ready = false
    private var cacheKey = ""
    private var closeDialogShowing = false
    private var keyboardRequested = false
    private var initialKeyboardConsumed = false

    private val diagnosticId = UUID.randomUUID().toString().take(8)
    private val startedAt = SystemClock.elapsedRealtime()
    @Volatile private var startupStage = "created"
    @Volatile private var javascriptInitialized = false

    private fun trace(stage: String) {
        startupStage = stage
        Log.i("ThoughtsEditor", "$diagnosticId +${SystemClock.elapsedRealtime() - startedAt}ms $stage")
    }

    // Exception messages, URLs, console text and source contents may contain private prose.
    private fun traceFailure(stage: String, error: Exception) {
        trace("$stage failed type=${error.javaClass.simpleName}")
        error.stackTrace.take(8).forEach { Log.e("ThoughtsEditor", "$diagnosticId at $it") }
    }

    private fun assetLabel(url: String?): String =
        android.net.Uri.parse(url.orEmpty()).lastPathSegment?.takeIf { it in ASSETS } ?: "other"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trace("activity-create")
        supportActionBar?.hide()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            if (intent.hasExtra(SELECTED_TEXT)) WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        web = WebView(this)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            trace("webview-created version=${WebView.getCurrentWebViewPackage()?.versionName}")
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                trace("console level=${message.messageLevel()} asset=${assetLabel(message.sourceId())} line=${message.lineNumber()}")
                return true
            }
        }
        web.isSaveEnabled = false // The journal owns potentially large editor state.
        // A WebView draws its page under its own padding, so the system-bar
        // padding goes on a container (Android 15+ always draws edge to edge).
        val root = android.widget.FrameLayout(this).apply { addView(web) }
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            setSupportMultipleWindows(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                trace("navigation-blocked asset=${assetLabel(request.url.toString())} main=${request.isForMainFrame}")
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                trace("page-started asset=${assetLabel(url)}")
            }
            override fun onPageCommitVisible(view: WebView, url: String?) {
                trace("page-visible asset=${assetLabel(url)}")
            }
            override fun onPageFinished(view: WebView, url: String?) {
                trace("page-finished asset=${assetLabel(url)}")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                trace("page-error code=${error.errorCode} asset=${assetLabel(request.url.toString())} main=${request.isForMainFrame}")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                trace("http-error code=${response.statusCode} asset=${assetLabel(request.url.toString())} main=${request.isForMainFrame}")
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val name = request.url.lastPathSegment.orEmpty()
                val allowed = request.url.scheme == "https" && request.url.host == HOST &&
                    request.url.path == "/$name" && name in ASSETS
                trace("asset-request name=${if (allowed) name else "blocked"} main=${request.isForMainFrame}")
                if (!allowed) return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                return WebResourceResponse(when {
                    name.endsWith(".js") -> "application/javascript"
                    name.endsWith(".css") -> "text/css"
                    else -> "text/html"
                }, "utf-8", try {
                    assets.open("proposal-review/$name")
                } catch (error: Exception) {
                    traceFailure("asset-open $name", error)
                    throw error
                })
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ready) web.evaluateJavascript("reviewApp.close()", null)
                else finish()
            }
        })
        lifecycleScope.launch {
            delay(10_000)
            if (!javascriptInitialized) {
                val lastStage = startupStage
                trace("startup-timeout last=$lastStage")
                web.evaluateJavascript("JSON.stringify({ready:document.readyState,body:!!document.body,app:!!window.reviewApp})") {
                    Log.i("ThoughtsEditor", "$diagnosticId startup-dom=$it")
                }
            }
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    trace("repository-init-start")
                    queue = SourceEditQueue(this@ProposalSourceActivity, scheduleRequests = false)
                    repository = FedoraRepository(applicationContext)
                    cacheKey = intent.getStringExtra(CACHE_KEY).orEmpty()
                    trace("snapshot-start")
                    snapshot = resolveSnapshot()
                    trace("snapshot-ready chars=${snapshot.contents.length}")
                    filename = intent.getStringExtra(PDF_FILENAME)?.takeIf(String::isNotBlank)
                        ?: requireNotNull(snapshot.bundlePdfFilename)
                    pdfHash = intent.getStringExtra(PDF_HASH)?.takeIf(String::isNotBlank)
                        ?: requireNotNull(snapshot.bundlePdfSha256)
                    trace("draft-read-start")
                    expectedWorkingSource = queue.localDraftContents(snapshot) ?: snapshot.contents
                    journal = ProposalReviewJournal(java.io.File(filesDir, "proposal-review"), snapshot.sourceFile)
                    trace("journal-read-start")
                    val journalStarted = System.nanoTime()
                    val recovered = journal.read()
                    trace("journal-reconcile-start recovered=${recovered != null}")
                    if (recovered != null) journal.recoverSave(recovered, expectedWorkingSource)
                    val state = if (recovered != null) {
                        if (SourceDraftMerge.reconcile(recovered, snapshot.sourceSha256, expectedWorkingSource)) {
                            journal.write(recovered)
                        }
                        recovered
                    } else JsonObject().apply {
                        addProperty("filename", snapshot.sourceFile)
                        addProperty("baseSha256", snapshot.sourceSha256)
                        addProperty("source", expectedWorkingSource)
                        addProperty("savedSource", expectedWorkingSource)
                        add("events", JsonArray()); add("undo", JsonArray()); add("redo", JsonArray())
                    }
                    trace("journal-ready")
                    Log.i("ThoughtsEditorTiming", "$diagnosticId open journal ${(System.nanoTime() - journalStarted) / 1_000_000} ms " +
                        "source=${state.get("source").asString.length} events=${state.getAsJsonArray("events").size()} " +
                        "undo=${state.getAsJsonArray("undo").size()}")
                    // Recover a durable source save interrupted before its journal confirmation.
                    journal.recoverSave(state, expectedWorkingSource)
                    val contents = state.get("source").asString
                    val fallback = contents.lineSequence().take((snapshot.sourceLine - 1).coerceAtLeast(0))
                        .sumOf { it.length + 1 }.coerceAtMost(contents.length)
                    val locks = decisionLocks(contents)
                    if (locks.isNotEmpty()) trace("decided-elsewhere count=${locks.size}")
                    initial = JsonObject().apply {
                        add("state", state); addProperty("proposalId", intent.getStringExtra(PROPOSAL).orEmpty())
                        add("decidedElsewhere", JsonObject().apply {
                            locks.forEach { (id, lock) ->
                                add(id, JsonObject().apply {
                                    addProperty("action", lock.action)
                                    addProperty("applied", lock.applied)
                                    if (lock.materialize) {
                                        addProperty("materialize", true)
                                        addProperty("group", lock.group)
                                    }
                                })
                            }
                        })
                        intent.getStringArrayExtra(CARD_MARKERS)?.takeIf { it.size == 2 }?.let { markers ->
                            add("cardMarkers", JsonArray().apply { markers.forEach(::add) })
                        }
                        if (intent.hasExtra(SELECTED_TEXT)) {
                            addProperty("pdfNavigation", true)
                            addProperty("openKeyboard", true)
                            addProperty("buildSource", snapshot.navigationBaseContents)
                            addProperty("selectedPdfText", intent.getStringExtra(SELECTED_TEXT).orEmpty())
                            addProperty("mappedLine", snapshot.sourceLine)
                            addProperty("pdfContext", snapshot.navigationContext)
                        } else {
                            addProperty("selectionStart", fallback)
                            addProperty("selectionEnd", fallback)
                        }
                    }
                }
                trace("initial-state-ready")
                web.addJavascriptInterface(Bridge(), "Native")
                ready = true
                trace("page-load-requested")
                web.loadUrl("https://$HOST/index.html")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                traceFailure("startup", error)
                val text = android.widget.TextView(this@ProposalSourceActivity).apply {
                    setPadding(24, 64, 24, 24); this.text = error.message; textSize = 16f
                }
                setContentView(text)
            }
        }
    }

    /** Decisions the PDF view or Overview made on proposals in [contents]; see [editorDecisionLocks]. */
    private suspend fun decisionLocks(contents: String): Map<String, EditorDecisionLock> {
        val ids = AgentProposals.parse(snapshot.sourceFile, contents).proposals.mapTo(hashSetOf()) { it.id }
        if (ids.isEmpty()) return emptyMap()
        val rows = AppDatabase.getInstance(applicationContext).thoughtsSyncDao().allOutbox()
        val published = runCatching {
            OfflineSourceBundleStore(applicationContext).agentProposals(filename)
                .filter { it.id in ids }
                .associate { it.id to it.bundleId }
        }.getOrDefault(emptyMap())
        return editorDecisionLocks(rows, ids, published)
    }

    /** Both ordinary PDF/source opens and proposal opens converge here. */
    /**
     * A snapshot cached before a Sync predates the synced source. Opening it showed the old text,
     * without the edit just synced, and the next save conflicted with that edit. Reopen the
     * current offline copy of the same file instead.
     */
    private fun currentOfflineSnapshot(cached: SourceSnapshot): SourceSnapshot {
        if (cached.bundleId == null) return cached
        val current = queue.openOfflineSource(cached.sourceFile, cached.sourceLine) ?: return cached
        val latest = current.cachedSnapshot ?: return cached
        // Only changed content is stale; a matching file keeps the cached navigation to the passage.
        if (latest.sourceSha256 == cached.sourceSha256) return cached
        cacheKey = current.cacheKey
        return latest
    }

    private suspend fun resolveSnapshot(): SourceSnapshot {
        if (cacheKey.isBlank()) {
            val handle = queue.openOfflineSource(intent.getStringExtra(SOURCE).orEmpty(), intent.getIntExtra(LINE, 1))
                ?: error("Source is not on this device. Run Sync to download it, then reopen Edit source.")
            cacheKey = handle.cacheKey
            return requireNotNull(handle.cachedSnapshot)
        }
        queue.recoverDeliveredRequests()
        queue.cachedSnapshot(cacheKey)?.let { return currentOfflineSnapshot(it) }
        val requestId = intent.getStringExtra(REQUEST_ID).orEmpty()
        check(requestId.isNotBlank()) { "Source is unavailable locally. Run Sync, then reopen Edit source." }
        val receipt = repository.resolveSourceOpenRequest(requestId)
        check(receipt.status == SourceEditQueue.STATUS_SUCCESS) { receipt.message }
        val resolved = requireNotNull(receipt.snapshot) { "Source resolution returned no source." }
        queue.cacheSnapshot(cacheKey, resolved)
        queue.acknowledge(requestId)
        return resolved
    }

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        if (!ready) return
        web.evaluateJavascript("reviewApp.hasReviewSelection()") { hasSelection ->
            if (hasSelection == "true") {
                for ((action, title) in listOf("accept" to "Accept", "reject" to "Reject")) {
                    mode.menu.add(title).setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        .setOnMenuItemClickListener {
                            web.evaluateJavascript("reviewApp.decideSelection('$action')", null)
                            mode.finish(); true
                        }
                }
                mode.invalidate()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && keyboardRequested) web.post { showRequestedKeyboard() }
    }

    private fun showRequestedKeyboard() {
        if (!keyboardRequested || !web.hasWindowFocus()) return
        keyboardRequested = false
        web.requestFocus()
        // WebView focus can move DOM focus to a toolbar button; restore the target
        // after native focus, before asking the IME to attach to its input connection.
        web.evaluateJavascript("reviewApp.focusKeyboardTarget()") { focused ->
            if (focused == "true") {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun load(): String {
            val payload = initial.toString()
            trace("bridge-load chars=${payload.length}")
            return payload
        }
        @JavascriptInterface fun editorDiagnostic(event: String, asset: String, line: Int) {
            if (event !in setOf("bootstrap", "initialize-start", "initialize-ready", "initialize-failed", "script-error", "unhandled-rejection")) return
            if (event == "initialize-ready") javascriptInitialized = true
            trace("javascript event=$event asset=${assetLabel(asset)} line=${line.coerceIn(0, 100000)}")
        }
        @JavascriptInterface fun sha256(text: String): String = digest(text)
        @JavascriptInterface fun uuid(): String = UUID.randomUUID().toString()
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
        /** Opens the Claude conversation that wrote a proposal (spec 3.6) in the Claude app or a browser. */
        @JavascriptInterface fun openConversation(url: String) {
            if (!url.startsWith("https://claude.ai/code/session_") || !AgentProposals.validConversationUrl(url)) return
            runOnUiThread {
                com.gitlab.mudlej.MjPdfReader.ui.agents.AgentConversationLinks.open(this@ProposalSourceActivity, url)
            }
        }
        @JavascriptInterface fun requestKeyboard() { runOnUiThread {
            if (!intent.hasExtra(SELECTED_TEXT) || initialKeyboardConsumed) return@runOnUiThread
            initialKeyboardConsumed = true
            keyboardRequested = true
            web.requestFocus()
            web.post { showRequestedKeyboard() }
        } }
        @JavascriptInterface fun initialSelectionReady() { runOnUiThread {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(web.windowToken, 0)
        } }
        @JavascriptInterface fun copy(text: String) { runOnUiThread {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Source text", text))
        } }
        @JavascriptInterface fun copyMarker(label: String, text: String) { runOnUiThread {
            com.gitlab.mudlej.MjPdfReader.ui.review.ReviewManualMarkers.copy(this@ProposalSourceActivity, label, text)
        } }
        @JavascriptInterface fun paste(): String = runBlocking(Dispatchers.Main) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this@ProposalSourceActivity)?.toString().orEmpty()
        }
        @JavascriptInterface fun requestClose(changed: Boolean) { runOnUiThread {
            if (!changed) { finish(); return@runOnUiThread }
            if (closeDialogShowing) return@runOnUiThread
            closeDialogShowing = true
            MaterialAlertDialogBuilder(this@ProposalSourceActivity)
                .setTitle("Save changes?")
                .setMessage("Save on this phone, discard changes since the last save, or keep editing.")
                .setPositiveButton("Save") { _, _ -> web.evaluateJavascript("reviewApp.save(true)", null) }
                .setNegativeButton("Discard") { _, _ -> web.evaluateJavascript("reviewApp.discardAndClose()", null) }
                .setNeutralButton("Cancel", null)
                .setOnDismissListener { closeDialogShowing = false }.show()
        } }
        @JavascriptInterface fun persist(raw: String): String = result {
            // The page persists ~0.7 s after typing; keyboard input never reaches onUserInteraction.
            SourceEditingGate.shared.noteInput()
            val started = System.nanoTime()
            val payload = JsonParser.parseString(raw).asJsonObject
            // A patch carries only what changed since the last persist; a whole state comes after
            // ResyncRequired and on the first persist of a new draft.
            val response = if (payload.has("patch")) {
                journal.persist(payload, SourceEditQueue.MAX_SOURCE_BYTES)
            } else {
                val state = validated(payload)
                journal.write(state)
                JsonObject().apply {
                    add("journalRevision", state.get("journalRevision"))
                    addProperty("eventCount", state.getAsJsonArray("events").size())
                }
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            Log.i("ThoughtsEditorTiming", "$diagnosticId persist ${if (payload.has("patch")) "patch" else "full"} " +
                "$millis ms chars=${raw.length}")
            response
        }
        /** The exact record shown in History; the journal stores whole-document evidence separately. */
        @JavascriptInterface fun eventRecord(eventId: String): String =
            runCatching { journal.hydratedEvent(eventId)?.toString() }.getOrNull().orEmpty()
        @JavascriptInterface fun save(raw: String): String = save(raw, confirmedProposalRemoval = false)
        @JavascriptInterface fun saveConfirmed(raw: String): String = save(raw, confirmedProposalRemoval = true)
        /** Removing a wrapper by hand is not a decision (agent-proposal spec section 10.5): ask first. */
        @JavascriptInterface fun confirmProposalRemoval(closeAfter: Boolean) { runOnUiThread {
            MaterialAlertDialogBuilder(this@ProposalSourceActivity)
                .setTitle(com.gitlab.mudlej.MjPdfReader.R.string.source_editor_proposal_removed_title)
                .setMessage(com.gitlab.mudlej.MjPdfReader.R.string.source_editor_proposal_removed_message)
                .setPositiveButton(com.gitlab.mudlej.MjPdfReader.R.string.source_editor_proposal_removed_save) { _, _ ->
                    web.evaluateJavascript("reviewApp.save($closeAfter, true)", null)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } }
        private fun save(raw: String, confirmedProposalRemoval: Boolean): String = result {
            val payload = JsonParser.parseString(raw).asJsonObject
            val state = if (payload.has("patch")) journal.patched(payload, SourceEditQueue.MAX_SOURCE_BYTES) else validated(payload)
            val current = (if (snapshot.bundleId != null) queue.openOfflineSource(snapshot.sourceFile, snapshot.sourceLine)?.cachedSnapshot
                else queue.cachedSnapshot(cacheKey)) ?: error("Source is no longer available locally.")
            val currentSource = queue.localDraftContents(current) ?: current.contents
            if (current.sourceSha256 != snapshot.sourceSha256 || currentSource != expectedWorkingSource) {
                SourceDraftMerge.reconcile(state, current.sourceSha256, currentSource)
                journal.write(state)
                snapshot = current
                expectedWorkingSource = currentSource
                return@result JsonObject().apply {
                    add("state", state)
                    addProperty("reconciled", true)
                    addProperty("warning", "Source updated. Your draft was merged; review the result before saving.")
                }
            }
            val source = state.get("source").asString
            check(!com.gitlab.mudlej.MjPdfReader.data.source.containsConflictMarkers(source)) {
                "Resolve the conflict markers before saving. Your draft is preserved on this phone."
            }
            // Decision claims (section 10.4.2): wrappers of the save's base that this save removes
            // through a decision recorded in the review journal.
            val claimed = state.getAsJsonArray(ProposalDecisionClaims.CLAIMED_EVENTS)
                ?.mapNotNull { runCatching { it.asString }.getOrNull() }?.toSet().orEmpty()
            val derived = ProposalDecisionClaims.derive(
                snapshot.sourceFile, expectedWorkingSource, source,
                state.getAsJsonArray("events").map { it.asJsonObject }, claimed,
            ) { UUID.randomUUID().toString() }
            if (!confirmedProposalRemoval) {
                val decided = derived.mapTo(hashSetOf()) { it.claim.proposalId }
                val byHand = ProposalDecisionClaims.removedWithoutDecision(snapshot.sourceFile, expectedWorkingSource, source) - decided
                if (byHand.isNotEmpty()) return@result JsonObject().apply { addProperty("confirmProposalRemoval", true) }
            }
            journal.prepareSave(state)
            // The claims are written with the draft and the request, before the decided text.
            queue.saveForNextSync(snapshot, filename, pdfHash, source, derived.map { it.claim })
            if (derived.isNotEmpty()) {
                state.add(ProposalDecisionClaims.CLAIMED_EVENTS, JsonArray().apply {
                    (claimed + derived.map { it.activationEventId }).forEach(::add)
                })
            }
            expectedWorkingSource = source
            journal.finishSave(state, source)
            retireDecisionsCarriedBySave()
            JsonObject().apply { add("state", state) }
        }
    }
    /**
     * A PDF-view Accept the editor showed as source text now travels as this source's decision
     * claim; its queued resolution must not reach Fedora as well (see [decisionsCarriedBySourceEdit]).
     * The save is already durable: if this fails, the resolution is sent first and the upload
     * merges onto the same accepted text.
     */
    private fun retireDecisionsCarriedBySave() {
        runCatching {
            val decided = queue.editorDecidedProposalIds(snapshot.sourceFile)
            if (decided.isEmpty()) return
            runBlocking(Dispatchers.IO) {
                val database = AppDatabase.getInstance(applicationContext)
                val dao = database.thoughtsSyncDao()
                val retired = database.withTransaction {
                    decisionsCarriedBySourceEdit(dao.allOutbox(), decided).onEach { operationId ->
                        dao.reconcileDeletion(
                            operationId, ThoughtsOutboxEntity.STATE_SUPERSEDED, null, "decided_in_source_editor",
                            "Carried by the source edit of ${snapshot.sourceFile}", System.currentTimeMillis(),
                        )
                    }
                }
                if (retired.isNotEmpty()) trace("retired-pdf-decisions count=${retired.size}")
            }
        }.onFailure { Log.w("ThoughtsEditor", "$diagnosticId could not retire PDF-view decisions", it) }
    }
    /** A whole state from the page; the journal validates its events when writing it. */
    private fun validated(state: JsonObject): JsonObject {
        check(state.get("filename").asString == snapshot.sourceFile && state.get("baseSha256").asString == snapshot.sourceSha256)
        check(state.get("source").asString.toByteArray().size <= SourceEditQueue.MAX_SOURCE_BYTES) { "Source is too large." }
        return state
    }
    private fun result(block: () -> JsonObject): String = try { block().toString() } catch (e: ProposalReviewJournal.ResyncRequired) {
        trace("journal-resync reason=${e.message}")
        JsonObject().apply { addProperty("resync", true) }.toString()
    } catch (e: Exception) {
        JsonObject().apply { addProperty("error", e.message ?: "Unable to save review. Your source was preserved.") }.toString()
    }
    override fun onResume() {
        super.onResume()
        SourceEditingGate.shared.editorResumed()
    }
    override fun onUserInteraction() {
        super.onUserInteraction()
        SourceEditingGate.shared.noteInput()
    }
    override fun onPause() {
        SourceEditingGate.shared.editorPaused()
        if (ready) web.evaluateJavascript("try { reviewApp.flush(); } catch(e) {}", null)
        super.onPause()
    }
    override fun onDestroy() {
        trace("activity-destroy initialized=$javascriptInitialized")
        web.removeJavascriptInterface("Native")
        web.destroy()
        super.onDestroy()
    }
    companion object {
        private const val HOST = "proposal-review.invalid"
        private val ASSETS = setOf("index.html", "diagnostics.js", "style.css", "modules.js", "history.js", "engine.js", "sourceModel.js", "sourceNavigation.js", "ui.js")
        private const val SOURCE = "proposal_source_file"
        private const val LINE = "proposal_source_line"
        private const val PROPOSAL = "proposal_id"
        private const val CACHE_KEY = "source_cache_key"
        private const val REQUEST_ID = "source_request_id"
        private const val PDF_FILENAME = "source_pdf_filename"
        private const val PDF_HASH = "source_pdf_sha256"
        private const val SELECTED_TEXT = "source_selected_text"
        private const val CARD_MARKERS = "source_card_markers"
        const val PRODUCER = "android-proposal-review"
        internal fun intent(context: Context, source: String, line: Int, proposal: String, cardMarkers: List<String>? = null) =
            Intent(context, ProposalSourceActivity::class.java).putExtra(SOURCE, source).putExtra(LINE, line).putExtra(PROPOSAL, proposal)
                .apply { cardMarkers?.let { putExtra(CARD_MARKERS, it.toTypedArray()) } }
        internal fun sourceIntent(context: Context, requestId: String, cacheKey: String, pdfFilename: String, pdfHash: String, selectedText: String) =
            Intent(context, ProposalSourceActivity::class.java).putExtra(REQUEST_ID, requestId).putExtra(CACHE_KEY, cacheKey)
                .putExtra(PDF_FILENAME, pdfFilename).putExtra(PDF_HASH, pdfHash)
                .putExtra(SELECTED_TEXT, selectedText.take(SourceEditQueue.MAX_SELECTED_TEXT))
        internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

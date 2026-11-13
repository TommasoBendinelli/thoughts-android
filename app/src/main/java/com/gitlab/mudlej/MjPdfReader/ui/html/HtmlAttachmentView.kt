// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.html

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentFile
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.ByteArrayInputStream

/**
 * A WebView showing one HTML attachment page (a standalone interactive `thoughts-html:` page)
 * with its own scripts, and the shared viewer injected in attachment mode
 * (thoughts-html-attachment-cards-SPEC.md, "Android").
 *
 * - The verified attachment file is the only thing served: `https://html-attachment.thoughts.invalid/a/<sha>/<file>`,
 *   answered by [WebViewClient.shouldInterceptRequest] with the attachment CSP (the page's scripts
 *   run, nothing reaches the network). Every other request is refused, network loads are blocked,
 *   there is no file, content, DOM storage or database access, no new windows and no permissions.
 * - Navigation away from the page is refused; `#hash` changes stay in the page. A web link the
 *   person taps (a user gesture) goes to [onExternalLink], which may open the browser.
 * - The viewer talks back through [BRIDGE]. Page scripts can call that object too, so its messages
 *   are UI hints only (a selection, a tap): a card is only made or changed by a native action.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class HtmlAttachmentView(context: Context) : WebView(context) {
    /** Viewer → app messages, on the main thread. */
    fun interface Listener {
        fun onViewerMessage(type: String, message: JsonObject)
    }

    var listener: Listener? = null
    var selectionMenu: HtmlEditionView.SelectionMenu? = null
    /** A tapped web link (http, https, mailto); the page itself never leaves. */
    var onExternalLink: ((Uri) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    var attachment: HtmlAttachmentFile? = null
        private set
    private var options: JsonObject = JsonObject()
    /** Increases with every [show]; late callbacks of an older page are dropped. */
    private var generation = 0
    var viewerLoaded = false
        private set
    /** Requests refused because they left the page (tests check that nothing tried). */
    @Volatile var refusedRequests = 0
        private set

    init {
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            domStorageEnabled = false
            @Suppress("DEPRECATION")
            databaseEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = false
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isAlgorithmicDarkeningAllowed = false
        }
        isSaveEnabled = false
        setDownloadListener { _, _, _, _, _ -> refusedRequests++ }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(TAG, "console ${message.message().take(300)} (${message.lineNumber()})")
                }
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
                callback?.onReceiveValue(null)
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                if (request.isForMainFrame && request.hasGesture() && scheme in setOf("http", "https", "mailto") && url.host != HOST) {
                    onExternalLink?.invoke(url)
                }
                // The page never navigates: a #hash link is handled in the page without loading.
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                serve(request.url, request.method)

            override fun onPageFinished(view: WebView, url: String?) {
                val attachment = attachment ?: return
                if (url?.substringBefore('#') != entryUrl(attachment)) return
                inject(generation)
            }
        }
        addJavascriptInterface(Bridge(), BRIDGE)
    }

    /** Shows [attachment]; [options] go to the viewer's `load()` (mode and transport are added here). */
    fun show(attachment: HtmlAttachmentFile, options: JsonObject) {
        this.attachment = attachment
        this.options = options.deepCopy().apply {
            addProperty("mode", "attachment")
            addProperty("contextMenu", "native")
            add("transport", JsonObject().apply { addProperty("kind", "android"); addProperty("object", BRIDGE) })
        }
        viewerLoaded = false
        generation++
        setBackgroundColor(Color.WHITE)
        loadUrl(entryUrl(attachment))
    }

    /** Leaves the page (nothing is shown until the next [show]). */
    fun clear() {
        generation++
        viewerLoaded = false
        attachment = null
        loadUrl("about:blank")
    }

    /** `ThoughtsViewer.call(name, args)`; [callback] gets the result (or a failure). */
    fun call(name: String, args: JsonArray = JsonArray(), callback: ((Result<JsonElement>) -> Unit)? = null) {
        if (!viewerLoaded && name != "load") {
            callback?.invoke(Result.failure(IllegalStateException("the HTML viewer is not loaded")))
            return
        }
        val expected = generation
        val script = "(function(){if(!window.ThoughtsViewer){return JSON.stringify({ok:false,error:'no viewer'});}" +
            "return ThoughtsViewer.call(${JsonPrimitive(name)}, ${JsonPrimitive(args.toString())});})()"
        evaluateJavascript(script) { raw ->
            if (expected != generation) return@evaluateJavascript
            val result = HtmlEditionView.decodeCallResult(raw)
            result.exceptionOrNull()?.let { Log.w(TAG, "viewer $name: ${it.message}") }
            callback?.invoke(result)
        }
    }

    fun callWith(name: String, vararg args: Any?, callback: ((Result<JsonElement>) -> Unit)? = null) =
        call(name, JsonArray().apply { args.forEach { add(HtmlEditionView.toJson(it)) } }, callback)

    private fun inject(expected: Int) {
        val (scripts, css) = HtmlViewerAssets.load(context.assets)
        fun next(index: Int) {
            if (expected != generation) return
            if (index < scripts.size) {
                evaluateJavascript(scripts[index]) { next(index + 1) }
                return
            }
            options.addProperty("css", css)
            viewerLoaded = true
            call("load", JsonArray().apply { add(options) }) { result ->
                if (result.isFailure) {
                    viewerLoaded = false
                    listener?.onViewerMessage("error", JsonObject().apply {
                        addProperty("message", result.exceptionOrNull()?.message ?: "load failed"); addProperty("where", "load")
                    })
                }
            }
        }
        next(0)
    }

    private fun serve(url: Uri, method: String?): WebResourceResponse {
        val attachment = attachment
        val path = url.encodedPath.orEmpty()
        val allowed = attachment != null && url.scheme == "https" && url.host == HOST && url.port == -1 &&
            method in setOf("GET", "HEAD", null) && path == entryPath(attachment) && url.encodedQuery == null
        val file = attachment?.file?.takeIf { allowed && it.isFile }
        if (file == null) {
            val favicon = url.host == HOST && path == "/favicon.ico"
            if (!favicon) refusedRequests++
            Log.i(TAG, "refused ${url.scheme}://${url.host}${if (url.host == HOST) path else ""}")
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", NO_STORE, ByteArrayInputStream(ByteArray(0)))
        }
        return WebResourceResponse("text/html", "utf-8", 200, "OK", NO_STORE + mapOf("Content-Security-Policy" to ATTACHMENT_CSP),
            file.inputStream())
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            // Page scripts can reach this object too: bounded, parsed, and only a UI hint.
            if (json.length > MAX_MESSAGE_CHARS) return
            val expected = generation
            main.post {
                if (expected != generation) return@post
                val message = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return@post
                val type = message.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return@post
                listener?.onViewerMessage(type, message)
            }
        }
    }

    // ---- Selection toolbar -----------------------------------------------------------------

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(callback?.let(::wrap), type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(callback?.let(::wrap))

    private fun wrap(original: ActionMode.Callback): ActionMode.Callback {
        val menu = selectionMenu ?: return original
        return object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, items: Menu): Boolean {
                original.onCreateActionMode(mode, items)
                items.clear()
                menu.populate(items)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, items: Menu): Boolean {
                original.onPrepareActionMode(mode, items)
                items.clear()
                menu.populate(items)
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (menu.onItem(item)) {
                    mode.finish()
                    return true
                }
                return original.onActionItemClicked(mode, item)
            }
            override fun onDestroyActionMode(mode: ActionMode) = original.onDestroyActionMode(mode)
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (original is ActionMode.Callback2) original.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
            }
        }
    }

    override fun destroy() {
        generation++
        listener = null
        selectionMenu = null
        onExternalLink = null
        removeJavascriptInterface(BRIDGE)
        super.destroy()
    }

    companion object {
        private const val TAG = "ThoughtsHtmlAttachment"
        const val HOST = "html-attachment.thoughts.invalid"
        const val ORIGIN = "https://$HOST"
        const val BRIDGE = "ThoughtsViewerBridge"
        private const val MAX_MESSAGE_CHARS = 2_000_000
        /**
         * The page's own scripts and styles run in an opaque origin (`sandbox allow-scripts`, as the
         * browser path's loopback server); nothing leaves the page: no network (`connect-src 'none'`,
         * and only the page itself is served for 'self'), no frames, plugins, forms or base.
         */
        const val ATTACHMENT_CSP = "sandbox allow-scripts; default-src 'self' 'unsafe-inline' data: blob:; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; connect-src 'none'; " +
            "frame-src 'none'; child-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; " +
            "form-action 'none'; manifest-src 'none'"
        private val NO_STORE = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer")

        fun entryPath(attachment: HtmlAttachmentFile) =
            "/a/${attachment.sha256}/${Uri.encode(HtmlAttachmentCards.servedName(attachment.path))}"

        fun entryUrl(attachment: HtmlAttachmentFile) = ORIGIN + entryPath(attachment)
    }
}

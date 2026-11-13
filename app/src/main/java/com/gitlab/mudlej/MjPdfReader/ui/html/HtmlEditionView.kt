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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionManifest
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionStore
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.ByteArrayInputStream

/**
 * A WebView showing one cached HTML edition with the shared viewer (assets/html-viewer).
 *
 * The edition is served from an in-process origin ([ORIGIN], answered by
 * [WebViewClient.shouldInterceptRequest] from [HtmlEditionStore]); every other request is refused,
 * network loads are blocked and there is no file or content access. The edition itself has no
 * scripts (`script-src 'none'`); the viewer is injected with `evaluateJavascript` in the order
 * geometry.js, text.js, viewer.js and talks back through the `ThoughtsViewerBridge` object.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class HtmlEditionView(context: Context) : WebView(context) {
    /** Viewer → app messages (README "Viewer → app messages"), on the main thread. */
    fun interface Listener {
        fun onViewerMessage(type: String, message: JsonObject)
    }

    /** Builds the selection toolbar's items; null keeps the WebView's own toolbar. */
    interface SelectionMenu {
        fun populate(menu: Menu)
        /** True when [item] was handled (the toolbar then closes). */
        fun onItem(item: MenuItem): Boolean
    }

    var listener: Listener? = null
    var selectionMenu: SelectionMenu? = null
    private val main = Handler(Looper.getMainLooper())
    private var store: HtmlEditionStore? = null
    var edition: HtmlEditionManifest? = null
        private set
    private var options: JsonObject = JsonObject()
    /** Increases with every [show]; late callbacks of an older page are dropped. */
    private var generation = 0
    var viewerLoaded = false
        private set
    /** Requests refused because they left the edition (tests check that nothing tried). */
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
            // The viewer's text size control scales the edition; the system font scale does not double it.
            textZoom = 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isAlgorithmicDarkeningAllowed = false
        }
        isSaveEnabled = false
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(TAG, "console ${message.message().take(300)} (${message.lineNumber()})")
                }
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                serve(request.url, request.method)

            override fun onPageFinished(view: WebView, url: String?) {
                val edition = edition ?: return
                if (url != entryUrl(edition)) return
                inject(generation)
            }
        }
        addJavascriptInterface(Bridge(), BRIDGE)
    }

    /** Shows [edition] from [store]; [options] go to the viewer's `load()` (transport is added here). */
    fun show(store: HtmlEditionStore, edition: HtmlEditionManifest, options: JsonObject) {
        this.store = store
        this.edition = edition
        this.options = options.deepCopy().apply {
            add("transport", JsonObject().apply { addProperty("kind", "android"); addProperty("object", BRIDGE) })
        }
        viewerLoaded = false
        generation++
        setBackgroundColor(if (options.get("theme")?.asString == "dark") Color.rgb(0x15, 0x15, 0x15) else Color.WHITE)
        loadUrl(entryUrl(edition))
    }

    /** Leaves the edition (nothing is shown until the next [show]). */
    fun clear() {
        generation++
        viewerLoaded = false
        edition = null
        loadUrl("about:blank")
    }

    /**
     * `ThoughtsViewer.call(name, args)`; [callback] gets the result (or null with the error logged).
     * Calls before the viewer is loaded are refused, as the viewer itself would.
     */
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
            val result = decodeCallResult(raw)
            result.exceptionOrNull()?.let { Log.w(TAG, "viewer $name: ${it.message}") }
            callback?.invoke(result)
        }
    }

    /** [call] with positional arguments converted by [toJson]. */
    fun callWith(name: String, vararg args: Any?, callback: ((Result<JsonElement>) -> Unit)? = null) =
        call(name, JsonArray().apply { args.forEach { add(toJson(it)) } }, callback)

    private fun inject(expected: Int) {
        val (scripts, css) = HtmlViewerAssets.load(context.assets)
        // Injected one by one in order; a second injection is a no-op in the viewer.
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
        val store = store
        val edition = edition
        val prefix = "/e/${edition?.sha256}/"
        val path = url.encodedPath.orEmpty()
        val allowed = store != null && edition != null && url.scheme == "https" && url.host == HOST &&
            url.port == -1 && method in setOf("GET", "HEAD", null) && path.startsWith(prefix)
        val relative = if (allowed) Uri.decode(path.removePrefix(prefix)) else ""
        val file = if (allowed && HtmlEditionManifest.validPath(relative)) store!!.file(edition!!, relative) else null
        if (file == null) {
            // The WebView's own favicon probe is not a request of the edition.
            val favicon = url.host == HOST && path == "/favicon.ico"
            if (!favicon) refusedRequests++
            Log.i(TAG, "refused ${url.scheme}://${url.host}${if (url.host == HOST) path else ""}" +
                if (allowed) " (not in the edition)" else "")
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", NO_STORE, ByteArrayInputStream(ByteArray(0)))
        }
        val type = HtmlEditionStore.mediaType(relative)
        val headers = NO_STORE + if (type == "text/html") mapOf("Content-Security-Policy" to EDITION_CSP) else emptyMap()
        return WebResourceResponse(type, if (type.startsWith("text/") || type.endsWith("+xml")) "utf-8" else null,
            200, "OK", headers, file.inputStream())
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
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
        removeJavascriptInterface(BRIDGE)
        super.destroy()
    }

    companion object {
        private const val TAG = "ThoughtsHtmlView"
        const val HOST = "html-edition.thoughts.invalid"
        const val ORIGIN = "https://$HOST"
        const val BRIDGE = "ThoughtsViewerBridge"
        /** The edition may load only its own styles, images and fonts; never a script or a frame. */
        const val EDITION_CSP = "default-src 'none'; script-src 'none'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; font-src 'self' data:; media-src 'self'; connect-src 'none'; frame-src 'none'; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'"
        private val NO_STORE = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer")

        fun entryUrl(edition: HtmlEditionManifest) = "$ORIGIN/e/${edition.sha256}/${Uri.encode(edition.entry, "/")}"

        /** `evaluateJavascript` hands back the call's JSON string JSON-encoded once more. */
        fun decodeCallResult(raw: String?): Result<JsonElement> = runCatching {
            val outer = JsonParser.parseString(raw ?: "null")
            check(outer.isJsonPrimitive && outer.asJsonPrimitive.isString) { "no result from the viewer" }
            val inner = JsonParser.parseString(outer.asString).asJsonObject
            if (inner.get("ok")?.asBoolean != true) error(inner.get("error")?.asString ?: "viewer call failed")
            inner.get("result") ?: JsonNull.INSTANCE
        }

        fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull.INSTANCE
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject().apply { value.forEach { (k, v) -> add(k.toString(), toJson(v)) } }
            is Iterable<*> -> JsonArray().apply { value.forEach { add(toJson(it)) } }
            else -> JsonPrimitive(value.toString())
        }
    }
}

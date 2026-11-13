// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import java.net.URI
import java.util.Locale

/**
 * The A/B testing tab: the card-pilot rating page at [PAGE_URL] (Gradle property
 * `thoughtsAbTestingUrl`; a build without it has no such tab, see [isConfigured]).
 * Ratings are saved by that page on its own server, never in Thoughts. Links to other hosts
 * open in the browser.
 */
internal class AbTestingTabController(private val activity: AppCompatActivity) {
    private var webView: WebView? = null
    private var loadFailed = false
    private var onReloaded: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(page: SwipeRefreshLayout) {
        if (!isConfigured) return
        val view = page.findViewById<WebView>(R.id.abTestingWebView)
        // A rebound page keeps its WebView, and reloading it would drop unsaved answers.
        if (view === webView) return
        webView = view
        // The page keeps its buttons at the bottom of the window: keep them above the navigation bar
        // (and the feedback field above the keyboard). A WebView ignores its own padding, so pad the page.
        ViewCompat.setOnApplyWindowInsetsListener(page) { target, insets ->
            val bottom = maxOf(insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, bottom)
            insets
        }
        ViewCompat.requestApplyInsets(page)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == PAGE_HOST) return false
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                loadFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                onReloaded?.invoke()
                onReloaded = null
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loadFailed = true
            }
        }
        view.loadUrl(PAGE_URL)
    }

    /** Pull-to-refresh: loads the page again (a new pilot round), dropping answers not yet saved; done runs once it has loaded. */
    fun reload(done: () -> Unit) {
        val view = webView ?: return done()
        onReloaded = done
        view.loadUrl(PAGE_URL)
    }

    /** Called when the tab is selected: retries a page that failed to load (e.g. Tailscale was off). */
    fun onSelected() {
        if (loadFailed) webView?.reload()
    }

    companion object {
        /** The rating page, ending in '/', or empty when this build has none. */
        val PAGE_URL: String = BuildConfig.AB_TESTING_URL.trim()
        val PAGE_HOST: String? = pageHost(PAGE_URL)

        /** Whether this build names a rating page; without one Home has no A/B testing tab. */
        val isConfigured: Boolean get() = PAGE_HOST != null

        internal fun pageHost(url: String): String? =
            url.takeIf(String::isNotBlank)
                ?.let { runCatching { URI(it) }.getOrNull() }
                ?.takeIf { it.scheme == "https" }
                ?.host
                ?.lowercase(Locale.ROOT)
                ?.takeIf(String::isNotEmpty)
    }
}

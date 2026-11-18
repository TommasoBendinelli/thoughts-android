// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMentalModelBinding
import java.net.URI
import java.util.Locale

/**
 * Review › Mental Model: what the user's review cards say about them, as written on the Mac (Review › Mental Model
 * there) and served by the card-pilot site this build names (Gradle property `thoughtsAbTestingUrl`; a build
 * without it has no Mental Model button). A cited card (`thoughts-card:<card id>`) opens that card; links to other
 * hosts open in the browser.
 */
class MentalModelActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMentalModelBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isAvailable) {
            finish()
            return
        }
        binding = ActivityMentalModelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15+ draws the app under the status and navigation bars: keep the title bar and page clear of them.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setSupportActionBar(binding.mentalModelToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.review_mental_model)
        val view = binding.mentalModelWebView
        view.settings.javaScriptEnabled = true
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == CARD_SCHEME) {
                    val cardId = url.schemeSpecificPart.orEmpty()
                    if (cardId.isNotBlank()) startActivity(ReviewCardDetailsActivity.intent(this@MentalModelActivity, cardId))
                    return true
                }
                if (url.host == PAGE_HOST) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.mentalModelRefresh.isRefreshing = false
            }
        }
        binding.mentalModelRefresh.setOnRefreshListener { view.reload() }
        binding.mentalModelRefresh.setOnChildScrollUpCallback { _, _ -> view.scrollY > 0 }
        if (savedInstanceState == null) view.loadUrl(PAGE_URL) else view.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) binding.mentalModelWebView.saveState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        /** The card-pilot site, ending in '/', or empty when this build names none; it serves this page as `mental-model`. */
        private val SITE_URL: String = BuildConfig.AB_TESTING_URL.trim()
        private val PAGE_HOST: String? = mentalModelPageHost(SITE_URL)
        private val PAGE_URL get() = SITE_URL + "mental-model"
        private const val CARD_SCHEME = "thoughts-card"

        /** False in a build that names no card-pilot site, which serves this page. */
        val isAvailable: Boolean get() = PAGE_HOST != null

        fun intent(context: Context): Intent = Intent(context, MentalModelActivity::class.java)
    }
}

/** The host of the card-pilot site's https [url], in lower case; null for a blank or non-https address. */
internal fun mentalModelPageHost(url: String): String? =
    url.takeIf(String::isNotBlank)
        ?.let { runCatching { URI(it) }.getOrNull() }
        ?.takeIf { it.scheme == "https" }
        ?.host
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)

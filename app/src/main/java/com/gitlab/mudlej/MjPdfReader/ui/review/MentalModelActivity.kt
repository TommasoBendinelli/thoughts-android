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
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMentalModelBinding
import com.gitlab.mudlej.MjPdfReader.ui.home.AbTestingTabController

/**
 * Review › Mental Model: what the user's review cards say about them, as written on the Mac (Review › Mental Model
 * there) and served next to the A/B testing page (Gradle property `thoughtsAbTestingUrl`; a build without it has
 * no Mental Model button). A cited card (`thoughts-card:<card id>`) opens that card; links to other hosts open in
 * the browser.
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
                if (url.host == AbTestingTabController.PAGE_HOST) return false
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
        private val PAGE_URL get() = AbTestingTabController.PAGE_URL + "mental-model"
        private const val CARD_SCHEME = "thoughts-card"

        /** False in a build without the A/B testing page, which serves this one. */
        val isAvailable: Boolean get() = AbTestingTabController.isConfigured

        fun intent(context: Context): Intent = Intent(context, MentalModelActivity::class.java)
    }
}

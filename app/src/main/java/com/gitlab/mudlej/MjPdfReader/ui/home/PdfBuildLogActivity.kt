// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.BackgroundColorSpan
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.sync.LocalBuildLogStore
import com.gitlab.mudlej.MjPdfReader.data.sync.serverLogSha256
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityPdfBuildLogBinding
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfBuildLogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPdfBuildLogBinding
    private var loadJob: Job? = null
    private lateinit var logReference: String
    private lateinit var pdfFilename: String
    private lateinit var source: String
    private lateinit var message: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfBuildLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        logReference = intent.getStringExtra(EXTRA_LOG_REFERENCE).orEmpty()
        pdfFilename = intent.getStringExtra(EXTRA_PDF_FILENAME).orEmpty()
        source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (pdfFilename.isBlank()) {
            getString(R.string.pdf_build_log_title)
        } else {
            getString(R.string.pdf_build_log_title_pdf, pdfFilename)
        }
        binding.logRetry.setOnClickListener { loadLog() }
        loadLog()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadLog() {
        loadJob?.cancel()
        binding.logStatus.setText(R.string.pdf_build_log_loading)
        binding.logRetry.visibility = View.GONE
        binding.logText.text = ""
        val store = LocalBuildLogStore()
        if (serverLogSha256(logReference) == null || !store.isAvailable(logReference)) {
            showFailure(getString(R.string.pdf_build_log_missing_local))
            return
        }
        loadJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    requireNotNull(store.read(logReference)) {
                        getString(R.string.pdf_build_log_missing_local)
                    }
                }
            }
            result.onSuccess(::showLog).onFailure { error ->
                val detail = error.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    .orEmpty()
                    .ifBlank { error.javaClass.simpleName }
                showFailure(getString(R.string.pdf_build_log_failed, detail))
            }
        }
    }

    private fun showLog(contents: String) {
        if (contents.isEmpty()) {
            showFailure(getString(R.string.pdf_build_log_empty))
            return
        }
        val anchor = pdfBuildLogAnchor(contents, source, message, pdfFilename)
        val displayed = SpannableString(contents)
        anchor?.takeIf { it.endExclusive > it.start }?.let {
            displayed.setSpan(
                BackgroundColorSpan(
                    MaterialColors.getColor(
                        binding.logText,
                        com.google.android.material.R.attr.colorSecondaryContainer,
                    )
                ),
                it.start,
                it.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        binding.logText.text = displayed
        binding.logStatus.text = getString(
            R.string.pdf_build_log_loaded,
            Formatter.formatShortFileSize(this, contents.toByteArray().size.toLong()),
        )
        binding.logRetry.visibility = View.GONE
        anchor?.let { target ->
            binding.logVerticalScroll.post {
                val layout = binding.logText.layout ?: return@post
                val line = layout.getLineForOffset(target.start.coerceIn(0, contents.lastIndex))
                val top = (layout.getLineTop(line) - binding.logVerticalScroll.height / 3)
                    .coerceAtLeast(0)
                binding.logVerticalScroll.scrollTo(0, top)
            }
        }
    }

    private fun showFailure(message: String) {
        binding.logStatus.text = message
        binding.logRetry.visibility = View.VISIBLE
    }

    companion object {
        private const val EXTRA_LOG_REFERENCE = "pdf_build_log_reference"
        private const val EXTRA_PDF_FILENAME = "pdf_build_log_pdf_filename"
        private const val EXTRA_SOURCE = "pdf_build_log_source"
        private const val EXTRA_MESSAGE = "pdf_build_log_message"

        fun intent(
            context: Context,
            logReference: String,
            pdfFilename: String,
            source: String,
            message: String,
        ): Intent = Intent(context, PdfBuildLogActivity::class.java).apply {
            putExtra(EXTRA_LOG_REFERENCE, logReference)
            putExtra(EXTRA_PDF_FILENAME, pdfFilename)
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_MESSAGE, message)
        }
    }
}

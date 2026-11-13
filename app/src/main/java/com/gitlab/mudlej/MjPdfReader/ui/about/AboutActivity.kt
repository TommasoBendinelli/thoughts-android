// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.about

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.linkIntent
import com.gitlab.mudlej.MjPdfReader.core.io.navIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.copyToClipboard
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.databinding.AboutRowItemBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityAboutBinding
import com.gitlab.mudlej.MjPdfReader.ui.intro.MainIntroActivity
import com.google.android.material.snackbar.Snackbar

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        bindHeader()
        bindRows()
    }

    private fun bindHeader() {
        val version = versionText()
        binding.versionChip.text = version
        binding.versionChip.setOnClickListener {
            copyToClipboard(this, getString(R.string.mj_app_name), version)
            AppSnackbar.make(binding.root, getString(R.string.copied), Snackbar.LENGTH_SHORT).show()
        }
        binding.featuredCard.setOnClickListener { openLink(MJ_PDF_REPO_URL) }
    }

    private fun bindRows() {
        bindRow(binding.whatsNewRow, R.drawable.log_icon, R.string.whats_new_title) {
            startActivity(navIntent(applicationContext, WhatsNewActivity::class.java))
        }
        bindRow(binding.appFeaturesRow, R.drawable.ic_awesome, R.string.features_title) {
            startActivity(navIntent(applicationContext, AppFeaturesActivity::class.java))
        }
        bindRow(binding.replayIntroRow, R.drawable.replay_icon, R.string.intro) {
            startActivity(navIntent(applicationContext, MainIntroActivity::class.java))
        }
        bindRow(binding.privacyRow, R.drawable.privacy_icon, R.string.privacy) {
            PrivacyInfoDialog().show(supportFragmentManager, PrivacyInfoDialog.TAG)
        }
        bindRow(binding.licenseRow, R.drawable.license_icon, R.string.myLicense) {
            openLink(LICENSE_URL)
        }
        bindRow(binding.sourceCodeRow, R.drawable.code_icon, R.string.code) {
            openLink(REPO_URL)
        }
        bindRow(binding.librariesRow, R.drawable.lib_icon, R.string.libs) {
            OpenSourceLibrariesDialog().show(supportFragmentManager, OpenSourceLibrariesDialog.TAG)
        }
        bindRow(binding.authorRow, R.drawable.ic_github, R.string.about_author_name, AUTHOR_PROFILE_NAME) {
            openLink(AUTHOR_PROFILE_URL)
        }
    }

    private fun bindRow(
        row: AboutRowItemBinding,
        @DrawableRes iconRes: Int,
        @StringRes titleRes: Int,
        subtitle: String? = null,
        onClick: () -> Unit,
    ) {
        row.rowIcon.setImageResource(iconRes)
        row.rowTitle.setText(titleRes)
        if (subtitle != null) {
            row.rowSubtitle.text = subtitle
            row.rowSubtitle.visibility = View.VISIBLE
        }
        row.root.setOnClickListener { onClick() }
    }

    private fun versionText(): String {
        val suffix = if (BuildConfig.DEBUG) "-debug" else ""
        return "Version ${BuildConfig.VERSION_NAME}$suffix"
    }

    private fun openLink(url: String) {
        try {
            startActivity(linkIntent(url))
        } catch (e: ActivityNotFoundException) {
            AppSnackbar.make(binding.root, url, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        // Thoughts started from MJ PDF by Mudlej; the About screen thanks it and links its repository.
        private const val MJ_PDF_REPO_URL = "https://gitlab.com/mudlej_android/mj_pdf_reader"
        private const val AUTHOR_PROFILE_NAME = "github.com/TommasoBendinelli"
        private const val AUTHOR_PROFILE_URL = "https://github.com/TommasoBendinelli"
        // The public repository of Thoughts for Android (set when it is published).
        private const val REPO_URL = "https://github.com/TommasoBendinelli/thoughts-android"
        private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.links

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.share.parseThoughtsCitationLink
import java.io.File

class ThoughtsCitationLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    private fun handle(sourceIntent: Intent) {
        val configuredBaseUrl = ThoughtsServerSecretStore(this).baseUrl()
        val link = sourceIntent.data?.toString()?.let { parseThoughtsCitationLink(it, configuredBaseUrl) }
        if (link == null) {
            openHome(R.string.citation_link_invalid)
            return
        }

        val canonicalDirectory = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val snapshot = SourceLinkedPdfIndex().snapshot()
        val filename = snapshot.libraryFilenames.singleOrNull {
            DocumentIdentityStore().byFilename(it)?.get("document_id")?.asString == link.documentId
        }
        val file = filename
            ?.let { File(canonicalDirectory, it) }
            ?.takeIf(File::isFile)
        val currentVersion = file?.let {
            snapshot.canonicalLibraryPdfSha256(it, canonicalDirectory)
        }
        if (file == null || currentVersion == null) {
            openHome(R.string.citation_document_missing)
            return
        }

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.fromFile(file)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_CITATION_PAGE_INDEX, link.originalPageNumber - 1)
                putExtra(EXTRA_CITATION_VERSION_CHANGED, currentVersion != link.versionSha256)
            }
        )
        finish()
    }

    private fun openHome(message: Int) {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_CITATION_MESSAGE, message)
        )
        finish()
    }

    companion object {
        const val EXTRA_CITATION_PAGE_INDEX =
            "com.gitlab.mudlej.MjPdfReader.extra.CITATION_PAGE_INDEX"
        const val EXTRA_CITATION_VERSION_CHANGED =
            "com.gitlab.mudlej.MjPdfReader.extra.CITATION_VERSION_CHANGED"
    }
}

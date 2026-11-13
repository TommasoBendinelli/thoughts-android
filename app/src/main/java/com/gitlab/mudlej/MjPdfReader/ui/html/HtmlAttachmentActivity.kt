// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.html

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.browserLinkIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.copyToClipboard
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentFile
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlReaderModel
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.ui.links.HtmlAttachmentLink
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardDetailsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * HTML attachment pages opened outside the reader (the Library's source list), and the
 * **Open in browser** path for every page.
 *
 * In-app (the default): the page in a full-screen [HtmlAttachmentView] with its own scripts, the
 * page's review cards highlighted, Copy, and Open in browser / Close in the menu. Cards are made
 * from the reader ([com.gitlab.mudlej.MjPdfReader.ui.reader.HtmlAttachmentReaderController]),
 * where the document whose link opened the page owns them.
 *
 * In the browser (`browser` extra, and desktop file links with a local copy): the old loopback
 * server ([OfflineHtmlService]) and the external browser.
 */
class HtmlAttachmentActivity : AppCompatActivity() {
    private val link get() = intent.getStringExtra("link").orEmpty()
    private val filename get() = intent.getStringExtra("pdf").orEmpty()
    private val inBrowser get() = intent.getBooleanExtra("browser", false)
    private val localCopies get() = LocalHtmlAttachments(File(filesDir, "local-html-attachments"))
    private var choosingFile = false
    private var page: HtmlAttachmentView? = null
    private var shown: HtmlAttachmentFile? = null
    private var ready = false
    private val chooseHtml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) finish() else lifecycleScope.launch {
            try {
                val html = withContext(Dispatchers.IO) {
                    requireNotNull(contentResolver.openInputStream(uri)).use { localCopies.save(filename, link, it) }
                }
                openBrowser(html)
            } catch (error: Exception) { fail(error) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        choosingFile = savedInstanceState?.getBoolean("choosingFile") ?: false
        if (choosingFile) return
        lifecycleScope.launch {
            try {
                if (LocalHtmlAttachments.handles(link)) {
                    val html = withContext(Dispatchers.IO) { localCopies.get(filename, link) }
                    if (html != null) openBrowser(html) else promptForCopy()
                    return@launch
                }
                val path = HtmlAttachmentLink.parse(link) ?: error("This HTML attachment link is invalid.")
                val pdf = filename.takeUnless { intent.getBooleanExtra("from_sources", false) || it.isBlank() }
                val attachment = withContext(Dispatchers.IO) {
                    OfflineSourceBundleStore(this@HtmlAttachmentActivity).htmlAttachmentFile(path, pdf)
                } ?: error(getString(R.string.html_attachment_not_downloaded))
                if (inBrowser) openBrowser(attachment.file) else showInApp(attachment)
            } catch (error: Exception) { fail(error) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("choosingFile", choosingFile)
        super.onSaveInstanceState(outState)
    }

    // ---- In the app ---------------------------------------------------------------------------

    private fun showInApp(attachment: HtmlAttachmentFile) {
        shown = attachment
        // The theme has no action bar (the browser path shows nothing): a toolbar of its own.
        val toolbar = com.google.android.material.appbar.MaterialToolbar(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(toolbar, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setSupportActionBar(toolbar)
        title = HtmlAttachmentCards.servedName(attachment.path)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val view = HtmlAttachmentView(this).apply {
            listener = HtmlAttachmentView.Listener { type, message -> onViewerMessage(type, message) }
            onExternalLink = { uri -> runCatching { startActivity(browserLinkIntent(uri.toString())) } }
            selectionMenu = object : HtmlEditionView.SelectionMenu {
                override fun populate(menu: Menu) {
                    menu.add(0, ITEM_COPY, 0, R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                override fun onItem(item: MenuItem): Boolean {
                    if (item.itemId != ITEM_COPY) return false
                    page?.call("copySelection") { result ->
                        val text = result.getOrNull()?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                        if (text.isNotBlank()) copyToClipboard(this@HtmlAttachmentActivity, getString(R.string.selected_text), text)
                        page?.call("clearSelection")
                    }
                    return true
                }
            }
        }
        page = view
        layout.addView(view, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        view.show(attachment, JsonObject().apply { addProperty("theme", "system") })
        invalidateOptionsMenu()
    }

    private fun onViewerMessage(type: String, message: JsonObject) {
        when (type) {
            "ready" -> {
                ready = true
                highlightCards()
            }
            "itemTapped" -> {
                val id = message.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (id in highlighted) startActivity(ReviewCardDetailsActivity.intent(this, id))
            }
        }
    }

    private var highlighted: Set<String> = emptySet()

    /** The page's review cards, by their text quotes. */
    private fun highlightCards() {
        val attachment = shown ?: return
        lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) {
                runCatching {
                    ReviewStore(applicationContext).browserCards().map { it.card }.filter { card ->
                        card.approved && card.schedule?.deleted != true && card.htmlAttachment?.path == attachment.path
                    }
                }.getOrDefault(emptyList())
            }
            if (shown !== attachment || !ready) return@launch
            highlighted = cards.map { it.id }.toSet()
            page?.call("setItems", JsonArray().apply {
                add(JsonArray().apply {
                    cards.forEach { card -> add(HtmlReaderModel.viewerCard(card.id, emptyList(), card.htmlAttachment!!.quote, style = "card")) }
                })
                add(JsonArray()); add(JsonArray())
            })
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (shown == null) return false
        menu.add(0, ITEM_BROWSER, 0, R.string.html_attachment_open_in_browser).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, ITEM_CLOSE, 1, R.string.close).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            ITEM_BROWSER -> shown?.let { attachment -> lifecycleScope.launch { runCatching { openBrowser(attachment.file, finish = false) }.onFailure { fail(Exception(it.message, it)) } } }
            ITEM_CLOSE, android.R.id.home -> finish()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onDestroy() {
        page?.destroy()
        page = null
        super.onDestroy()
    }

    // ---- In the browser -----------------------------------------------------------------------

    private fun promptForCopy() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose a local HTML copy")
            .setMessage("This PDF links to an HTML file on another computer. Choose a copy saved on this phone. Thoughts will remember it for this PDF and open it offline in your browser.")
            .setPositiveButton("Choose HTML") { _, _ ->
                choosingFile = true
                chooseHtml.launch(arrayOf("text/html", "application/xhtml+xml", "application/octet-stream"))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private suspend fun openBrowser(html: File, finish: Boolean = true) {
        val url = withContext(Dispatchers.IO) { OfflineHtmlService.serve(html) }
        ContextCompat.startForegroundService(this, Intent(this, OfflineHtmlService::class.java))
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        if (finish) finish()
    }

    private fun fail(error: Exception) {
        Toast.makeText(this, error.message ?: "Could not open the HTML attachment.", Toast.LENGTH_LONG).show()
        if (shown == null) finish()
    }

    companion object {
        private const val ITEM_COPY = 301
        private const val ITEM_BROWSER = 302
        private const val ITEM_CLOSE = 303

        /** A page of the Library's source list: in the app. */
        fun openSource(context: Context, path: String) {
            if (!HtmlAttachmentLink.isPath(path)) return
            context.startActivity(Intent(context, HtmlAttachmentActivity::class.java)
                .putExtra("link", "thoughts-html:$path").putExtra("from_sources", true))
        }

        /** Open in browser: the page through the loopback server in the external browser (the old path). */
        fun openInBrowser(context: Context, path: String, pdfFilename: String?) {
            if (!HtmlAttachmentLink.isPath(path)) return
            context.startActivity(Intent(context, HtmlAttachmentActivity::class.java)
                .putExtra("link", "thoughts-html:$path").putExtra("browser", true)
                .apply { if (pdfFilename.isNullOrBlank()) putExtra("from_sources", true) else putExtra("pdf", pdfFilename) })
        }

        /**
         * A link of [pdfFilename] outside the reader: a `thoughts-html:` page in the app, a desktop
         * file link through its local copy in the browser. False when [link] is neither.
         */
        fun open(context: Context, link: String?, pdfFilename: String): Boolean {
            if (link == null || (!link.startsWith("thoughts-html:", true) && !LocalHtmlAttachments.handles(link))) return false
            context.startActivity(Intent(context, HtmlAttachmentActivity::class.java).putExtra("link", link).putExtra("pdf", pdfFilename))
            return true
        }
    }
}

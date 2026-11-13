// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Feedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Kind
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Polarity
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.review.basicPdfSha256
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One comment of a version in the document's History: its kind, its words (else its passage), its page. */
internal data class HistoryComment(
    val label: String,
    val text: String,
    val page: Int?,
    val resolved: Boolean,
    val createdAt: String,
    /** Where it is marked on its version (the first area), to show it there. */
    val region: ReviewCardFeedback.Region? = null,
)

/** A version of the document: its PDF's SHA-256, whether it is the current one, and when it was first commented on. */
internal data class HistoryVersion(
    val sha256: String,
    val current: Boolean,
    val firstAt: Instant?,
    val comments: List<HistoryComment>,
)

/**
 * The document's History as the desktop lists it: the current version first, then every earlier
 * version a comment was made on (the document's own feedback and the feedback on its cards'
 * answers, by the PDF each was marked on), newest first; each with its comments, oldest first.
 */
internal fun documentHistory(
    currentSha256: String?,
    documentFeedback: List<Feedback>,
    cardFeedback: List<Feedback>,
): List<HistoryVersion> {
    val whitespace = Regex("\\s+")
    fun comment(record: Feedback, card: Boolean): HistoryComment {
        val kind = when (record.kind) {
            Kind.QUESTION -> "Question"
            Kind.OTHER -> "Comment"
            Kind.DRAWING -> "Drawing"
            Kind.TEXT -> "Text box"
            null -> if (record.polarity == Polarity.NEGATIVE) "Disliked" else "Liked"
        }
        val words = record.comment.replace(whitespace, " ").trim()
        val quote = record.exact.replace(whitespace, " ").trim()
        return HistoryComment(
            label = if (card) "Card answer: $kind" else kind,
            text = words.ifEmpty { if (record.isPageMark) "" else "“$quote”" },
            page = record.regions.firstOrNull()?.page?.takeIf { it > 0 },
            resolved = record.kind != null && record.resolvedAt != null,
            createdAt = record.createdAt,
            region = record.regions.firstOrNull()?.takeIf { !record.isPageMark },
        )
    }
    val current = currentSha256?.lowercase()?.takeIf(String::isNotBlank)
    val bySha = linkedMapOf<String, MutableList<Pair<Feedback, Boolean>>>()
    current?.let { bySha[it] = mutableListOf() }
    (documentFeedback.map { it to false } + cardFeedback.map { it to true })
        .filter { (record, _) -> !record.deleted && record.pdfSha256.isNotBlank() }
        .forEach { pair -> bySha.getOrPut(pair.first.pdfSha256.lowercase()) { mutableListOf() } += pair }
    return bySha.map { (sha, records) ->
        val ordered = records.sortedWith(compareBy({ it.first.createdAt }, { it.first.id }))
        HistoryVersion(
            sha256 = sha,
            current = sha == current,
            firstAt = ordered.firstNotNullOfOrNull { runCatching { Instant.parse(it.first.createdAt) }.getOrNull() },
            comments = ordered.map { (record, card) -> comment(record, card) },
        )
    }.sortedWith(compareByDescending<HistoryVersion> { it.current }.thenByDescending { it.firstAt ?: Instant.MIN })
}

/**
 * The Library document a History belongs to: [currentSha] its current PDF and [libraryUri] where
 * the phone keeps it; [openedSha] the version shown in this tab. An earlier version's tab knows it
 * from the extras it was opened with, since its file is not the Library's.
 */
internal data class HistoryDocument(
    val documentId: String,
    val name: String,
    val currentSha: String?,
    val libraryUri: Uri?,
    val openedSha: String?,
)

/**
 * History in the reader menu: the document's versions with their comments; a tap opens that
 * version in its own tab (at the comment's page), downloading its PDF from Fedora by its SHA-256
 * when this phone does not have it. An earlier version's tab has the same History, its own version
 * marked; the current version opens the Library's tab.
 */
internal class DocumentHistoryController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) {
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

    fun show(document: DocumentState, intent: Intent) {
        val history = historyDocument(document, intent)
        if (history == null) {
            AppSnackbar.make(binding.root, R.string.document_history_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        activity.lifecycleScope.launch {
            val versions = withContext(Dispatchers.IO) {
                runCatching {
                    val cardFeedback = ReviewCardFeedback.all().filter { it.pdfDocumentId.equals(history.documentId, ignoreCase = true) }
                    documentHistory(history.currentSha, DocumentFeedback.forDocument(history.documentId), cardFeedback)
                }.onFailure { android.util.Log.w(TAG, "Could not read the document's History", it) }.getOrDefault(emptyList())
            }
            // An earlier version is listed even when it has no comments of its own.
            val shown = if (history.openedSha == null || versions.any { it.sha256 == history.openedSha }) versions
                else versions + HistoryVersion(history.openedSha, false, null, emptyList())
            showDialog(shown, history)
        }
    }

    /** The Library document of this tab: its own, or the one an earlier version was opened for. */
    private fun historyDocument(document: DocumentState, intent: Intent): HistoryDocument? {
        val ownId = document.documentId?.lowercase()?.takeIf(ReviewCardFeedback::validUuid4)
        if (ownId != null && document.projectReading == null) {
            val sha = document.annotationPdfSha256?.lowercase()
            return HistoryDocument(ownId, document.name.removeSuffix(".pdf").ifBlank { "Document" }, sha, document.uri, sha)
        }
        val versionOf = intent.getStringExtra(EXTRA_HISTORY_DOCUMENT_ID)?.lowercase()?.takeIf(ReviewCardFeedback::validUuid4)
            ?: return null
        return HistoryDocument(
            documentId = versionOf,
            name = intent.getStringExtra(EXTRA_HISTORY_NAME).orEmpty().ifBlank { "Document" },
            currentSha = intent.getStringExtra(EXTRA_HISTORY_CURRENT_SHA),
            libraryUri = intent.getStringExtra(EXTRA_HISTORY_LIBRARY_URI)?.let(Uri::parse),
            openedSha = intent.getStringExtra(EXTRA_HISTORY_VERSION_SHA),
        )
    }

    private fun showDialog(versions: List<HistoryVersion>, history: HistoryDocument) {
        val context = activity
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, (8 * density).toInt())
        }
        val selectable = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        if (versions.none { it.comments.isNotEmpty() }) {
            list.addView(TextView(context).apply {
                setText(R.string.document_history_empty)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            })
        }
        versions.forEach { version ->
            val title = when {
                version.current -> context.getString(R.string.document_history_current)
                version.firstAt != null -> context.getString(R.string.document_history_earlier, dateFormat.format(version.firstAt))
                else -> context.getString(R.string.document_history_earlier_undated)
            } + if (version.sha256 == history.openedSha && !version.current) context.getString(R.string.document_history_open_here) else ""
            val count = context.resources.getQuantityString(R.plurals.document_history_comments, version.comments.size, version.comments.size)
            val openHere = version.sha256 == history.openedSha
            list.addView(TextView(context).apply {
                text = "$title\n$count · ${context.getString(R.string.document_history_open)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                if (openHere) {
                    // The version shown in this tab: bold on a tinted band.
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(this,
                        com.google.android.material.R.attr.colorOnSecondaryContainer))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 8 * density
                        setColor(com.google.android.material.color.MaterialColors.getColor(list,
                            com.google.android.material.R.attr.colorSecondaryContainer))
                    }
                    setPadding((8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                } else {
                    setPadding(0, (14 * density).toInt(), 0, (6 * density).toInt())
                    setBackgroundResource(selectable)
                }
                setOnClickListener {
                    dialog.dismiss()
                    open(version, history, page = null)
                }
            })
            version.comments.forEach { comment ->
                list.addView(TextView(context).apply {
                    val page = comment.page?.let { " · p. $it" }.orEmpty()
                    val resolved = if (comment.resolved) " (resolved)" else ""
                    text = buildString {
                        append(comment.label).append(page).append(resolved)
                        if (comment.text.isNotEmpty()) append(" — ").append(comment.text)
                    }
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    minHeight = (40 * density).toInt()
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((12 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
                    setBackgroundResource(selectable)
                    setOnClickListener {
                        dialog.dismiss()
                        open(version, history, comment.page, comment.region)
                    }
                })
            }
        }
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.document_history)
            .setView(ScrollView(context).apply { addView(list) })
            .create()
        dialog.show()
    }

    /**
     * The version shown here goes to [page]; the current one opens the Library's tab; an earlier one
     * opens in its own tab, fetched first when needed, told which document it belongs to.
     */
    private fun open(version: HistoryVersion, history: HistoryDocument, page: Int?, region: ReviewCardFeedback.Region? = null) {
        val area = region?.let { floatArrayOf(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        if (version.sha256 == history.openedSha) {
            page?.let { showPassage(it, area) }
            return
        }
        val name = history.name
        if (version.current) {
            val library = history.libraryUri ?: return
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(library, "application/pdf")
                page?.let { putExtra(EXTRA_HISTORY_PAGE, it) }
                area?.let { putExtra(EXTRA_HISTORY_AREA, it) }
            })
            return
        }
        val folder = File(activity.cacheDir, "pdf-versions/${version.sha256}")
        val label = version.firstAt?.let(dateFormat::format) ?: version.sha256.take(8)
        val target = File(folder, activity.getString(R.string.document_history_file, name, label)
            .replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), " ").trim() + ".pdf")
        val progress = AppSnackbar.make(binding.root, R.string.document_history_downloading, Snackbar.LENGTH_INDEFINITE)
        activity.lifecycleScope.launch {
            val cached = folder.listFiles { file -> file.extension == "pdf" && file.length() > 0 }?.firstOrNull()
            if (cached == null) progress.show()
            val file = runCatching { cached ?: withContext(Dispatchers.IO) { download(version.sha256, target) } }
            progress.dismiss()
            file.onSuccess { pdf ->
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    setDataAndType(Uri.fromFile(pdf), "application/pdf")
                    page?.let { putExtra(EXTRA_HISTORY_PAGE, it) }
                    area?.let { putExtra(EXTRA_HISTORY_AREA, it) }
                    putExtra(EXTRA_HISTORY_DOCUMENT_ID, history.documentId)
                    putExtra(EXTRA_HISTORY_NAME, name)
                    putExtra(EXTRA_HISTORY_CURRENT_SHA, history.currentSha)
                    putExtra(EXTRA_HISTORY_LIBRARY_URI, history.libraryUri?.toString())
                    putExtra(EXTRA_HISTORY_VERSION_SHA, version.sha256)
                })
            }.onFailure { error ->
                android.util.Log.w(TAG, "Could not get the version ${version.sha256}", error)
                AppSnackbar.make(binding.root, activity.getString(R.string.document_history_download_failed,
                    error.message ?: error.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Shows a comment's passage: its 1-based [page], centred on [area] (top-origin page fractions)
     * when known, outlined in the selection colour for a moment so it can be found.
     */
    fun showPassage(page: Int, area: FloatArray?) {
        val view = binding.pdfView
        view.jumpTo(page - 1)
        val rect = area?.takeIf { it.size == 4 }?.let { android.graphics.RectF(it[0], it[1], it[2], it[3]) } ?: return
        view.post {
            view.focusOnReviewRegion(page - 1, rect, view.height.toFloat())
            view.setSelectedReviewHighlight(mapOf(page - 1 to listOf(rect)))
            view.postDelayed({ view.clearSelectedReviewHighlight() }, PASSAGE_OUTLINE_MS)
        }
    }

    /** Off the main thread: the version's PDF from Fedora (`GET /v1/objects/{sha256}`), checked against its SHA-256. */
    private fun download(sha256: String, target: File): File {
        val remote = FedoraRepository(activity.applicationContext).htmlEditionRemote()
            ?: throw IOException("Sync is not set up on this phone")
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "download.part")
        try {
            remote.download(sha256, partial)
            if (!basicPdfSha256(partial).equals(sha256, ignoreCase = true)) throw IOException("the download does not match that version")
            if (!partial.renameTo(target)) throw IOException("it could not be saved")
            return target
        } finally {
            partial.delete()
        }
    }

    companion object {
        private const val TAG = "ThoughtsHistory"
        /** The 1-based page an earlier version opened from History shows first. */
        const val EXTRA_HISTORY_PAGE = "historyPage"
        /** The comment's area on that page, [left, top, right, bottom] in top-origin fractions, outlined on arrival. */
        const val EXTRA_HISTORY_AREA = "historyArea"
        private const val PASSAGE_OUTLINE_MS = 2_500L
        /** An earlier version's tab: the Library document it is a version of, and what History needs of it. */
        const val EXTRA_HISTORY_DOCUMENT_ID = "historyDocumentId"
        const val EXTRA_HISTORY_NAME = "historyDocumentName"
        const val EXTRA_HISTORY_CURRENT_SHA = "historyCurrentSha"
        const val EXTRA_HISTORY_LIBRARY_URI = "historyLibraryUri"
        const val EXTRA_HISTORY_VERSION_SHA = "historyVersionSha"

        /** Whether [uri] is an earlier version History fetched: a version of a Library document, not one to import. */
        fun isVersionFile(context: android.content.Context, uri: Uri?): Boolean {
            val path = uri?.takeIf { it.scheme == "file" }?.path ?: return false
            val versions = File(context.cacheDir, "pdf-versions").canonicalPath + File.separator
            return runCatching { File(path).canonicalPath.startsWith(versions) }.getOrDefault(false)
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.SourceOwner
import com.gitlab.mudlej.MjPdfReader.ui.source.ProposalSourceActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the source passage a review card's mask markers enclose, shared by Card details and the reader. */
internal class ReviewPlacementEditor(private val activity: AppCompatActivity) {
    private var markers: ReviewManualMarkers? = null

    /** Opens the source for [card]; with [markers], the editor offers them to copy. */
    fun edit(card: ReviewCard, markers: ReviewManualMarkers? = null) {
        this.markers = markers
        activity.lifecycleScope.launch {
            val sourceFiles = withContext(Dispatchers.IO) {
                val filename = "${card.pdfStem}.pdf"
                val bundle = OfflineSourceBundleStore(activity.applicationContext).librarySourceMetadata(filename)
                reviewPlacementSourceFiles(card.sourceFiles, bundle?.pdfs?.get(filename), card.pdfPage ?: 1)
            }
            if (sourceFiles.isEmpty()) {
                showProblem(R.string.review_card_placement_no_source)
                return@launch
            }
            val sources = withContext(Dispatchers.IO) {
                try {
                    val store = OfflineSourceBundleStore(activity.applicationContext)
                    sourceFiles.mapNotNull { path ->
                        store.sourceSnapshot(path, 1)?.let { path to it.contents }
                    }.toMap()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyMap()
                }
            }
            if (sources.isEmpty()) {
                showProblem(R.string.review_card_placement_source_unavailable)
                return@launch
            }
            val ranges = withContext(Dispatchers.Default) {
                reviewPlacementRanges(card.id, sources)
            }
            when {
                // A half-placed marker pair makes the scan invalid; open where this card's marker is
                // so it can be finished or fixed, rather than locking the source away.
                ranges == null -> reviewCardMarkerLine(card.id, sources)?.let { (path, line) -> openSource(path, line) }
                    ?: chooseFile(sources.keys.toList())
                ranges.isEmpty() -> chooseFile(sources.keys.toList())
                ranges.size == 1 -> show(ranges.single())
                else -> MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.review_card_edit_placement)
                    .setItems(ranges.map { range ->
                        val path = SourceOwner.relative(range.sourceFile) ?: range.sourceFile
                        activity.getString(R.string.review_card_placement_range, path, range.startLine, range.endLine)
                    }.toTypedArray()) { _, which -> show(ranges[which]) }
                    .show()
            }
        }
    }

    fun open(range: ReviewPlacementRange) {
        markers = null
        show(range)
    }

    private fun show(range: ReviewPlacementRange) = openSource(range.sourceFile, range.startLine)

    fun showMessage(message: String) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showProblem(@StringRes message: Int) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun chooseFile(paths: List<String>) {
        if (paths.size == 1) {
            openSource(paths.single(), 1)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.review_card_placement_choose_file)
            .setItems(paths.map { SourceOwner.relative(it) ?: it }.toTypedArray()) { _, which ->
                openSource(paths[which], 1)
            }
            .show()
    }

    private fun openSource(path: String, line: Int) {
        activity.startActivity(ProposalSourceActivity.intent(activity, path, line, "", markers?.let { listOf(it.start, it.end) }))
    }
}

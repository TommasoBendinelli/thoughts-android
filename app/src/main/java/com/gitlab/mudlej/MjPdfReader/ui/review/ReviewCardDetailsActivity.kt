// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewHistoryEntry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewIntervalUnit
import com.gitlab.mudlej.MjPdfReader.ui.reader.label
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.review.REVIEW_PENDING_SERVER_LINK
import com.gitlab.mudlej.MjPdfReader.data.review.REVIEW_PLACE_AGAIN_ERROR_CODES
import com.gitlab.mudlej.MjPdfReader.data.review.needsPlacingAgain
import com.gitlab.mudlej.MjPdfReader.data.review.placeAgainReason
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.SourceOwner
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.ui.home.ReviewGenerationProjects
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesFileObserver
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityReviewCardDetailsBinding
import com.gitlab.mudlej.MjPdfReader.databinding.DialogReviewCardEditBinding
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.DateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReviewCardDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewCardDetailsBinding
    private lateinit var reviewStore: ReviewStore
    private val sourceIndex = SourceLinkedPdfIndex()
    private val placementEditor = ReviewPlacementEditor(this)
    private lateinit var pendingObserver: PendingChangesFileObserver
    private var sourceObserver: FileObserver? = null
    private var pendingObserverStartJob: Job? = null
    private var loadJob: Job? = null
    private var item: ReviewBrowserCard? = null
    private var projectNames = ""
    private var documentName = ""
    private var cardId = ""
    private var actionInFlight = false
    private var sourceRanges: List<ReviewPlacementRange>? = null
    private var historyExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewCardDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        title = getString(R.string.review_card_details_title)

        cardId = intent.getStringExtra(EXTRA_CARD_ID).orEmpty()
        reviewStore = ReviewStore(applicationContext)
        pendingObserver = PendingChangesFileObserver(
            listOf(
                ReviewStore.defaultRoot(),
                ReviewStore.defaultIncomingRoot(),
                ReviewStore.defaultOutgoingEventsRoot(),
                ReviewStore.defaultOutgoingCardsRoot(),
            ),
        )
        binding.reviewCardDetailsEditNote.setOnClickListener {
            if (!actionInFlight) item?.let(::showEditNote)
        }
        binding.reviewCardDetailsRetry.setOnClickListener { loadCard(showProgress = true) }
        binding.reviewCardDetailsLinking.setOnClickListener {
            val current = item
            if (current?.card?.sourceLinkState == "linking_failed") {
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { reviewStore.checkManualSourceLink(current.card.id) }
                    result.onSuccess { loadCard(showProgress = false) }
                        .onFailure { editPlacement(current) }
                }
            }
        }
        binding.reviewCardDetailsCopyStartMarker.setOnClickListener {
            item?.let { ReviewManualMarkers.copy(this, "Start marker", manualMarkers(it).start) }
        }
        binding.reviewCardDetailsCopyEndMarker.setOnClickListener {
            item?.let { ReviewManualMarkers.copy(this, "End marker", manualMarkers(it).end) }
        }
        binding.reviewCardDetailsOpenMarkerSource.setOnClickListener {
            item?.let { placementEditor.edit(it.card, manualMarkers(it)) }
        }
        binding.reviewCardDetailsCheckMarkers.setOnClickListener {
            val current = item ?: return@setOnClickListener
            if (actionInFlight) return@setOnClickListener
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { reviewStore.checkManualSourceLink(current.card.id) }
                result.onSuccess { loadCard(showProgress = false) }
                    .onFailure { placementEditor.showMessage(getString(R.string.review_manual_markers_not_found)) }
            }
        }
        binding.reviewCardDetailsReview.setOnClickListener { item?.let(::reviewCard) }
        binding.reviewCardDetailsPlaceAgain.setOnClickListener { item?.let(::placeAgain) }
        binding.reviewCardDetailsEdit.setOnClickListener { item?.let(::showEditQuestion) }
        binding.reviewCardDetailsCheckSourceEdits.setOnClickListener { item?.let(::checkSourceEdits) }
        binding.reviewCardDetailsShowHistory.setOnClickListener {
            historyExpanded = !historyExpanded
            binding.reviewCardDetailsHistorySection.visibility = if (historyExpanded) View.VISIBLE else View.GONE
            binding.reviewCardDetailsShowHistory.setText(if (historyExpanded)
                R.string.review_card_details_hide_history else R.string.review_card_details_show_history)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PendingChangesSignal.generation.drop(1).collectLatest {
                    delay(INVALIDATION_DEBOUNCE_MS)
                    loadCard(showProgress = false)
                }
            }
        }
        if (cardId.isBlank()) {
            showLoadFailure(IllegalArgumentException("Missing card ID"))
        } else {
            loadCard(showProgress = true)
        }
    }

    override fun onStart() {
        super.onStart()
        pendingObserverStartJob?.cancel()
        pendingObserverStartJob = lifecycleScope.launch(Dispatchers.IO) {
            pendingObserver.start()
        }
        sourceObserver = sourceIndex.observe { PendingChangesSignal.notifyChanged() }
    }

    override fun onResume() {
        super.onResume()
        if (item != null) loadCard(showProgress = false)
    }

    override fun onStop() {
        pendingObserverStartJob?.cancel()
        pendingObserverStartJob = null
        sourceObserver?.stopWatching()
        sourceObserver = null
        pendingObserver.stop()
        super.onStop()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_TECHNICAL, 0, R.string.review_card_details_technical)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        // Retire card (Restore to review on a retired card) sits next to Delete, its permanent alternative.
        menu.add(0, MENU_RETIRE, 1, R.string.review_card_retire_menu)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_DELETE, 1, R.string.review_card_details_delete_menu)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_RETIRE)?.apply {
            setTitle(reviewRetireMenuTitle(item?.state == ReviewBrowserState.RETIRED))
            isEnabled = item != null && !actionInFlight
        }
        menu.findItem(MENU_DELETE)?.isEnabled = item != null && !actionInFlight
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
        MENU_TECHNICAL -> {
            binding.reviewCardDetailsTechnical.visibility = if (
                binding.reviewCardDetailsTechnical.visibility == View.VISIBLE
            ) View.GONE else View.VISIBLE
            true
        }
        MENU_RETIRE -> {
            item?.let(::retireOrRestore)
            true
        }
        MENU_DELETE -> {
            item?.let(::confirmDelete)
            true
        }
        else -> super.onOptionsItemSelected(menuItem)
    }

    private fun loadCard(showProgress: Boolean) {
        loadJob?.cancel()
        if (showProgress || item == null) {
            binding.reviewCardDetailsProgress.visibility = View.VISIBLE
            binding.reviewCardDetailsMessagePanel.visibility = View.VISIBLE
            binding.reviewCardDetailsMessage.setText(R.string.review_card_browser_loading)
            binding.reviewCardDetailsRetry.visibility = View.GONE
        }
        loadJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    sourceIndex.invalidate()
                    val snapshot = sourceIndex.snapshot()
                    val canonicalHashes = snapshot.libraryPdfSha256ByFilename.takeIf {
                        snapshot.libraryAvailable && snapshot.generationCurrent
                    }
                    val card = reviewStore.browserCards(
                        canonicalPdfSha256ByFilename = canonicalHashes,
                    ).firstOrNull { it.card.id == cardId }
                        ?: error("The review card is unavailable")
                    val filename = "${card.card.pdfStem}.pdf"
                    linkingSourceAvailable = com.gitlab.mudlej.MjPdfReader.data.source.reviewSourceAvailability(
                        OfflineSourceBundleStore(applicationContext).librarySourceMetadata(filename)?.pdfs.orEmpty(),
                        filename, card.card.pdfSha256, listOf(card.card.pdfPage ?: 1),
                    )
                    val projects = LibraryProjectStore(applicationContext)
                        .projects()
                    projectNames = projects.mapNotNull { value ->
                        val project = value.asJsonObject
                        val belongs = project.get("target")?.asString == filename ||
                            project.getAsJsonArray("documents")?.any {
                                it.asJsonObject.get("filename")?.asString == filename
                            } == true
                        if (belongs) project.get("name")?.asString?.takeIf(String::isNotBlank)
                        else null
                    }.distinct().joinToString("\n")
                    documentName = ReviewDocumentNames(
                        LibraryProjectStore(applicationContext).projects(),
                        OfflineSourceBundleStore(applicationContext)
                            .librarySourceMetadata()?.pdfs.orEmpty(),
                        snapshot.libraryDisplayNamesByFilename,
                    ).name(card.card)
                    val project = card.detailSourceFiles().firstOrNull()?.let(SourceOwner::of).orEmpty()
                    val ranges = if (project.isBlank()) null else {
                        try {
                            OfflineSourceBundleStore(applicationContext).annotationIndex
                                .locations("review_card", card.card.id, project)
                                ?.map { location -> ReviewPlacementRange(
                                    location.get("source_file").asString,
                                    location.get("start_line").asInt,
                                    location.get("end_line").asInt,
                                ) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    }
                    card to ranges
                }
            }
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            binding.reviewCardDetailsProgress.visibility = View.GONE
            result.onSuccess { (card, ranges) ->
                sourceRanges = ranges
                render(card)
            }.onFailure(::showLoadFailure)
        }
    }

    private var linkingSourceAvailable: Boolean? = null

    private fun render(browserCard: ReviewBrowserCard) {
        item = browserCard
        binding.reviewCardDetailsMessagePanel.visibility = View.GONE
        binding.reviewCardDetailsContent.visibility = View.VISIBLE
        binding.reviewCardDetailsPending.visibility = if (browserCard.pendingSync) View.VISIBLE else View.GONE
        binding.reviewCardDetailsQuestion.text = browserCard.card.question
        binding.reviewCardDetailsCardId.text = browserCard.card.id
        binding.reviewCardDetailsNote.visibility = if (browserCard.card.note.isBlank()) View.GONE else View.VISIBLE
        binding.reviewCardDetailsNote.text = getString(R.string.review_card_note_preview, browserCard.card.note)
        binding.reviewCardDetailsProject.text = projectNames.ifBlank {
            getString(R.string.review_card_details_none)
        }
        binding.reviewCardDetailsDocument.text = documentName
        val linkingLabel = getString(R.string.review_card_details_linking) + ": "
        binding.reviewCardDetailsLinking.text = SpannableString(linkingLabel + linkingText(browserCard)).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, linkingLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val pdfAvailable = canonicalPdf(browserCard)?.isFile == true
        val manual = browserCard.card.sourceLinkState == "linking_failed" && !browserCard.card.markerRemovalPending
        binding.reviewCardDetailsManualMarkers.visibility = if (manual) View.VISIBLE else View.GONE
        if (manual) {
            val markers = manualMarkers(browserCard)
            val file = browserCard.card.sourceFiles.firstOrNull()?.substringAfterLast('/')
            binding.reviewCardDetailsManualMarkersHint.text = if (file.isNullOrBlank()) getString(R.string.review_manual_markers_hint_no_file)
                else getString(R.string.review_manual_markers_hint, file)
            binding.reviewCardDetailsManualMarkersText.text = "${markers.start}\n…answer…\n${markers.end}"
        }
        binding.reviewCardDetailsPdfPlacement.text = when {
            !pdfAvailable -> getString(R.string.review_card_details_pdf_unavailable)
            browserCard.card.isHtmlAttachmentCard ->
                com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCardStatus.of(this).notSent(browserCard.card.id)
                    ?: if (com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCardStatus.of(this).isNotFound(browserCard.card.id))
                    getString(R.string.html_attachment_answer_not_found)
                else getString(R.string.html_attachment_card_placement, browserCard.card.htmlAttachment!!.path.substringAfterLast('/'))
            !browserCard.card.hasPdfPlacement -> getString(R.string.html_view_placed_next_sync_title)
            browserCard.placementPending() -> "Placement pending"
            browserCard.state !in setOf(ReviewBrowserState.REPAIRING, ReviewBrowserState.NEEDS_ATTENTION, ReviewBrowserState.STALE)
                && browserCard.card.reanchorState in setOf("succeeded", "current", "ok", "exact_text", "exact_point") ->
                getString(R.string.review_card_details_pdf_pages, browserCard.card.pagesLabel)
            browserCard.card.sourceAnchor != null && browserCard.card.sourceLocationState == "linked" ->
                getString(R.string.review_linking_location_failed)
            else -> getString(R.string.review_card_details_pdf_placement_failed)
        }
        binding.reviewCardDetailsSchedule.text = listOfNotNull(
            // Retired cards keep their schedule; it applies again after Restore to review.
            browserCard.retiredLabel(this),
            getString(R.string.review_card_details_due, dueText(browserCard)),
            getString(R.string.review_card_details_interval, intervalText(browserCard)),
        ).joinToString("\n")
        binding.reviewCardDetailsHistory.text = buildList {
            add(getString(
                R.string.review_card_details_reviews_lapses,
                browserCard.reviewCount,
                browserCard.lapseCount,
            ))
            browserCard.lastRating?.let { rating ->
                // Show the answer's tags ("Skip, hard") rather than the stored rating ("custom_interval").
                val answer = browserCard.history.lastOrNull {
                    it.effective && !it.retracted && it.retractedEventId == null && it.rating == rating
                }?.historyMark(this@ReviewCardDetailsActivity)?.takeIf(String::isNotBlank) ?: rating
                add(getString(R.string.review_card_details_last_rating, answer))
            }
            browserCard.lastReviewedAt?.let {
                add(getString(R.string.review_card_details_last_reviewed, dateTimeText(it.toEpochMilli())))
            }
            browserCard.createdAt?.let {
                add(getString(R.string.review_card_details_created, dateTimeText(it.toEpochMilli())))
            }
            browserCard.updatedAt?.let {
                add(getString(R.string.review_card_details_updated, dateTimeText(it.toEpochMilli())))
            }
            browserCard.history.forEach { event ->
                add(historyText(event))
            }
            // Cards are approved as they are created; only an older card approved later shows its approval.
            browserCard.card.approvedAt?.takeIf { approved ->
                browserCard.createdAt?.let { java.time.Duration.between(it, approved).abs().toMinutes() < 1 } != true
            }?.let {
                add(getString(R.string.review_card_details_approved, dateTimeText(it.toEpochMilli())))
            }
        }.joinToString("\n").ifBlank { getString(R.string.review_card_details_none) }
        binding.reviewCardDetailsTechnical.text = buildList {
            browserCard.revision?.let {
                add(getString(R.string.review_card_details_revision, it))
            }
            add(getString(R.string.review_card_details_pdf_hash, browserCard.card.pdfSha256))
            add(getString(
                R.string.review_card_details_page_visual_hash,
                browserCard.card.pageVisualSha256,
            ))
            add(getString(R.string.review_card_details_region, browserCard.normalizedRegionText()))
            add(getString(R.string.review_card_details_origin, browserCard.card.originId.ifBlank {
                getString(R.string.review_card_details_none)
            }))
            add(getString(R.string.review_card_details_reanchor, browserCard.card.reanchorState))
            browserCard.card.reanchorReason.takeIf(String::isNotBlank)?.let {
                add(getString(R.string.review_card_details_reanchor_reason, it))
            }
            if (browserCard.history.isNotEmpty()) {
                add("")
                add(getString(R.string.review_card_details_event_history))
                browserCard.history.forEachIndexed { index, event ->
                    add("${index + 1}. ${event.technicalText()}")
                }
            }
        }.joinToString("\n")

        val placeAgain = browserCard.state != ReviewBrowserState.DELETED &&
            browserCard.card.needsPlacingAgain(linkingSourceAvailable) && pdfAvailable
        binding.reviewCardDetailsPlaceAgain.visibility = if (placeAgain) View.VISIBLE else View.GONE
        binding.reviewCardDetailsPlaceAgain.isEnabled = !actionInFlight
        val canReview = browserCard.canReview() && pdfAvailable
        binding.reviewCardDetailsReview.isEnabled = canReview && !actionInFlight
        binding.reviewCardDetailsReview.visibility = View.VISIBLE
        binding.reviewCardDetailsEditNote.isEnabled = !actionInFlight
        binding.reviewCardDetailsEditNote.setText(if (browserCard.card.note.isBlank()) R.string.review_card_add_note else R.string.review_card_edit_note)
        binding.reviewCardDetailsEdit.isEnabled = !actionInFlight
        binding.reviewCardDetailsCheckSourceEdits.visibility = if (
            browserCard.card.sourceAnchor != null && browserCard.card.sourceLinkState != "linking_failed"
        ) View.VISIBLE else View.GONE
        binding.reviewCardDetailsCheckSourceEdits.isEnabled = !actionInFlight
        binding.reviewCardDetailsHistorySection.visibility = if (historyExpanded) View.VISIBLE else View.GONE
        binding.reviewCardDetailsShowHistory.setText(if (historyExpanded)
            R.string.review_card_details_hide_history else R.string.review_card_details_show_history)
        invalidateOptionsMenu()
        // A failed link already explains itself in Linking status and the marker box.
        binding.reviewCardDetailsUnavailable.visibility = if (canReview || (manual && pdfAvailable && !placeAgain)) {
            View.GONE
        } else {
            View.VISIBLE
        }
        val retired = browserCard.retiredLabel(this)
        binding.reviewCardDetailsUnavailable.text = if (retired != null) {
            getString(R.string.review_card_details_retired, retired)
        } else if (browserCard.state == ReviewBrowserState.UNPUBLISHED) {
            getString(R.string.review_card_details_unpublished)
        } else if (!pdfAvailable) {
            getString(R.string.review_card_details_pdf_unavailable)
        } else if (browserCard.card.sourceLinkState == REVIEW_PENDING_SERVER_LINK) {
            listOf(getString(R.string.review_link_pending_server), browserCard.card.sourceLinkReason)
                .filter(String::isNotBlank).joinToString("\n")
        } else if (placeAgain) {
            // Fedora's or the phone's reason, then what to do about it.
            browserCard.card.placeAgainReason().ifBlank { getString(R.string.review_place_again_default_reason) }
        } else if (browserCard.card.sourceLinkReason.isNotBlank()) {
            browserCard.card.sourceLinkReason
        } else {
            getString(when (browserCard.state) {
                ReviewBrowserState.REPAIRING -> R.string.review_location_updating_reason
                ReviewBrowserState.NEEDS_ATTENTION -> if (browserCard.card.reanchorReason == "basic_content_changed") R.string.review_content_changed_reason else R.string.review_location_failed_reason
                ReviewBrowserState.STALE -> R.string.review_location_stale_reason
                else -> R.string.review_card_details_unavailable
            })
        }
    }

    /**
     * One history line, then for an answer given in two steps its Before, After and Agent lines, then
     * the answer's group notes on lines of their own.
     */
    private fun historyText(event: ReviewHistoryEntry): String =
        (listOf(historyLine(event)) + event.phaseLines(this) + event.groupNoteLines(this)).joinToString("\n")

    private fun historyLine(event: ReviewHistoryEntry): String = buildList {
        add(event.historyMark(this@ReviewCardDetailsActivity))
        event.reviewedAt?.let { add(dateTimeText(it.toEpochMilli())) }
        if (event.intervalAction == "set") {
            val unit = runCatching { ReviewIntervalUnit.valueOf(event.intervalUnit.orEmpty().uppercase()) }.getOrNull()
            val amount = event.intervalAmount?.let { ReviewInterval.parseAmount(it) }
            val interval = when {
                event.intervalUnit == "never" -> getString(R.string.review_history_never)
                // Older reviews recorded long intervals in the hours they were suggested in.
                unit == ReviewIntervalUnit.HOURS && amount != null ->
                    ReviewInterval.readable(amount.multiply(java.math.BigDecimal(3_600_000))).label(this@ReviewCardDetailsActivity)
                unit != null && amount != null -> ReviewInterval(amount, unit).label(this@ReviewCardDetailsActivity)
                event.intervalUnit == "date" -> event.intervalDate.orEmpty()
                else -> ""
            }
            if (interval.isNotBlank()) add(interval)
            event.scheduledDueAt?.let { add(getString(R.string.review_history_next_review, dateTimeText(it.toEpochMilli()))) }
            return@buildList
        }
        val until = if (event.queueAction == "skip") event.skipUntil else event.scheduledDueAt
        if (until != null && event.reviewedAt != null && until.isAfter(event.reviewedAt)) {
            // suggested() doubles its input, so half the gap yields the gap itself in a whole unit.
            val half = java.time.Duration.between(event.reviewedAt, until).dividedBy(2)
            add(ReviewInterval.suggested(event.reviewedAt, event.reviewedAt.plus(half)).label(this@ReviewCardDetailsActivity))
            add(getString(R.string.review_history_until, dateTimeText(until.toEpochMilli())))
        } else if (event.rating == "retained") add(getString(R.string.review_history_never))
    }.joinToString(" · ")

    private fun linkingText(browserCard: ReviewBrowserCard): String {
        val card = browserCard.card
        val ranges = sourceRanges
        if (card.markerRemovalPending) return getString(R.string.review_marker_removal_pending)
        if (card.wholeDocument) return getString(R.string.review_whole_document_details)
        if (card.sourceLinkState == "linking_failed") return getString(
            R.string.review_card_details_linking_failed,
            ReviewManualMarkers.withoutPlacementHint(card.sourceLinkReason).ifBlank { getString(R.string.overview_linking_failed) },
        )
        if (card.reanchorState == "needs_attention" && card.reanchorErrorCode in REVIEW_PLACE_AGAIN_ERROR_CODES) return getString(
            R.string.review_card_details_linking_failed,
            card.placeAgainReason().ifBlank { getString(R.string.review_place_again_default_reason) },
        )
        // A card with several areas has one marked passage per area, all in one file.
        reviewLinkedSegmentsText(card, ranges)?.let { (path, lines) ->
            return getString(R.string.review_card_details_linked_source_ranges, ranges!!.size, path, lines)
        }
        if (ranges != null && ranges.size > 1) return getString(
            R.string.review_card_details_linking_multiple_locations,
            reviewPlacementLocationsText(ranges),
        )
        if (card.sourceLocationState == "ambiguous")
            return getString(R.string.review_card_details_linking_ambiguous)
        if (card.sourceAnchor != null) {
            if (card.sourceLocationState == "linked" && ranges?.size == 1) {
                val range = ranges.single()
                return getString(R.string.review_card_details_linked_source_range,
                    range.sourceFile, range.startLine, range.endLine)
            }
            return getString(if (ranges?.isEmpty() == true) R.string.review_card_details_linking_missing
                else R.string.review_card_details_linking_unavailable)
        }
        return getString(browserCard.linkingLabelRes(linkingSourceAvailable))
    }

    private fun showLoadFailure(error: Throwable) {
        item = null
        binding.reviewCardDetailsContent.visibility = View.GONE
        binding.reviewCardDetailsMessagePanel.visibility = View.VISIBLE
        binding.reviewCardDetailsMessage.text = getString(
            R.string.review_card_browser_load_failed_detail,
            error.message?.lineSequence()?.firstOrNull().orEmpty()
                .ifBlank { error.javaClass.simpleName },
        )
        binding.reviewCardDetailsRetry.visibility = View.VISIBLE
    }

    private fun reviewCard(browserCard: ReviewBrowserCard) {
        val pdf = canonicalPdf(browserCard)
        if (!browserCard.canReview() || pdf == null || !pdf.isFile) {
            AppSnackbar.make(binding.root, R.string.review_card_details_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        val readerIntent = Intent(this, MainActivity::class.java).apply {
            data = Uri.fromFile(pdf)
            putExtra(HomeActivity.EXTRA_FROM_HOME, true)
            putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, browserCard.card.id)
        }
        startActivity(ReviewCardReaderContract.singleCardSession(readerIntent))
    }

    /** Opens the document at the card's page in Edit card, to mark each of its areas in the source again. */
    private fun placeAgain(browserCard: ReviewBrowserCard) {
        val pdf = canonicalPdf(browserCard)
        if (pdf == null || !pdf.isFile) {
            AppSnackbar.make(binding.root, R.string.review_place_again_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        val readerIntent = Intent(this, MainActivity::class.java).apply {
            data = Uri.fromFile(pdf)
            putExtra(HomeActivity.EXTRA_FROM_HOME, true)
            putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, browserCard.card.id)
        }
        startActivity(ReviewCardReaderContract.placeAgain(ReviewCardReaderContract.openPage(readerIntent, ((browserCard.card.pdfPage ?: 1) - 1))))
    }

    private fun showEditNote(browserCard: ReviewBrowserCard) {
        val editor = DialogReviewCardEditBinding.inflate(layoutInflater)
        editor.reviewCardEditLayout.hint = getString(R.string.review_card_note_hint)
        editor.reviewCardEditLayout.helperText = getString(R.string.review_card_note_help)
        editor.reviewCardEditLayout.counterMaxLength = ReviewStore.MAX_NOTE_LENGTH
        editor.reviewCardEditQuestion.filters = emptyArray()
        editor.reviewCardEditQuestion.setText(browserCard.card.note)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (browserCard.card.note.isBlank()) R.string.review_card_add_note else R.string.review_card_edit_note)
            .setView(editor.root)
            .setPositiveButton(R.string.review_card_edit_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val text = editor.reviewCardEditQuestion.text?.toString().orEmpty()
                if (text.trim().length > ReviewStore.MAX_NOTE_LENGTH) {
                    editor.reviewCardEditLayout.error = getString(R.string.review_card_note_too_long)
                    return@setOnClickListener
                }
                if (actionInFlight) return@setOnClickListener
                actionInFlight = true
                save.isEnabled = false
                dialog.setCancelable(false)
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        reviewStore.updateNote(browserCard.card.id, text, expectedNote = browserCard.card.note)
                    }
                    actionInFlight = false
                    result.onSuccess {
                        dialog.dismiss()
                        loadCard(showProgress = false)
                    }.onFailure {
                        editor.reviewCardEditLayout.error = it.message
                        save.isEnabled = true
                        dialog.setCancelable(true)
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showEditQuestion(browserCard: ReviewBrowserCard) {
        val editBinding = DialogReviewCardEditBinding.inflate(layoutInflater)
        editBinding.reviewCardEditQuestion.setText(browserCard.card.question)
        editBinding.reviewCardEditQuestion.setSelection(browserCard.card.question.length)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.review_card_edit_title)
            .setView(editBinding.root)
            .setPositiveButton(R.string.review_card_edit_save, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.review_card_edit_placement, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editPlacement(browserCard)
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val question = editBinding.reviewCardEditQuestion.text?.toString()?.trim().orEmpty()
                when {
                    question.isEmpty() -> editBinding.reviewCardEditLayout.error =
                        getString(R.string.review_card_edit_empty)
                    question.length > MAX_QUESTION_LENGTH -> editBinding.reviewCardEditLayout.error =
                        getString(R.string.review_card_edit_too_long)
                    else -> {
                        editBinding.reviewCardEditLayout.error = null
                        dialog.dismiss()
                        saveQuestion(browserCard, question)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun editPlacement(browserCard: ReviewBrowserCard) {
        placementEditor.edit(browserCard.card, if (browserCard.card.sourceLinkState == "linking_failed") manualMarkers(browserCard) else null)
    }

    private fun manualMarkers(browserCard: ReviewBrowserCard) =
        ReviewManualMarkers.forCard(browserCard.card.id, browserCard.card.sourceFiles.firstOrNull())

    private fun checkSourceEdits(browserCard: ReviewBrowserCard) {
        // A card with several passages opens at its first one.
        val severalPassages = (browserCard.card.sourceAnchor?.getAsJsonArray("segments")?.size() ?: 0) > 1
        val range = (if (severalPassages) sourceRanges?.minByOrNull { it.startLine } else sourceRanges?.singleOrNull())
            ?: browserCard.card.sourceAnchor
            ?.getAsJsonArray("segments")?.firstOrNull()?.asJsonObject?.let { segment ->
                val path = segment.get("source_file")?.asString.orEmpty()
                val line = segment.get("line_start")?.asInt ?: 0
                if (path.isNotBlank() && line > 0) ReviewPlacementRange(path, line, line) else null
            }
        if (range == null) placementEditor.showProblem(R.string.review_card_placement_source_unavailable)
        else placementEditor.open(range)
    }

    private fun saveQuestion(browserCard: ReviewBrowserCard, question: String) {
        runAction(
            operation = { reviewStore.updateQuestion(browserCard.card.id, question).withoutValue() },
            successMessage = getString(R.string.review_card_edit_saved),
        )
    }

    private fun retireOrRestore(browserCard: ReviewBrowserCard) {
        val cardId = browserCard.card.id
        if (browserCard.state == ReviewBrowserState.RETIRED) runAction(
            operation = { reviewStore.restore(cardId).withoutValue() },
            successMessage = getString(R.string.review_card_restored_to_review),
        ) else runAction(
            operation = { reviewStore.retire(cardId).withoutValue() },
            successMessage = getString(R.string.review_card_retired),
        )
    }

    private fun confirmDelete(browserCard: ReviewBrowserCard) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.review_card_delete_title)
            .setMessage(R.string.review_card_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                runAction(
                    operation = { reviewStore.deleteCard(browserCard.card.id).withoutValue() },
                    successMessage = getString(R.string.review_card_deleted),
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runAction(
        operation: () -> Result<Unit>,
        successMessage: String,
    ) {
        if (actionInFlight) return
        actionInFlight = true
        item?.let(::render)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { operation() }
            actionInFlight = false
            result.onSuccess {
                AppSnackbar.make(binding.root, successMessage, Snackbar.LENGTH_LONG).show()
                loadCard(showProgress = false)
            }.onFailure { error ->
                item?.let(::render)
                AppSnackbar.make(
                    binding.root,
                    getString(
                        R.string.review_card_action_failed,
                        error.message?.lineSequence()?.firstOrNull().orEmpty()
                            .ifBlank { error.javaClass.simpleName },
                    ),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun canonicalPdf(browserCard: ReviewBrowserCard): File? = reviewStore.reviewPdfFile(browserCard.card)

    private fun dueText(browserCard: ReviewBrowserCard): String = when {
        browserCard.card.schedule?.retained == true -> getString(R.string.review_action_never)
        browserCard.currentMark() == null && browserCard.dueAt == null -> getString(R.string.review_card_browser_new_due)
        browserCard.dueAt == null -> getString(R.string.review_card_browser_no_due)
        else -> DATE_FORMATTER.format(
            browserCard.dueAt.atZone(ZoneId.systemDefault()).toLocalDate(),
        )
    }

    private fun intervalText(browserCard: ReviewBrowserCard): String = when {
        browserCard.card.schedule?.retained == true -> getString(R.string.review_action_never)
        browserCard.card.schedule == null -> getString(R.string.review_card_browser_new_due)
        browserCard.intervalDays == 0 -> getString(R.string.review_learning)
        else -> resources.getQuantityString(
            R.plurals.review_interval_days,
            browserCard.intervalDays,
            browserCard.intervalDays,
        )
    }

    private fun dateTimeText(epochMillis: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
    ).format(Date(epochMillis))

    companion object {
        private const val EXTRA_CARD_ID = "review_card_details_card_id"
        private const val MAX_QUESTION_LENGTH = 500
        private const val INVALIDATION_DEBOUNCE_MS = 120L
        private const val MENU_TECHNICAL = 1
        private const val MENU_DELETE = 2
        private const val MENU_RETIRE = 3
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

        fun intent(context: Context, cardId: String): Intent =
            Intent(context, ReviewCardDetailsActivity::class.java).apply {
                putExtra(EXTRA_CARD_ID, cardId)
            }
    }
}

private fun <T> Result<T>.withoutValue(): Result<Unit> {
    val error = exceptionOrNull()
    return if (error == null) Result.success(Unit) else Result.failure(error)
}

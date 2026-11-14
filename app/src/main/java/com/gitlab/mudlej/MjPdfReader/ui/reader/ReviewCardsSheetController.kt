// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PdfReviewCardsSheetBinding
import com.gitlab.mudlej.MjPdfReader.databinding.RowReviewCardSheetBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One of the open document's review cards; [pageIndex] is zero-based in the document shown. */
data class ReviewCardsSheetItem(
    val cardId: String,
    val question: String,
    val pageIndex: Int,
    val metadata: String,
    /** False for a card of another PDF in a project reading: it can only be shown, not reviewed here. */
    val reviewable: Boolean,
    /** The answer's normalized top-left fragments by zero-based page; empty while the card has no PDF place. */
    val regions: Map<Int, List<RectF>> = emptyMap(),
    /** Retired: it stays on the PDF version it was retired on, so a tap opens the card, not a place here. */
    val retired: Boolean = false,
)

/** The sheet's two lists: the cards on the PDF shown, and the document's retired cards. */
data class ReviewCardsSheetContent(
    val cards: List<ReviewCardsSheetItem>,
    val retired: List<ReviewCardsSheetItem> = emptyList(),
)

/**
 * "Page 4 · Correct · due 9 Oct": where the card is, its current tags and when it is due, then
 * the group notes of the answer that gave those tags.
 */
internal fun reviewCardsSheetMetadata(
    context: Context,
    pageIndex: Int,
    tags: List<String>?,
    dueAt: Instant?,
    reviewCount: Int,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    /** The current answer's group notes as "<Group>: <note>" lines, shown under the summary. */
    groupNotes: List<String> = emptyList(),
    /** A retired card shows "Retired <day>" instead of when it is due. */
    retiredAt: Instant? = null,
    /** Instead of "Page N": where a card without a PDF place is (an HTML attachment page). */
    place: String? = null,
): String {
    fun day(at: Instant) = at.atZone(zone).format(DateTimeFormatter.ofPattern(
        if (at.atZone(zone).year == now.atZone(zone).year) "d MMM" else "d MMM yyyy", Locale.getDefault(),
    ))
    val due = when {
        retiredAt != null -> context.getString(R.string.review_retired_on, day(retiredAt))
        dueAt == null && reviewCount == 0 -> context.getString(R.string.review_cards_sheet_new)
        dueAt == null -> context.getString(R.string.review_cards_sheet_not_scheduled)
        !dueAt.isAfter(now) -> context.getString(R.string.review_cards_sheet_due_now)
        else -> context.getString(R.string.review_cards_sheet_due, dueAt.atZone(zone).format(
            DateTimeFormatter.ofPattern(
                if (dueAt.atZone(zone).year == now.atZone(zone).year) "d MMM" else "d MMM yyyy", Locale.getDefault(),
            ),
        ))
    }
    return listOfNotNull(
        place ?: context.getString(R.string.review_cards_sheet_page, pageIndex + 1),
        tags?.takeIf { it.isNotEmpty() }?.joinToString { reviewTagLabel(context, it) },
        due,
    ).joinToString(" · ").let { summary -> (listOf(summary) + groupNotes).joinToString("\n") }
}

private class ReviewCardsSheetAdapter(
    private val onClick: (ReviewCardsSheetItem) -> Unit,
    private val onReview: (ReviewCardsSheetItem) -> Unit,
) : ListAdapter<ReviewCardsSheetItem, ReviewCardsSheetAdapter.Holder>(object : DiffUtil.ItemCallback<ReviewCardsSheetItem>() {
    override fun areItemsTheSame(old: ReviewCardsSheetItem, new: ReviewCardsSheetItem) = old.cardId == new.cardId
    override fun areContentsTheSame(old: ReviewCardsSheetItem, new: ReviewCardsSheetItem) = old == new
}) {
    class Holder(val row: RowReviewCardSheetBinding) : RecyclerView.ViewHolder(row.root)

    var selectedId: String? = null
        set(value) {
            if (field == value) return
            val previous = currentList.indexOfFirst { it.cardId == field }
            field = value
            val next = currentList.indexOfFirst { it.cardId == value }
            if (previous >= 0) notifyItemChanged(previous)
            if (next >= 0) notifyItemChanged(next)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(RowReviewCardSheetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val selected = item.cardId == selectedId
        holder.row.reviewCardQuestion.text = item.question
        holder.row.reviewCardMetadata.text = item.metadata
        holder.row.root.setCardBackgroundColor(MaterialColors.getColor(holder.row.root,
            if (selected) com.google.android.material.R.attr.colorSecondaryContainer
            else com.google.android.material.R.attr.colorSurface))
        holder.row.reviewCardReview.visibility = if (selected && item.reviewable) View.VISIBLE else View.GONE
        holder.row.reviewCardReview.setOnClickListener { onReview(item) }
        holder.row.root.setOnClickListener { onClick(item) }
    }
}

/**
 * The open document's review cards, in the reader's sheet host like Comments. A tap highlights the
 * card's answer in the PDF, as the desktop sidebar does; the selected card's Review button reviews it.
 */
class ReviewCardsSheetController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val scope: CoroutineScope,
    private val beforeShow: () -> Unit,
    private val loadCards: suspend () -> ReviewCardsSheetContent,
    /** Highlights the card's answer in the space above the sheet; null clears the highlight. */
    private val highlightCard: (ReviewCardsSheetItem?) -> Unit,
    private val openCard: (ReviewCardsSheetItem) -> Unit,
    /** Told when the sheet opens or closes; the PDF highlights every card while it is open. */
    private val openChanged: (Boolean) -> Unit,
    /** A retired card picked in the sheet: its details, since it has no place on the PDF shown. */
    private val openRetiredCard: (ReviewCardsSheetItem) -> Unit = {},
) {
    private val adapter = ReviewCardsSheetAdapter(::onCardClicked, ::onReviewClicked)
    private var sheet: PdfReviewCardsSheetBinding? = null
    private var container: FrameLayout? = null
    private var behavior: BottomSheetBehavior<FrameLayout>? = null
    private var loadJob: Job? = null
    private var generation = 0
    private var settledState = BottomSheetBehavior.STATE_HALF_EXPANDED
    private var reopenAfterLoad = false
    private var syncPosted = false
    private val host get() = binding.commentSheetHost
    private var loaded = ReviewCardsSheetContent(emptyList())
    /** The retired cards are listed instead of the PDF's cards. */
    private var showingRetired = false

    fun show() {
        if (sheet != null || activity.isFinishing || activity.isDestroyed) return
        beforeShow()
        reopenAfterLoad = false
        openChanged(true)
        val request = ++generation
        val content = PdfReviewCardsSheetBinding.inflate(activity.layoutInflater, host, false)
        val frame = FrameLayout(activity).apply {
            isClickable = true
            elevation = dp(8).toFloat()
            addView(content.root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        val bottomSheet = BottomSheetBehavior<FrameLayout>().apply {
            isFitToContents = false
            isHideable = false
            // Only the explicit size button may resize the panel.
            isDraggable = false
            halfExpandedRatio = 0.5f
            skipCollapsed = false
            state = settledState
        }
        sheet = content
        container = frame
        behavior = bottomSheet
        host.visibility = View.VISIBLE
        host.addView(frame, CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { behavior = bottomSheet })
        content.reviewCardsList.layoutManager = LinearLayoutManager(activity)
        content.reviewCardsList.adapter = adapter
        content.reviewCardsClose.setOnClickListener { dismiss() }
        content.reviewCardsResize.setOnClickListener {
            bottomSheet.state = if (bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED) {
                BottomSheetBehavior.STATE_HALF_EXPANDED
            } else {
                BottomSheetBehavior.STATE_EXPANDED
            }
        }
        updateResizeButton()
        bottomSheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (container !== frame) return
                if (newState in listOf(BottomSheetBehavior.STATE_COLLAPSED,
                        BottomSheetBehavior.STATE_HALF_EXPANDED, BottomSheetBehavior.STATE_EXPANDED)) {
                    settledState = newState
                    updateResizeButton()
                }
                scheduleVisibleAreaSync()
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) = scheduleVisibleAreaSync()
        })
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleVisibleAreaSync() }
        host.addOnLayoutChangeListener(hostLayoutListener)
        host.doOnLayout {
            if (container !== frame) return@doOnLayout
            configureSize()
            scheduleVisibleAreaSync()
        }
        adapter.submitList(emptyList())
        showingRetired = false
        content.reviewCardsRetired.setOnClickListener {
            showingRetired = !showingRetired
            showLoaded(content)
        }
        loadJob = scope.launch {
            try {
                val cards = loadCards()
                if (request != generation || sheet !== content) return@launch
                content.reviewCardsProgress.visibility = View.GONE
                loaded = cards
                showLoaded(content)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ReviewCardsSheet", "Could not load review cards", error)
                if (request == generation && sheet === content) {
                    content.reviewCardsProgress.visibility = View.GONE
                    content.reviewCardsMessage.setText(R.string.review_cards_sheet_failed)
                    content.reviewCardsMessage.visibility = View.VISIBLE
                }
            }
        }
    }

    /** The list chosen (the PDF's cards, or the retired ones), its title, and the button to the other. */
    private fun showLoaded(content: PdfReviewCardsSheetBinding) {
        if (loaded.retired.isEmpty()) showingRetired = false
        val shown = if (showingRetired) loaded.retired else loaded.cards
        content.reviewCardsTitle.text = activity.getString(
            if (showingRetired) R.string.review_cards_sheet_retired_title else R.string.review_cards_sheet_count, shown.size)
        content.reviewCardsMessage.setText(R.string.review_cards_sheet_empty)
        content.reviewCardsMessage.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        content.reviewCardsRetired.visibility = if (loaded.retired.isEmpty()) View.GONE else View.VISIBLE
        content.reviewCardsRetired.text = if (showingRetired) {
            activity.getString(R.string.review_cards_sheet_current_button, loaded.cards.size)
        } else {
            activity.getString(R.string.review_cards_sheet_retired_button, loaded.retired.size)
        }
        if (adapter.selectedId != null) {
            adapter.selectedId = null
            highlightCard(null)
        }
        adapter.submitList(shown)
    }

    private fun onCardClicked(item: ReviewCardsSheetItem) {
        if (item.retired) {
            openRetiredCard(item)
            return
        }
        val content = sheet ?: return
        adapter.selectedId = item.cardId
        syncVisibleArea()
        // Focus after the bottom sheet has established the unobscured PDF viewport.
        content.root.post { if (sheet === content && adapter.selectedId == item.cardId) highlightCard(item) }
    }

    private fun onReviewClicked(item: ReviewCardsSheetItem) {
        // The review card takes the bottom of the screen, so the sheet makes way for it.
        dismiss()
        openCard(item)
    }

    private val hostLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        configureSize()
        scheduleVisibleAreaSync()
    }

    private fun configureSize() {
        val current = behavior ?: return
        if (host.height <= 0) return
        val hostLocation = IntArray(2)
        val pdfLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        binding.pdfView.getLocationOnScreen(pdfLocation)
        val pdfTop = (pdfLocation[1] - hostLocation[1]).coerceAtLeast(0)
        val expandedOffset = minOf(pdfTop, host.height / 3)
        if (current.expandedOffset != expandedOffset) current.expandedOffset = expandedOffset
        val peekHeight = maxOf((host.height * .30f).toInt(), dp(200))
            .coerceAtMost((host.height * .45f).toInt())
        if (current.peekHeight != peekHeight) current.peekHeight = peekHeight
    }

    private fun updateResizeButton() {
        val button = sheet?.reviewCardsResize ?: return
        val expanded = settledState == BottomSheetBehavior.STATE_EXPANDED
        button.setImageResource(if (expanded) R.drawable.ic_contents_collapse else R.drawable.ic_fullscreen_grey)
        val label = activity.getString(if (expanded) R.string.contents_collapse_panel else R.string.contents_expand_panel)
        button.contentDescription = label
        androidx.appcompat.widget.TooltipCompat.setTooltipText(button, label)
    }

    private fun scheduleVisibleAreaSync() {
        val frame = container ?: return
        if (syncPosted) return
        syncPosted = true
        // CoordinatorLayout positions its child after the child's layout callback.
        frame.postOnAnimation {
            if (container === frame) {
                syncPosted = false
                syncVisibleArea()
            }
        }
    }

    private fun syncVisibleArea() {
        val frame = container ?: return
        val content = sheet ?: return
        val height = (host.height - frame.top).coerceIn(0, frame.height)
        // The list must end at the screen edge even when the sheet is only half open.
        if (height > 0 && content.root.layoutParams.height != height) {
            content.root.layoutParams = content.root.layoutParams.apply { this.height = height }
        }
        val frameLocation = IntArray(2)
        val pdfLocation = IntArray(2)
        frame.getLocationOnScreen(frameLocation)
        binding.pdfView.getLocationOnScreen(pdfLocation)
        val bottom = (frameLocation[1] - pdfLocation[1]).coerceIn(0, binding.pdfView.height)
        if (bottom > 0 && binding.pdfView.width > 0) {
            binding.pdfView.setContentViewport(RectF(0f, 0f, binding.pdfView.width.toFloat(), bottom.toFloat()))
        }
    }

    fun handleBack(): Boolean = dismiss()

    fun dismiss(): Boolean {
        val frame = container ?: return false
        if (adapter.selectedId != null) {
            adapter.selectedId = null
            highlightCard(null)
        }
        generation++
        loadJob?.cancel()
        loadJob = null
        sheet?.reviewCardsList?.adapter = null
        sheet = null
        container = null
        syncPosted = false
        behavior = null
        host.removeOnLayoutChangeListener(hostLayoutListener)
        host.removeView(frame)
        if (host.childCount == 0) host.visibility = View.GONE
        binding.pdfView.clearContentViewport()
        openChanged(false)
        return true
    }

    fun reset() {
        dismiss()
        loaded = ReviewCardsSheetContent(emptyList())
        adapter.submitList(emptyList())
        settledState = BottomSheetBehavior.STATE_HALF_EXPANDED
        reopenAfterLoad = false
    }

    fun saveState(bundle: Bundle) {
        bundle.putBoolean("review_cards_sheet_open", sheet != null || reopenAfterLoad)
        bundle.putInt("review_cards_sheet_position", settledState)
    }

    fun restoreState(bundle: Bundle) {
        settledState = if (bundle.getInt("review_cards_sheet_position") == BottomSheetBehavior.STATE_EXPANDED) {
            BottomSheetBehavior.STATE_EXPANDED
        } else {
            BottomSheetBehavior.STATE_HALF_EXPANDED
        }
        reopenAfterLoad = bundle.getBoolean("review_cards_sheet_open")
    }

    fun onDocumentLoaded() {
        if (reopenAfterLoad) show()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}

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
import android.text.InputType
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PdfReviewCardsSheetBinding
import com.gitlab.mudlej.MjPdfReader.databinding.RowReviewCardSheetBinding
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardMerge
import com.gitlab.mudlej.MjPdfReader.ui.usernotes.AgentEditSelection
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
    /** How often it was answered: Merge cards keeps the most reviewed card unless told otherwise. */
    val reviewCount: Int = 0,
)

/** What Merge cards in the sheet came to. */
sealed class ReviewCardsSheetMergeOutcome {
    /** The server merged them; [message] is shown, and the list follows at the next Sync. */
    data class Merged(val message: String) : ReviewCardsSheetMergeOutcome()
    /** Nothing changed; [message] says why. */
    data class Refused(val message: String) : ReviewCardsSheetMergeOutcome()
}

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
    /** A project card's mark, "Project card · Changed since you last saw it" (review-card-kinds). */
    kindMark: String? = null,
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
        kindMark,
    ).joinToString(" · ").let { summary -> (listOf(summary) + groupNotes).joinToString("\n") }
}

private class ReviewCardsSheetAdapter(
    private val onClick: (ReviewCardsSheetItem) -> Unit,
    private val onReview: (ReviewCardsSheetItem) -> Unit,
    /** A long press; true when it was handled (a card on this PDF starts selection mode). */
    private val onLongClick: (ReviewCardsSheetItem) -> Boolean = { false },
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

    /** Selection mode: the selected card IDs; null while it is off. */
    var selection: Set<String>? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(RowReviewCardSheetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val selecting = selection != null && !item.retired
        val checked = selecting && item.cardId in selection.orEmpty()
        val selected = checked || (selection == null && item.cardId == selectedId)
        val context = holder.row.root.context
        holder.row.reviewCardQuestion.text = item.question
        holder.row.reviewCardMetadata.text = item.metadata
        // A card that is not checkable ignores setChecked: clear the check first, then the mode.
        holder.row.root.isCheckable = true
        holder.row.root.isChecked = checked
        holder.row.root.isCheckable = selecting
        holder.row.root.setCardBackgroundColor(MaterialColors.getColor(holder.row.root,
            if (selected) com.google.android.material.R.attr.colorSecondaryContainer
            else com.google.android.material.R.attr.colorSurface))
        holder.row.reviewCardReview.visibility =
            if (selection == null && selected && item.reviewable) View.VISIBLE else View.GONE
        holder.row.reviewCardReview.setOnClickListener { onReview(item) }
        holder.row.root.setOnClickListener { onClick(item) }
        if (item.retired) {
            holder.row.root.setOnLongClickListener(null)
            holder.row.root.isLongClickable = false
        } else {
            holder.row.root.setOnLongClickListener { onLongClick(item) }
        }
        // TalkBack: "Selected" or "Not selected", and what a tap or a long press does now.
        ViewCompat.setStateDescription(holder.row.root, if (!selecting) null
            else context.getString(if (checked) R.string.review_cards_sheet_row_selected else R.string.review_cards_sheet_row_not_selected))
        ViewCompat.replaceAccessibilityAction(holder.row.root, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            if (!selecting) null else context.getString(if (checked) R.string.review_cards_sheet_row_deselect else R.string.review_cards_sheet_row_select), null)
        ViewCompat.replaceAccessibilityAction(holder.row.root, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            if (!item.retired && selection == null) context.getString(R.string.review_cards_sheet_row_select) else null, null)
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
    /**
     * Merges the cards (in list order) into the kept one with the question given; null when this
     * reader cannot merge. Called off the main thread's work by the caller (it suspends).
     */
    private val mergeCards: (suspend (List<ReviewCardsSheetItem>, String, String) -> ReviewCardsSheetMergeOutcome)? = null,
) {
    private val adapter = ReviewCardsSheetAdapter(::onCardClicked, ::onReviewClicked, ::onCardLongClicked)
    /** Cards held for Merge cards: a long press starts it, a tap adds or removes one. */
    private val selection = AgentEditSelection()
    private var merging = false
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
        selection.clear()
        adapter.selection = null
        content.reviewCardsRetired.setOnClickListener {
            showingRetired = !showingRetired
            showLoaded(content)
        }
        content.reviewCardsCancelSelection.setOnClickListener { endSelection() }
        content.reviewCardsMerge.setOnClickListener { showMergeDialog() }
        load(content, request)
    }

    private fun load(content: PdfReviewCardsSheetBinding, request: Int) {
        loadJob?.cancel()
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

    /** Reads the cards again while the sheet is open (after a Sync): the list follows merges made anywhere. */
    fun refresh() {
        val content = sheet ?: return
        if (merging) return
        load(content, generation)
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
        if (selection.active && !selection.retainExisting(loaded.cards.mapTo(mutableSetOf()) { it.cardId })) adapter.selection = null
        adapter.submitList(shown)
        updateSelectionUi(content)
    }

    /** The header says "N selected" with Cancel, and Merge N cards replaces Retired, while cards are held. */
    private fun updateSelectionUi(content: PdfReviewCardsSheetBinding) {
        val selecting = selection.active && !showingRetired
        adapter.selection = if (selecting) selection.ids else null
        if (selecting) {
            content.reviewCardsTitle.text = activity.getString(R.string.review_cards_sheet_selected, selection.count)
        } else {
            val shown = if (showingRetired) loaded.retired else loaded.cards
            content.reviewCardsTitle.text = activity.getString(
                if (showingRetired) R.string.review_cards_sheet_retired_title else R.string.review_cards_sheet_count, shown.size)
        }
        ViewCompat.setAccessibilityLiveRegion(content.reviewCardsTitle,
            if (selecting) ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE else ViewCompat.ACCESSIBILITY_LIVE_REGION_NONE)
        content.reviewCardsCancelSelection.visibility = if (selecting) View.VISIBLE else View.GONE
        content.reviewCardsResize.visibility = if (selecting) View.GONE else View.VISIBLE
        content.reviewCardsClose.visibility = if (selecting) View.GONE else View.VISIBLE
        content.reviewCardsRetired.visibility = if (selecting || loaded.retired.isEmpty()) View.GONE else View.VISIBLE
        content.reviewCardsMerge.visibility = if (selecting && mergeCards != null) View.VISIBLE else View.GONE
        content.reviewCardsMerge.isEnabled = selection.count >= ReviewCardMerge.MIN_CARDS && !merging
        content.reviewCardsMerge.text = if (selection.count >= ReviewCardMerge.MIN_CARDS) {
            activity.getString(R.string.review_cards_sheet_merge_button, selection.count)
        } else {
            activity.getString(R.string.review_cards_sheet_merge_button_disabled)
        }
    }

    private fun onCardLongClicked(item: ReviewCardsSheetItem): Boolean {
        val content = sheet ?: return false
        if (item.retired || showingRetired || mergeCards == null || merging) return false
        if (adapter.selectedId != null) {
            adapter.selectedId = null
            highlightCard(null)
        }
        selection.longPress(item.cardId)
        updateSelectionUi(content)
        return true
    }

    private fun endSelection() {
        val content = sheet ?: return
        selection.clear()
        updateSelectionUi(content)
    }

    /** Merge cards: which card to keep (the most reviewed by default) and its question, then the server merges. */
    private fun showMergeDialog() {
        val content = sheet ?: return
        val chosen = loaded.cards.filter { it.cardId in selection }
        if (chosen.size < ReviewCardMerge.MIN_CARDS) return
        val density = activity.resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val keepIds = chosen.map { it.cardId }
        var kept = ReviewCardMerge.defaultKept(keepIds, chosen.associate { it.cardId to it.reviewCount }) ?: keepIds.first()
        val questionLayout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.review_cards_sheet_merge_question)
        }
        val question = TextInputEditText(questionLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            setText(chosen.first { it.cardId == kept }.question)
        }
        questionLayout.addView(question)
        var questionEdited = false
        var settingQuestion = false
        question.doAfterTextChanged {
            if (!settingQuestion) questionEdited = true
            questionLayout.error = null
        }
        val group = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        chosen.forEachIndexed { index, item ->
            group.addView(RadioButton(activity).apply {
                id = View.generateViewId()
                tag = item.cardId
                text = item.question
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                isChecked = item.cardId == kept
            })
        }
        group.setOnCheckedChangeListener { radios, checkedId ->
            val id = radios.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            kept = id
            // The kept card's question, until the user writes their own.
            if (!questionEdited) {
                settingQuestion = true
                question.setText(chosen.first { it.cardId == id }.question)
                settingQuestion = false
            }
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(TextView(activity).apply {
                setText(R.string.review_cards_sheet_merge_explanation)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            })
            addView(TextView(activity).apply {
                setText(R.string.review_cards_sheet_merge_keep)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setPadding(0, padding / 2, 0, 0)
            })
            addView(group)
            addView(questionLayout)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.review_cards_sheet_merge_title)
            .setView(android.widget.ScrollView(activity).apply { addView(body) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.review_cards_sheet_merge_action, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = question.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    questionLayout.error = activity.getString(R.string.review_cards_sheet_merge_question_empty)
                    return@setOnClickListener
                }
                dialog.dismiss()
                merge(content, chosen, kept, text.take(ReviewCardMerge.MAX_QUESTION_LENGTH))
            }
        }
        dialog.show()
    }

    private fun merge(content: PdfReviewCardsSheetBinding, cards: List<ReviewCardsSheetItem>, keptId: String, question: String) {
        val mergeAction = mergeCards ?: return
        merging = true
        updateSelectionUi(content)
        content.reviewCardsTitle.setText(R.string.review_cards_sheet_merging)
        scope.launch {
            val outcome = try {
                mergeAction(cards, keptId, question)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ReviewCardsSheet", "Could not merge review cards", error)
                ReviewCardsSheetMergeOutcome.Refused(activity.getString(R.string.review_cards_sheet_merge_offline))
            }
            merging = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when (outcome) {
                is ReviewCardsSheetMergeOutcome.Merged -> {
                    selection.clear()
                    if (sheet === content) updateSelectionUi(content)
                    AppSnackbar.make(binding.root, outcome.message, Snackbar.LENGTH_LONG).show()
                }
                is ReviewCardsSheetMergeOutcome.Refused -> {
                    if (sheet === content) updateSelectionUi(content)
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.review_cards_sheet_merge_failed_title)
                        .setMessage(activity.getString(R.string.review_cards_sheet_merge_failed, outcome.message))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun onCardClicked(item: ReviewCardsSheetItem) {
        if (selection.active && !showingRetired) {
            if (item.retired || merging) return
            selection.toggle(item.cardId)
            sheet?.let(::updateSelectionUi)
            return
        }
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

    fun handleBack(): Boolean {
        if (selection.active && sheet != null && !merging) {
            endSelection()
            return true
        }
        return dismiss()
    }

    fun dismiss(): Boolean {
        val frame = container ?: return false
        if (adapter.selectedId != null) {
            adapter.selectedId = null
            highlightCard(null)
        }
        generation++
        loadJob?.cancel()
        loadJob = null
        selection.clear()
        adapter.selection = null
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

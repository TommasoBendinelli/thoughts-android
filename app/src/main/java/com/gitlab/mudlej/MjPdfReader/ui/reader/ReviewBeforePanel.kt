// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewAgentPrediction
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerPhases
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagScales
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemReviewBeforeScaleBinding
import com.google.android.material.color.MaterialColors

/** Where the agent's recall prediction for a card is: asked, arrived, or not to be had. */
internal sealed interface ReviewRecallPredictionState {
    data object Pending : ReviewRecallPredictionState
    data class Ready(val prediction: ReviewAgentPrediction) : ReviewRecallPredictionState
    data object Failed : ReviewRecallPredictionState
}

/**
 * The line the agent's prediction shows once the answer is shown: "Agent predicted: Partly recalled —
 * <reason>", "Agent: predicting…" while it is asked; null (nothing) when it failed or was never asked.
 */
internal fun reviewRecallPredictionText(context: Context, state: ReviewRecallPredictionState?): String? = when (state) {
    ReviewRecallPredictionState.Pending -> context.getString(R.string.review_agent_predicting)
    is ReviewRecallPredictionState.Ready -> state.prediction.let { prediction ->
        val tag = reviewTagLabel(context, prediction.tag)
        if (prediction.reason.isBlank()) context.getString(R.string.review_agent_predicted, tag)
        else context.getString(R.string.review_agent_predicted_reason, tag, prediction.reason)
    }
    ReviewRecallPredictionState.Failed, null -> null
}

/**
 * Whether the before step on screen must be drawn again: a Sync (or a card shown again) found other
 * before groups than those drawn for this answer, or another answer is up. Unchanged groups keep the
 * panel as it is, so a Sync never disturbs a note being typed.
 */
internal fun reviewBeforeStepNeedsRedraw(
    drawn: List<ReviewTagGroups.Group>?,
    current: List<ReviewTagGroups.Group>,
    sameAnswer: Boolean,
): Boolean = !sameAnswer || drawn != current

/**
 * The before panel of a review in two steps, in the review card under the question while the answer
 * is hidden: one slider per group asked before the answer ([ReviewAnswerPhases.beforeGroups]; as in
 * the tag sheet, only sliders), nothing picked, then a "Before note". Picks and note live in the
 * answer's [ReviewAnswerDraft] (the note under [ReviewGroupNotes.BEFORE_NOTE_KEY]). Once the answer is
 * shown the panel is locked: one line names what was picked ("Before: Meh · Partly recalled — note"),
 * then the agent's prediction ([showPrediction]); "Back to before step" opens it again until the
 * answer is shown again. A slider's menu sets when the group is asked
 * ("Ask: …", as in the tag sheet); [onLayoutChanged] follows such an edit. Without a group to ask
 * before the answer the panel is hidden and the review is as before.
 */
internal class ReviewBeforePanel(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val originId: () -> String,
    /** The summary line ("Before: …") was tapped: open the before step again on its picks. */
    private val onSummaryTapped: () -> Unit = {},
    /** A pick or the note changed: the draft is kept until the card's answer is recorded ([ReviewBeforeDrafts]). */
    private val onDraftChanged: () -> Unit = {},
    private val onLayoutChanged: () -> Unit,
) {
    private var draft: ReviewAnswerDraft? = null
    private var filling = false
    /** The sliders on screen, with their steps. */
    private val sliders = mutableListOf<Pair<ItemReviewBeforeScaleBinding, List<String>>>()
    private var mapping: Map<String, String> = emptyMap()
    private var prediction: String? = null
    /** The before groups [show] last drew, for [showIfChanged]. */
    private var drawnGroups: List<ReviewTagGroups.Group>? = null

    init {
        binding.reviewBeforeNote.doAfterTextChanged { text ->
            if (filling) return@doAfterTextChanged
            val draft = draft?.takeUnless { it.beforeLocked } ?: return@doAfterTextChanged
            draft.setNote(ReviewGroupNotes.BEFORE_NOTE_KEY, text?.toString().orEmpty())
            onDraftChanged()
        }
        // Once the answer was shown the picks fold into one line; tapping it reopens them, as
        // "Back to before step" in the card menu does.
        binding.reviewBeforeSummary.setOnClickListener { onSummaryTapped() }
    }

    /** Shows [draft]'s before step: to pick in while the answer is hidden, else its summary line. */
    fun show(draft: ReviewAnswerDraft) {
        this.draft = draft
        mapping = ReviewTagMerges.mapping()
        val groups = currentGroups(mapping)
        drawnGroups = groups
        binding.reviewBeforeGroups.removeAllViews()
        sliders.clear()
        if (!draft.beforeLocked && groups.isNotEmpty()) draft.beforeShown = true
        if (!draft.beforeShown) {
            hide()
            return
        }
        binding.reviewBeforePanel.isVisible = true
        val locked = draft.beforeLocked
        binding.reviewBeforeGroups.isVisible = !locked
        binding.reviewBeforeNoteLayout.isVisible = !locked
        binding.reviewBeforeSummary.isVisible = locked
        if (locked) {
            binding.reviewBeforeSummary.text = summary(draft, groups)
        } else {
            groups.forEach(::addSlider)
            filling = true
            binding.reviewBeforeNote.setText(draft.note(ReviewGroupNotes.BEFORE_NOTE_KEY))
            filling = false
            refresh()
        }
        showPrediction(prediction)
    }

    /**
     * [show]s [draft] again when the layout's before groups changed since it was drawn (a Sync brought
     * another layout); false, touching nothing, when they did not.
     */
    fun showIfChanged(draft: ReviewAnswerDraft): Boolean {
        if (!reviewBeforeStepNeedsRedraw(drawnGroups, currentGroups(ReviewTagMerges.mapping()), draft === this.draft)) return false
        show(draft)
        return true
    }

    private fun currentGroups(mapping: Map<String, String>): List<ReviewTagGroups.Group> =
        ReviewAnswerPhases.beforeGroups(ReviewTagGroups.resolved(ReviewTagGroups.groups(), mapping)).filter { it.isScale }

    fun hide() {
        if (binding.reviewBeforeNote.hasFocus()) binding.reviewBeforeNote.clearFocus()
        binding.reviewBeforePanel.isVisible = false
    }

    /** The agent's line ([reviewRecallPredictionText]), shown only once the panel is locked. */
    fun showPrediction(text: String?) {
        prediction = text
        val shown = text?.takeIf { draft?.beforeLocked == true && draft?.beforeShown == true }
        binding.reviewAgentPrediction.text = shown
        binding.reviewAgentPrediction.isVisible = shown != null
    }

    /** "Before: Meh · Partly recalled — note", the picks in group order. */
    private fun summary(draft: ReviewAnswerDraft, groups: List<ReviewTagGroups.Group>): String {
        val tags = ReviewTagScales.summaryTags(draft.beforeTags, groups, mapping)
            .joinToString(" · ") { reviewTagLabel(context, it) }
            .ifEmpty { context.getString(R.string.review_before_nothing) }
        val note = draft.note(ReviewGroupNotes.BEFORE_NOTE_KEY).trim()
        return if (note.isEmpty()) context.getString(R.string.review_before_summary, tags)
            else context.getString(R.string.review_before_summary_note, tags, note)
    }

    private fun addSlider(group: ReviewTagGroups.Group) {
        val steps = group.scale.orEmpty()
        val item = ItemReviewBeforeScaleBinding.inflate(LayoutInflater.from(context), binding.reviewBeforeGroups, false)
        item.reviewBeforeScaleName.text = group.name
        item.reviewBeforeScaleMenu.contentDescription = context.getString(R.string.review_tag_groups_options, group.name)
        TooltipCompat.setTooltipText(item.reviewBeforeScaleMenu, item.reviewBeforeScaleMenu.contentDescription)
        item.reviewBeforeScaleMenu.setOnClickListener { anchor -> showMenu(anchor, group) }
        item.reviewBeforeScaleTrack.steps = steps.size
        item.reviewBeforeScaleTrack.isVisible = steps.isNotEmpty()
        item.reviewBeforeScaleTrack.onTap = { index -> pick(steps, steps[index], toggle = true) }
        item.reviewBeforeScaleTrack.onDrag = { index -> pick(steps, steps[index], toggle = false) }
        steps.forEach { step ->
            item.reviewBeforeScaleLabels.addView(reviewScaleStepLabel(context, reviewTagLabel(context, step)) {
                pick(steps, step, toggle = true)
            }.apply {
                // The cells share a row with the group's name, so the page stays in view.
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                updatePadding(left = 0, top = 0, right = 0)
            })
        }
        binding.reviewBeforeGroups.addView(item.root)
        sliders += item to steps
    }

    /** Picks [step] of a slider (replacing its other step); [toggle] clears it when it is the picked one. */
    private fun pick(steps: List<String>, step: String, toggle: Boolean) {
        val draft = draft?.takeUnless { it.beforeLocked } ?: return
        draft.beforeTags = if (toggle) ReviewTagScales.toggle(draft.beforeTags, steps, step, mapping)
            else ReviewTagScales.select(draft.beforeTags, steps, step, mapping)
        refresh()
        onDraftChanged()
    }

    private fun refresh() {
        val chosen = draft?.beforeTags.orEmpty()
        sliders.forEach { (item, steps) ->
            val step = ReviewTagScales.selectedStep(steps, chosen, mapping)
            item.reviewBeforeScaleTrack.selected = step?.let(steps::indexOf)
            val name = item.reviewBeforeScaleName.text
            item.reviewBeforeScaleTrack.contentDescription = if (step == null) context.getString(R.string.review_tag_scale_unset, name)
                else context.getString(R.string.review_tag_scale_value, name, reviewTagLabel(context, step))
            for (index in 0 until item.reviewBeforeScaleLabels.childCount) {
                val text = item.reviewBeforeScaleLabels.getChildAt(index) as TextView
                val on = steps.getOrNull(index) == step
                text.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                text.setTextColor(MaterialColors.getColor(text,
                    if (on) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
                text.isSelected = on
            }
        }
    }

    /** The slider's menu: only "Ask", the group's other options are in the tag sheet. */
    private fun showMenu(anchor: android.view.View, group: ReviewTagGroups.Group) {
        val popup = PopupMenu(context, anchor)
        addReviewTagPhaseMenu(popup.menu, group, Menu.NONE)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId !in REVIEW_TAG_PHASE_ITEMS) return@setOnMenuItemClickListener false
            try {
                ReviewTagGroups.edit(ReviewTagGroups.Edit.SetPhase(group.id, reviewTagPhaseOf(item.itemId)), originId())
                onLayoutChanged()
            } catch (refused: ReviewTagGroups.EditRefused) {
                Toast.makeText(context, reviewTagGroupProblem(context, refused.problem), Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, R.string.review_tag_groups_failed, Toast.LENGTH_LONG).show()
            }
            true
        }
        popup.show()
    }
}

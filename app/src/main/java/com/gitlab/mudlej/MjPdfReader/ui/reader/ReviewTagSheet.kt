// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagScales
import com.gitlab.mudlej.MjPdfReader.databinding.DialogReviewTagsBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemReviewTagGroupBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemReviewTagScaleBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemReviewTagSectionBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The review bar's tag sheet: one row per tag group (in record order), then Other, then New group.
 * Every group, slider or not, folds under its header: a closed header shows the group's name and,
 * on the same line, what this answer chose in it ("Recall · Recalled") and a mark when it has a
 * note; tapping it opens the group with its chips, New tag and a note field. Groups open and close
 * on their own, remembered on this phone per group ([ReviewTagGroupFolding]; Understanding and
 * Recall start open). Chips read
 * "<tag> (<cards>)": the cards that currently have the tag ([usage], counted once per opening), as
 * they would be once this answer is saved: one more when the card does not have it now ([current])
 * and it is chosen, one fewer when the card has it and it is not. The order (most used first) stays
 * on the counts the sheet opened with so chips do not move as they are tapped. A tag no card has
 * (0) is left out unless it is chosen; one that drops to 0 while the sheet is open stays until it
 * closes, and New tag picks a hidden tag by its name. Several tags, from
 * any groups, may be chosen, in closed groups too; each change is passed on at once. The notes live
 * in [draft], so they survive closing the sheet for the same answer. Long-press a chip to read its
 * whole name; each row's menu renames, reorders or deletes the group and moves tags into it. At the
 * end, after New group, one free-form note for the whole answer ("Note"), always open; it is a
 * group note under the reserved key [ReviewGroupNotes.ANSWER_NOTE_KEY]. Below it, "About this…":
 * one note on the card's section, page or document, shared by the document's cards
 * ([bindReviewContextNotes]).
 *
 * Slider groups (with a `scale`) come first: a header (name, then Edit slider, note button and
 * menu) and, while open, a track with one tick per step and the step's label and count under it, as on a chip (a step
 * at 0 included: a slider keeps all its steps; the chosen step bold), then apart from the slider,
 * under a "Tags" caption, the group's free tags as chips with New tag, then its note once the note
 * button opens it. An answer holds at most one step per slider: picking a step replaces the other,
 * tapping the chosen step clears it ([ReviewTagScales]). Edit slider (also in the menu) edits only
 * the steps: add, order, remove ([showReviewScaleEditor]); the menu also turns a group into a slider
 * and back.
 *
 * Groups with a section ([ReviewTagGroups.Group.section]) that are adjacent in the record show under
 * one bold header with their rows indented ([ReviewTagGroups.sectionRuns]; sliders and chip groups
 * each in their own part of the sheet, so a section holding both has a header in each). A header
 * opens and closes its groups (remembered on this phone by section name, open at first) without
 * changing their own folding; closed, it names what this answer chose in them ("Work left ·
 * Structure: Missing steps · Style: Clean"), long-press for the whole text. "Section…" in a group's
 * menu sets or clears its section.
 *
 * Review in two steps: the sheet is the step after the answer, so groups asked only before it
 * ([ReviewTagGroups.Group.askedAfter] false) are left out; a before-and-after group (Recall) is asked
 * again ([selected] already holds its predicted step). [agentPrediction], when given, is the agent's
 * recall prediction under the title. "Ask" in a group's menu sets when a review asks it.
 */
internal fun createReviewTagSheet(
    context: Context,
    tags: List<String>,
    selected: List<String>,
    originId: String,
    draft: ReviewAnswerDraft,
    usage: Map<String, Int>,
    current: Collection<String>,
    onTagCreated: (String) -> Unit,
    agentPrediction: CharSequence? = null,
    onChanged: (List<String>) -> Unit,
): BottomSheetDialog {
    val inflater = LayoutInflater.from(context)
    val binding = DialogReviewTagsBinding.inflate(inflater)
    val order = tags.toMutableList()
    val chosen = selected.toMutableSet()
    val otherName = context.getString(R.string.review_tag_groups_other)
    val label = { tag: String -> reviewTagLabel(context, tag) }
    // What the slider steps show.
    val currentTags = current.toSet()
    val count = { tag: String -> reviewTagShownCount(usage, currentTags, chosen, tag) }
    val countedLabel = { tag: String -> reviewTagCountText(label(tag), count(tag)) }
    // The rows on screen, by group key, with the tags each one lists.
    val rows = linkedMapOf<String, Pair<ItemReviewTagGroupBinding, List<String>>>()
    // The slider groups on screen, by group key, with their steps and all their tags.
    val scales = linkedMapOf<String, Triple<ItemReviewTagScaleBinding, List<String>, List<String>>>()
    // Slider groups whose note field is open (by key); a note is opened with the note button.
    val openNotes = mutableSetOf<String>()
    // Which groups are open, remembered on this phone; groups never opened or closed follow their
    // default (by key, set in render()).
    val folding = ReviewTagGroupFolding(context)
    val openByDefault = mutableMapOf<String, Boolean>()
    fun isOpen(key: String) = folding.isOpen(key, openByDefault[key] ?: false)
    fun headerDescription(name: CharSequence, summary: String, open: Boolean) = listOf(
        name, summary, context.getString(if (open) R.string.review_tag_group_expanded else R.string.review_tag_group_collapsed),
    ).filter { it.isNotEmpty() }.joinToString(", ")
    var mapping = ReviewTagMerges.mapping()
    // The section headers on screen, each with the keys of the groups under it, and every group's
    // shown name, tags and slider steps by key (for the headers' summaries).
    class SectionBlock(val item: ItemReviewTagSectionBinding, val name: String, val keys: MutableList<String> = mutableListOf())
    val sectionBlocks = mutableListOf<SectionBlock>()
    val groupInfo = mutableMapOf<String, Triple<String, List<String>, List<String>>>()
    // Tags given a chip since the sheet opened: one that drops to 0 under the finger keeps it.
    val shownTags = mutableSetOf<String>()
    var fullTag: PopupWindow? = null
    binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { fullTag?.dismiss() }
    })

    fun refreshRows() {
        rows.forEach { (key, row) ->
            val (item, groupTags) = row
            val summary = reviewTagGroupSummary(groupTags, chosen, label)
            val open = isOpen(key)
            item.reviewTagGroupSummary.text = context.getString(R.string.review_tag_group_summary, summary)
            // An open group shows its chosen tags as chips, with their counts; the summary is for
            // closed ones, on the name's line: "Interest · Somewhat, Submitted".
            item.reviewTagGroupSummary.isVisible = summary.isNotEmpty() && !open
            item.reviewTagGroupNoteMark.isVisible = draft.hasNote(key)
            if (!open && item.reviewTagGroupNote.hasFocus()) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(item.reviewTagGroupNote.windowToken, 0)
                item.reviewTagGroupNote.clearFocus()
            }
            item.reviewTagGroupBody.isVisible = open
            item.reviewTagGroupExpand.setImageResource(if (open) R.drawable.ic_small_arrow_down else R.drawable.ic_small_arrow_right)
            item.reviewTagGroupHeading.contentDescription = headerDescription(item.reviewTagGroupName.text, summary, open)
        }
    }
    fun refreshScales() {
        scales.forEach { (key, block) ->
            val (item, steps, groupTags) = block
            val held = ReviewTagScales.chosenSteps(steps, chosen, mapping)
            val step = held.firstOrNull()
            item.reviewTagScaleTrack.selected = step?.let(steps::indexOf)
            val labels = item.reviewTagScaleLabels
            for (index in 0 until labels.childCount) {
                val text = labels.getChildAt(index) as TextView
                // The count follows the choice, as on a chip.
                steps.getOrNull(index)?.let { text.text = countedLabel(it) }
                val on = steps.getOrNull(index) in held
                text.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                text.setTextColor(MaterialColors.getColor(text,
                    if (on) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
                text.isSelected = on
            }
            val name = item.reviewTagScaleName.text
            item.reviewTagScaleTrack.contentDescription = if (step == null) context.getString(R.string.review_tag_scale_unset, name)
                else context.getString(R.string.review_tag_scale_value, name, label(step))
            val hasNote = draft.hasNote(key)
            item.reviewTagScaleNoteButton.setImageResource(if (hasNote) R.drawable.ic_comment else R.drawable.ic_comment_add)
            item.reviewTagScaleNoteButton.imageTintList = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(
                item.reviewTagScaleNoteButton,
                if (hasNote) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
            val groupOpen = isOpen(key)
            val summary = reviewTagGroupChoiceSummary(groupTags, steps, chosen + held, label)
            item.reviewTagScaleSummary.text = context.getString(R.string.review_tag_group_summary, summary)
            item.reviewTagScaleSummary.isVisible = summary.isNotEmpty() && !groupOpen
            item.reviewTagScaleBody.isVisible = groupOpen
            item.reviewTagScaleExpand.setImageResource(if (groupOpen) R.drawable.ic_small_arrow_down else R.drawable.ic_small_arrow_right)
            item.reviewTagScaleHeading.contentDescription = headerDescription(name, summary, groupOpen)
            val open = groupOpen && key in openNotes
            item.reviewTagScaleNoteLayout.isVisible = open
            if (!open && item.reviewTagScaleNote.hasFocus()) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(item.reviewTagScaleNote.windowToken, 0)
                item.reviewTagScaleNote.clearFocus()
            }
        }
    }
    fun sectionSummary(block: SectionBlock): String = reviewTagSectionSummary(block.keys.mapNotNull { key ->
        groupInfo[key]?.let { (name, groupTags, steps) ->
            name to reviewTagGroupChoiceSummary(groupTags, steps, chosen + ReviewTagScales.chosenSteps(steps, chosen, mapping), label)
        }
    })
    fun refreshSections() {
        sectionBlocks.forEach { block ->
            val item = block.item
            val open = folding.isSectionOpen(block.name)
            val summary = sectionSummary(block)
            item.reviewTagSectionSummary.text = context.getString(R.string.review_tag_group_summary, summary)
            item.reviewTagSectionSummary.isVisible = summary.isNotEmpty() && !open
            item.reviewTagSectionBody.isVisible = open
            item.reviewTagSectionExpand.setImageResource(if (open) R.drawable.ic_small_arrow_down else R.drawable.ic_small_arrow_right)
            item.reviewTagSectionHeading.contentDescription = headerDescription(block.name, summary, open)
        }
    }
    fun changed() {
        refreshRows()
        refreshScales()
        refreshSections()
        onChanged(order.filter { it in chosen })
    }
    fun chosenList(): List<String> = order.filter { it in chosen } + chosen.filter { it !in order }
    /** Picks [step] of a slider (replacing its other step); [toggle] clears it when it is the chosen one. */
    fun pickStep(steps: List<String>, step: String, toggle: Boolean) {
        val next = if (toggle) ReviewTagScales.toggle(chosenList(), steps, step, mapping)
            else ReviewTagScales.select(chosenList(), steps, step, mapping)
        chosen.clear()
        chosen += next
        if (step !in order) order += step
        changed()
    }

    lateinit var render: () -> Unit
    fun edit(edit: ReviewTagGroups.Edit): Boolean = try {
        ReviewTagGroups.edit(edit, originId)
        render()
        true
    } catch (refused: ReviewTagGroups.EditRefused) {
        Toast.makeText(context, reviewTagGroupProblem(context, refused.problem), Toast.LENGTH_LONG).show()
        false
    } catch (error: Exception) {
        Toast.makeText(context, R.string.review_tag_groups_failed, Toast.LENGTH_LONG).show()
        false
    }

    /** The slider editor for [group]; a group shown as chips starts a slider with no steps. */
    fun editSlider(group: ReviewTagGroups.Group) {
        val steps = group.scale.orEmpty()
        val title = context.getString(if (group.isScale) R.string.review_tag_scale_edit_title else R.string.review_tag_scale_make_title, group.name)
        // A typed step that names a tag (hidden, merged or of another group) is that tag.
        val resolve = { typed: String ->
            normalizeReviewTag(context, typed)?.let { reviewTypedTag(it, order, ReviewTagMerges.mapping(), label) }
        }
        showReviewScaleEditor(context, title, steps, group.tags.filter { it !in steps }, label, resolve) { picked ->
            val created = picked.filter { it !in order }
            if (edit(ReviewTagGroups.Edit.SetScale(group.id, picked))) created.forEach { tag ->
                onTagCreated(tag)
                if (tag !in order) order += tag
            }
        }
    }

    /**
     * [up] and [down]: how far the group moves in the record to pass the previous or next group shown
     * like it (sliders among sliders, rows among rows); null when it is first or last of them.
     */
    fun showGroupMenu(anchor: View, group: ReviewTagGroups.Group?, up: Int?, down: Int?) {
        val popup = PopupMenu(context, anchor)
        // Other is implicit: it can only receive tags (they leave their groups).
        if (group != null) {
            popup.menu.add(Menu.NONE, RENAME_ITEM, 0, R.string.review_tag_groups_rename)
            popup.menu.add(Menu.NONE, UP_ITEM, 1, R.string.review_tag_groups_move_up).isEnabled = up != null
            popup.menu.add(Menu.NONE, DOWN_ITEM, 2, R.string.review_tag_groups_move_down).isEnabled = down != null
            // Only sliders are shown: no turning them into tags, and no tags to move.
            if (group.isScale) popup.menu.add(Menu.NONE, SCALE_STEPS_ITEM, 3, R.string.review_tag_scale_edit_menu)
            popup.menu.add(Menu.NONE, SECTION_ITEM, 3, R.string.review_tag_section_menu)
            addReviewTagPhaseMenu(popup.menu, group, 4)
        }
        if (group != null) popup.menu.add(Menu.NONE, DELETE_ITEM, 5, R.string.review_tag_groups_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                RENAME_ITEM -> group?.let {
                    showReviewTagGroupNameDialog(
                        context, R.string.review_tag_groups_rename_title, R.string.review_tag_groups_save, it.name, it.id,
                    ) { name -> edit(ReviewTagGroups.Edit.Rename(it.id, name)) }
                }
                UP_ITEM -> if (group != null && up != null) edit(ReviewTagGroups.Edit.MoveGroup(group.id, up))
                DOWN_ITEM -> if (group != null && down != null) edit(ReviewTagGroups.Edit.MoveGroup(group.id, down))
                SCALE_STEPS_ITEM -> group?.let(::editSlider)
                SECTION_ITEM -> group?.let {
                    showReviewTagSectionDialog(context, it, ReviewTagGroups.sectionNames(ReviewTagGroups.groups())) { section ->
                        edit(ReviewTagGroups.Edit.SetSection(it.id, section))
                    }
                }
                in REVIEW_TAG_PHASE_ITEMS -> group?.let { edit(ReviewTagGroups.Edit.SetPhase(it.id, reviewTagPhaseOf(item.itemId))) }
                DELETE_ITEM -> group?.let {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(context.getString(R.string.review_tag_groups_delete_title, it.name))
                        .setMessage(R.string.review_tag_groups_delete_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.review_tag_groups_delete) { _, _ ->
                            edit(ReviewTagGroups.Edit.DeleteGroup(it.id))
                        }
                        .show()
                }
            }
            true
        }
        popup.show()
        lastReviewTagGroupMenu = java.lang.ref.WeakReference(popup)
    }

    render = {
        mapping = ReviewTagMerges.mapping()
        // Groups asked only before the answer are not asked again (review in two steps).
        val allSections = reviewTagSheetSections(ReviewTagGroups.sections(order), usage, label)
        val sections = allSections.filter { it.group?.askedAfter != false }
        // Group tags this phone has not used yet can be chosen like any other.
        sections.flatMap { it.tags }.filter { it !in order }.forEach { order += it }
        // Moves count in the whole record, past the groups left out too.
        val groups = allSections.mapNotNull { it.group }
        fun moves(group: ReviewTagGroups.Group): Pair<Int?, Int?> {
            val at = groups.indexOf(group)
            val alike = groups.indices.filter { groups[it].isScale == group.isScale && groups[it].askedAfter }
            val position = alike.indexOf(at)
            return alike.getOrNull(position - 1)?.minus(at) to alike.getOrNull(position + 1)?.minus(at)
        }
        val hasGroups = groups.isNotEmpty()
        // Adjacent groups of one section share a header; in each part of the sheet a group goes
        // under the header of its run (made when the run's first group there is placed) or on its own.
        val runs = ReviewTagGroups.sectionRuns(groups).withIndex().associate { groups[it.index].id to it.value }
        sectionBlocks.clear()
        groupInfo.clear()
        fun placer(container: LinearLayout): (ReviewTagGroups.Group?, String, View) -> Unit {
            var last: Pair<Int, SectionBlock>? = null
            return { group, key, view ->
                val at = group?.let { runs[it.id] }
                val name = group?.section
                if (at == null || name == null) container.addView(view) else {
                    val block = last?.takeIf { it.first == at }?.second ?: run {
                        val item = ItemReviewTagSectionBinding.inflate(inflater, container, false)
                        item.reviewTagSectionName.text = name
                        val made = SectionBlock(item, name)
                        item.reviewTagSectionHeading.setOnClickListener {
                            folding.setSectionOpen(name, !folding.isSectionOpen(name))
                            refreshSections()
                        }
                        // The summary is cut at the end of the line; pressing the header shows all of it.
                        item.reviewTagSectionHeading.setOnLongClickListener { heading ->
                            fullTag?.dismiss()
                            fullTag = showFullReviewTag(heading, reviewTagSectionText(name, sectionSummary(made)))
                            true
                        }
                        container.addView(item.root)
                        sectionBlocks += made
                        last = at to made
                        made
                    }
                    block.item.reviewTagSectionBody.addView(view)
                    block.keys += key
                }
            }
        }
        val placeScale = placer(binding.reviewTagScales)
        openByDefault.clear()
        sections.forEach { openByDefault[ReviewGroupNotes.keyOf(it.group)] = reviewTagGroupOpenByDefault(it.group, hasGroups) }
        binding.reviewTagScales.removeAllViews()
        scales.clear()
        sections.filter { it.group?.isScale == true }.forEach { section ->
            val group = checkNotNull(section.group)
            val key = ReviewGroupNotes.keyOf(group)
            val steps = group.scale.orEmpty()
            val item = ItemReviewTagScaleBinding.inflate(inflater, binding.reviewTagScales, false)
            item.reviewTagScaleName.text = group.name
            item.reviewTagScaleMenu.contentDescription = context.getString(R.string.review_tag_groups_options, group.name)
            TooltipCompat.setTooltipText(item.reviewTagScaleMenu, item.reviewTagScaleMenu.contentDescription)
            item.reviewTagScaleMenu.setOnClickListener { view -> moves(group).let { showGroupMenu(view, group, it.first, it.second) } }
            TooltipCompat.setTooltipText(item.reviewTagScaleEditButton, item.reviewTagScaleEditButton.contentDescription)
            item.reviewTagScaleEditButton.setOnClickListener { editSlider(group) }
            item.reviewTagScaleNoteButton.contentDescription = context.getString(R.string.review_tag_scale_note, group.name)
            TooltipCompat.setTooltipText(item.reviewTagScaleNoteButton, item.reviewTagScaleNoteButton.contentDescription)
            item.reviewTagScaleNoteButton.setOnClickListener {
                // The note is in the group: on a closed group the button opens both.
                if (!isOpen(key)) {
                    folding.setOpen(key, true)
                    openNotes += key
                } else if (!openNotes.remove(key)) openNotes += key
                refreshScales()
                if (key in openNotes) item.reviewTagScaleNote.requestFocus()
            }
            item.reviewTagScaleHeading.setOnClickListener {
                folding.setOpen(key, !isOpen(key))
                refreshScales()
            }
            item.reviewTagScaleTrack.steps = steps.size
            item.reviewTagScaleTrack.isVisible = steps.isNotEmpty()
            item.reviewTagScaleLabels.isVisible = steps.isNotEmpty()
            item.reviewTagScaleEmpty.isVisible = steps.isEmpty()
            item.reviewTagScaleTrack.onTap = { index -> pickStep(steps, steps[index], toggle = true) }
            item.reviewTagScaleTrack.onDrag = { index -> pickStep(steps, steps[index], toggle = false) }
            steps.forEach { step -> item.reviewTagScaleLabels.addView(reviewScaleStepLabel(context, countedLabel(step)) {
                pickStep(steps, step, toggle = true)
            }) }
            // Tags are gone: a slider shows only its steps (and its note).
            item.reviewTagScaleTagsCaption.isVisible = false
            item.reviewTagScaleChips.isVisible = false
            item.reviewTagScaleNoteLayout.hint = context.getString(R.string.review_tag_groups_note_hint, group.name)
            item.reviewTagScaleNote.setText(draft.note(key))
            item.reviewTagScaleNote.doAfterTextChanged { text ->
                draft.setNote(key, text?.toString().orEmpty())
                refreshScales()
            }
            scales[key] = Triple(item, steps, section.tags)
            groupInfo[key] = Triple(group.name, section.tags, steps)
            placeScale(group, key, item.root)
        }
        binding.reviewTagScales.isVisible = scales.isNotEmpty()
        binding.reviewTags.removeAllViews()
        rows.clear()
        // Chip groups and Other held only tags, which are no longer offered.
        binding.reviewTags.isVisible = false
        refreshRows()
        refreshScales()
        refreshSections()
    }
    binding.reviewTagSheetAgent.text = agentPrediction
    binding.reviewTagSheetAgent.isVisible = !agentPrediction.isNullOrEmpty()
    // A new group could only hold tags until made a slider; tags are gone, so it is not offered.
    binding.reviewNewTagGroup.isVisible = false
    // The answer's one free-form note, at the end and always open; kept in the draft under its reserved key.
    val answerNoteKey = ReviewGroupNotes.ANSWER_NOTE_KEY
    binding.reviewAnswerNote.setText(draft.note(answerNoteKey))
    binding.reviewAnswerNote.doAfterTextChanged { text -> draft.setNote(answerNoteKey, text?.toString().orEmpty()) }
    // The note fields are the last things on the sheet: once the keyboard has made room, scroll the
    // focused one into view.
    fun showFocusedNote() {
        val field = when {
            binding.reviewAnswerNote.hasFocus() -> binding.reviewAnswerNoteLayout
            binding.reviewContextNote.hasFocus() -> binding.reviewContextNoteLayout
            else -> return
        }
        field.requestRectangleOnScreen(Rect(0, 0, field.width, field.height), true)
    }
    binding.reviewAnswerNote.setOnFocusChangeListener { _, focused -> if (focused) binding.root.postDelayed(::showFocusedNote, 250) }
    binding.reviewContextNote.setOnFocusChangeListener { _, focused -> if (focused) binding.root.postDelayed(::showFocusedNote, 250) }
    binding.root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) binding.root.post(::showFocusedNote)
    }
    bindReviewContextNotes(context, binding, draft)
    render()
    return BottomSheetDialog(context).apply {
        setContentView(binding.root)
        // The note field needs room above the keyboard; the sheet opens fully so a row opens in place.
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        behavior.skipCollapsed = true
        setOnShowListener { behavior.state = BottomSheetBehavior.STATE_EXPANDED }
    }
}

/**
 * "About this…" after the answer's Note: a Section | Page | Document selector, a line naming what the
 * chosen scope is about (the section's heading, "Page N", the document's name) and one box with the
 * note shared by every card of the document for that section, page or document. Shown when [draft]
 * knows the card's targets ([ReviewAnswerDraft.settleContext]); Section is disabled when the card
 * has no enclosing heading. The box starts with the note's current text; edits stay in the draft per
 * scope, so switching keeps them, and Next records the changed scopes with the answer.
 */
private fun bindReviewContextNotes(context: Context, binding: DialogReviewTagsBinding, draft: ReviewAnswerDraft) {
    val about = draft.context ?: return
    binding.reviewContextBlock.isVisible = true
    val buttons = linkedMapOf(
        ReviewContextNotes.Scope.SECTION to binding.reviewContextSection,
        ReviewContextNotes.Scope.PAGE to binding.reviewContextPage,
        ReviewContextNotes.Scope.DOCUMENT to binding.reviewContextDocument,
    )
    val offered = buttons.keys.filter { about.targets.containsKey(it.wire) }
    if (offered.isEmpty()) {
        binding.reviewContextBlock.isVisible = false
        return
    }
    buttons.forEach { (scope, button) -> button.isEnabled = scope in offered }
    if (ReviewContextNotes.Scope.SECTION !in offered) {
        val reason = context.getString(R.string.review_context_no_sections)
        binding.reviewContextSection.contentDescription = "${binding.reviewContextSection.text}, $reason"
        TooltipCompat.setTooltipText(binding.reviewContextSection, reason)
    }
    var shown = draft.contextScope?.takeIf { it in offered } ?: offered.first()
    var filling = false
    fun show(scope: ReviewContextNotes.Scope) {
        shown = scope
        draft.contextScope = scope
        binding.reviewContextLabel.text = about.targets[scope.wire]?.label.orEmpty()
        binding.reviewContextNote.hint = context.getString(when (scope) {
            ReviewContextNotes.Scope.SECTION -> R.string.review_context_section_hint
            ReviewContextNotes.Scope.PAGE -> R.string.review_context_page_hint
            ReviewContextNotes.Scope.DOCUMENT -> R.string.review_context_document_hint
        })
        filling = true
        binding.reviewContextNote.setText(draft.contextText(scope))
        filling = false
    }
    binding.reviewContextScopes.check(buttons.getValue(shown).id)
    show(shown)
    binding.reviewContextScopes.addOnButtonCheckedListener { _, id, checked ->
        if (!checked) return@addOnButtonCheckedListener
        buttons.entries.firstOrNull { it.value.id == id }?.key?.takeIf { it != shown }?.let(::show)
    }
    binding.reviewContextNote.doAfterTextChanged { text ->
        if (!filling) draft.setContextText(shown, text?.toString().orEmpty())
    }
}

/** One step's label under its tick: an equal share of the row, up to two lines, centred; a tap picks the step. */
internal fun reviewScaleStepLabel(context: Context, text: String, onClick: () -> Unit): TextView = TextView(context).apply {
    val density = resources.displayMetrics.density
    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    gravity = Gravity.CENTER_HORIZONTAL
    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
    this.text = text
    maxLines = 2
    ellipsize = android.text.TextUtils.TruncateAt.END
    val padding = (2 * density).toInt()
    setPadding(padding, padding, padding, (4 * density).toInt())
    background = androidx.core.content.ContextCompat.getDrawable(context,
        android.util.TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId)
    setOnClickListener { onClick() }
}

/**
 * The slider editor: only the steps, numbered from left (1) to right, each with arrows to move it and
 * a button to remove it, then Add step. Add step takes a typed name ([resolve] turns it into the tag it
 * names, or a new one) or one of [groupTags] (the group's tags that are not steps). Save passes the
 * steps in order and needs at least two. A removed step stays a tag of the group; no answer changes.
 */
internal fun showReviewScaleEditor(
    context: Context,
    title: String,
    initial: List<String>,
    groupTags: List<String>,
    label: (String) -> String,
    resolve: (String) -> String?,
    onSave: (List<String>) -> Unit,
): androidx.appcompat.app.AlertDialog {
    val density = context.resources.displayMetrics.density
    val steps = initial.toMutableList()
    val muted = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var dialog: androidx.appcompat.app.AlertDialog? = null
    fun updateSave() {
        dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = steps.size >= MIN_SCALE_STEPS
    }
    fun iconButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        val size = (40 * density).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
        val outValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        setBackgroundResource(outValue.resourceId)
        imageTintList = android.content.res.ColorStateList.valueOf(muted)
        contentDescription = description
        TooltipCompat.setTooltipText(this, description)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.3f
        setOnClickListener { onClick() }
    }
    lateinit var rebuild: () -> Unit
    rebuild = {
        list.removeAllViews()
        if (steps.isEmpty()) list.addView(TextView(context).apply {
            setText(R.string.review_tag_scale_edit_empty)
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(muted)
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        })
        steps.forEachIndexed { index, step ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (48 * density).toInt()
                addView(TextView(context).apply {
                    text = "${index + 1}"
                    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(muted)
                    layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(TextView(context).apply {
                    text = label(step)
                    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(iconButton(R.drawable.ic_small_arrow_up, context.getString(R.string.review_tag_scale_step_up, label(step)), index > 0) {
                    steps.add(index - 1, steps.removeAt(index))
                    rebuild()
                })
                addView(iconButton(R.drawable.ic_small_arrow_down, context.getString(R.string.review_tag_scale_step_down, label(step)),
                    index < steps.lastIndex) {
                    steps.add(index + 1, steps.removeAt(index))
                    rebuild()
                })
                addView(iconButton(R.drawable.ic_close, context.getString(R.string.review_tag_scale_remove_step, label(step)), true) {
                    steps.removeAt(index)
                    rebuild()
                })
            })
        }
        updateSave()
    }
    fun addStep(tag: String) {
        if (tag !in steps) steps += tag
        rebuild()
    }
    val addButton = com.google.android.material.button.MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        setText(R.string.review_tag_scale_add_step)
        setIconResource(R.drawable.ic_library_add)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener {
            showReviewAddStepDialog(context, groupTags.filter { it !in steps }, label, resolve, ::addStep)
        }
    }
    rebuild()
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((24 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), 0)
        addView(TextView(context).apply {
            setText(R.string.review_tag_scale_edit_message)
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(muted)
            setPadding(0, 0, (12 * density).toInt(), (8 * density).toInt())
        })
        addView(list)
        addView(addButton)
    }
    return MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(content) })
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.review_tag_groups_save) { _, _ -> onSave(steps.toList()) }
        .show()
        .also {
            dialog = it
            updateSave()
            lastReviewScaleEditor = java.lang.ref.WeakReference(it)
        }
}

/** The last slider editor shown, for tests. */
internal var lastReviewScaleEditor: java.lang.ref.WeakReference<androidx.appcompat.app.AlertDialog>? = null

/** Add step: a name to type, or one of [groupTags] to tap; [onStep] gets the step's tag. */
private fun showReviewAddStepDialog(
    context: Context,
    groupTags: List<String>,
    label: (String) -> String,
    resolve: (String) -> String?,
    onStep: (String) -> Unit,
) {
    val density = context.resources.displayMetrics.density
    val input = TextInputEditText(context).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = true
    }
    var dialog: androidx.appcompat.app.AlertDialog? = null
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * density).toInt()
        setPadding(padding, padding / 2, padding, 0)
        addView(TextInputLayout(context).apply {
            hint = context.getString(R.string.review_tag_scale_step_hint)
            addView(input)
        })
        if (groupTags.isNotEmpty()) {
            addView(TextView(context).apply {
                setText(R.string.review_tag_scale_add_step_existing)
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
            })
            addView(ChipGroup(context).apply {
                groupTags.forEach { tag ->
                    addView(Chip(context).apply {
                        text = label(tag)
                        this.tag = tag
                        setOnClickListener {
                            dialog?.dismiss()
                            onStep(tag)
                        }
                    })
                }
            })
        }
    }
    dialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.review_tag_scale_add_step)
        .setView(ScrollView(context).apply { addView(content) })
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.review_tags_add) { _, _ -> resolve(input.text.toString())?.let(onStep) }
        .show()
    val add = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
    add.isEnabled = false
    input.doAfterTextChanged { add.isEnabled = !it.isNullOrBlank() }
    // The keyboard's Done key adds the step like Add (it used to only close the keyboard).
    input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    input.setOnEditorActionListener { _, _, _ ->
        if (add.isEnabled) add.performClick()
        true
    }
    input.requestFocus()
    lastReviewAddStepDialog = java.lang.ref.WeakReference(dialog)
}

/**
 * "Section…": the group's section name to type (empty: no section), or one of the sections in use
 * ([existing]) to tap; No section clears it. [onSection] gets the trimmed name, or null for none.
 */
internal fun showReviewTagSectionDialog(
    context: Context,
    group: ReviewTagGroups.Group,
    existing: List<String>,
    onSection: (String?) -> Unit,
) {
    val density = context.resources.displayMetrics.density
    val initial = group.section.orEmpty()
    val input = TextInputEditText(context).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = true
        setText(initial)
        setSelection(initial.length)
    }
    val layout = TextInputLayout(context).apply {
        hint = context.getString(R.string.review_tag_section_hint)
        addView(input)
    }
    var dialog: androidx.appcompat.app.AlertDialog? = null
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * density).toInt()
        setPadding(padding, padding / 2, padding, 0)
        addView(TextView(context).apply {
            setText(R.string.review_tag_section_message)
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            setPadding(0, 0, 0, (12 * density).toInt())
        })
        addView(layout)
        val others = existing.filter { it != group.section }
        if (others.isNotEmpty()) {
            addView(TextView(context).apply {
                setText(R.string.review_tag_section_existing)
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
            })
            addView(ChipGroup(context).apply {
                others.forEach { section ->
                    addView(Chip(context).apply {
                        text = section
                        tag = section
                        setOnClickListener {
                            dialog?.dismiss()
                            onSection(section)
                        }
                    })
                }
            })
        }
    }
    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.review_tag_section_title, group.name))
        .setView(ScrollView(context).apply { addView(content) })
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.review_tag_groups_save, null)
    if (group.section != null) builder.setNeutralButton(R.string.review_tag_section_none) { _, _ -> onSection(null) }
    val shown = builder.show()
    dialog = shown
    input.doAfterTextChanged { layout.error = null }
    shown.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val section = ReviewTagGroups.normalizeSection(input.text?.toString())
        if (section != null && section.length > ReviewTagGroups.MAX_SECTION_LENGTH) {
            layout.error = context.getString(R.string.review_tag_section_too_long, ReviewTagGroups.MAX_SECTION_LENGTH)
            return@setOnClickListener
        }
        shown.dismiss()
        if (section != group.section) onSection(section)
    }
    lastReviewSectionDialog = java.lang.ref.WeakReference(shown)
}

/** The last group menu shown, for tests. */
internal var lastReviewTagGroupMenu: java.lang.ref.WeakReference<PopupMenu>? = null

/** The last Section… dialog shown, for tests. */
internal var lastReviewSectionDialog: java.lang.ref.WeakReference<androidx.appcompat.app.AlertDialog>? = null

/** The last Add step dialog shown, for tests. */
internal var lastReviewAddStepDialog: java.lang.ref.WeakReference<androidx.appcompat.app.AlertDialog>? = null

/** A small bubble with the whole tag over [anchor]; a tap anywhere else closes it. */
internal fun showFullReviewTag(anchor: View, text: String): PopupWindow {
    val context = anchor.context
    val density = context.resources.displayMetrics.density
    val elevation = 6 * density
    val view = TextView(context).apply {
        this.text = text
        setTextIsSelectable(false)
        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurface))
        val padding = (12 * density).toInt()
        setPadding(padding, padding * 2 / 3, padding, padding * 2 / 3)
        maxWidth = minOf((320 * density).toInt(), context.resources.displayMetrics.widthPixels - (32 * density).toInt())
        background = MaterialShapeDrawable.createWithElevationOverlay(context, elevation).apply {
            setCornerSize(8 * density)
        }
    }
    return PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        isOutsideTouchable = true
        this.elevation = elevation
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        view.setOnClickListener { dismiss() }
        // Above the chip when there is room, otherwise below it.
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val above = location[1] - view.measuredHeight - (4 * density).toInt()
        if (above > 0) showAsDropDown(anchor, 0, -anchor.height - view.measuredHeight - (4 * density).toInt())
        else showAsDropDown(anchor, 0, (4 * density).toInt())
    }
}

/** The tags "Move tags here…" offers for the group [targetId] (null: Other): the others' tags, by group. */
internal fun reviewMoveTagCandidates(
    sections: List<ReviewTagGroups.Section>,
    targetId: String?,
): List<Pair<ReviewTagGroups.Group?, List<String>>> =
    sections.filter { it.group?.id != targetId && it.tags.isNotEmpty() }.map { it.group to it.tags }

/**
 * The chips a group shows out of its [tags]: those [count] puts above 0 (a chosen tag always is)
 * and those already in [shown], which is the set of tags given a chip since the sheet opened and
 * takes the ones returned. Every tag stays in "Move tags here…" and can be picked with New tag.
 */
internal fun reviewTagsToShow(tags: List<String>, count: (String) -> Int, shown: MutableSet<String>): List<String> =
    tags.filter { it in shown || count(it) > 0 }.also { shown += it }

/**
 * The tag a [typed] New tag name stands for, ignoring case: an old name becomes the tag it was
 * merged into ([merges]: old name to final name), and a name or shown [label] of one of [tags]
 * (hidden ones included) is that tag. Otherwise the name is new.
 */
internal fun reviewTypedTag(
    typed: String,
    tags: List<String>,
    merges: Map<String, String>,
    label: (String) -> String = { it },
): String {
    val name = merges[typed] ?: merges.entries.firstOrNull { it.key.equals(typed, ignoreCase = true) }?.value ?: typed
    return tags.firstOrNull { it.equals(name, ignoreCase = true) }
        ?: tags.firstOrNull { label(it).equals(name, ignoreCase = true) }
        ?: name
}

/**
 * The sheet's rows: each group's tags most used first ([usage]: answers per tag), ties by shown
 * [label] ignoring case. Other stays as soon as there is a group, even empty, so its menu can take
 * tags out of every group; with no groups it is the only row.
 */
internal fun reviewTagSheetSections(
    sections: List<ReviewTagGroups.Section>,
    usage: Map<String, Int>,
    label: (String) -> String,
): List<ReviewTagGroups.Section> {
    val order = compareByDescending<String> { usage[it] ?: 0 }
        .thenBy(String.CASE_INSENSITIVE_ORDER, label)
        .thenBy { it }
    val rows = if (sections.any { it.group == null }) sections else sections + ReviewTagGroups.Section(null, emptyList())
    return rows.map { it.copy(tags = it.tags.sortedWith(order)) }
}

/**
 * The count a chip shows: the cards that would have [tag] once this answer is saved. [recorded]
 * counts the cards that have it now, this card included when its [current] tags have it; the card
 * then counts when the tag is [chosen]. Choosing a tag the card has (a review starts with them all
 * chosen) or leaving out one it lacks changes nothing; choosing a new one shows one more, leaving
 * out one it has one fewer. Once recorded, the store's count already holds the card's new tags.
 */
internal fun reviewTagShownCount(recorded: Map<String, Int>, current: Set<String>, chosen: Set<String>, tag: String): Int =
    ((recorded[tag] ?: 0) - (if (tag in current) 1 else 0)).coerceAtLeast(0) + if (tag in chosen) 1 else 0

/** A tag with its card count, in full: "Correct (42)". */
internal fun reviewTagCountText(label: String, count: Int): String = "$label ($count)"

/**
 * A chip's text: the whole label and its count, the chip as wide as that text; only a label that
 * does not [fit] a whole row is shortened, never the count: "A long tag na… (3)".
 */
internal fun reviewTagChipText(label: String, count: Int, fits: (String) -> Boolean = { true }): String {
    val suffix = " ($count)"
    if (fits(label + suffix)) return label + suffix
    val stem = label.trimEnd()
    for (length in stem.length - 1 downTo 1) {
        val kept = stem.take(length).trimEnd()
        if (kept.isEmpty()) continue
        val text = "$kept…$suffix"
        if (fits(text)) return text
    }
    return "…$suffix"
}

/**
 * Keeps each tag chip's count visible: once [chips] knows its width, a chip too wide for it gets a
 * shorter label instead of the chip cutting off the end of its text (where the count is).
 */
private fun fitReviewTagChips(chips: ChipGroup, label: (String) -> String, count: (String) -> Int) {
    chips.addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
        val width = right - left - view.paddingLeft - view.paddingRight
        if (width <= 0 || right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
        view.post {
            for (index in 0 until chips.childCount) fitReviewTagChip(chips.getChildAt(index) as? Chip ?: continue, width, label, count)
        }
    }
}

/** Sets a tag chip's text with its current count, shortening the label to [width] once it is known (> 0). */
private fun fitReviewTagChip(chip: Chip, width: Int, label: (String) -> String, count: (String) -> Int) {
    val tag = chip.tag as? String ?: return
    // Room for the check mark too, which appears when the chip is chosen.
    val extra = chip.chipStartPadding + chip.iconStartPadding + chip.chipIconSize + chip.iconEndPadding +
        chip.textStartPadding + chip.textEndPadding + chip.chipEndPadding + 2 * chip.resources.displayMetrics.density
    val text = reviewTagChipText(label(tag), count(tag)) { width <= 0 || chip.paint.measureText(it) + extra <= width }
    if (chip.text.toString() != text) chip.text = text
}

/** Asks for a group name; the dialog stays open, with the reason, while the name cannot be used. */
internal fun showReviewTagGroupNameDialog(
    context: Context,
    title: Int,
    action: Int,
    initial: String,
    groupId: String?,
    onName: (String) -> Unit,
) {
    val input = TextInputEditText(context).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = true
        setText(initial)
        setSelection(initial.length)
    }
    val layout = TextInputLayout(context).apply {
        hint = context.getString(R.string.review_tag_groups_new_hint)
        addView(input)
    }
    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setView(android.widget.FrameLayout(context).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(layout)
        })
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(action, null)
        .show()
    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { layout.error = null }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    })
    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = ReviewTagGroups.normalizeName(input.text.toString())
        val problem = ReviewTagGroups.nameProblem(ReviewTagGroups.groups(), name, groupId)
        if (problem != null) {
            layout.error = reviewTagGroupProblem(context, problem)
            return@setOnClickListener
        }
        dialog.dismiss()
        onName(name)
    }
}

internal fun reviewTagGroupProblem(context: Context, problem: ReviewTagGroups.Problem): String = when (problem) {
    ReviewTagGroups.Problem.NAME_EMPTY -> context.getString(R.string.review_tag_groups_name_empty)
    ReviewTagGroups.Problem.NAME_TOO_LONG ->
        context.getString(R.string.review_tag_groups_name_too_long, ReviewTagGroups.MAX_NAME_LENGTH)
    ReviewTagGroups.Problem.NAME_RESERVED -> context.getString(R.string.review_tag_groups_name_reserved)
    ReviewTagGroups.Problem.NAME_TAKEN -> context.getString(R.string.review_tag_groups_name_taken)
    ReviewTagGroups.Problem.TOO_MANY_GROUPS -> context.getString(R.string.review_tag_groups_too_many, ReviewTagGroups.MAX_GROUPS)
    ReviewTagGroups.Problem.TAG_TOO_LONG ->
        context.getString(R.string.review_tag_groups_tag_too_long, ReviewTagGroups.MAX_TAG_LENGTH)
    ReviewTagGroups.Problem.TOO_MANY_TAGS -> context.getString(R.string.review_tag_groups_too_many_tags, ReviewTagGroups.MAX_TAGS)
    ReviewTagGroups.Problem.SECTION_TOO_LONG ->
        context.getString(R.string.review_tag_section_too_long, ReviewTagGroups.MAX_SECTION_LENGTH)
    ReviewTagGroups.Problem.NO_SUCH_GROUP, ReviewTagGroups.Problem.INVALID -> context.getString(R.string.review_tag_groups_failed)
}

/**
 * "Ask" in a group's menu (review in two steps): a submenu with one radio item per phase, the
 * group's own checked (After the answer for a phase this client does not know).
 */
internal fun addReviewTagPhaseMenu(menu: Menu, group: ReviewTagGroups.Group, order: Int) {
    val ask = menu.addSubMenu(Menu.NONE, PHASE_MENU_ITEM, order, R.string.review_tag_phase_menu)
    ask.add(PHASE_GROUP, PHASE_AFTER_ITEM, 0, R.string.review_tag_phase_after)
    ask.add(PHASE_GROUP, PHASE_BEFORE_ITEM, 1, R.string.review_tag_phase_before)
    ask.add(PHASE_GROUP, PHASE_BEFORE_AND_AFTER_ITEM, 2, R.string.review_tag_phase_before_and_after)
    ask.setGroupCheckable(PHASE_GROUP, true, true)
    ask.findItem(when (group.phase) {
        ReviewTagGroups.PHASE_BEFORE -> PHASE_BEFORE_ITEM
        ReviewTagGroups.PHASE_BEFORE_AND_AFTER -> PHASE_BEFORE_AND_AFTER_ITEM
        else -> PHASE_AFTER_ITEM
    })?.isChecked = true
}

/** The phase an "Ask" item ([REVIEW_TAG_PHASE_ITEMS]) stands for; null is after the answer. */
internal fun reviewTagPhaseOf(itemId: Int): String? = when (itemId) {
    PHASE_BEFORE_ITEM -> ReviewTagGroups.PHASE_BEFORE
    PHASE_BEFORE_AND_AFTER_ITEM -> ReviewTagGroups.PHASE_BEFORE_AND_AFTER
    else -> null
}

private const val PHASE_GROUP = 1
private const val PHASE_MENU_ITEM = 20
private const val PHASE_AFTER_ITEM = 21
private const val PHASE_BEFORE_ITEM = 22
private const val PHASE_BEFORE_AND_AFTER_ITEM = 23
internal val REVIEW_TAG_PHASE_ITEMS = setOf(PHASE_AFTER_ITEM, PHASE_BEFORE_ITEM, PHASE_BEFORE_AND_AFTER_ITEM)
private const val RENAME_ITEM = 1
private const val UP_ITEM = 2
private const val DOWN_ITEM = 3
private const val DELETE_ITEM = 4
private const val SCALE_STEPS_ITEM = 8
private const val SECTION_ITEM = 9
private const val MIN_SCALE_STEPS = 2

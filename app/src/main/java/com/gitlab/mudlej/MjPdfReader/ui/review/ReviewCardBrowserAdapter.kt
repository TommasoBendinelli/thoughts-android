// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.databinding.ItemReviewBrowserCardBinding
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReviewCardBrowserAdapter(
    private val dueText: (ReviewBrowserCard) -> String,
    private val onCardClicked: (ReviewBrowserCard) -> Unit,
    private val onCardLongClicked: (ReviewBrowserCard) -> Unit = onCardClicked,
) : ListAdapter<ReviewBrowserCard, ReviewCardBrowserAdapter.CardViewHolder>(Comparator) {

    private var selectedIds: Set<String> = emptySet()

    fun setSelectedIds(ids: Set<String>) {
        val changed = (selectedIds union ids) - (selectedIds intersect ids)
        selectedIds = ids.toSet()
        currentList.forEachIndexed { index, item ->
            if (item.card.id in changed) notifyItemChanged(index)
        }
    }

    private var visibleColumns = ReviewCardBrowserColumn.entries.filter { it in ReviewCardBrowserColumn.defaults }

    fun setVisibleColumns(columns: List<ReviewCardBrowserColumn>) {
        if (visibleColumns == columns) return
        visibleColumns = columns.toList()
        notifyItemRangeChanged(0, itemCount)
    }

    /** Column widths in pixels; with [stretchColumns], they share the row in proportion instead. */
    private var columnWidths: Map<ReviewCardBrowserColumn, Int> = emptyMap()
    private var stretchColumns = true

    fun setColumnWidths(widths: Map<ReviewCardBrowserColumn, Int>, stretch: Boolean) {
        if (columnWidths == widths && stretchColumns == stretch) return
        columnWidths = widths.toMap()
        stretchColumns = stretch
        notifyItemRangeChanged(0, itemCount)
    }

    /** The text a column shows for [item]; the browser also measures it to size the column. */
    fun cellText(context: android.content.Context, item: ReviewBrowserCard, column: ReviewCardBrowserColumn): String = when (column) {
        ReviewCardBrowserColumn.QUESTION -> item.card.question
        ReviewCardBrowserColumn.DUE -> dueText(item)
        ReviewCardBrowserColumn.DECK -> item.projectDisplayName.ifBlank { "—" }
        ReviewCardBrowserColumn.DOCUMENT -> item.documentDisplayName
        ReviewCardBrowserColumn.PAGE -> item.card.pagesLabel
        ReviewCardBrowserColumn.SOURCE -> item.detailSourceFiles()
            .map { it.substringAfterLast('/') }
            .distinct()
            .joinToString("\n")
            .ifBlank { "—" }
        ReviewCardBrowserColumn.STATE -> item.stateLabel(context)
        ReviewCardBrowserColumn.REPETITIONS -> item.cardStatusLabel()
        ReviewCardBrowserColumn.CREATED -> item.createdAt?.atZone(ZoneId.systemDefault())?.format(CREATION_FORMATTER) ?: "—"
        ReviewCardBrowserColumn.LAST_REVIEWED -> item.lastReviewedAt?.atZone(ZoneId.systemDefault())?.format(CREATION_FORMATTER) ?: "—"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder = CardViewHolder(
        ItemReviewBrowserCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        ),
    )

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CardViewHolder(
        private val binding: ItemReviewBrowserCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ReviewBrowserCard) {
            val cells = mapOf(
                ReviewCardBrowserColumn.QUESTION to binding.reviewCardQuestion,
                ReviewCardBrowserColumn.DUE to binding.reviewCardDue,
                ReviewCardBrowserColumn.DECK to binding.reviewCardDeck,
                ReviewCardBrowserColumn.DOCUMENT to binding.reviewCardDocument,
                ReviewCardBrowserColumn.PAGE to binding.reviewCardPage,
                ReviewCardBrowserColumn.SOURCE to binding.reviewCardSource,
                ReviewCardBrowserColumn.STATE to binding.reviewCardState,
                ReviewCardBrowserColumn.REPETITIONS to binding.reviewCardRepetitions,
                ReviewCardBrowserColumn.CREATED to binding.reviewCardCreated,
                ReviewCardBrowserColumn.LAST_REVIEWED to binding.reviewCardLastReviewed,
            )
            val row = binding.reviewCardQuestion.parent as LinearLayout
            visibleColumns.forEach { column ->
                val cell = cells.getValue(column)
                row.removeView(cell)
                row.addView(cell)
            }
            cells.forEach { (column, view) ->
                view.visibility = if (column in visibleColumns) View.VISIBLE else View.GONE
                applyColumnWidth(view, columnWidths[column], stretchColumns)
            }
            val context = binding.root.context
            cells.forEach { (column, view) -> view.text = cellText(context, item, column) }
            val state = binding.reviewCardState.text.toString()
            val project = binding.reviewCardDeck.text.toString()
            val due = binding.reviewCardDue.text.toString()
            val created = binding.reviewCardCreated.text.toString()
            binding.root.contentDescription = context.getString(
                R.string.review_card_browser_row_description,
                item.card.question,
                due,
                project,
                state,
                item.card.pagesLabel,
                item.documentDisplayName,
                item.cardStatusLabel(),
                if (item.pendingSync) context.getString(R.string.review_card_browser_pending) else "",
            )
            if (ReviewCardBrowserColumn.CREATED in visibleColumns) {
                binding.root.contentDescription = "${binding.root.contentDescription}, " +
                    "${context.getString(R.string.review_card_browser_created)}: $created"
            }
            binding.root.isSelected = item.card.id in selectedIds
            binding.root.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                binding.root,
                if (binding.root.isSelected) com.google.android.material.R.attr.colorSecondaryContainer
                else com.google.android.material.R.attr.colorSurface,
            ))
            androidx.core.view.ViewCompat.setStateDescription(binding.root,
                context.getString(if (binding.root.isSelected) R.string.review_card_browser_selected
                    else R.string.review_card_browser_not_selected))
            binding.root.setOnClickListener { onCardClicked(item) }
            binding.root.setOnLongClickListener { onCardLongClicked(item); true }
        }
    }

    companion object {
        /** Sizes a header or row cell: a fixed [width] in pixels, or with [stretch] its share of the row. */
        fun applyColumnWidth(view: View, width: Int?, stretch: Boolean) {
            val params = view.layoutParams as LinearLayout.LayoutParams
            val (newWidth, weight) = when {
                width == null -> 0 to 1f
                stretch -> 0 to width.toFloat()
                else -> width to 0f
            }
            if (params.width == newWidth && params.weight == weight) return
            params.width = newWidth
            params.weight = weight
            view.layoutParams = params
        }

        private val CREATION_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    private object Comparator : DiffUtil.ItemCallback<ReviewBrowserCard>() {
        override fun areItemsTheSame(oldItem: ReviewBrowserCard, newItem: ReviewBrowserCard): Boolean =
            oldItem.card.id == newItem.card.id

        override fun areContentsTheSame(oldItem: ReviewBrowserCard, newItem: ReviewBrowserCard): Boolean =
            oldItem == newItem
    }
}

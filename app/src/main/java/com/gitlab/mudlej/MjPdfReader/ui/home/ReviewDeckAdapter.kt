// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewDeckSummary
import com.gitlab.mudlej.MjPdfReader.databinding.ItemReviewDeckBinding
import java.text.NumberFormat

class ReviewDeckAdapter(
    private val onDeckClicked: (ReviewDeckSummary) -> Unit,
) : ListAdapter<ReviewDeckSummary, ReviewDeckAdapter.DeckViewHolder>(DeckComparator()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeckViewHolder {
        val binding = ItemReviewDeckBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return DeckViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeckViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeckViewHolder(
        private val binding: ItemReviewDeckBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(deck: ReviewDeckSummary) {
            binding.reviewDeckName.text = deck.pdfStem
            binding.reviewDeckDueCounts.text = binding.root.context.getString(
                R.string.review_counts,
                deck.counts.newCount,
                deck.counts.learningCount + deck.counts.reviewCount,
            )
            binding.reviewDeckDueTotal.text = NumberFormat.getIntegerInstance().format(deck.counts.total)
            binding.reviewDeckCard.setOnClickListener { onDeckClicked(deck) }
        }
    }

    private class DeckComparator : DiffUtil.ItemCallback<ReviewDeckSummary>() {
        override fun areItemsTheSame(oldItem: ReviewDeckSummary, newItem: ReviewDeckSummary) =
            oldItem.pdfStem == newItem.pdfStem

        override fun areContentsTheSame(oldItem: ReviewDeckSummary, newItem: ReviewDeckSummary) =
            oldItem == newItem
    }
}

package com.gitlab.mudlej.MjPdfReader.ui.tableofcontents

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.TableOfContentsRowItemBinding
import com.google.android.material.color.MaterialColors

class TableOfContentsViewHolder(
    private val binding: TableOfContentsRowItemBinding,
    private val tableOfContentsAdapter: TableOfContentsAdapter,
) : RecyclerView.ViewHolder(binding.root) {
    private val defaultCardColor = binding.root.cardBackgroundColor
    private val defaultMetadataColor = binding.bookmarkPageNumber.textColors

    fun bind(row: TableOfContentsRow) {
        val entry = row.entry
        val density = itemView.resources.displayMetrics.density
        val params = binding.root.layoutParams as ViewGroup.MarginLayoutParams
        // Keep deeply nested headings readable at large system font sizes.
        params.marginStart = ((8 + entry.level.coerceIn(0, 4) * 12) * density).toInt()
        binding.root.layoutParams = params
        binding.bookmarkText.text = entry.title
        binding.bookmarkPageNumber.text = itemView.context.getString(
            if (row.current) R.string.contents_current_page else R.string.bookmark_page_label,
            entry.pageIdx + 1,
        )
        binding.root.setCardBackgroundColor(if (row.current) MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorSecondaryContainer,
        ) else defaultCardColor.defaultColor)
        if (row.current) {
            binding.bookmarkPageNumber.setTextColor(MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorOnSecondaryContainer,
            ))
        } else {
            binding.bookmarkPageNumber.setTextColor(defaultMetadataColor)
        }
        binding.root.setOnClickListener { tableOfContentsAdapter.bookmarkFunctions.onEntryClicked(entry) }
        binding.toggleButton.apply {
            visibility = if (row.expandable) View.VISIBLE else View.GONE
            setImageResource(if (row.expanded) R.drawable.ic_small_arrow_down else R.drawable.ic_small_arrow_right)
            contentDescription = context.getString(
                if (row.expanded) R.string.contents_collapse_heading else R.string.contents_expand_heading,
                entry.title,
            )
            isEnabled = row.expandable && !tableOfContentsAdapter.isFiltering()
            setOnClickListener { tableOfContentsAdapter.onToggleClicked(entry) }
        }
    }
}

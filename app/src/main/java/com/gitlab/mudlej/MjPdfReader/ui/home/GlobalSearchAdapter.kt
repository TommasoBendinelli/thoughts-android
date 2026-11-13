// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchQuery

/** One row of the global search list: a section header, a result or a status line. */
internal sealed class GlobalSearchRow {
    abstract val key: String

    data class Header(val text: String) : GlobalSearchRow() {
        override val key get() = "header:$text"
    }

    data class Status(val text: String) : GlobalSearchRow() {
        override val key get() = "status"
    }

    data class Result(
        override val key: String,
        val title: String,
        val subtitle: String,
        val snippet: GlobalSearchQuery.Snippet?,
        val titleMatches: List<IntRange> = emptyList(),
        val open: () -> Unit,
    ) : GlobalSearchRow() {
        override fun equals(other: Any?): Boolean = other is Result && other.key == key &&
            other.title == title && other.subtitle == subtitle && other.snippet == snippet
        override fun hashCode(): Int = key.hashCode()
    }
}

internal class GlobalSearchAdapter : ListAdapter<GlobalSearchRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is GlobalSearchRow.Result -> RESULT
        is GlobalSearchRow.Header, is GlobalSearchRow.Status -> HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = if (viewType == RESULT) R.layout.item_global_search_result else R.layout.item_global_search_header
        return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val view = holder.itemView
        when (val row = getItem(position)) {
            is GlobalSearchRow.Header -> (view as TextView).apply {
                text = row.text
                alpha = 1f
            }
            is GlobalSearchRow.Status -> (view as TextView).apply {
                text = row.text
                alpha = STATUS_ALPHA
            }
            is GlobalSearchRow.Result -> {
                view.findViewById<TextView>(R.id.globalSearchTitle).text = bold(row.title, row.titleMatches)
                view.findViewById<TextView>(R.id.globalSearchSubtitle).apply {
                    text = row.subtitle
                    visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
                }
                view.findViewById<TextView>(R.id.globalSearchSnippet).apply {
                    val snippet = row.snippet
                    visibility = if (snippet == null || snippet.text.isBlank()) View.GONE else View.VISIBLE
                    text = snippet?.let { bold(it.text, it.matches) }
                }
                view.setOnClickListener { row.open() }
            }
        }
    }

    private fun bold(text: String, ranges: List<IntRange>): CharSequence {
        if (ranges.isEmpty()) return text
        return SpannableString(text).apply {
            ranges.filter { it.first >= 0 && it.last < text.length }.forEach {
                setSpan(StyleSpan(Typeface.BOLD), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    companion object {
        private const val RESULT = 0
        private const val HEADER = 1
        private const val STATUS_ALPHA = 0.7f

        private val DIFF = object : DiffUtil.ItemCallback<GlobalSearchRow>() {
            override fun areItemsTheSame(oldItem: GlobalSearchRow, newItem: GlobalSearchRow) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: GlobalSearchRow, newItem: GlobalSearchRow) = oldItem == newItem
        }
    }
}

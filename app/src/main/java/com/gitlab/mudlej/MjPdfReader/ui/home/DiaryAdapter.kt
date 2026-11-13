// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.DiaryEntry
import com.gitlab.mudlej.MjPdfReader.data.annotation.DiaryRow
import com.gitlab.mudlej.MjPdfReader.databinding.ItemDiaryDayBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemDiaryEntryBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemOverviewEmptyBinding

internal sealed interface DiaryListRow {
    data class Row(val value: DiaryRow) : DiaryListRow
    data class Empty(val searching: Boolean, val loading: Boolean) : DiaryListRow
}

internal class DiaryAdapter(
    private val onClick: (DiaryEntry, View) -> Unit,
    private val onLongClick: (DiaryEntry, View) -> Unit,
) : ListAdapter<DiaryListRow, RecyclerView.ViewHolder>(ROW_DIFF) {

    fun submitRows(rows: List<DiaryRow>, searching: Boolean, loading: Boolean, committed: Runnable? = null) {
        submitList(
            if (rows.isEmpty()) listOf(DiaryListRow.Empty(searching, loading)) else rows.map(DiaryListRow::Row),
            committed,
        )
    }

    override fun getItemViewType(position: Int): Int = when (val row = getItem(position)) {
        is DiaryListRow.Empty -> EMPTY
        is DiaryListRow.Row -> if (row.value is DiaryRow.Day) DAY else ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            DAY -> DayHolder(ItemDiaryDayBinding.inflate(inflater, parent, false))
            ENTRY -> EntryHolder(ItemDiaryEntryBinding.inflate(inflater, parent, false))
            else -> EmptyHolder(ItemOverviewEmptyBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is DiaryListRow.Empty -> (holder as EmptyHolder).bind(row)
            is DiaryListRow.Row -> when (val value = row.value) {
                is DiaryRow.Day -> (holder as DayHolder).bind(value)
                is DiaryRow.Entry -> (holder as EntryHolder).bind(value)
            }
        }
    }

    private class DayHolder(private val binding: ItemDiaryDayBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: DiaryRow.Day) {
            binding.diaryDayLabel.text = row.label
        }
    }

    private inner class EntryHolder(private val binding: ItemDiaryEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: DiaryRow.Entry) {
            val context = binding.root.context
            binding.diaryEntryTime.text = row.time
            binding.diaryEntryText.text = row.entry.text
            binding.diaryEntryDate.text = row.dateLabel.orEmpty()
            binding.diaryEntryDate.visibility = if (row.dateLabel == null) View.GONE else View.VISIBLE
            binding.diaryEntryCard.contentDescription = context.getString(
                R.string.diary_entry_accessibility,
                row.dateLabel.orEmpty(),
                row.time,
                row.entry.text,
            ).removePrefix(", ")
            binding.diaryEntryCard.setOnClickListener { onClick(row.entry, it) }
            binding.diaryEntryCard.setOnLongClickListener {
                onLongClick(row.entry, it)
                true
            }
        }
    }

    private class EmptyHolder(private val binding: ItemOverviewEmptyBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: DiaryListRow.Empty) {
            when {
                row.loading -> {
                    binding.overviewEmptyTitle.setText(R.string.overview_loading_title)
                    binding.overviewEmptyMessage.text = ""
                }
                row.searching -> {
                    binding.overviewEmptyTitle.setText(R.string.overview_no_matches)
                    binding.overviewEmptyMessage.text = ""
                }
                else -> {
                    binding.overviewEmptyTitle.setText(R.string.diary_empty_title)
                    binding.overviewEmptyMessage.setText(R.string.diary_empty_message)
                }
            }
        }
    }

    companion object {
        private const val DAY = 0
        private const val ENTRY = 1
        private const val EMPTY = 2
        private val ROW_DIFF = object : DiffUtil.ItemCallback<DiaryListRow>() {
            override fun areItemsTheSame(oldItem: DiaryListRow, newItem: DiaryListRow): Boolean {
                if (oldItem is DiaryListRow.Empty && newItem is DiaryListRow.Empty) return true
                val old = (oldItem as? DiaryListRow.Row)?.value ?: return false
                val new = (newItem as? DiaryListRow.Row)?.value ?: return false
                return when {
                    old is DiaryRow.Day && new is DiaryRow.Day -> old.date == new.date
                    old is DiaryRow.Entry && new is DiaryRow.Entry -> old.entry.id == new.entry.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: DiaryListRow, newItem: DiaryListRow): Boolean = oldItem == newItem
        }
    }
}

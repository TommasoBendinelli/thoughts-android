// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.ui.notes.calendarLabel
import com.gitlab.mudlej.MjPdfReader.ui.notes.visibleFromLabel
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.databinding.ItemOverviewCommentBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemOverviewEmptyBinding
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

internal sealed interface OverviewCommentRow {
    data class Comment(
        val value: OverviewComment,
        val selected: Boolean,
        val decisionStatus: String? = null,
    ) : OverviewCommentRow

    data class Empty(val hasAnyComments: Boolean, val loading: Boolean = false) : OverviewCommentRow
}

internal fun overviewCommentRows(
    comments: List<OverviewComment>,
    selectedKeys: Set<String>,
    hasAnyComments: Boolean,
    loading: Boolean = false,
    decisionStatus: (OverviewComment) -> String? = { null },
): List<OverviewCommentRow> = if (comments.isEmpty()) {
    listOf(OverviewCommentRow.Empty(hasAnyComments, loading))
} else {
    comments.map { comment ->
        OverviewCommentRow.Comment(
            comment,
            overviewCommentKey(comment) in selectedKeys,
            decisionStatus(comment),
        )
    }
}

internal class OverviewCommentsAdapter(
    private val onClick: (OverviewComment) -> Unit,
    private val onLongClick: (OverviewComment) -> Unit,
    private val documentDisplayName: (OverviewComment) -> String = { it.documentStem },
    private val decisionStatus: (OverviewComment) -> String? = { null },
    private val notesOnly: Boolean = false,
    private val commentMetadata: ((OverviewComment) -> String)? = null,
) : ListAdapter<OverviewCommentRow, RecyclerView.ViewHolder>(ROW_DIFF) {

    fun submitList(
        value: List<OverviewComment>,
        selected: Set<String> = emptySet(),
        hasAny: Boolean = value.isNotEmpty(),
        loading: Boolean = false,
    ) {
        submitList(overviewCommentRows(value, selected.toSet(), hasAny, loading = loading, decisionStatus = decisionStatus))
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is OverviewCommentRow.Comment -> COMMENT
        is OverviewCommentRow.Empty -> EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == EMPTY) {
            EmptyHolder(ItemOverviewEmptyBinding.inflate(inflater, parent, false))
        } else {
            CommentHolder(ItemOverviewCommentBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is OverviewCommentRow.Comment -> (holder as CommentHolder).bind(row)
            is OverviewCommentRow.Empty -> (holder as EmptyHolder).bind(row.hasAnyComments, row.loading)
        }
    }

    private inner class CommentHolder(
        private val binding: ItemOverviewCommentBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: OverviewCommentRow.Comment) {
            val comment = row.value
            val context = binding.root.context
            val rawText = comment.contents.trim().ifBlank {
                context.getString(R.string.overview_no_comment_text)
            }
            binding.commentText.text = if (comment.isReply) {
                context.getString(R.string.overview_reply_prefix, rawText)
            } else {
                rawText
            }
            val documentText = commentMetadata?.invoke(comment) ?: if (comment.isNote) {
                documentDisplayName(comment)
            } else if (comment.logicalLocations.isNotEmpty()) {
                comment.logicalLocations.distinctBy { it.pdfFilename to it.page }.joinToString("  •  ") { placement ->
                    context.getString(
                        R.string.overview_comment_document_page,
                        placement.pdfFilename.removeSuffix(".pdf"),
                        placement.page,
                    )
                }
            } else if (comment.pageIndex >= 0) {
                context.getString(
                    R.string.overview_comment_document_page,
                    documentDisplayName(comment),
                    comment.pageIndex + 1,
                )
            } else {
                context.getString(R.string.overview_source_only)
            }
            val metadataText = metadata(comment)
            val typeText = context.getString(
                when {
                    comment.isNote -> R.string.note_type
                    comment.subtype.equals("AgentRemove", ignoreCase = true) -> R.string.agent_proposal_dialog_title
                    comment.isReply -> R.string.overview_comment_type_reply
                    comment.subtype.equals("StrikeOut", ignoreCase = true) -> R.string.annotation_strikethrough
                    comment.subtype.equals("Highlight", ignoreCase = true) -> R.string.overview_comment_type_highlight
                    comment.subtype.equals("Text", ignoreCase = true) -> R.string.overview_comment_type_standalone
                    else -> R.string.overview_comment_type_unknown
                },
            )
            binding.commentType.text = typeText
            binding.commentType.visibility = if (notesOnly) View.GONE else View.VISIBLE
            binding.commentDocument.text = documentText
            binding.commentDocument.visibility = if (documentText.isBlank()) View.GONE else View.VISIBLE
            binding.commentMetadata.maxLines = if (comment.isNote) 4 else 2
            binding.commentMetadata.text = listOfNotNull(metadataText, row.decisionStatus,
                comment.noteDetails?.calendarLabel()?.takeIf(String::isNotBlank),
                comment.noteDetails?.takeIf { it.hiddenOn(java.time.LocalDate.now()) }
                    ?.let { context.getString(R.string.todo_hidden_until, it.visibleFromLabel()) },
                comment.noteDetails?.checkWhere?.takeIf(String::isNotBlank)?.let { context.getString(R.string.note_check_where_summary, it) })
                .joinToString("\n")
            binding.commentCard.strokeColor = MaterialColors.getColor(
                binding.commentCard,
                com.google.android.material.R.attr.colorOutlineVariant,
            )
            binding.commentCard.strokeWidth = (
                binding.root.resources.displayMetrics.density
            ).roundToInt()
            binding.commentCard.contentDescription = context.getString(
                R.string.overview_comment_accessibility,
                binding.commentText.text,
                documentText,
                if (notesOnly) metadataText else "$typeText · $metadataText",
            )
            binding.commentCard.isChecked = row.selected
            binding.commentCard.setOnClickListener { onClick(comment) }
            binding.commentCard.setOnLongClickListener {
                onLongClick(comment)
                true
            }
        }

        private fun metadata(comment: OverviewComment): String {
            val context = binding.root.context
            val author = comment.author.ifBlank {
                context.getString(R.string.overview_unknown_author)
            }
            val activity = comment.activityMillis.takeIf { it > 0 }?.let {
                DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            }
            return listOfNotNull(author, activity).joinToString(" · ")
        }
    }

    private inner class EmptyHolder(
        private val binding: ItemOverviewEmptyBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(hasAny: Boolean, loading: Boolean = false) {
            // Before the first catalogue arrives, "No notes yet" would read as lost notes.
            if (loading) {
                binding.overviewEmptyTitle.setText(R.string.overview_loading_title)
                binding.overviewEmptyMessage.text = ""
                return
            }
            binding.overviewEmptyTitle.setText(
                if (hasAny) R.string.overview_no_matches
                else if (notesOnly) R.string.notes_empty_title else R.string.overview_empty_title
            )
            binding.overviewEmptyMessage.text = if (hasAny) {
                ""
            } else {
                binding.root.context.getString(if (notesOnly) R.string.notes_empty_message else R.string.overview_empty_message)
            }
        }
    }

    companion object {
        private const val COMMENT = 0
        private const val EMPTY = 1
        private val ROW_DIFF = object : DiffUtil.ItemCallback<OverviewCommentRow>() {
            override fun areItemsTheSame(
                oldItem: OverviewCommentRow,
                newItem: OverviewCommentRow,
            ): Boolean = when {
                oldItem is OverviewCommentRow.Comment && newItem is OverviewCommentRow.Comment ->
                    overviewCommentKey(oldItem.value) == overviewCommentKey(newItem.value)
                oldItem is OverviewCommentRow.Empty && newItem is OverviewCommentRow.Empty -> true
                else -> false
            }

            override fun areContentsTheSame(
                oldItem: OverviewCommentRow,
                newItem: OverviewCommentRow,
            ): Boolean = oldItem == newItem
        }
    }
}

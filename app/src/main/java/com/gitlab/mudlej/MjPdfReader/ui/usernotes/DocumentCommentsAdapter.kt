// Written for Thoughts. License is GPLv3.
package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.RowDocumentCommentBinding
import com.google.android.material.color.MaterialColors

internal class DocumentCommentsAdapter(
    private val onClick: (DocumentCommentItem) -> Unit,
) : ListAdapter<DocumentCommentItem, DocumentCommentsAdapter.CommentViewHolder>(CommentComparator) {
    var selectedId: String? = null
        set(value) {
            if (field == value) return
            val previous = currentList.indexOfFirst { it.comment.id == field }
            field = value
            val next = currentList.indexOfFirst { it.comment.id == value }
            if (previous >= 0) notifyItemChanged(previous)
            if (next >= 0) notifyItemChanged(next)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CommentViewHolder(
        RowDocumentCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) = holder.bind(getItem(position))

    inner class CommentViewHolder(private val binding: RowDocumentCommentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DocumentCommentItem) {
            val context = binding.root.context
            val comment = item.comment
            val text = comment.contents.trim().ifBlank { context.getString(R.string.overview_no_comment_text) }
            binding.commentText.text = if (comment.isReply) context.getString(R.string.overview_reply_prefix, text) else text
            val page = commentDisplayPage(comment.pageIndex)
            val location = page?.let { context.getString(R.string.bookmark_page_label, it) }
                ?: context.getString(R.string.comments_location_unavailable)
            val type = context.getString(if (item.isAgentProposal) R.string.comments_row_agent else R.string.comments_row_user)
            binding.commentMetadata.text = "$location · $type"
            binding.root.setCardBackgroundColor(MaterialColors.getColor(binding.root,
                if (comment.id == selectedId) com.google.android.material.R.attr.colorSecondaryContainer
                else com.google.android.material.R.attr.colorSurface))
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    private object CommentComparator : DiffUtil.ItemCallback<DocumentCommentItem>() {
        override fun areItemsTheSame(oldItem: DocumentCommentItem, newItem: DocumentCommentItem) = oldItem.comment.id == newItem.comment.id
        override fun areContentsTheSame(oldItem: DocumentCommentItem, newItem: DocumentCommentItem) = oldItem == newItem
    }
}

internal fun commentDisplayPage(pageIndex: Int): Int? = pageIndex.takeIf { it >= 0 && it < Int.MAX_VALUE }?.plus(1)

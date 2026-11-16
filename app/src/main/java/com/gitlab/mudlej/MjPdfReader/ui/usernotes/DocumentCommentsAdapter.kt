// Written for Thoughts. License is GPLv3.
package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.RowDocumentCommentBinding
import com.google.android.material.color.MaterialColors

internal class DocumentCommentsAdapter(
    private val onClick: (DocumentCommentItem) -> Unit,
    /** A long press; true when it was handled (an agent edit starts selection mode). */
    private val onLongClick: (DocumentCommentItem) -> Boolean = { false },
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

    /** Selection mode: the selected proposal IDs ([proposalKey]); null while it is off. */
    var selection: Set<String>? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** Decisions saved on this phone and not yet applied: proposal ID to "accept" or "disregard". */
    var pendingActions: Map<String, String> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
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
            val key = proposalKey(item)
            val pending = pendingActions[key]?.let {
                context.getString(if (it == "accept") R.string.agent_edits_row_accept_pending else R.string.agent_edits_row_reject_pending)
            }
            binding.commentMetadata.text = listOfNotNull(location, type, pending).joinToString(" · ")
            val selecting = selection != null && item.isAgentProposal
            val checked = selecting && key in selection.orEmpty()
            // A card that is not checkable ignores setChecked: clear the check first, then the mode.
            binding.root.isCheckable = true
            binding.root.isChecked = checked
            binding.root.isCheckable = selecting
            binding.root.setCardBackgroundColor(MaterialColors.getColor(binding.root,
                if (checked || (selection == null && comment.id == selectedId)) com.google.android.material.R.attr.colorSecondaryContainer
                else com.google.android.material.R.attr.colorSurface))
            binding.root.setOnClickListener { onClick(item) }
            if (item.isAgentProposal) binding.root.setOnLongClickListener { onLongClick(item) } else {
                binding.root.setOnLongClickListener(null)
                binding.root.isLongClickable = false
            }
            // TalkBack: "Selected" or "Not selected", and what a tap or a long press does now.
            ViewCompat.setStateDescription(binding.root, if (!selecting) null
                else context.getString(if (checked) R.string.agent_edits_row_selected else R.string.agent_edits_row_not_selected))
            ViewCompat.replaceAccessibilityAction(binding.root, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                if (!selecting) null else context.getString(if (checked) R.string.agent_edits_row_deselect else R.string.agent_edits_row_select), null)
            ViewCompat.replaceAccessibilityAction(binding.root, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                if (item.isAgentProposal && selection == null) context.getString(R.string.agent_edits_row_select) else null, null)
        }
    }

    private object CommentComparator : DiffUtil.ItemCallback<DocumentCommentItem>() {
        override fun areItemsTheSame(oldItem: DocumentCommentItem, newItem: DocumentCommentItem) = oldItem.comment.id == newItem.comment.id
        override fun areContentsTheSame(oldItem: DocumentCommentItem, newItem: DocumentCommentItem) = oldItem == newItem
    }
}

/** The proposal an agent-edit row stands for (removal rows carry an "agent-remove-" prefix). */
internal fun proposalKey(item: DocumentCommentItem): String = item.comment.id.removePrefix("agent-remove-")

internal fun commentDisplayPage(pageIndex: Int): Int? = pageIndex.takeIf { it >= 0 && it < Int.MAX_VALUE }?.plus(1)

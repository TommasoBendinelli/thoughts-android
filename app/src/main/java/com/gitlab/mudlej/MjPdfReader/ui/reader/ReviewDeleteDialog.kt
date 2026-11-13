// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.DialogReviewDeleteBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal fun createReviewDeleteDialog(
    context: Context,
    canDeleteEnclosedText: Boolean,
    serverSupportsSourceDeletion: Boolean = false,
    onDelete: (Boolean) -> Unit,
): AlertDialog {
    val options = if (canDeleteEnclosedText)
        DialogReviewDeleteBinding.inflate(LayoutInflater.from(context)) else null
    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.review_card_delete_title)
        .setMessage(R.string.review_card_delete_now_message)
        .setPositiveButton(R.string.delete) { _, _ ->
            onDelete(serverSupportsSourceDeletion && options?.deleteEnclosedText?.isChecked == true)
        }
        .setNegativeButton(R.string.cancel, null)
    options?.let {
        it.deleteEnclosedText.isEnabled = serverSupportsSourceDeletion
        if (!serverSupportsSourceDeletion) {
            builder.setMessage(R.string.review_card_delete_text_server_required)
        }
        builder.setView(it.root)
    }
    val dialog = builder.create()
    options?.deleteEnclosedText?.setOnCheckedChangeListener { _, checked ->
        dialog.setMessage(context.getString(if (checked)
            R.string.review_card_delete_text_message else R.string.review_card_delete_now_message))
    }
    return dialog
}

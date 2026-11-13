// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal data class ProjectExportDocument(val id: String, val name: String)

/** The chosen document identities, or null when every document is chosen (the whole project). */
internal fun chosenProjectExportDocumentIds(documents: List<ProjectExportDocument>, checked: BooleanArray): List<String>? =
    if (checked.all { it }) null else documents.filterIndexed { index, _ -> checked[index] }.map { it.id }

/** Lets the user untick documents before a combined export; every document starts ticked. */
internal fun chooseProjectExportDocuments(
    context: Context,
    documents: List<ProjectExportDocument>,
    onChosen: (List<String>?) -> Unit,
) {
    if (documents.size < 2) {
        onChosen(null)
        return
    }
    val checked = BooleanArray(documents.size) { true }
    MaterialAlertDialogBuilder(context).setTitle("Export combined PDF")
        .setMultiChoiceItems(documents.map { it.name }.toTypedArray(), checked) { dialog, which, isChecked ->
            checked[which] = isChecked
            (dialog as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = checked.any { it }
        }
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Export") { _, _ -> onChosen(chosenProjectExportDocumentIds(documents, checked)) }
        .show()
}

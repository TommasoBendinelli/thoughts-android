// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gitlab.mudlej.MjPdfReader.data.importing.PaperWebsite
import com.gitlab.mudlej.MjPdfReader.data.importing.PreparedPaperImport
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** URL -> temporary preparation -> editable confirmation -> local import. */
internal class WebsiteImportDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val projectName: String,
    private val import: (PreparedPaperImport, String) -> Unit,
    private val onImported: () -> Unit,
    private val prepare: (PaperWebsite, () -> Unit) -> PreparedPaperImport = { paper, active ->
        PreparedPaperImport.prepare(context, paper, active)
    },
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8))
    }
    private val link = EditText(context).apply {
        hint = "Paper page or PDF link"; setSingleLine()
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    private val status = TextView(context)
    private val dialog = MaterialAlertDialogBuilder(context).setTitle("Import from website")
        .setView(ScrollView(context).apply { addView(column) })
        .setNegativeButton("Cancel", null).setNeutralButton("Open browser", null).setPositiveButton("Next", null).create()
    private var job: Job? = null

    fun show(): androidx.appcompat.app.AlertDialog {
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
        showUrl()
        return dialog
    }

    private fun showUrl(error: String? = null) {
        dialog.setTitle("Import from website")
        column.removeAllViews(); column.addView(link); column.addView(status)
        link.isEnabled = true
        status.text = error ?: "Supported websites: OpenReview, arXiv, NeurIPS Proceedings, ICLR Proceedings, and PMLR. Next downloads the PDF for confirmation. Destination: $projectName."
        dialog.setCancelable(true)
        dialog.getButton(-2).isEnabled = true
        dialog.getButton(-3).apply {
            text = "Open browser"; isEnabled = true; visibility = View.VISIBLE
            setOnClickListener {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PaperWebsite.resolve(link.text.toString()).pageUrl))) }
                catch (error: Exception) { link.error = error.message ?: "No browser is available" }
            }
        }
        dialog.getButton(-1).apply {
            text = "Next"; isEnabled = true
            setOnClickListener {
                val paper = try { PaperWebsite.resolve(link.text.toString()) }
                catch (error: IllegalArgumentException) { link.error = error.message; return@setOnClickListener }
                link.error = null; link.isEnabled = false; isEnabled = false
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(link.windowToken, 0)
                dialog.getButton(-3).isEnabled = false
                status.text = "Fetching title and downloading PDF…"
                job = scope.launch {
                    var prepared: PreparedPaperImport? = null
                    try {
                        // Assign inside IO so cancellation during dispatch back to Main cannot leak the file.
                        withContext(Dispatchers.IO) {
                            val current = coroutineContext
                            prepared = prepare(paper) { current.ensureActive() }
                        }
                        val ready = requireNotNull(prepared)
                        var suggestedName = ready.title.orEmpty()
                        var saveError: String? = null
                        while (true) {
                            val name = confirm(ready, suggestedName, saveError) ?: break
                            suggestedName = name
                            try {
                                withContext(Dispatchers.IO) { import(ready, name) }
                                onImported()
                                dialog.dismiss()
                                return@launch
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { saveError = error.message ?: "The PDF could not be saved. Try again." }
                        }
                        showUrl()
                    } catch (cancelled: CancellationException) { dialog.dismiss(); throw cancelled }
                    catch (error: Exception) { showUrl(error.message ?: "The PDF could not be prepared. Check your connection and try again.") }
                    finally { prepared?.close() }
                }
            }
        }
    }

    private suspend fun confirm(ready: PreparedPaperImport, suggestedName: String, error: String?): String? =
        suspendCancellableCoroutine { continuation ->
            dialog.setTitle("Confirm import")
            column.removeAllViews()
            fun label(text: String) { column.addView(TextView(context).apply { this.text = text; setPadding(0, dp(6), 0, dp(6)) }) }
            label("Website: ${ready.paper.websiteType}")
            label("Document name")
            val name = EditText(context).apply {
                hint = "Document name"; setText(suggestedName); minLines = 2; maxLines = 5
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            column.addView(name)
            label("File size: ${Formatter.formatFileSize(context, ready.sizeBytes)}\nPages: ${ready.pageCount}\nDestination: $projectName")
            status.text = error ?: when {
                ready.title == null -> "The title could not be found. Enter a document name to continue."
                suggestedName.length > 150 -> "Shorten the document name to 150 characters before importing."
                else -> "You can edit the name before importing."
            }
            column.addView(status)
            dialog.setCancelable(true)
            dialog.getButton(-2).isEnabled = true
            fun finish(value: String?) {
                if (!continuation.isActive) return
                dialog.getButton(-1).isEnabled = false
                dialog.getButton(-3).isEnabled = false
                if (value != null) {
                    dialog.setCancelable(false); dialog.getButton(-2).isEnabled = false
                    name.isEnabled = false; status.text = "Saving PDF…"
                }
                continuation.resume(value)
            }
            dialog.getButton(-3).apply { text = "Back"; isEnabled = true; setOnClickListener { finish(null) } }
            dialog.getButton(-1).apply {
                text = "Import"; isEnabled = true
                setOnClickListener {
                    val display = name.text.toString().trim().replace(Regex("[\\r\\n\\t]+"), " ")
                    if (display.isBlank() || display.length > 150 || display.any { it.code < 32 }) {
                        name.error = "Enter a name between 1 and 150 characters"
                    } else finish(display)
                }
            }
        }
}

// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.share

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import kotlinx.coroutines.CancellationException
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.SharedFile
import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageRangeValidation
import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import com.gitlab.mudlej.MjPdfReader.core.io.fileShareIntent
import com.gitlab.mudlej.MjPdfReader.core.io.filesShareIntent
import com.gitlab.mudlej.MjPdfReader.core.io.plainTextShareIntent
import com.gitlab.mudlej.MjPdfReader.core.io.validatePdfPageRange
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentShareExporter
import com.gitlab.mudlej.MjPdfReader.databinding.DialogSharePagesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PdfShareController(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val startIntent: (Intent) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {

    fun show(
        pdfUri: Uri,
        displayName: String,
        embeddedComments: List<PDFView.PdfComment>? = null,
        shareableLink: String? = null,
        citationContext: ThoughtsCitationContext? = null,
        pageCount: Int = 0,
        password: String? = null,
        documentSourceUri: Uri = pdfUri,
        currentPageIndex: Int = 0,
        expectedSourceSha256: String? = null,
    ) {
        val choices = buildList {
            shareableLink?.takeIf(String::isNotBlank)?.let { link ->
                add(ShareChoice(R.string.share_link_option) {
                    startIntent(
                        plainTextShareIntent(
                            activity.getString(R.string.share_file),
                            link,
                        )
                    )
                })
            }
            add(ShareChoice(R.string.share_pdf_only_option) {
                choosePages(pageCount, documentSourceUri, currentPageIndex, expectedSourceSha256, password) { selection, documentName ->
                    launchShare {
                        pdfOnlyIntent(pdfUri, displayName, password, selection, documentName)
                    }
                }
            })
            citationContext?.let { context ->
                add(ShareChoice(R.string.share_pdf_with_citing_prompt_option) {
                    choosePages(pageCount, documentSourceUri, currentPageIndex, expectedSourceSha256, password) { selection, documentName ->
                        launchShare {
                            pdfWithCitingPromptIntent(
                                pdfUri,
                                displayName,
                                password,
                                selection,
                                context,
                                documentName,
                            )
                        }
                    }
                })
            }
            add(ShareChoice(R.string.share_pdf_with_comments_option) {
                choosePages(pageCount, documentSourceUri, currentPageIndex, expectedSourceSha256, password) { selection, documentName ->
                    launchShare {
                        pdfWithCommentsIntent(
                            pdfUri,
                            displayName,
                            password,
                            embeddedComments,
                            selection,
                            documentName,
                        )
                    }
                }
            })
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.share_file)
            .setItems(choices.map { activity.getString(it.label) }.toTypedArray()) { _, index ->
                runCatching(choices[index].action).onFailure(onFailure)
            }
            .show()
    }

    private fun choosePages(
        pageCount: Int,
        sourceUri: Uri,
        currentPageIndex: Int,
        expectedSha256: String?,
        password: String?,
        onSelected: (PdfPageSelection, String?) -> Unit,
    ) {
        if (pageCount <= 1) {
            onSelected(PdfPageSelection.All, null)
            return
        }

        val binding = DialogSharePagesBinding.inflate(activity.layoutInflater)
        binding.fromPage.setText("1")
        binding.toPage.setText(pageCount.toString())

        var documents = emptyList<ProjectShareDocument>()
        var scopeChanged = false
        binding.projectDocument.isEnabled = false

        fun showDocumentPages() {
            val document = documents.getOrNull(binding.projectDocumentPicker.selectedItemPosition) ?: return
            binding.fromPage.setText((document.pages.firstPageIndex + 1).toString())
            binding.toPage.setText((document.pages.lastPageIndex + 1).toString())
        }

        fun setRangeEnabled(enabled: Boolean) {
            binding.fromPageLayout.isEnabled = enabled
            binding.toPageLayout.isEnabled = enabled
        }

        fun clearErrors() {
            binding.fromPageLayout.error = null
            binding.toPageLayout.error = null
        }

        binding.pageSelectionGroup.setOnCheckedChangeListener { _, checkedId ->
            scopeChanged = true
            clearErrors()
            setRangeEnabled(checkedId == R.id.pageRange)
            binding.projectDocumentPicker.visibility =
                if (checkedId == R.id.projectDocument) View.VISIBLE else View.GONE
            if (checkedId == R.id.projectDocument) showDocumentPages()
            if (checkedId == R.id.allPages) {
                binding.fromPage.setText("1")
                binding.toPage.setText(pageCount.toString())
            }
        }
        binding.projectDocumentPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (binding.projectDocument.isChecked) showDocumentPages()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.fromPage.doAfterTextChanged { binding.fromPageLayout.error = null }
        binding.toPage.doAfterTextChanged { binding.toPageLayout.error = null }
        setRangeEnabled(false)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.share_pages_title)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.share, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener {
                    clearErrors()
                    if (binding.allPages.isChecked) {
                        dialog.dismiss()
                        onSelected(PdfPageSelection.All, null)
                        return@setOnClickListener
                    }
                    if (binding.projectDocument.isChecked) {
                        val document = documents.getOrNull(binding.projectDocumentPicker.selectedItemPosition)
                            ?: return@setOnClickListener
                        dialog.dismiss()
                        onSelected(document.pages, document.name)
                        return@setOnClickListener
                    }
                    when (val validation = validatePdfPageRange(
                        binding.fromPage.text?.toString().orEmpty(),
                        binding.toPage.text?.toString().orEmpty(),
                        pageCount,
                    )) {
                        is PdfPageRangeValidation.Valid -> {
                            dialog.dismiss()
                            onSelected(validation.selection, null)
                        }
                        PdfPageRangeValidation.MissingPage -> {
                            val message = activity.getString(R.string.share_page_required)
                            if (binding.fromPage.text?.toString().orEmpty().trim().toIntOrNull() == null) {
                                binding.fromPageLayout.error = message
                            }
                            if (binding.toPage.text?.toString().orEmpty().trim().toIntOrNull() == null) {
                                binding.toPageLayout.error = message
                            }
                        }
                        PdfPageRangeValidation.OutOfBounds -> {
                            val message = activity.getString(
                                R.string.share_page_out_of_bounds,
                                pageCount,
                            )
                            val first = binding.fromPage.text?.toString().orEmpty().trim().toIntOrNull()
                            val last = binding.toPage.text?.toString().orEmpty().trim().toIntOrNull()
                            if (first == null || first !in 1..pageCount) {
                                binding.fromPageLayout.error = message
                            }
                            if (last == null || last !in 1..pageCount) {
                                binding.toPageLayout.error = message
                            }
                        }
                        PdfPageRangeValidation.Reversed -> {
                            binding.toPageLayout.error = activity.getString(
                                R.string.share_page_order_invalid
                            )
                        }
                    }
                }
        }
        dialog.show()
        val loadJob = scope.launch {
            documents = try {
                loadProjectShareDocuments(activity, sourceUri, pageCount, expectedSha256, password)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            binding.projectDocument.isEnabled = documents.isNotEmpty()
            binding.projectDocumentStatus.visibility = if (documents.isEmpty()) View.VISIBLE else View.GONE
            binding.projectDocumentStatus.setText(R.string.share_documents_unavailable)
            binding.projectDocumentPicker.adapter = ArrayAdapter(
                activity,
                R.layout.item_share_document,
                documents.map { activity.getString(R.string.share_document_pages, it.name,
                    it.pages.firstPageIndex + 1, it.pages.lastPageIndex + 1) },
            ).apply { setDropDownViewResource(R.layout.item_share_document_choice) }
            binding.projectDocumentPicker.setSelection(currentProjectShareDocument(documents, currentPageIndex))
            if (documents.isNotEmpty() && !scopeChanged) {
                binding.pageSelectionGroup.check(R.id.projectDocument)
            }
        }
        dialog.setOnDismissListener { loadJob.cancel() }
    }

    private fun launchShare(buildIntent: suspend () -> Intent) {
        scope.launch {
            runCatching { buildIntent() }
                .onSuccess(startIntent)
                .onFailure(onFailure)
        }
    }

    private suspend fun pdfOnlyIntent(
        pdfUri: Uri,
        displayName: String,
        password: String?,
        selection: PdfPageSelection,
        documentName: String?,
    ): Intent {
        val sharedPdf = PdfPageSubsetExporter(activity).export(
            pdfUri,
            displayName,
            password,
            selection,
            outputName = documentName,
        )
        return fileShareIntent(
            activity.getString(R.string.share_file),
            sharedPdf.fileName,
            sharedPdf.uri,
        )
    }

    private suspend fun pdfWithCommentsIntent(
        pdfUri: Uri,
        displayName: String,
        password: String?,
        embeddedComments: List<PDFView.PdfComment>?,
        selection: PdfPageSelection,
        documentName: String?,
    ): Intent {
        val sharedPdf = PdfPageSubsetExporter(activity).export(
            pdfUri,
            displayName,
            password,
            selection,
            outputName = documentName,
        )
        val comments = CommentShareExporter(activity).export(
            pdfUri,
            displayName,
            embeddedComments,
            selection,
            sharedPdf.uri,
            sharedPdf.fileName,
        )
        return filesShareIntent(
            activity.getString(R.string.share_file),
            listOf(
                SharedFile(
                    sharedPdf.fileName,
                    PDF_MIME_TYPE,
                    sharedPdf.uri,
                ),
                SharedFile(
                    comments.fileName,
                    JSON_MIME_TYPE,
                    comments.uri,
                ),
            ),
        )
    }

    private suspend fun pdfWithCitingPromptIntent(
        pdfUri: Uri,
        displayName: String,
        password: String?,
        selection: PdfPageSelection,
        citationContext: ThoughtsCitationContext,
        documentName: String?,
    ): Intent {
        val sharedPdf = PdfPageSubsetExporter(activity).export(
            pdfUri,
            displayName,
            password,
            selection,
            outputName = documentName,
        )
        val instructions = CitationInstructionExporter(activity).export(
            citationContext,
            selection,
        )
        return filesShareIntent(
            activity.getString(R.string.share_file),
            listOf(
                SharedFile(
                    sharedPdf.fileName,
                    PDF_MIME_TYPE,
                    sharedPdf.uri,
                ),
                SharedFile(
                    instructions.fileName,
                    TEXT_MIME_TYPE,
                    instructions.uri,
                ),
            ),
        )
    }

    private data class ShareChoice(
        val label: Int,
        val action: () -> Unit,
    )

    companion object {
        private const val PDF_MIME_TYPE = "application/pdf"
        private const val JSON_MIME_TYPE = "application/json"
        private const val TEXT_MIME_TYPE = "text/plain"
    }
}

// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.library

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedCommentIdentity
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.render.sourceLinkedLogicalFile
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryFilenameTakenException
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryImportScheduler
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutation
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryImportController(
    private val activity: MainActivity,
    private val root: View,
    private val sourceIndex: SourceLinkedPdfIndex = SourceLinkedPdfIndex(),
) {
    fun canImport(uri: Uri?, sourceIdentity: SourceLinkedCommentIdentity): Boolean {
        if (uri == null) return false
        // An earlier version opened from History belongs to a Library document already.
        if (com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentHistoryController.isVersionFile(activity, uri)) return false
        val logicalFile = sourceLinkedLogicalFile(activity, uri)
        return canPutInLibrary(
            hasDocument = true,
            sourceIdentity = sourceIdentity,
            isLibraryPdf = logicalFile?.let(sourceIndex::isLibraryPdf) == true,
        )
    }

    fun putInLibrary(uri: Uri?, documentName: String) {
        if (uri == null) return
        val initialName = canonicalFilename(documentName)
        val safeName = pdfLinkSafeFilename(initialName)
        enqueue(uri, safeName)
    }

    private fun showNameDialog(uri: Uri, proposedName: String, message: Int) {
        val editor = EditText(activity).apply { setText(proposedName); selectAll() }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.library_import_name_conflict_title)
            .setMessage(message)
            .setView(editor)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.library_import_retry) { _, _ ->
                putInLibrary(uri, editor.text.toString())
            }
            .show()
    }

    /**
     * Adds the PDF to this phone's Library at once and queues it; the next Sync the user starts
     * uploads it (on mobile data only within the Sync data limit or after approval).
     */
    private fun enqueue(uri: Uri, filename: String) {
        activity.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val staged = stagePdf(uri)
                    try {
                        addToLocalLibrary(activity, staged.file, filename, staged.sha256, sourceIndex)
                    } finally {
                        // Consumed when it was added; otherwise the copy is no longer needed.
                        staged.file.delete()
                    }
                }
            }
            when (val error = result.exceptionOrNull()) {
                null -> {
                    PendingChangesSignal.notifyChanged()
                    AppSnackbar.make(root, R.string.library_import_success, Snackbar.LENGTH_LONG).show()
                }
                is LibraryFilenameTakenException ->
                    showNameDialog(uri, filename, R.string.library_import_name_conflict_message)
                else -> {
                    Log.e(TAG, "Could not add the PDF to the Library", error)
                    AppSnackbar.make(root, R.string.library_import_failed, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private data class StagedPdf(val file: File, val sha256: String)

    private fun stagePdf(uri: Uri): StagedPdf {
        val staging = LibraryImportScheduler.stagingRoot(activity).apply { mkdirs() }
        val payload = File(staging, "${UUID.randomUUID()}.pdf")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            activity.contentResolver.openInputStream(uri).use { raw ->
                requireNotNull(raw) { "The PDF could not be opened" }
                DigestInputStream(raw, digest).use { input ->
                    payload.outputStream().use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            val header = ByteArray(5)
            require(payload.inputStream().use { it.read(header) == header.size } && header.contentEquals("%PDF-".toByteArray())) {
                "The selected document is not a PDF"
            }
            return StagedPdf(payload, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (error: Exception) {
            payload.delete()
            throw error
        }
    }

    private companion object {
        const val TAG = "LibraryImport"
    }

    private fun canonicalFilename(value: String): String {
        val base = File(value.ifBlank { "document.pdf" }).name
            .filter { it.code >= 32 && it != '/' && it != '\\' }
            .ifBlank { "document.pdf" }
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
    }

}

/**
 * Adds a verified staged PDF to this phone's Library under [filename] and queues it for Sync. The
 * filename is taken when the Library on this phone (including changes waiting for Sync) already
 * lists it, or a file of that name is in the Library directory.
 */
internal fun addToLocalLibrary(
    context: Context,
    staged: File,
    filename: String,
    sha256: String,
    sourceIndex: SourceLinkedPdfIndex = SourceLinkedPdfIndex(),
): LibraryMutation {
    val canonical = com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue.defaultCanonicalPdfDirectory()
    return LibraryMutationStore(context).importPdf(
        staged = staged,
        filename = filename,
        sha256 = sha256,
        isFilenameTaken = { name ->
            sourceIndex.invalidate()
            val snapshot = sourceIndex.snapshot()
            name in snapshot.libraryFilenames || name in snapshot.filenames || File(canonical, name).exists()
        },
    ).also { sourceIndex.invalidate() }
}

internal fun pdfLinkSafeFilename(value: String): String {
    val base = File(value.ifBlank { "document.pdf" }).name
    val stem = if (base.endsWith(".pdf", ignoreCase = true)) base.dropLast(4) else base
    val sanitized = buildString {
        stem.forEach { character ->
            append(if (character.isAsciiPdfLinkCharacter()) character else '_')
        }
    }.replace(Regex("_+"), "_").ifBlank { "document" }
    val safeStem = if (sanitized.first().isAsciiLetterOrDigit()) {
        sanitized
    } else {
        "document_${sanitized.dropWhile { !it.isAsciiLetterOrDigit() }.ifBlank { "pdf" }}"
    }
    return "$safeStem.pdf"
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private fun Char.isAsciiPdfLinkCharacter(): Boolean =
    isAsciiLetterOrDigit() || this == '.' || this == '_' || this == '-'

internal fun canPutInLibrary(
    hasDocument: Boolean,
    sourceIdentity: SourceLinkedCommentIdentity,
    isLibraryPdf: Boolean,
): Boolean = hasDocument &&
    sourceIdentity == SourceLinkedCommentIdentity.NOT_SOURCE_LINKED &&
    !isLibraryPdf

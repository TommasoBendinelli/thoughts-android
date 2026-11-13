// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.DialogRecordOptionsBinding
import com.gitlab.mudlej.MjPdfReader.databinding.DialogRenameRecordBinding
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.gitlab.mudlej.MjPdfReader.data.HistoryCleaner
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryRemovalStore
import com.gitlab.mudlej.MjPdfReader.ui.reader.showMetaDialog
import com.gitlab.mudlej.MjPdfReader.ui.share.PdfShareController
import com.gitlab.mudlej.MjPdfReader.ui.share.thoughtsCitationContextFor
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.core.io.appDateFormatter
import com.gitlab.mudlej.MjPdfReader.core.io.computeHash
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordOptionsDialog(
    private val activity: AppCompatActivity,
    private val pdfRepository: PdfRepository,
    private val coverCache: CoverCache,
    private val historyPolicy: HistoryPolicy,
    private val historyCleaner: HistoryCleaner,
    private val scope: CoroutineScope,
    private val sourcePdfIndex: SourceLinkedPdfIndex,
    private val canShowSyncBuild: (HomeItem) -> Boolean,
    private val onShowSyncBuild: (HomeItem) -> Unit,
    private val onChanged: () -> Unit,
    private val onPendingChangesChanged: () -> Unit,
) {

    private val pdfShareController by lazy {
        PdfShareController(
            activity,
            scope,
            activity::startActivity,
        ) {
            Toast.makeText(activity, R.string.home_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun show(item: HomeItem) {
        scope.launch {
            val record = pdfRepository.findRecord(item.hash)
            buildAndShow(item, record)
        }
    }

    private fun buildAndShow(item: HomeItem, record: PdfRecord?) {
        val binding = DialogRecordOptionsBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.CompactMaterialAlertDialog)
            .setView(binding.root)
            .create()

        binding.optionsTitle.text = item.libraryDisplayName.ifBlank { item.title }
        binding.optionsInfo.text = buildInfoLine(item, item.length)
        coverCache.bind(binding.optionsCover, item.coverKey, item.uri, COVER_WIDTH_PX, scope)

        if (item.length <= 0) {
            scope.launch {
                val meta = withContext(Dispatchers.IO) { readDocumentMeta(item.uri) }
                val pages = meta?.totalPages ?: 0
                if (pages > 0) {
                    binding.optionsInfo.text = buildInfoLine(item, pages)
                }
            }
        }

        if (item.progressPercent > 0) {
            binding.optionsProgress.visibility = View.VISIBLE
            binding.optionsProgress.progress = item.progressPercent
            binding.optionsPercent.visibility = View.VISIBLE
            binding.optionsPercent.text = activity.getString(
                R.string.home_percent_position_template,
                item.progressPercent,
                item.pageNumber + 1,
                item.length,
            )
        }

        if (item.hasBeenOpened) {
            binding.optionsLastOpened.visibility = View.VISIBLE
            binding.optionsLastOpened.text = activity.getString(
                R.string.home_last_opened, item.lastOpened.format(appDateFormatter)
            )
        }

        bindStatus(binding, item, record)

        if (item.hasBeenOpened && record != null) {
            binding.removeRecentRow.visibility = View.VISIBLE
            binding.removeRecentRow.setOnClickListener {
                dialog.dismiss()
                scope.launch {
                    pdfRepository.setLastOpened(
                        record.hash, LocalDateTime.parse(PdfRecord.UNSET_DATE)
                    )
                    onChanged()
                }
            }
        }

        binding.hideRowLabel.setText(
            if (record?.hidden == true) {
                R.string.home_show_in_library
            } else {
                R.string.home_hide_from_library
            }
        )
        binding.hideRow.setOnClickListener {
            dialog.dismiss()
            scope.launch {
                val hash = record?.hash ?: resolveContentHash(item) ?: return@launch
                if (record == null && !pdfRepository.hasRecord(hash)) {
                    if (!historyPolicy.canRecord()) {
                        Toast.makeText(activity, R.string.history_action_blocked, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    pdfRepository.saveRecordInBackground(newRecord(item, hash))
                }
                pdfRepository.setHidden(hash, !(record?.hidden ?: false))
                onChanged()
            }
        }

        binding.sharePdfRow.setOnClickListener {
            dialog.dismiss()
            sharePdf(item, record)
        }

        val canShowDocumentActions = canShowSyncBuild(item)
        binding.syncBuildRow.visibility = if (canShowDocumentActions) View.VISIBLE else View.GONE
        binding.syncBuildRow.setOnClickListener {
            dialog.dismiss()
            onShowSyncBuild(item)
        }

        binding.optionsInfoButton.setOnClickListener { showFullProperties(item, record) }

        val libraryKind = managedLibraryKind(item)
        if (item.uri.scheme == "file") {
            binding.renameRow.setOnClickListener {
                dialog.dismiss()
                showRenameDialog(item, record, libraryKind != null)
            }
        } else {
            binding.renameRow.visibility = View.GONE
        }

        if (libraryKind != null) {
            // Notes an agent sent about this document; offered once its Library document ID is known.
            val libraryFilename = item.uri.path?.let(::File)?.name.orEmpty()
            scope.launch {
                val documentId = withContext(Dispatchers.IO) {
                    runCatching {
                        com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().byFilename(libraryFilename)
                            ?.get("document_id")?.takeIf { it.isJsonPrimitive }?.asString
                    }.getOrNull()
                }?.takeIf(String::isNotBlank) ?: return@launch
                binding.agentNotesRow.visibility = View.VISIBLE
                binding.agentNotesRow.setOnClickListener {
                    dialog.dismiss()
                    activity.startActivity(com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNotesListActivity.intent(
                        activity, documentId = documentId, subtitle = item.libraryDisplayName.ifBlank { item.title }))
                }
            }
        }
        if (libraryKind != null) {
            val filename = item.uri.path?.let(::File)?.name.orEmpty()
            val archived = filename in sourcePdfIndex.snapshot().archivedLibraryFilenames
            binding.archiveRow.visibility = View.VISIBLE
            binding.archiveRowLabel.setText(if (archived) R.string.home_unarchive else R.string.home_archive)
            binding.archiveRow.setOnClickListener {
                dialog.dismiss()
                performLibraryArchiveChange(item, !archived)
            }
        } else {
            binding.archiveRow.visibility = View.GONE
        }

        when (recordDeleteAction(libraryKind)) {
            RecordDeleteAction.DELETE_LOCAL_FILE -> {
                binding.deleteRowLabel.setText(R.string.home_delete_from_device)
                binding.deleteRow.setOnClickListener {
                    dialog.dismiss()
                    confirmDelete(item, record)
                }
            }
            RecordDeleteAction.QUEUE_LIBRARY_REMOVAL -> {
                binding.deleteRowLabel.setText(R.string.home_remove_from_library)
                binding.deleteRow.setOnClickListener {
                    dialog.dismiss()
                    confirmLibraryRemoval(item)
                }
            }
            RecordDeleteAction.UNAVAILABLE -> binding.deleteRow.visibility = View.GONE
        }

        dialog.show()
    }

    private fun buildInfoLine(item: HomeItem, length: Int): String {
        val size = fileSizeBytes(item.uri)
        val sizeText = size?.let { String.format(Locale.US, "%.2f MB", it / (1024.0 * 1024.0)) }
        return when {
            length > 0 && sizeText != null ->
                activity.getString(R.string.home_pages_size_template, length, sizeText)
            length > 0 ->
                activity.resources.getQuantityString(R.plurals.home_pages, length, length)
            else -> sizeText.orEmpty()
        }
    }

    private fun bindStatus(binding: DialogRecordOptionsBinding, item: HomeItem, record: PdfRecord?) {
        val statuses = listOf(
            ReadingStatus.UNSET to activity.getString(R.string.none),
            ReadingStatus.TO_READ to activity.getString(R.string.home_chip_to_read),
            ReadingStatus.READING to activity.getString(R.string.home_chip_reading),
            ReadingStatus.ON_HOLD to activity.getString(R.string.home_chip_on_hold),
            ReadingStatus.COMPLETED to activity.getString(R.string.home_chip_completed),
            ReadingStatus.ABANDONED to activity.getString(R.string.home_chip_abandoned),
        )
        binding.statusDropdown.setSimpleItems(statuses.map { it.second }.toTypedArray())

        var currentStatus = record?.reading ?: ReadingStatus.UNSET
        binding.statusDropdown.setText(statuses.first { it.first == currentStatus }.second, false)

        var resolvedHash = record?.hash
        binding.statusDropdown.setOnItemClickListener { _, _, position, _ ->
            val status = statuses[position].first
            if (status == currentStatus) {
                return@setOnItemClickListener
            }
            currentStatus = status
            scope.launch {
                val hash = resolvedHash ?: resolveContentHash(item) ?: return@launch
                resolvedHash = hash
                if (!pdfRepository.hasRecord(hash)) {
                    if (!historyPolicy.canRecord()) {
                        Toast.makeText(activity, R.string.history_action_blocked, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    pdfRepository.saveRecordInBackground(newRecord(item, hash))
                }
                pdfRepository.setReading(hash, status)
                onChanged()
            }
        }
    }

    suspend fun ensureRecordHash(item: HomeItem): String? {
        if (!item.isScanOnly) {
            return item.hash
        }
        val hash = resolveContentHash(item) ?: return null
        if (!pdfRepository.hasRecord(hash)) {
            if (!historyPolicy.canRecord()) {
                return null
            }
            pdfRepository.saveRecordInBackground(newRecord(item, hash))
        }
        return hash
    }

    private suspend fun resolveContentHash(item: HomeItem): String? {
        val path = item.uri.path ?: return null
        return withContext(Dispatchers.IO) { computeHash(File(path)) }
    }

    private fun newRecord(item: HomeItem, hash: String): PdfRecord {
        return PdfRecord(
            hash,
            0,
            item.uri,
            item.length,
            fileNameOf(item, null),
            null,
            LocalDateTime.parse(PdfRecord.UNSET_DATE),
            ReadingStatus.UNSET,
            false,
        )
    }

    private fun showFullProperties(item: HomeItem, record: PdfRecord?) {
        scope.launch {
            val meta = withContext(Dispatchers.IO) { readDocumentMeta(item.uri) }
            val filePath = withContext(Dispatchers.IO) {
                UriCanonicalizer.canonicalize(activity, item.uri)?.absolutePath
            }
            showMetaDialog(activity, meta, fileNameOf(item, record), fileSizeBytes(item.uri), filePath = filePath)
        }
    }

    private fun fileNameOf(item: HomeItem, record: PdfRecord?): String {
        return record?.fileName
            ?: item.uri.path?.let { File(it).nameWithoutExtension }
            ?: item.title
    }

    private fun readDocumentMeta(uri: Uri): PdfDocument.Meta? {
        return runCatching {
            val core = PdfiumCore(activity)
            val fd = activity.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val document = try {
                core.newDocument(fd)
            } catch (throwable: Throwable) {
                runCatching { fd.close() }
                return null
            }
            try {
                core.getDocumentMeta(document)
            } finally {
                core.closeDocument(document)
            }
        }.getOrNull()
    }

    private fun fileSizeBytes(uri: Uri): Long? {
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it).length() }
            else -> runCatching {
                activity.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()
        }?.takeIf { it > 0 }
    }

    private fun sharePdf(item: HomeItem, record: PdfRecord?) {
        pdfShareController.show(
            item.uri,
            fileNameOf(item, record),
            citationContext = thoughtsCitationContextFor(item.uri, sourcePdfIndex),
            pageCount = item.length.coerceAtLeast(record?.length ?: 0),
            password = record?.password,
        )
    }

    private fun managedLibraryKind(item: HomeItem): LibraryPdfKind? {
        val file = item.uri.takeIf { it.scheme == "file" }?.path?.let(::File) ?: return null
        return sourcePdfIndex.snapshot().libraryKindFor(
            file,
            PhoneRenderQueue.defaultCanonicalPdfDirectory(),
        )
    }

    private fun showRenameDialog(item: HomeItem, record: PdfRecord?, managedLibrary: Boolean) {
        val binding = DialogRenameRecordBinding.inflate(activity.layoutInflater)
        binding.renameInput.setText(
            if (managedLibrary) {
                item.libraryDisplayName.ifBlank { item.uri.path?.let(::File)?.nameWithoutExtension.orEmpty() }
            } else {
                fileNameOf(item, record)
            }
        )

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_rename)
            .setView(binding.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                binding.renameInputLayout.error = null
                val input = documentRenameInput(
                    raw = binding.renameInput.text?.toString().orEmpty(),
                    allowFileSeparator = managedLibrary,
                )
                val errorMessage = when (input.error) {
                    DocumentRenameInputError.EMPTY -> R.string.home_rename_empty
                    DocumentRenameInputError.TOO_LONG -> R.string.home_rename_too_long
                    DocumentRenameInputError.CONTROL_CHARACTER -> R.string.home_rename_invalid_character
                    DocumentRenameInputError.FILE_SEPARATOR -> R.string.home_rename_invalid_filename
                    null -> null
                }
                if (errorMessage != null) {
                    binding.renameInputLayout.error = activity.getString(errorMessage)
                    return@setOnClickListener
                }
                binding.renameInput.setText(input.normalized)
                dialog.dismiss()
                if (managedLibrary) performLibraryDisplayRename(item, input.normalized)
                else performRename(item, record, input.normalized)
            }
        }
        dialog.show()
    }

    private fun performLibraryDisplayRename(item: HomeItem, displayName: String) {
        scope.launch {
            val file = item.uri.path?.let(::File) ?: return@launch
            val snapshot = sourcePdfIndex.snapshot()
            if (snapshot.libraryKindsByFilename[file.name] == null) {
                Toast.makeText(activity, R.string.home_rename_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val expectedSha256 = snapshot.libraryPdfSha256ByFilename[file.name]
                ?: return@launch Toast.makeText(activity, R.string.home_rename_failed, Toast.LENGTH_SHORT).show()
            // Saved on this phone at once; the next Sync the user starts sends it to Fedora.
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    LibraryMutationStore(activity).enqueueRename(file.name, expectedSha256, displayName)
                }
            }
            if (result.isFailure) {
                val failure = result.exceptionOrNull()
                Log.e(TAG, "Library display rename failed for ${file.name}", failure)
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.home_rename_failed)
                    .setMessage(
                        activity.getString(
                            R.string.home_rename_failed_detail,
                            failure?.message?.takeIf(String::isNotBlank)
                                ?: activity.getString(R.string.home_rename_failed),
                        )
                    )
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }
            sourcePdfIndex.invalidate()
            onChanged()
            onPendingChangesChanged()
            Toast.makeText(activity, R.string.home_rename_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun performLibraryArchiveChange(item: HomeItem, archived: Boolean) {
        scope.launch {
            val file = item.uri.path?.let(::File) ?: return@launch
            val snapshot = sourcePdfIndex.snapshot()
            if (snapshot.libraryKindsByFilename[file.name] == null) {
                Toast.makeText(activity, R.string.home_archive_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val expectedSha256 = snapshot.libraryPdfSha256ByFilename[file.name]
                ?: return@launch Toast.makeText(activity, R.string.home_archive_failed, Toast.LENGTH_SHORT).show()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    LibraryMutationStore(activity).enqueueArchive(file.name, expectedSha256, archived)
                }
            }
            if (result.isFailure) {
                Log.e(TAG, "Could not save the Library archive change for ${file.name}", result.exceptionOrNull())
                Toast.makeText(activity, R.string.home_archive_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            sourcePdfIndex.invalidate()
            onChanged()
            onPendingChangesChanged()
            Toast.makeText(activity, if (archived) R.string.home_archive_success else R.string.home_unarchive_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun performRename(item: HomeItem, record: PdfRecord?, newName: String) {
        scope.launch {
            val path = item.uri.path ?: return@launch
            val oldFile = File(path)
            val target = File(oldFile.parentFile, "$newName.pdf")

            if (target.exists()) {
                Toast.makeText(activity, R.string.home_rename_exists, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val renamed = withContext(Dispatchers.IO) { oldFile.renameTo(target) }
            if (!renamed) {
                Toast.makeText(activity, R.string.home_rename_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (record != null) {
                pdfRepository.updateRecordIdentity(
                    record.hash, Uri.fromFile(target), newName, record.lastOpened
                )
                pdfRepository.setDocumentTitle(record.hash, null)
            }
            onChanged()
        }
    }

    private fun confirmDelete(item: HomeItem, record: PdfRecord?) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_dialog_title)
            .setMessage(
                activity.getString(R.string.home_delete_confirm_message, fileNameOf(item, record))
            )
            .setPositiveButton(R.string.delete) { _, _ -> performDelete(item, record) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmLibraryRemoval(item: HomeItem) {
        val filename = item.uri.path?.let(::File)?.name.orEmpty()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_remove_from_library_title)
            .setMessage(
                activity.getString(R.string.home_remove_from_library_confirm_message, filename)
            )
            .setPositiveButton(R.string.delete) { _, _ -> performLibraryRemoval(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performLibraryRemoval(item: HomeItem) {
        scope.launch {
            val file = item.uri.path?.let(::File)
            val snapshot = sourcePdfIndex.snapshot()
            val expectedSha256 = file?.name
                ?.takeIf { snapshot.libraryKindsByFilename[it] == LibraryPdfKind.UNLINKED }
                ?.let(snapshot.libraryPdfSha256ByFilename::get)
            if (file == null || expectedSha256 == null) {
                Toast.makeText(
                    activity,
                    R.string.home_remove_from_library_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    // A PDF added on this phone that Fedora has not received is simply withdrawn.
                    LibraryMutationStore(activity).cancelAdd(file.name, expectedSha256).also { withdrawn ->
                        if (!withdrawn) LibraryRemovalStore(activity).enqueue(file.name, expectedSha256)
                    }
                }
            }
            if (saved.isFailure) {
                Log.e(TAG, "Could not queue Library removal for ${file.name}", saved.exceptionOrNull())
                Toast.makeText(
                    activity,
                    R.string.home_remove_from_library_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            sourcePdfIndex.invalidate()
            onChanged()
            onPendingChangesChanged()
            Toast.makeText(
                activity,
                if (saved.getOrNull() == true) {
                    R.string.home_remove_from_library_withdrawn
                } else {
                    R.string.home_remove_from_library_queued
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun performDelete(item: HomeItem, record: PdfRecord?) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                if (item.uri.scheme == "file") {
                    item.uri.path?.let { File(it).delete() } ?: false
                } else {
                    runCatching {
                        DocumentsContract.deleteDocument(activity.contentResolver, item.uri)
                    }.getOrDefault(false)
                }
            }
            if (!deleted) {
                Toast.makeText(activity, R.string.home_delete_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }

            historyCleaner.deleteDocument(record?.hash ?: item.hash)
            coverCache.invalidate(item.coverKey)
            onChanged()
        }
    }

    companion object {
        private const val TAG = "RecordOptionsDialog"
        private const val COVER_WIDTH_PX = 256
    }
}

internal enum class RecordDeleteAction {
    DELETE_LOCAL_FILE,
    QUEUE_LIBRARY_REMOVAL,
    UNAVAILABLE,
}

internal fun recordDeleteAction(libraryKind: LibraryPdfKind?): RecordDeleteAction = when (libraryKind) {
    null -> RecordDeleteAction.DELETE_LOCAL_FILE
    LibraryPdfKind.UNLINKED -> RecordDeleteAction.QUEUE_LIBRARY_REMOVAL
    LibraryPdfKind.SOURCE_LINKED -> RecordDeleteAction.UNAVAILABLE
}

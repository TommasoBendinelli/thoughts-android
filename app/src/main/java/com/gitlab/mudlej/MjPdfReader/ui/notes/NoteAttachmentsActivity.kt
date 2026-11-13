// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.notes

import android.content.Intent
import android.os.Bundle
import android.graphics.BitmapFactory
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.*
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The same attachment manager serves unfinished captures and saved logical notes. */
class NoteAttachmentsActivity : AppCompatActivity() {
    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private lateinit var store: NoteAttachmentStore
    private var items = emptyList<NoteAttachment>()
    private var busy = false
    private val noteId get() = requireNotNull(intent.getStringExtra("note_id"))
    private val draft get() = intent.getBooleanExtra("draft", false)
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) work {
            require(items.size + uris.size <= 100) { "A note can contain up to 100 attachments" }
            // Persist each successful import, even if a later provider/file fails.
            uris.forEach { uri ->
                val item = withContext(Dispatchers.IO) { store.import(uri) }
                change { it + item }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteAttachmentStore(applicationContext)
        title = getString(R.string.note_attachments)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(NestedScrollView(this).apply { addView(column) })
        onBackPressedDispatcher.addCallback(this) { if (!busy) finish() }
        work {
            items = withContext(Dispatchers.IO) {
                if (draft) store.draft(noteId)
                else NoteAttachment.read(FedoraRepository(applicationContext).effectiveLogicalCommentPayloads()
                    .firstOrNull { it.get("comment_id")?.asString == noteId && it.get("deleted")?.asBoolean != true }
                    ?: error("This note is no longer available"))
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { if (!busy) finish(); return true }

    private suspend fun change(transform: (List<NoteAttachment>) -> List<NoteAttachment>) {
        items = withContext(Dispatchers.IO) {
            if (draft) {
                check(NoteDrafts.unfinished(applicationContext).any { it.id == noteId }) { "This draft has already been saved" }
                transform(store.draft(noteId)).also { store.keepDraft(noteId, it) }
            } else {
                var changed = emptyList<NoteAttachment>()
                LogicalCommentStore(applicationContext).update(noteId) { current ->
                    changed = transform(NoteAttachment.read(current))
                    NoteAttachment.write(requireNotNull(logicalCommentMutationPayload(current, noteId, deleted = false)), changed)
                }
                changed
            }
        }
    }

    private fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        render()
        lifecycleScope.launch {
            try { action() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                Toast.makeText(this@NoteAttachmentsActivity, error.message ?: getString(R.string.quick_note_failed), Toast.LENGTH_LONG).show()
            } finally { busy = false; render() }
        }
    }

    private fun render() {
        column.removeAllViews()
        fun button(label: String, action: () -> Unit) = MaterialButton(this).apply {
            text = label; isAllCaps = false; isEnabled = !busy
            setOnClickListener { action() }
        }
        column.addView(button(getString(R.string.note_attach_pictures)) { picker.launch(arrayOf("image/*", "video/*")) })
        column.addView(button(getString(R.string.note_attach_files)) { picker.launch(arrayOf("*/*")) })
        status = TextView(this).apply {
            text = getString(if (busy) R.string.note_attachment_working else R.string.note_attachment_sync_hint)
        }
        column.addView(status)
        if (items.isEmpty() && !busy) column.addView(TextView(this).apply { setText(R.string.note_attachments_empty) })
        items.forEach { item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (item.mimeType.startsWith("image/") && store.file(item).isFile) {
                val preview = ImageView(this).apply {
                    contentDescription = item.name
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                row.addView(preview, LinearLayout.LayoutParams(-1, (140 * resources.displayMetrics.density).toInt()))
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        runCatching {
                            val path = store.file(item).absolutePath
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(path, options)
                            options.inSampleSize = 1
                            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
                            options.inJustDecodeBounds = false
                            BitmapFactory.decodeFile(path, options)
                        }.getOrNull()
                    }
                    preview.setImageBitmap(bitmap)
                }
            }
            row.addView(button("${item.name} · ${android.text.format.Formatter.formatShortFileSize(this, item.size)}") {
                work {
                    val file = withContext(Dispatchers.IO) { FedoraRepository(applicationContext).downloadNoteAttachment(item) }
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file, item.name)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, item.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newRawUri(item.name, uri)
                    }, item.name))
                }
            })
            row.addView(button(getString(R.string.note_attachment_remove)) {
                MaterialAlertDialogBuilder(this).setMessage(getString(R.string.note_attachment_remove_question, item.name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.note_attachment_remove) { _, _ -> work { change { it.filterNot { existing -> existing.id == item.id } } } }
                    .show()
            })
            column.addView(row)
        }
    }
}

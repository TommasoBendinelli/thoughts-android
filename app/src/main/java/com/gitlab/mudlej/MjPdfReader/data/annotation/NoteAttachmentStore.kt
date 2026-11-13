// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal class NoteAttachmentStore(private val context: Context) {
    private val root get() = File(context.filesDir, "note_attachments").apply { mkdirs() }
    private val drafts get() = context.getSharedPreferences("note_attachment_drafts", Context.MODE_PRIVATE)
    fun file(item: NoteAttachment) = File(root, item.sha256)
    fun draft(id: String): List<NoteAttachment> = drafts.getString(id, null)?.let {
        NoteAttachment.read(JsonParser.parseString(it).asJsonObject)
    }.orEmpty()
    fun keepDraft(id: String, items: List<NoteAttachment>) {
        check(drafts.edit().putString(id, NoteAttachment.write(JsonObject(), items).toString()).commit())
    }
    fun clearDraft(id: String) { check(drafts.edit().remove(id).commit()) }

    fun import(uri: Uri): NoteAttachment {
        val resolver = context.contentResolver
        var name = "Attachment"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        name = name.map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }.joinToString("").take(255).ifBlank { "Attachment" }
        val mime = resolver.getType(uri)?.takeIf { it.matches(Regex("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")) } ?: "application/octet-stream"
        val temporary = File.createTempFile("import-", ".part", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            requireNotNull(resolver.openInputStream(uri)) { "Cannot read this file" }.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= NoteAttachment.MAX_BYTES) { "Attachments must be 100 MB or smaller" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val item = NoteAttachment(UUID.randomUUID().toString(), name, mime, digest.digest().joinToString("") { "%02x".format(it) }, size)
            val destination = file(item)
            if (!destination.exists()) check(temporary.renameTo(destination)) { "Cannot save attachment" }
            return item
        } finally { temporary.delete() }
    }

    fun verify(item: NoteAttachment, candidate: File = file(item)): Boolean {
        if (!candidate.isFile || candidate.length() != item.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        candidate.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == item.sha256
    }

    fun receive(item: NoteAttachment, download: (File) -> Unit): File {
        if (verify(item)) return file(item)
        val temporary = File.createTempFile("download-", ".part", root)
        try {
            download(temporary)
            check(verify(item, temporary)) { "Attachment download is incomplete or damaged" }
            check(temporary.renameTo(file(item))) { "Cannot save attachment" }
            return file(item)
        } finally { temporary.delete() }
    }
}

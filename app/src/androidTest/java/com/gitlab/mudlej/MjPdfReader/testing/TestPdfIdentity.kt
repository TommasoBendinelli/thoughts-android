// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.testing

import android.content.Context
import android.graphics.pdf.PdfDocument
import com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore
import java.io.File
import java.security.MessageDigest

/** Generated sandbox document with the same permanent-identity precondition as a real import. */
internal fun registerTestPdf(context: Context, filename: String): String {
    check(context.packageName.endsWith(".sandbox"))
    val file = File(context.filesDir, filename)
    val pdf = PdfDocument()
    try {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create())
        pdf.finishPage(page)
        file.outputStream().use(pdf::writeTo)
    } finally { pdf.close() }
    val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    DocumentIdentityStore().registerImport(filename, hash, file)
    return hash
}

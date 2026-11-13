// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.share

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerAddress
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SharedCitationInstructions(
    val uri: Uri,
    val fileName: String,
)

class CitationInstructionExporter(private val context: Context) {

    suspend fun export(
        citationContext: ThoughtsCitationContext,
        selection: PdfPageSelection,
    ): SharedCitationInstructions = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        purgeExpiredCitationFiles(directory)
        val fileName = citationInstructionFileName(citationContext, selection)
        val target = File(directory, fileName)
        val temporary = File(directory, "$fileName.part")
        temporary.delete()
        try {
            temporary.writeText(
                buildThoughtsCitationPrompt(
                    citationContext,
                    selection,
                    ThoughtsServerAddress.citationLinkHost(ThoughtsServerSecretStore(context).baseUrl()),
                ),
                Charsets.UTF_8,
            )
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
        SharedCitationInstructions(
            uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            ),
            fileName = fileName,
        )
    }

    companion object {
        private const val SHARE_DIRECTORY = "share"
        internal const val CACHE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}

internal fun citationInstructionFileName(
    context: ThoughtsCitationContext,
    selection: PdfPageSelection,
): String = "${CITATION_FILE_PREFIX}${context.documentId}_" +
    "${thoughtsCitationFirstOriginalPage(selection)}_${context.versionSha256.take(12)}.txt"

internal fun purgeExpiredCitationFiles(
    directory: File,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val expiredBefore = nowMillis - CitationInstructionExporter.CACHE_MAX_AGE_MILLIS
    directory.listFiles()?.filter {
        it.name.startsWith(CITATION_FILE_PREFIX) &&
            (it.name.endsWith(".txt") || it.name.endsWith(".txt.part")) &&
            it.lastModified() < expiredBefore
    }?.forEach(File::delete)
}

private const val CITATION_FILE_PREFIX = "THOUGHTS_CITATION_INSTRUCTIONS_"

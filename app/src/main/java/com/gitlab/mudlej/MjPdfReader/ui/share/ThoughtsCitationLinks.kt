// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.share

import android.net.Uri
import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerAddress
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID

data class ThoughtsCitationContext(
    val documentId: String,
    val versionSha256: String,
)

data class ThoughtsCitationLink(
    val documentId: String,
    val sharedPageNumber: Int,
    val firstOriginalPage: Int,
    val versionSha256: String,
) {
    val originalPageNumber: Int
        get() = Math.addExact(firstOriginalPage, sharedPageNumber - 1)
}

fun thoughtsCitationContextFor(
    uri: Uri?,
    sourcePdfIndex: SourceLinkedPdfIndex = SourceLinkedPdfIndex(),
): ThoughtsCitationContext? {
    val file = uri
        ?.takeIf { it.scheme == "file" }
        ?.path
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?: return null
    val canonicalDirectory = PhoneRenderQueue.defaultCanonicalPdfDirectory()
    val snapshot = sourcePdfIndex.snapshot()
    if (snapshot.libraryKindFor(file, canonicalDirectory) == null) return null
    val sha256 = snapshot.canonicalLibraryPdfSha256(file, canonicalDirectory)
        ?.takeIf(SHA256_REGEX::matches)
        ?: return null
    return ThoughtsCitationContext(
        documentId = DocumentIdentityStore().byFilename(file.name)?.get("document_id")?.asString ?: return null,
        versionSha256 = sha256,
    )
}

fun buildThoughtsCitationPrompt(
    context: ThoughtsCitationContext,
    selection: PdfPageSelection,
    linkHost: String = ThoughtsServerAddress.PUBLIC_HOST,
): String {
    val firstOriginalPage = thoughtsCitationFirstOriginalPage(selection)
    val link = "https://$linkHost/open/document/${context.documentId}" +
        "?page=N&first=$firstOriginalPage&version=${context.versionSha256}"
    return "Instructions for ChatGPT\n\n" +
        "When referring to a page in the attached PDF, include:\n" +
        "[Open page N in Thoughts]($link)\n\n" +
        "Replace N with the cited one-based page number from the attached PDF.\n" +
        "Keep the document ID, first-page value, and version unchanged."
}

internal fun thoughtsCitationFirstOriginalPage(selection: PdfPageSelection): Int =
    when (selection) {
        PdfPageSelection.All -> 1
        is PdfPageSelection.Range -> selection.firstPageIndex + 1
    }

/**
 * Parses a citation link naming one of [linkHosts] (the build's public host, its legacy hosts and
 * the host of [configuredBaseUrl]), or the `thoughts://` fallback.
 */
fun parseThoughtsCitationLink(
    value: String,
    configuredBaseUrl: String? = null,
    linkHosts: Set<String> = ThoughtsServerAddress.citationLinkHosts(configuredBaseUrl),
): ThoughtsCitationLink? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null

    val documentId = when {
        uri.scheme == "https" &&
            uri.host?.lowercase(Locale.ROOT) in linkHosts -> {
            documentIdFromPath(uri.rawPath, "/open/document/")
        }
        uri.scheme == THOUGHTS_CUSTOM_SCHEME && uri.host == "open" -> {
            documentIdFromPath(uri.rawPath, "/document/")
        }
        else -> null
    } ?: return null
    if (!isCanonicalUuid(documentId)) return null

    val query = parseStrictQuery(uri.rawQuery) ?: return null
    if (query.keys != setOf("page", "first", "version")) return null
    val page = query["page"]?.toPositiveInt() ?: return null
    val first = query["first"]?.toPositiveInt() ?: return null
    val version = query["version"]?.takeIf(SHA256_REGEX::matches) ?: return null
    if (first.toLong() + page.toLong() - 1L > Int.MAX_VALUE) return null

    return ThoughtsCitationLink(documentId, page, first, version)
}

private fun documentIdFromPath(rawPath: String?, prefix: String): String? {
    val path = rawPath ?: return null
    if (!path.startsWith(prefix)) return null
    return path.removePrefix(prefix).takeIf { it.isNotEmpty() && '/' !in it }
}

private fun parseStrictQuery(rawQuery: String?): Map<String, String>? {
    val query = rawQuery ?: return null
    if (query.isBlank()) return null
    val result = linkedMapOf<String, String>()
    for (part in query.split('&')) {
        val separator = part.indexOf('=')
        if (separator <= 0 || separator == part.lastIndex) return null
        val key = part.substring(0, separator)
        val value = part.substring(separator + 1)
        if (!QUERY_TOKEN_REGEX.matches(key) || !QUERY_TOKEN_REGEX.matches(value)) return null
        if (result.put(key, value) != null) return null
    }
    return result
}

private fun String.toPositiveInt(): Int? = toIntOrNull()?.takeIf { it > 0 }

private fun isCanonicalUuid(value: String): Boolean =
    UUID_REGEX.matches(value) && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

const val THOUGHTS_CUSTOM_SCHEME = "thoughts"

private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val QUERY_TOKEN_REGEX = Regex("^[A-Za-z0-9._~-]+$")

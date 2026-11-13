// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

/**
 * Which HTML editions are downloaded on their own after a Sync. The phone plan is small and an
 * edition can be several MB (math-heavy LaTeX), downloaded again after each rebuild, so:
 *
 * - on an unmetered network (Wi-Fi) every ready edition is downloaded;
 * - on a metered network only the editions of documents read in the HTML view on this phone (the
 *   reader's remembered PDF | HTML choice, [PREFERENCES]) are downloaded. The others wait for Wi-Fi,
 *   or for the moment the reader switches that document to HTML ([HtmlEditionSync.downloadNow]).
 */
object HtmlEditionDownloadPolicy {
    /** The reader's SharedPreferences: `mode:<document id or filename>` = [MODE_HTML] when read as HTML. */
    const val PREFERENCES = "html_view"
    const val MODE_HTML = "html"
    private const val MODE_PREFIX = "mode:"

    fun modeKey(documentKey: String) = MODE_PREFIX + documentKey

    fun downloadAfterSync(unmetered: Boolean, openedInHtml: Boolean): Boolean = unmetered || openedInHtml

    /** The document keys (document id, or the filename without one) the reader shows as HTML. */
    fun htmlDocumentKeys(preferences: Map<String, *>): Set<String> = preferences.mapNotNullTo(mutableSetOf()) { (key, value) ->
        key.removePrefix(MODE_PREFIX).takeIf { key.startsWith(MODE_PREFIX) && value == MODE_HTML && it.isNotBlank() }
    }

    /** Whether the Library PDF [filename] (permanent id [documentId], if any) was read in the HTML view. */
    fun openedInHtml(filename: String, documentId: String?, htmlKeys: Set<String>): Boolean =
        (!documentId.isNullOrBlank() && documentId in htmlKeys) || filename in htmlKeys
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.links

object HtmlAttachmentLink {
    fun isPath(value: String): Boolean {
        val parts = value.split('/')
        return parts.size >= 3 && Regex("docs-[A-Za-z0-9_.-]+").matches(parts[0]) &&
            parts[1] in setOf("tex", "markdown") && parts.all { it.isNotEmpty() && !it.startsWith('.') } &&
            value.none { it.code < 32 || it in "\\\\?#%" } &&
            value.substringAfterLast('.').lowercase() in setOf("html", "htm")
    }
    fun parse(value: String): String? = value.takeIf { it.startsWith("thoughts-html:") }
        ?.removePrefix("thoughts-html:")?.let { raw ->
            if ('?' in raw || '#' in raw || raw.startsWith("//")) null
            else runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull()
        }?.takeIf(::isPath)
            // A PDF not rebuilt since this phone moved its connected folders links to the old name.
            ?.let { com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames.followLocal(it) }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.importing

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import org.jsoup.Jsoup

/** Optional title lookup. A missing title must never turn a paper ID into a title. */
class PaperMetadata(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    data class Details(val paper: PaperWebsite, val title: String?)

    fun title(paper: PaperWebsite, checkActive: () -> Unit = {}): String? = lookup(paper, checkActive).title

    fun lookup(paper: PaperWebsite, checkActive: () -> Unit = {}): Details {
        val urls = if (paper.websiteType == "OpenReview") {
            val id = URL(paper.pdfUrl).query
            listOf("https://api2.openreview.net/notes?$id", "https://api.openreview.net/notes?$id", paper.pageUrl)
        } else listOf(paper.pageUrl)
        for (url in urls) {
            checkActive()
            try {
                val body = read(url, checkActive) ?: continue
                val title = if (url.contains("/notes?")) openReviewTitle(body) else htmlTitle(body)
                val resolved = if (paper.websiteType in setOf("PMLR", "NeurIPS Proceedings", "ICLR Proceedings")) {
                    val link = Jsoup.parse(body, paper.pageUrl).selectFirst("meta[name=citation_pdf_url]")?.attr("content")
                    val candidate = link?.let { runCatching { PaperWebsite.resolve(URL(URL(paper.pageUrl), it).toString()) }.getOrNull() }
                    // Only accept the main PDF for this same paper, never supplements or another paper.
                    if (candidate?.pageUrl == paper.pageUrl) candidate else paper
                } else paper
                if (title != null || resolved != paper) return Details(resolved, title)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* PDF metadata or manual naming remains available. */ }
        }
        return Details(paper, null)
    }

    private fun read(address: String, checkActive: () -> Unit): String? {
        var url = URL(address)
        repeat(6) {
            checkActive()
            require(url.protocol == "https" && url.userInfo == null)
            val connection = openConnection(url).apply {
                connectTimeout = 10000; readTimeout = 10000; instanceFollowRedirects = false
                setRequestProperty("Accept", "text/html, application/json")
                setRequestProperty("User-Agent", "Thoughts/3.0 (Android; user-requested paper metadata)")
            }
            try {
                when (connection.responseCode) {
                    301, 302, 303, 307, 308 -> url = URL(url, connection.getHeaderField("Location") ?: return null)
                    200 -> return connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 2 * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                        checkActive()
                        output.toString("UTF-8")
                    }
                    else -> return null
                }
            } finally { connection.disconnect() }
        }
        return null
    }

    companion object {
        fun usableTitle(raw: String?): String? = raw?.replace(Regex("[\\s\\u00a0]+"), " ")?.trim()
            ?.takeIf { it.isNotEmpty() && it.none { c -> c.code < 32 } }
            ?.takeUnless { it.lowercase() in setOf("untitled", "untitled document", "document", "paper", "main", "main.pdf") ||
                it.matches(Regex("(?i)(?:arxiv[ :]+)?[0-9]{4}\\.[0-9]{4,5}(?:v[0-9]+)?(?:\\.pdf)?")) }

        fun htmlTitle(body: String): String? {
            val document = Jsoup.parse(body)
            // Never use a generic page title: it may describe a login or browser challenge.
            return usableTitle(document.selectFirst("meta[name=citation_title]")?.attr("content"))
        }

        fun openReviewTitle(body: String): String? {
            val notes = JsonParser.parseString(body).asJsonObject.getAsJsonArray("notes") ?: return null
            if (notes.size() != 1) return null
            val title = notes[0].asJsonObject.getAsJsonObject("content")?.get("title") ?: return null
            return usableTitle(if (title.isJsonObject) title.asJsonObject.get("value")?.asString else title.asString)
        }
    }
}

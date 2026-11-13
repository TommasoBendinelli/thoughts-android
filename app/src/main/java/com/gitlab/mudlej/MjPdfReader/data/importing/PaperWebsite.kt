// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.importing

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

data class PaperWebsite(
    val pdfUrl: String,
    val displayName: String,
    val websiteType: String = if (URI(pdfUrl).host == "arxiv.org") "arXiv" else "OpenReview",
    val pageUrl: String = if (websiteType == "arXiv") pdfUrl.replace("/pdf/", "/abs/")
        else pdfUrl.replace("/pdf?", "/forum?"),
) {
    companion object {
        fun resolve(input: String): PaperWebsite {
            val raw = input.trim()
            val uri = try { URI(if (raw.contains("://")) raw else "https://$raw") }
            catch (_: Exception) { throw IllegalArgumentException("Enter an OpenReview, arXiv, NeurIPS Proceedings, ICLR Proceedings, or PMLR paper link") }
            require(uri.scheme.lowercase(Locale.ROOT) in setOf("http", "https") &&
                uri.rawUserInfo == null && uri.port == -1) { "Enter an OpenReview, arXiv, NeurIPS Proceedings, ICLR Proceedings, or PMLR paper link" }
            return when (uri.host?.lowercase(Locale.ROOT)) {
                "openreview.net", "www.openreview.net" -> {
                    require(uri.path.trimEnd('/') in setOf("/pdf", "/forum")) { "Use an OpenReview paper or PDF link" }
                    val ids = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
                        .filter { it[0] == "id" && it.size == 2 }.map { URLDecoder.decode(it[1], "UTF-8") }
                    require(ids.size == 1 && ids.single().matches(Regex("[A-Za-z0-9_-]+"))) { "The OpenReview link needs a paper ID" }
                    PaperWebsite("https://openreview.net/pdf?id=${ids.single()}", "OpenReview ${ids.single()}")
                }
                "arxiv.org", "www.arxiv.org", "export.arxiv.org" -> {
                    val match = Regex("^/(?:abs|pdf|html)/(.+?)(?:\\.pdf)?/?$").matchEntire(uri.path)
                    val id = match?.groupValues?.get(1).orEmpty()
                    require(id.matches(Regex("(?:[0-9]{4}\\.[0-9]{4,5}|[a-zA-Z-]+(?:\\.[A-Z]{2})?/[0-9]{7})(?:v[1-9][0-9]*)?"))) {
                        "Use an arXiv abstract or PDF link"
                    }
                    PaperWebsite("https://arxiv.org/pdf/$id", "arXiv $id")
                }
                "proceedings.neurips.cc", "papers.neurips.cc", "proceedings.nips.cc", "papers.nips.cc" ->
                    proceedings(uri, "NeurIPS", "proceedings.neurips.cc")
                "proceedings.iclr.cc" -> proceedings(uri, "ICLR", "proceedings.iclr.cc")
                "proceedings.mlr.press" -> {
                    val page = Regex("^/v([0-9]+)/([A-Za-z0-9_-]+)\\.html/?$").matchEntire(uri.path)
                    val pdf = Regex("^/v([0-9]+)/([A-Za-z0-9_-]+)(?:/([A-Za-z0-9_-]+))?\\.pdf$").matchEntire(uri.path)
                    require(page != null || pdf != null) { "Use a PMLR paper or PDF link" }
                    val match = page ?: pdf!!
                    val volume = match.groupValues[1]
                    val id = match.groupValues[2]
                    require(pdf == null || pdf.groupValues[3].let { it.isEmpty() || it == id }) { "Use a PMLR paper PDF link" }
                    val base = "https://proceedings.mlr.press/v$volume"
                    PaperWebsite(if (pdf != null) "https://proceedings.mlr.press${uri.path}" else "$base/$id/$id.pdf",
                        "PMLR $id", "PMLR", "$base/$id.html")
                }
                "raw.githubusercontent.com" -> {
                    val match = Regex("^/mlresearch/v([0-9]+)/(?:main|master)/assets/([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)\\.pdf$")
                        .matchEntire(uri.path)
                    require(match != null && match.groupValues[2] == match.groupValues[3]) { "Use a PMLR paper PDF link" }
                    val volume = match.groupValues[1]
                    val id = match.groupValues[2]
                    PaperWebsite("https://raw.githubusercontent.com${uri.path}", "PMLR $id", "PMLR",
                        "https://proceedings.mlr.press/v$volume/$id.html")
                }
                else -> throw IllegalArgumentException("Supported websites: OpenReview, arXiv, NeurIPS Proceedings, ICLR Proceedings, and PMLR")
            }
        }

        private fun proceedings(uri: URI, label: String, host: String): PaperWebsite {
            val match = Regex("^/(?:paper|paper_files/paper)/([0-9]{4})/(?:hash/([a-fA-F0-9]{32})-Abstract(-[A-Za-z0-9_-]+)?\\.html|file/([a-fA-F0-9]{32})-Paper(-[A-Za-z0-9_-]+)?\\.pdf)/?$")
                .matchEntire(uri.path)
            requireNotNull(match) { "Use a $label Proceedings paper or PDF link" }
            val year = match.groupValues[1]
            val id = match.groupValues[2].ifEmpty { match.groupValues[4] }.lowercase(Locale.ROOT)
            val track = match.groupValues[3].ifEmpty { match.groupValues[5] }
            val base = "https://$host/paper_files/paper/$year"
            return PaperWebsite("$base/file/$id-Paper$track.pdf", "$label $year $id", "$label Proceedings",
                "$base/hash/$id-Abstract$track.html")
        }
    }
}

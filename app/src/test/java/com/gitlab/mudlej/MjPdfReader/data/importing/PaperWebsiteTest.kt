// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.importing

import org.junit.Assert.*
import org.junit.Test

class PaperWebsiteTest {
    @Test fun openReviewPaperAndPdfLinksRemoveTracking() {
        listOf("https://openreview.net/pdf?id=iEmRSwdzyw&utm_source=chatgpt.com",
            "https://openreview.net/forum?id=iEmRSwdzyw#discussion",
            " openreview.net/pdf?id=iEmRSwdzyw ").forEach {
            assertEquals("https://openreview.net/pdf?id=iEmRSwdzyw", PaperWebsite.resolve(it).pdfUrl)
        }
    }

    @Test fun arxivSupportsAbstractPdfHtmlAndLegacyIdsWithVersions() {
        mapOf("https://arxiv.org/abs/2501.12345v2" to "2501.12345v2",
            "https://arxiv.org/pdf/1706.03762.pdf?download=true" to "1706.03762",
            "http://export.arxiv.org/pdf/hep-th/9901001v3" to "hep-th/9901001v3",
            "arxiv.org/html/2501.12345v1" to "2501.12345v1",
            "https://arxiv.org/abs/math.GT/0309136" to "math.GT/0309136").forEach { (link, id) ->
            assertEquals("https://arxiv.org/pdf/$id", PaperWebsite.resolve(link).pdfUrl)
        }
    }

    @Test fun neuripsSupportsLegacyAndModernPagesAndPdfs() {
        val id = "c9450295fd667740a39a68148fc17f6e"
        for (host in listOf("proceedings.neurips.cc", "papers.neurips.cc", "papers.nips.cc", "proceedings.nips.cc")) {
            for (prefix in listOf("paper", "paper_files/paper")) {
                for (track in listOf("", "-Conference", "-Datasets_and_Benchmarks")) {
                    val base = "https://proceedings.neurips.cc/paper_files/paper/2023"
                    for (path in listOf("hash/$id-Abstract$track.html", "file/$id-Paper$track.pdf")) {
                        val paper = PaperWebsite.resolve("https://$host/$prefix/2023/$path?utm_source=test#paper")
                        assertEquals("$base/file/$id-Paper$track.pdf", paper.pdfUrl)
                        assertEquals("$base/hash/$id-Abstract$track.html", paper.pageUrl)
                        assertEquals("NeurIPS Proceedings", paper.websiteType)
                    }
                }
            }
        }
    }

    @Test fun iclrSupportsPagesAndPdfs() {
        // Regression: a user-supplied ICLR 2026 conference PDF link.
        val regression = PaperWebsite.resolve(
            "https://proceedings.iclr.cc/paper_files/paper/2026/file/93f3ac5bb6d24ed7cec95ae11209ca34-Paper-Conference.pdf")
        assertEquals("https://proceedings.iclr.cc/paper_files/paper/2026/file/93f3ac5bb6d24ed7cec95ae11209ca34-Paper-Conference.pdf", regression.pdfUrl)
        assertEquals("https://proceedings.iclr.cc/paper_files/paper/2026/hash/93f3ac5bb6d24ed7cec95ae11209ca34-Abstract-Conference.html", regression.pageUrl)
        assertEquals("ICLR Proceedings", regression.websiteType)

        val id = "93f3ac5bb6d24ed7cec95ae11209ca34"
        for (prefix in listOf("paper", "paper_files/paper")) {
            for (track in listOf("", "-Conference", "-Datasets_and_Benchmarks")) {
                val base = "https://proceedings.iclr.cc/paper_files/paper/2026"
                for (path in listOf("hash/$id-Abstract$track.html", "file/$id-Paper$track.pdf")) {
                    val paper = PaperWebsite.resolve("https://proceedings.iclr.cc/$prefix/2026/$path?utm_source=test#paper")
                    assertEquals("$base/file/$id-Paper$track.pdf", paper.pdfUrl)
                    assertEquals("$base/hash/$id-Abstract$track.html", paper.pageUrl)
                    assertEquals("ICLR Proceedings", paper.websiteType)
                }
            }
        }
    }

    @Test fun pmlrPagesAndPdfHostsKeepTheMetadataPage() {
        listOf("https://proceedings.mlr.press/v235/zheng24e.html",
            "https://proceedings.mlr.press/v235/zheng24e/zheng24e.pdf",
            "https://proceedings.mlr.press/v235/zheng24e.pdf",
            "https://raw.githubusercontent.com/mlresearch/v235/main/assets/zheng24e/zheng24e.pdf",
            "https://raw.githubusercontent.com/mlresearch/v235/master/assets/zheng24e/zheng24e.pdf").forEach {
            val paper = PaperWebsite.resolve("$it?download=1#page=2")
            assertEquals("PMLR", paper.websiteType)
            assertEquals("https://proceedings.mlr.press/v235/zheng24e.html", paper.pageUrl)
            assertFalse(paper.pdfUrl.contains("?"))
        }
    }

    @Test fun proceedingsRejectIndexesSupplementsAndUnrelatedHosts() {
        listOf("https://proceedings.mlr.press/v235/", "https://proceedings.mlr.press/v235/a/b.pdf",
            "https://proceedings.mlr.press.evil.com/v235/a.html",
            "https://raw.githubusercontent.com/other/v235/main/assets/a/a.pdf",
            "https://raw.githubusercontent.com/mlresearch/v235/main/assets/a/b.pdf",
            "https://proceedings.neurips.cc/paper_files/paper/2023",
            "https://proceedings.neurips.cc/paper_files/paper/2023/file/c9450295fd667740a39a68148fc17f6e-Supplemental-Conference.pdf",
            "https://proceedings.iclr.cc/paper_files/paper/2026",
            "https://proceedings.iclr.cc/paper_files/paper/2026/file/93f3ac5bb6d24ed7cec95ae11209ca34-Supplemental-Conference.pdf",
            "https://proceedings.mlr.press/v235/%2e%2e.html").forEach {
            assertTrue("Must reject $it", runCatching { PaperWebsite.resolve(it) }.isFailure)
        }
    }

    @Test fun invalidLinksAreRejectedBeforeDownloading() {
        listOf("", "https://openreview.net", "https://openreview.net/pdf", "https://openreview.net/pdf?id=a&id=b",
            "https://openreview.net/pdf?id=../../secret", "https://openreview.net.evil.com/pdf?id=a",
            "https://user@openreview.net/pdf?id=a", "file://openreview.net/pdf?id=a",
            "https://arxiv.org/search/test", "https://arxiv.org/abs/hello", "https://arxiv.org:8080/pdf/2501.12345",
            "https://arxiv.org/abs/2501.12345v0").forEach {
            assertTrue("Must reject $it", runCatching { PaperWebsite.resolve(it) }.isFailure)
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.importing

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PaperMetadataTest {
    @Test fun citationTitleDecodesEntitiesAndWhitespaceRegardlessOfAttributeOrder() {
        assertEquals("A & B: \"Learning\"", PaperMetadata.htmlTitle("""
            <html><title>arXiv generic page</title>
            <meta content=' A &amp; B: &quot;Learning&quot; &#10; ' name="citation_title"></html>
        """))
    }

    @Test fun arxivExampleAndVersionedLinksKeepTheCorrectSiteAndMetadataPage() {
        val paper = PaperWebsite.resolve("https://arxiv.org/pdf/2506.03106v7.pdf")
        assertEquals("arXiv", paper.websiteType)
        assertEquals("https://arxiv.org/abs/2506.03106v7", paper.pageUrl)
        val title = "Critique-GRPO: Advancing LLM Reasoning with Natural Language and Numerical Feedback"
        assertEquals(title, PaperMetadata.htmlTitle("<meta name='citation_title' content='$title'>"))
        val review = PaperWebsite.resolve("https://openreview.net/pdf?id=iEmRSwdzyw")
        assertEquals("OpenReview", review.websiteType)
        assertEquals("https://openreview.net/forum?id=iEmRSwdzyw", review.pageUrl)
    }

    @Test fun bothOpenReviewApiFormatsAreSupported() {
        assertEquals("Paper v2", PaperMetadata.openReviewTitle("""{"notes":[{"content":{"title":{"value":"Paper v2"}}}]}"""))
        assertEquals("Paper v1", PaperMetadata.openReviewTitle("""{"notes":[{"content":{"title":"Paper v1"}}]}"""))
    }

    @Test fun missingTitlesAndBrowserChallengesCannotBecomeDocumentNames() {
        assertNull(PaperMetadata.htmlTitle("<title>Verifying your browser | OpenReview</title>"))
        assertNull(PaperMetadata.openReviewTitle("""{"notes":[]}"""))
        listOf(null, "", " Untitled ", "main.pdf", "arXiv 2506.03106v7").forEach { assertNull(PaperMetadata.usableTitle(it)) }
    }

    @Test fun networkFailureAllowsPdfFallbackButCancellationPropagates() {
        val paper = PaperWebsite.resolve("https://arxiv.org/abs/2506.03106")
        val metadata = PaperMetadata { throw java.io.IOException("Offline") }
        assertNull(metadata.title(paper))
        try { metadata.title(paper) { throw CancellationException() }; fail("Must cancel") }
        catch (_: CancellationException) { }
    }

    @Test fun openReviewFallsBackToLegacyApiAndDisconnectsRequests() {
        val requests = mutableListOf<String>()
        var closed = 0
        val metadata = PaperMetadata { url ->
            requests.add(url.toString())
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() { closed++ }
                override fun getResponseCode() = if (url.host == "api2.openreview.net") 404 else 200
                override fun getInputStream() = ByteArrayInputStream("""{"notes":[{"content":{"title":"Legacy title"}}]}""".toByteArray())
            }
        }
        assertEquals("Legacy title", metadata.title(PaperWebsite.resolve("https://openreview.net/forum?id=abc")))
        assertEquals(listOf("https://api2.openreview.net/notes?id=abc", "https://api.openreview.net/notes?id=abc"), requests)
        assertEquals(2, closed)
    }

    private fun htmlMetadata(body: String) = PaperMetadata { url -> object : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() = Unit
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    } }

    @Test fun pmlrUsesPublishedGithubPdfEvenWithoutTitle() {
        val paper = PaperWebsite.resolve("https://proceedings.mlr.press/v235/zheng24e.html")
        val pdf = "https://raw.githubusercontent.com/mlresearch/v235/main/assets/zheng24e/zheng24e.pdf"
        val details = htmlMetadata("<meta name='citation_pdf_url' content='$pdf'>").lookup(paper)
        assertEquals(pdf, details.paper.pdfUrl)
        assertEquals(paper.pageUrl, details.paper.pageUrl)
        assertNull(details.title)
    }

    @Test fun publishedRelativePdfAndTitleAreReadTogether() {
        val paper = PaperWebsite.resolve("https://proceedings.mlr.press/v10/smith10.html")
        val details = htmlMetadata("""<meta name="citation_pdf_url" content="smith10.pdf">
            <meta name="citation_title" content="Learning &amp; inference">""").lookup(paper)
        assertEquals("https://proceedings.mlr.press/v10/smith10.pdf", details.paper.pdfUrl)
        assertEquals("Learning & inference", details.title)
    }

    @Test fun iclrProceedingsReadsTitleAndKeepsPublishedPdf() {
        val paper = PaperWebsite.resolve(
            "https://proceedings.iclr.cc/paper_files/paper/2026/hash/93f3ac5bb6d24ed7cec95ae11209ca34-Abstract-Conference.html")
        val details = htmlMetadata("""
            <meta name="citation_title" content="Off-Trajectory Reasoning: Can LLMs Collaborate on Reasoning Trajectories?">
            <meta name="citation_pdf_url" content="https://proceedings.iclr.cc/paper_files/paper/2026/file/93f3ac5bb6d24ed7cec95ae11209ca34-Paper-Conference.pdf">
        """).lookup(paper)
        assertEquals("Off-Trajectory Reasoning: Can LLMs Collaborate on Reasoning Trajectories?", details.title)
        assertEquals("https://proceedings.iclr.cc/paper_files/paper/2026/file/93f3ac5bb6d24ed7cec95ae11209ca34-Paper-Conference.pdf", details.paper.pdfUrl)
        assertEquals(paper.pageUrl, details.paper.pageUrl)
        assertEquals("ICLR Proceedings", details.paper.websiteType)
    }

    @Test fun unrelatedPublishedPdfCannotReplaceMainPaper() {
        val paper = PaperWebsite.resolve("https://proceedings.mlr.press/v235/zheng24e.html")
        listOf("https://example.com/file.pdf", "https://proceedings.mlr.press/v235/other/other.pdf",
            "https://raw.githubusercontent.com/mlresearch/v234/main/assets/zheng24e/zheng24e.pdf").forEach { pdf ->
            assertEquals(paper, htmlMetadata("<meta name='citation_pdf_url' content='$pdf'>").lookup(paper).paper)
        }
        assertEquals(paper, PaperMetadata { throw java.io.IOException("Offline") }.lookup(paper).paper)
    }

    @Test fun downloaderCancellationRemovesPartialFile() {
        val directory = java.nio.file.Files.createTempDirectory("paper-cancel").toFile()
        try {
            var checks = 0
            val downloader = PaperPdfDownloader { url -> object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() = Unit
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream("%PDF-fixture".toByteArray())
            } }
            try {
                downloader.download(PaperWebsite.resolve("https://arxiv.org/abs/2506.03106"), directory) {
                    if (++checks == 3) throw CancellationException()
                }
                fail("Must cancel")
            } catch (_: CancellationException) { }
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.importing

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PaperPdfDownloaderTest {
    private val paper = PaperWebsite.resolve("https://openreview.net/pdf?id=iEmRSwdzyw")
    private class Response(val status: Int = 200, val body: String = "%PDF-1.4\nexample", val location: String? = null,
        val length: Long = -1) : HttpURLConnection(URL("https://openreview.net/pdf")) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body.byteInputStream()
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String) = if (name == "Location") location else null
    }
    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("paper-download-test").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun followsRelativeRedirectAndSavesExactPdfBytes() = inDirectory { directory ->
        val redirect = Response(302, location = "/pdf?id=redirected")
        val pdf = Response()
        val urls = mutableListOf<String>()
        val file = PaperPdfDownloader { url -> urls.add(url.toString()); if (urls.size == 1) redirect else pdf }.download(paper, directory)
        assertEquals(pdf.body, file.readText())
        assertEquals("https://openreview.net/pdf?id=redirected", urls.last())
        assertTrue(redirect.closed && pdf.closed)
    }

    @Test fun httpFailuresHtmlOversizeAndDowngradesLeaveNoTemporaryFiles() = inDirectory { directory ->
        listOf(Response(403), Response(404), Response(503), Response(body = "<html>Sign in</html>"),
            Response(length = 101L * 1024 * 1024), Response(302, location = "http://openreview.net/pdf?id=a")).forEach { response ->
            assertTrue(runCatching { PaperPdfDownloader { response }.download(paper, directory) }.isFailure)
            assertTrue(response.closed)
            assertEquals(0, directory.listFiles()!!.size)
        }
    }

    @Test fun cancellationCleansPartialDownload() = inDirectory { directory ->
        val response = Response()
        var checks = 0
        val result = runCatching { PaperPdfDownloader { response }.download(paper, directory) {
            if (++checks == 3) throw CancellationException()
        } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(response.closed)
        assertEquals(0, directory.listFiles()!!.size)
    }
}

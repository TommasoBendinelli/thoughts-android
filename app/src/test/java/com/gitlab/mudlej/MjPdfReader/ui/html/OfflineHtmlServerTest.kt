// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.html

import com.gitlab.mudlej.MjPdfReader.ui.links.HtmlAttachmentLink
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class OfflineHtmlServerTest {
    @Test fun stableLinksRejectTraversalAndRemoteUrls() {
        val path = "docs-example/tex/support/html/plot.html"
        assertEquals(path, HtmlAttachmentLink.parse("thoughts-html:$path"))
        assertEquals("docs-example/tex/source/sub/my plot.HTML", HtmlAttachmentLink.parse("thoughts-html:docs-example/tex/source/sub/my%20plot.HTML"))
        assertEquals("docs-example/markdown/source/plot.htm", HtmlAttachmentLink.parse("thoughts-html:docs-example/markdown/source/plot.htm"))
        assertNull(HtmlAttachmentLink.parse("thoughts-html:docs-example/tex/%2e%2e/secret.html"))
        assertNull(HtmlAttachmentLink.parse("thoughts-html:docs-example/tex/a%5cb.html"))
        for (value in listOf("thoughts-html:../secret.html", "thoughts-html:docs-example/tex/support/html/../a.html", "thoughts-html:$path?file=secret", "https://example.com/a.html")) assertNull(HtmlAttachmentLink.parse(value))
    }
    @Test fun rejectsInvalidUtf8BeforeServing() {
        val file = java.io.File.createTempFile("invalid-html", ".html")
        try {
            file.writeBytes(byteArrayOf(60, 112, 62, 0xc3.toByte()))
            assertThrows(java.nio.charset.MalformedInputException::class.java) { OfflineHtmlServer(file) }
        } finally { file.delete() }
    }
    @Test fun streamsFullSizeFile() {
        val file = java.io.File.createTempFile("html100", ".html")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(100_000_000) }
            OfflineHtmlServer(file).use { server ->
                val connection = URL(server.url).openConnection() as HttpURLConnection
                assertEquals(100_000_000L, connection.contentLengthLong)
                var count = 0L
                connection.inputStream.use { input ->
                    val chunk = ByteArray(65536)
                    while (true) { val n = input.read(chunk); if (n < 0) break; count += n }
                }
                assertEquals(100_000_000L, count)
            }
        } finally { file.delete() }
    }
    @Test fun servesExactOfflineBytesAndDeniesOtherRoutes() {
        val html = "<!doctype html><button onclick=\"this.textContent=42\">Calculate</button>".toByteArray()
        val file = java.io.File.createTempFile("offline-html", ".html").apply { writeBytes(html); deleteOnExit() }
        OfflineHtmlServer(file).use { server ->
            val url = URL(server.url)
            assertEquals("127.0.0.1", url.host)
            val connection = url.openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
            assertArrayEquals(html, connection.inputStream.use { it.readBytes() })
            assertTrue(connection.getHeaderField("Content-Security-Policy").contains("connect-src 'none'"))
            val denied = URL(url, "/secret").openConnection() as HttpURLConnection
            assertEquals(404, denied.responseCode)
            val head = url.openConnection() as HttpURLConnection
            head.requestMethod = "HEAD"
            assertEquals(200, head.responseCode)
            assertEquals(html.size, head.contentLength)
            assertEquals(0, head.inputStream.readBytes().size)
        }
    }
}

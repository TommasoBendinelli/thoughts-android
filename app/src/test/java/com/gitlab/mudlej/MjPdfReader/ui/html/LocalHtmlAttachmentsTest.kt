// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.html

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LocalHtmlAttachmentsTest {
    @Test fun onlyLocalHtmlLinksAreIntercepted() {
        assertTrue(LocalHtmlAttachments.handles("file:///Users/me/plot.html"))
        assertTrue(LocalHtmlAttachments.handles("file:///Users/me/My%20plot.HTM"))
        for (link in listOf("https://example.com/plot.html", "file://server/plot.html", "file:///a.pdf", "thoughts-html:docs-test/tex/support/html/a.html")) {
            assertFalse(link, LocalHtmlAttachments.handles(link))
        }
    }
    @Test fun copiesAreIsolatedByPdfAndExactLinkAndFailedReplacementPreservesCopy() {
        val root = Files.createTempDirectory("local-html-test").toFile()
        try {
            val store = LocalHtmlAttachments(root)
            val link = "file:///Users/me/plot.html"
            val html = "<html>Offline</html>".toByteArray()
            store.save("one.pdf", link, html.inputStream())
            assertArrayEquals(html, store.get("one.pdf", link)!!.readBytes())
            assertNull(store.get("two.pdf", link))
            assertNull(store.get("one.pdf", "file:///other/plot.html"))
            try { store.save("one.pdf", link, byteArrayOf().inputStream()); fail("Empty import accepted") }
            catch (_: IllegalArgumentException) { }
            assertArrayEquals(html, store.get("one.pdf", link)!!.readBytes())
            assertEquals(1, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class HtmlEditionStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A fake object endpoint: content-addressed bytes, counting downloads. */
    private class Server : HtmlEditionFetcher {
        val objects = mutableMapOf<String, ByteArray>()
        val downloads = mutableListOf<String>()
        var corrupt: String? = null
        override fun download(sha256: String, destination: File) {
            downloads += sha256
            val bytes = objects[sha256] ?: throw IOException("404 $sha256")
            destination.writeBytes(if (sha256 == corrupt) bytes + 1 else bytes)
        }
    }

    private fun edition(server: Server, html: String = "<p>Hello</p>", css: String = "p{}"): String {
        val files = JsonObject()
        listOf("index.html" to html, "style.css" to css, "assets/a.svg" to "<svg/>").forEach { (path, text) ->
            val bytes = text.toByteArray()
            server.objects[sha(bytes)] = bytes
            files.addProperty(path, sha(bytes))
        }
        val manifest = JsonObject().apply {
            addProperty("kind", "thoughts_html_edition"); addProperty("schema", 1)
            addProperty("entry", "index.html"); addProperty("source_format", "latex"); addProperty("source_map", "lines")
            add("files", files)
            add("anchors", com.google.gson.JsonArray().apply { add("card:c1:0") })
        }.toString().toByteArray()
        server.objects[sha(manifest)] = manifest
        return sha(manifest)
    }

    private val pdf = "a".repeat(64)

    @Test fun stateFromPublicationFields() {
        assertEquals(HtmlEditionState.Status.PENDING, HtmlEditionState.fromFields("pending", null, null).status)
        val ready = HtmlEditionState.fromFields("ready", "B".repeat(64), null)
        assertEquals(HtmlEditionState.Status.READY, ready.status)
        assertEquals("b".repeat(64), ready.editionSha256)
        assertEquals(HtmlEditionState.Status.FAILED, HtmlEditionState.fromFields("ready", "nope", null).status)
        assertEquals("tex4ht crashed", HtmlEditionState.fromFields("failed", null, " tex4ht crashed ").error)
        assertEquals(HtmlEditionState.NONE, HtmlEditionState.fromFields(null, null, null))
        // The publication list nests the fields under "html".
        val nested = JsonObject().apply { add("html", JsonObject().apply { addProperty("html_status", "pending") }) }
        assertEquals(HtmlEditionState.Status.PENDING, HtmlEditionState.fromJson(nested).status)
    }

    @Test fun installsVerifiedFilesAndReportsAvailability() {
        val server = Server()
        val edition = edition(server)
        val store = HtmlEditionStore(temp.newFolder())
        assertTrue(store.availability(pdf) is HtmlAvailability.Unavailable)
        store.recordState(pdf, "doc.pdf", HtmlEditionState(HtmlEditionState.Status.PENDING))
        assertEquals(HtmlAvailability.Building, store.availability(pdf))
        store.recordState(pdf, "doc.pdf", HtmlEditionState(HtmlEditionState.Status.READY, edition))
        assertEquals(HtmlAvailability.NotDownloaded, store.availability(pdf))
        val manifest = store.install(pdf, "doc.pdf", edition, server)
        assertEquals("index.html", manifest.entry)
        assertEquals(listOf("card:c1:0"), manifest.anchors)
        val ready = store.availability(pdf) as HtmlAvailability.Ready
        assertEquals("<p>Hello</p>", store.file(ready.edition, "index.html")!!.readText())
        assertNull(store.file(ready.edition, "../index.html"))
        assertNull(store.file(ready.edition, "missing.css"))
        // A second install downloads nothing.
        server.downloads.clear()
        store.install(pdf, "doc.pdf", edition, server)
        assertEquals(emptyList<String>(), server.downloads)
        // The same state again keeps it installed; a failed state replaces it.
        store.recordState(pdf, "doc.pdf", HtmlEditionState(HtmlEditionState.Status.READY, edition))
        assertTrue(store.availability(pdf) is HtmlAvailability.Ready)
        store.recordState(pdf, "doc.pdf", HtmlEditionState(HtmlEditionState.Status.FAILED, error = "make4ht failed"))
        assertEquals(HtmlAvailability.Unavailable("make4ht failed"), store.availability(pdf))
        assertEquals(HtmlAvailability.Unavailable("imported PDFs have no HTML edition"), store.availability(pdf, hasSource = false))
    }

    @Test fun corruptObjectIsNotKept() {
        val server = Server()
        val edition = edition(server)
        val store = HtmlEditionStore(temp.newFolder())
        server.corrupt = server.objects.keys.first { it != edition }
        try {
            store.install(pdf, "doc.pdf", edition, server)
            fail("a corrupt object must fail the install")
        } catch (expected: HtmlEditionInvalid) {
            assertTrue(expected.message!!.contains("verification"))
        }
        assertFalse(File(store.root, "objects/${server.corrupt}").exists())
        assertTrue(store.availability(pdf) is HtmlAvailability.Unavailable)
        assertTrue(File(store.root, "objects").listFiles().orEmpty().none { it.name.startsWith(".tmp") })
    }

    @Test fun manifestValidationRejectsUnsafePaths() {
        fun manifest(files: String) = """{"kind":"thoughts_html_edition","schema":1,"entry":"index.html","files":$files}""".toByteArray()
        val digest = "c".repeat(64)
        HtmlEditionManifest.parse(digest, manifest("""{"index.html":"$digest","assets/x.png":"$digest"}"""))
        listOf("../x", "/etc/passwd", "a//b", "a\\b", "https://x/y", "./index.html").forEach { path ->
            try {
                HtmlEditionManifest.parse(digest, manifest("""{"index.html":"$digest","$path":"$digest"}"""))
                fail("accepted $path")
            } catch (expected: HtmlEditionInvalid) { }
        }
        try {
            HtmlEditionManifest.parse(digest, manifest("""{"style.css":"$digest"}"""))
            fail("accepted a manifest without its entry")
        } catch (expected: HtmlEditionInvalid) { }
        try {
            HtmlEditionManifest.parse(digest, """{"kind":"other","schema":1,"files":{}}""".toByteArray())
            fail("accepted another kind")
        } catch (expected: HtmlEditionInvalid) { }
    }

    @Test fun retentionFollowsThePdfsAndKeepsSharedFiles() {
        val server = Server()
        val first = edition(server, html = "<p>v1</p>")
        val second = edition(server, html = "<p>v2</p>") // same style.css and asset
        val store = HtmlEditionStore(temp.newFolder())
        val pdf2 = "d".repeat(64)
        store.install(pdf, "doc.pdf", first, server)
        store.install(pdf2, "doc.pdf", second, server)
        val removed = store.retain(setOf(pdf2))
        assertEquals(2, removed) // the old manifest and the old index.html
        assertTrue(store.availability(pdf) is HtmlAvailability.Unavailable)
        assertTrue(store.availability(pdf2) is HtmlAvailability.Ready)
    }

    @Test fun mediaTypes() {
        assertEquals("text/html", HtmlEditionStore.mediaType("index.html"))
        assertEquals("image/svg+xml", HtmlEditionStore.mediaType("assets/x.SVG"))
        assertEquals("application/octet-stream", HtmlEditionStore.mediaType("x"))
    }
}

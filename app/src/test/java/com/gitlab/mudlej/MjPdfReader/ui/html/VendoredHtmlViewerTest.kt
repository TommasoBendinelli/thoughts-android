// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.html

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The shared HTML viewer is vendored by the server repo's clients/html-viewer/install-into.py.
 * Its files must be exactly the pinned version: a hand edit here would fork the viewer.
 */
class VendoredHtmlViewerTest {
    private val dir = File("src/main/assets/html-viewer")

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    @Test
    fun vendoredFilesMatchTheirManifest() {
        val manifest = JsonParser.parseString(File(dir, "html-viewer.json").readText()).asJsonObject
        assertEquals("thoughts-html-viewer", manifest["name"].asString)
        assertEquals(HtmlViewerAssets.PINNED_VERSION, manifest["version"].asString)
        assertEquals(HtmlViewerAssets.PROTOCOL, manifest["protocol"].asInt)
        assertEquals(HtmlViewerAssets.INJECT, manifest["inject"].asJsonArray.map { it.asString })
        val files = manifest["files"].asJsonObject
        assertTrue(files.size() >= 5)
        files.entrySet().forEach { (name, digest) ->
            assertEquals("$name differs from html-viewer ${HtmlViewerAssets.PINNED_VERSION}", digest.asString, sha256(File(dir, name)))
        }
        assertEquals(HtmlViewerAssets.PINNED_VERSION, File(dir, "VERSION").readText().trim())
    }

    @Test
    fun viewerScriptDeclaresThePinnedVersionAndProtocol() {
        val viewer = File(dir, "viewer.js").readText()
        assertTrue(viewer.contains("const VERSION = \"${HtmlViewerAssets.PINNED_VERSION}\";"))
        assertTrue(viewer.contains("const PROTOCOL = ${HtmlViewerAssets.PROTOCOL};"))
    }
}

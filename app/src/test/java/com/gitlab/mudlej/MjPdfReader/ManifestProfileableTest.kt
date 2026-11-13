// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestProfileableTest {
    private fun profileable(manifest: String): Int = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File(manifest)).getElementsByTagName("profileable").length

    /** The main manifest is the release build's: only debug and sandbox builds let the adb shell profile them. */
    @Test
    fun onlyDebugAndSandboxBuildsAreProfileableFromTheShell() {
        assertEquals(0, profileable("src/main/AndroidManifest.xml"))
        assertEquals(1, profileable("src/debug/AndroidManifest.xml"))
        assertEquals(1, profileable("src/sandbox/AndroidManifest.xml"))
        assertEquals(false, File("src/release/AndroidManifest.xml").exists())
    }
}

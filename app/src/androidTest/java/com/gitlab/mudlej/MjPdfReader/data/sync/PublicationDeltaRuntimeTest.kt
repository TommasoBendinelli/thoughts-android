// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** A real zstd --patch-from fixture, decoded by the native library packaged in the APK. */
class PublicationDeltaRuntimeTest {
    @Test fun decodesServerCliPatchOnAndroid() {
        val base = buildString {
            repeat(12000) { append("PDF source line $it: unchanged content for dictionary verification.\n") }
        }
        val expected = base.replace("PDF source line 6000: unchanged content", "PDF source line 6000: edited content").toByteArray()
        val patch = InstrumentationRegistry.getInstrumentation().context.assets
            .open("pdf-delta/server-cli.zstpatch").use { it.readBytes() }
        assertArrayEquals(expected, decodeZstdPublicationDelta(base.toByteArray(), patch, expected.size))
    }
    @Test fun replaysRealPublicationPatchWhenProvided() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("realPublicationPatch") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.io.File(context.cacheDir, "patch-verification")
        val manifest = com.google.gson.JsonParser.parseString(java.io.File(root, "manifest.json").readText()).asJsonObject
        val size = manifest["result_size_bytes"].asInt
        val patch = java.io.File(root, "patch.zst").readBytes()
        val result = requireNotNull(decodeZstdPublicationDelta(java.io.File(root, "base.pdf").readBytes(), patch, size))
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(result)
            .joinToString("") { "%02x".format(it) }
        org.junit.Assert.assertEquals(manifest["result_sha256"].asString, sha)
        java.io.File(root, "verified.txt").writeText("patch_bytes=${patch.size} result_bytes=${result.size} sha256=$sha")
    }
}

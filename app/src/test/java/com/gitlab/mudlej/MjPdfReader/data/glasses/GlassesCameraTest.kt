// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.glasses

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GlassesCameraTest {
    /** The glasses' camera server as the Mac companion and Fedora's fetcher see it (port 8089 in real life). */
    private class FakeGlasses : AutoCloseable {
        val gallery = CopyOnWriteArrayList<String>()   // "IMG_x/base.jpg"
        val trash = CopyOnWriteArrayList<String>()     // "IMG_x"
        val files = mutableMapOf<String, ByteArray>()  // download path -> bytes
        val paths = CopyOnWriteArrayList<String>()
        var status: (String) -> Int = { 200 }
        /** What a take-picture does: the photo the glasses then save. */
        var onShot: () -> Unit = {}
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val url get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val query = exchange.requestURI.query
            paths += if (query == null) path else "$path?$query"
            val code = status(path)
            val body: ByteArray = when {
                code != 200 -> "{}".toByteArray()
                path == "/api/gallery" ->
                    """{"status":"success","data":{"photos":[${gallery.joinToString(",") { "{\"name\":\"$it\",\"size\":5}" }}]}}""".toByteArray()
                path == "/api/v3/trash" ->
                    """{"status":"success","data":{"captures":[${trash.joinToString(",") { "{\"capture_id\":\"$it\"}" }}]}}""".toByteArray()
                path == "/api/take-picture" -> { onShot(); """{"status":"success"}""".toByteArray() }
                path == "/api/download" -> files[query?.removePrefix("file=")] ?: run {
                    exchange.sendResponseHeaders(404, -1); exchange.close(); return
                }
                else -> { exchange.sendResponseHeaders(404, -1); exchange.close(); return }
            }
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        override fun close() = server.stop(0)
    }

    private val glasses = mutableListOf<FakeGlasses>()
    @After fun stop() = glasses.forEach { it.close() }
    private fun fake() = FakeGlasses().also { glasses += it }

    private var now = 0L
    private fun camera(url: String?, timeout: Long = 20_000) = GlassesCamera(
        baseUrl = { url }, clock = { now }, sleep = { now += it }, photoTimeoutMillis = timeout, connectTimeoutMillis = 2_000,
    )

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 7, 7)

    private fun problem(block: () -> Unit): GlassesCameraProblem {
        try {
            block()
        } catch (error: GlassesCameraException) {
            return error.problem
        }
        fail("expected a GlassesCameraException")
        throw AssertionError()
    }

    @Test fun takesThePictureAndDownloadsTheNewCaptureOnly() {
        val glasses = fake()
        glasses.gallery += "IMG_20261111_165900_000_old/base.jpg"
        glasses.files["IMG_20261111_165900_000_old/base.jpg"] = jpeg + 1
        glasses.onShot = {
            glasses.gallery += "IMG_20261111_170138_123_ab12/base.jpg"
            glasses.files["IMG_20261111_170138_123_ab12/base.jpg"] = jpeg
        }
        val photo = camera(glasses.url).takePhoto()
        assertEquals("IMG_20261111_170138_123_ab12", photo.capture)
        assertArrayEquals(jpeg, photo.bytes)
        assertEquals("image/jpeg", photo.mediaType)
        assertTrue(glasses.paths.contains("/api/take-picture"))
        assertTrue(glasses.paths.none { it.contains("165900") })
    }

    @Test fun aPhotoAlreadyMovedToTheTrashIsReadFromThere() {
        // Another device archived it and trashed it on the glasses before the phone looked.
        val glasses = fake()
        glasses.onShot = {
            glasses.trash += "IMG_20261111_170138_123_ab12"
            glasses.files["_gallery_trash/IMG_20261111_170138_123_ab12/base.jpg"] = jpeg
        }
        assertEquals("IMG_20261111_170138_123_ab12", camera(glasses.url).takePhoto().capture)
    }

    @Test fun aPhotoNotReadableYetIsAskedAgain() {
        val glasses = fake()
        var rounds = 0
        glasses.onShot = { glasses.gallery += "IMG_20261111_170138_123_ab12/base.jpg" }
        glasses.status = { path ->
            if (path == "/api/gallery" && glasses.gallery.size == 1 && ++rounds == 3) {
                glasses.files["IMG_20261111_170138_123_ab12/base.jpg"] = jpeg
            }
            200
        }
        assertArrayEquals(jpeg, camera(glasses.url).takePhoto().bytes)
    }

    @Test fun saysWhyNoPictureCame() {
        assertEquals(GlassesCameraProblem.NOT_SET_UP, problem { camera(null).takePhoto() })
        assertEquals(GlassesCameraProblem.NOT_SET_UP, problem { camera("  ").takePhoto() })
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(GlassesCameraProblem.NOT_CONNECTED, problem { camera("http://127.0.0.1:$closed").takePhoto() })

        val refusing = fake().apply { status = { 403 } }
        assertEquals(GlassesCameraProblem.NOT_ALLOWED, problem { camera(refusing.url).takePhoto() })
        assertTrue("nothing is shot when the gallery refuses", refusing.paths.none { it == "/api/take-picture" })

        val busy = fake().apply { status = { path -> if (path == "/api/take-picture") 503 else 200 } }
        assertEquals(GlassesCameraProblem.CAMERA_BUSY, problem { camera(busy.url).takePhoto() })

        val silent = fake()
        now = 0
        assertEquals(GlassesCameraProblem.NO_PHOTO, problem { camera(silent.url, timeout = 5_000).takePhoto() })
        assertTrue(now >= 5_000)
        // Not a JPEG: never sent as the picture.
        val broken = fake().apply {
            onShot = { gallery += "IMG_20261111_170138_123_ab12/base.jpg"; files["IMG_20261111_170138_123_ab12/base.jpg"] = "<html>".toByteArray() }
        }
        assertEquals(GlassesCameraProblem.NO_PHOTO, problem { camera(broken.url, timeout = 2_000).takePhoto() })
    }

    @Test fun addressesAreCompletedOrRefused() {
        assertEquals("http://192.0.2.10:8089", GlassesCamera.normalizedBaseUrl("192.0.2.10"))
        assertEquals("http://192.0.2.10:8089", GlassesCamera.normalizedBaseUrl(" http://192.0.2.10:8089/ "))
        assertEquals("http://glasses.example.ts.net:9000", GlassesCamera.normalizedBaseUrl("glasses.example.ts.net:9000"))
        assertEquals("https://192.0.2.10:8089", GlassesCamera.normalizedBaseUrl("https://192.0.2.10"))
        assertNull(GlassesCamera.normalizedBaseUrl(""))
        assertNull(GlassesCamera.normalizedBaseUrl("http://192.0.2.10:8089/api/gallery"))
        assertNull(GlassesCamera.normalizedBaseUrl("http://"))
    }
}

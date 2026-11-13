// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.html

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/** One immutable, self-contained page per origin; no filesystem or network proxy routes. */
internal class OfflineHtmlServer(private val html: java.io.File) : Closeable {
    private val listener = run {
        require(html.isFile && html.length() in 1..100_000_000)
        java.io.InputStreamReader(html.inputStream(), Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)).use { reader ->
            val buffer = CharArray(65536)
            while (reader.read(buffer) >= 0) { /* Validate before opening a listening socket. */ }
        }
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    }
    private val route = "/${UUID.randomUUID()}/index.html"
    private val host = "127.0.0.1:${listener.localPort}"
    val url = "http://$host$route"
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "thoughts-html").apply { isDaemon = true } }
    init {
        executor.execute {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                runCatching { socket.use(::respond) }
            }
        }
    }
    private fun respond(socket: Socket) {
        socket.soTimeout = 2000
        val input = socket.getInputStream()
        val buffer = java.io.ByteArrayOutputStream()
        while (buffer.size() <= 8192) {
            val next = input.read()
            if (next < 0) return
            buffer.write(next)
            val raw = buffer.toByteArray()
            if (raw.size >= 4 && raw.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
        }
        if (buffer.size() > 8192) return
        val lines = buffer.toString("US-ASCII").split("\r\n")
        val head = lines.first() == "HEAD $route HTTP/1.1"
        val hosts = lines.filter { it.startsWith("Host:", true) }
        val ok = hosts.size == 1 && hosts.single().substringAfter(':').trim() == host &&
            (head || lines.first() == "GET $route HTTP/1.1")
        val size = if (ok) html.length() else 9L
        val policy = "sandbox allow-scripts allow-downloads; default-src 'none'; " +
            "script-src 'unsafe-inline' 'unsafe-eval' data: blob:; style-src 'unsafe-inline' data:; " +
            "img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; " +
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        val headers = "HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: $policy\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            if (!head) {
                if (ok) html.inputStream().use { it.copyTo(this) } else write("Not found".toByteArray())
            }
            flush()
        }
    }
    override fun close() { listener.close(); executor.shutdownNow() }
}

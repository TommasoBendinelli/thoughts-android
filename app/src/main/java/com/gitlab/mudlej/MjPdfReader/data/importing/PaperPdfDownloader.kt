// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.importing

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PaperWebsiteAccessException : IllegalStateException(
    "The website requires sign-in or a browser check. Open it in your browser, download the PDF, then use Import document….",
)

/** Downloads after the user presses Next; callers own the returned temporary file. */
class PaperPdfDownloader(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun download(paper: PaperWebsite, directory: File, checkActive: () -> Unit = {}): File {
        directory.mkdirs()
        val file = File.createTempFile("paper-", ".pdf", directory)
        try {
            var url = URL(paper.pdfUrl)
            repeat(6) {
                checkActive()
                require(url.protocol == "https" && url.userInfo == null) { "The website redirected to an unsupported address" }
                val connection = openConnection(url).apply {
                    connectTimeout = 15000; readTimeout = 15000; instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/pdf")
                    setRequestProperty("User-Agent", "Thoughts/3.0 (Android; user-requested paper download)")
                }
                try {
                    when (connection.responseCode) {
                        301, 302, 303, 307, 308 -> {
                            val location = connection.getHeaderField("Location") ?: error("The website returned an empty redirect")
                            url = URL(url, location)
                        }
                        200 -> {
                            require(connection.contentLengthLong <= MAX_BYTES) { "This PDF exceeds the 100 MB import limit" }
                            connection.inputStream.use { input -> file.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                var total = 0L
                                while (true) {
                                    checkActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= MAX_BYTES) { "This PDF exceeds the 100 MB import limit" }
                                    output.write(buffer, 0, count)
                                }
                            } }
                            checkActive()
                            require(file.inputStream().use { String(it.readNBytes(5), Charsets.US_ASCII) } == "%PDF-") {
                                "The website did not return a PDF; the paper may require sign-in"
                            }
                            return file
                        }
                        401, 403 -> throw PaperWebsiteAccessException()
                        404 -> error("The paper was not found; check the link")
                        else -> error("The website returned HTTP ${connection.responseCode}; try again later")
                    }
                } finally { connection.disconnect() }
            }
            error("The website redirected too many times")
        } catch (error: Exception) { file.delete(); throw error }
    }

    companion object { private const val MAX_BYTES = 100L * 1024 * 1024 }
}

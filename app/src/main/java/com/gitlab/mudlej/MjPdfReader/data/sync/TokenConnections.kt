// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/**
 * Connections that carry the device token never follow a redirect: HttpURLConnection would repeat
 * the request, token included, at whatever address the answer names. The server does not redirect
 * its API, so a redirect is reported as the failed request it is.
 */
internal object TokenConnections {
    fun withoutRedirects(connection: URLConnection): URLConnection =
        connection.also { (it as? HttpURLConnection)?.instanceFollowRedirects = false }

    val open: (URL) -> URLConnection = { withoutRedirects(it.openConnection()) }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenConnectionsTest {
    private class FakeConnection(url: URL) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    @Test
    fun aConnectionThatCarriesTheTokenDoesNotFollowRedirects() {
        val connection = FakeConnection(URL("https://thoughts.example.org/v1/library"))
        assertTrue(connection.instanceFollowRedirects)
        assertSame(connection, TokenConnections.withoutRedirects(connection))
        assertFalse(connection.instanceFollowRedirects)
        // Opening makes no request; the connection it gives is already set.
        assertFalse((TokenConnections.open(URL("https://thoughts.example.org/v1/library")) as HttpURLConnection).instanceFollowRedirects)
    }
}

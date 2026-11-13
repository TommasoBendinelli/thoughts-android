// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FedoraBuildLogReferenceTest {

    @Test
    fun acceptsOnlyAnExactServerObjectSha256() {
        val digest = "a".repeat(64)

        assertEquals(digest, serverLogSha256("server-object:$digest"))
        assertNull(serverLogSha256(digest))
        assertNull(serverLogSha256("server-object:${"A".repeat(64)}"))
        assertNull(serverLogSha256("server-object:${digest}extra"))
        assertNull(serverLogSha256("file:///srv/thoughts/$digest"))
    }
}

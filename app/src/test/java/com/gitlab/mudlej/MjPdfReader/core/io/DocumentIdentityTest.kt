// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentIdentityTest {

    @Test
    fun loadReadsBothIdentitiesFromTheCurrentSource() = runBlocking {
        val calls = mutableListOf<String>()

        val identity = computeFreshDocumentIdentity(
            source = "replacement.pdf",
            historyHasher = {
                calls += "history:$it"
                "new-history-hash"
            },
            annotationHasher = {
                calls += "annotation:$it"
                "new-sha256"
            },
        )

        assertEquals("new-history-hash", identity.historyHash)
        assertEquals("new-sha256", identity.annotationPdfSha256)
        assertEquals(
            listOf("history:replacement.pdf", "annotation:replacement.pdf"),
            calls,
        )
    }

    @Test
    fun loadDoesNotRetainAnIdentityWhenReadingTheCurrentSourceFails() = runBlocking {
        val identity = computeFreshDocumentIdentity(
            source = "unreadable.pdf",
            historyHasher = { null },
            annotationHasher = { null },
        )

        assertNull(identity.historyHash)
        assertNull(identity.annotationPdfSha256)
    }
}

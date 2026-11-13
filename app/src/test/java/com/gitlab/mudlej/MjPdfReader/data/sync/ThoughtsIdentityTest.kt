// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ThoughtsIdentityTest {
    @Test
    fun filenameDerivedIdentifiersCannotEnterTheNewProtocol() {
        assertFalse(DocumentIdentityStore.validId("f9b62a55-e56e-5135-aaad-faf149099d7f"))
        assertFalse(DocumentIdentityStore.validId("ideas.pdf"))
        assertFalse(DocumentIdentityStore.validId(null))
        assertTrue(DocumentIdentityStore.validId(UUID.randomUUID().toString()))
    }
}

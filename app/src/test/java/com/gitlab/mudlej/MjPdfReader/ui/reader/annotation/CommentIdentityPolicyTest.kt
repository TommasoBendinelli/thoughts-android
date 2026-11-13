// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedCommentIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentIdentityPolicyTest {

    @Test
    fun sourceLinkedCommentEditsFailClosedUntilPublicationIsVerified() {
        SourceLinkedCommentIdentity.entries.forEach { identity ->
            val expected = identity == SourceLinkedCommentIdentity.NOT_SOURCE_LINKED ||
                identity == SourceLinkedCommentIdentity.VERIFIED
            assertTrue(
                "Unexpected policy for $identity",
                canPersistCommentEdit(identity, sourceLinkedCommentEdit = true) == expected,
            )
        }
    }

    @Test
    fun ordinaryAnnotationsRemainAvailableForEveryIdentityState() {
        SourceLinkedCommentIdentity.entries.forEach { identity ->
            assertTrue(canPersistCommentEdit(identity, sourceLinkedCommentEdit = false))
        }
        assertFalse(SourceLinkedCommentIdentity.HASH_MISMATCH.allowsMutation)
    }
}

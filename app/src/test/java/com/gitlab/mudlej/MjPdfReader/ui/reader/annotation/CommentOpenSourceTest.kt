// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedCommentIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentOpenSourceTest {
    @Test
    fun existingSidecarIsAlwaysAuthoritative() {
        SourceLinkedCommentIdentity.entries.forEach { identity ->
            assertEquals(CommentOpenSource.SIDECAR, commentOpenSource(true, identity))
        }
    }

    @Test
    fun verifiedLinkedPdfNeverImportsEmbeddedComments() {
        assertEquals(
            CommentOpenSource.SIDECAR,
            commentOpenSource(false, SourceLinkedCommentIdentity.VERIFIED),
        )
    }

    @Test
    fun onlyUnlinkedPdfWithoutSidecarImportsEmbeddedComments() {
        assertEquals(
            CommentOpenSource.EMBEDDED_IMPORT,
            commentOpenSource(false, SourceLinkedCommentIdentity.NOT_SOURCE_LINKED),
        )
        listOf(
            SourceLinkedCommentIdentity.VERIFYING,
            SourceLinkedCommentIdentity.INDEX_UNAVAILABLE,
            SourceLinkedCommentIdentity.PUBLICATION_UNVERIFIED,
            SourceLinkedCommentIdentity.HASH_MISMATCH,
        ).forEach { identity ->
            assertEquals(CommentOpenSource.BLOCKED, commentOpenSource(false, identity))
        }
    }
}

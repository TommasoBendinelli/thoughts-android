// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationSavePolicyTest {

    @Test
    fun macOwnsSourceLinkedCanonicalPdfsAndFailsClosedWithoutAnIndex() {
        assertTrue(isMacOwnedSourceCapability(SourcePdfCapability.SOURCE_LINKED))
        assertTrue(isMacOwnedSourceCapability(SourcePdfCapability.INDEX_UNAVAILABLE))
        assertFalse(isMacOwnedSourceCapability(SourcePdfCapability.NOT_SOURCE_LINKED))
    }
}

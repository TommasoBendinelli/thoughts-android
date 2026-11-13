// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentCreationMetadataTest {

    @Test
    fun usesBrightYellowAndPrefixedThoughtsIds() {
        assertEquals(0xFFFFFF00.toInt(), CommentCreationMetadata.DEFAULT_POPUP_COLOR)
        assertEquals("Comment", CommentCreationMetadata.CANONICAL_POPUP_ICON)
        assertEquals("Comment", CommentCreationMetadata.canonicalPopupIcon("Text"))
        assertNull(CommentCreationMetadata.canonicalPopupIcon("Highlight"))
        assertTrue(CommentCreationMetadata.newThoughtsId().startsWith("thoughts-"))
    }

    @Test
    fun infersKnownCreatorsAndRejectsAmbiguousLegacyRecords() {
        val legacyMj = JsonParser.parseString(
            """{"id":"5fb7110f-6ae4-4590-8bc5-cb2500bee670","subtype":"Text","author":"Alex Example","flags":28,"color":[0.980392,0.85098,0]}""",
        ).asJsonObject
        val okular = JsonParser.parseString(
            """{"id":"okular-{4fec44d9-35d0-472d-9c82-51e534229bd5}","subtype":"Text"}""",
        ).asJsonObject
        val ambiguous = JsonParser.parseString(
            """{"id":"5fb7110f-6ae4-4590-8bc5-cb2500bee670","subtype":"Text","author":"Someone else","flags":28,"color":[0.980392,0.85098,0]}""",
        ).asJsonObject

        assertEquals(CommentCreationMetadata.LEGACY_MJ_PDF_READER, CommentCreationMetadata.infer(legacyMj, OWNER))
        assertEquals(CommentCreationMetadata.LEGACY_OKULAR_ENTER, CommentCreationMetadata.infer(okular, OWNER))
        assertNull(CommentCreationMetadata.infer(ambiguous, OWNER))
        // A build that names no owner attributes no plain-UUID note to the old reader.
        assertNull(CommentCreationMetadata.infer(legacyMj, null))
        assertNull(CommentCreationMetadata.infer(legacyMj, ""))
        assertEquals(CommentCreationMetadata.LEGACY_OKULAR_ENTER, CommentCreationMetadata.infer(okular, null))
        // The build's own option decides the default: the rule applies only when it names an author.
        assertEquals(
            if (CommentCreationMetadata.LEGACY_MJ_PDF_AUTHOR == "Alex Example") CommentCreationMetadata.LEGACY_MJ_PDF_READER else null,
            CommentCreationMetadata.infer(legacyMj),
        )
    }

    @Test
    fun versionUpgradeNormalizesOnceAndThenPreservesRecoloring() {
        val legacy = JsonParser.parseString(
            """{"schema_version":4,"id":"5fb7110f-6ae4-4590-8bc5-cb2500bee670","subtype":"Text","author":"Alex Example","flags":28,"color":[0.980392,0.85098,0]}""",
        ).asJsonObject
        CommentCreationMetadata.upgradeLegacyRecord(legacy, 4, 5, OWNER)
        assertEquals("mj-pdf-reader", legacy["created_in_app"].asString)
        assertEquals(listOf(1.0, 1.0, 0.0), legacy["color"].asJsonArray.map { it.asDouble })

        legacy.add("color", JsonParser.parseString("[1,0,0]"))
        CommentCreationMetadata.upgradeLegacyRecord(legacy, 5, 5, OWNER)
        assertEquals(listOf(1.0, 0.0, 0.0), legacy["color"].asJsonArray.map { it.asDouble })
    }

    private companion object {
        /** A neutral stand-in for the build's thoughtsLegacyMjPdfAuthor. */
        const val OWNER = "Alex Example"
    }
}

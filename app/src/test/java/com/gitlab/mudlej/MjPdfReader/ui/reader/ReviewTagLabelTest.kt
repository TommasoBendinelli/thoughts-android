// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewTagLabelTest {
    @Test
    fun shortTagsAreShownWhole() {
        assertEquals("Correct", shortReviewTag("Correct"))
        assertEquals("missed the key step", shortReviewTag("missed the key step"))
    }

    @Test
    fun longTagsShowTheirFirstWords() {
        assertEquals("never submitted and…", shortReviewTag("never submitted and a lot of suffering reading this"))
        assertEquals("Supercalifragilisticex…", shortReviewTag("Supercalifragilisticexpialidocious"))
    }

    @Test
    fun longTagsAreStoredWhole() {
        val text = "never submitted and a lot of suffering reading this proof before it finally clicked"
        assertEquals(text, ReviewTags.normalize(text))
    }
}

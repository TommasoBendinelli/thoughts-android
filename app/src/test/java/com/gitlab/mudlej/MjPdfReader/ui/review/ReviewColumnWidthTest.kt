// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewColumnWidthTest {
    // One unit per character keeps the expectations readable.
    private val measure: (String) -> Float = { it.length.toFloat() }

    @Test fun shortValuesStayOnOneLineAndLongOnesWrapAtTheTarget() {
        assertEquals(11f, reviewColumnTextWidth("Link failed", measure, 14f))
        assertEquals(14f, reviewColumnTextWidth("Current Skip · 2 shown", measure, 14f))
    }

    @Test fun aWordWiderThanTheTargetIsNeverSplit() {
        assertEquals(10f, reviewColumnTextWidth("2026-10-21", measure, 6f))
        assertEquals(8f, reviewColumnTextWidth("Document", measure, 4f))
    }

    @Test fun hyphenatedNamesMayBreakAfterTheHyphenButDatesMayNot() {
        assertEquals(7f, reviewColumnTextWidth("course-notes", measure, 4f))
        assertEquals(10f, reviewColumnTextWidth("2026-08-09 16:37", measure, 4f))
    }

    @Test fun eachSourceLineIsMeasuredOnItsOwn() {
        assertEquals(8f, reviewColumnTextWidth("main.tex\nab.tex", measure, 20f))
    }
}

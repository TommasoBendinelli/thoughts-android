// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentRenameInputTest {

    @Test
    fun pastedPaperTitleNormalizesLineBreaksAndRepeatedWhitespace() {
        val input = documentRenameInput(
            "  ProRL: Prolonged Reinforcement Learning Expands\r\n" +
                "  Reasoning Boundaries in Large Language Models  ",
            allowFileSeparator = true,
        )

        assertEquals(
            "ProRL: Prolonged Reinforcement Learning Expands " +
                "Reasoning Boundaries in Large Language Models",
            input.normalized,
        )
        assertNull(input.error)
    }

    @Test
    fun blankNameReturnsVisibleValidationError() {
        assertEquals(
            DocumentRenameInputError.EMPTY,
            documentRenameInput(" \n\t ", allowFileSeparator = true).error,
        )
    }

    @Test
    fun overlongNameReturnsVisibleValidationErrorAfterNormalization() {
        assertEquals(
            DocumentRenameInputError.TOO_LONG,
            documentRenameInput("a".repeat(201), allowFileSeparator = true).error,
        )
    }

    @Test
    fun nonWhitespaceControlCharacterRemainsInvalid() {
        assertEquals(
            DocumentRenameInputError.CONTROL_CHARACTER,
            documentRenameInput("Paper\u0000title", allowFileSeparator = true).error,
        )
    }

    @Test
    fun slashIsAllowedForDisplayNamesButNotLocalFilenames() {
        assertNull(documentRenameInput("Question / answer", allowFileSeparator = true).error)
        assertEquals(
            DocumentRenameInputError.FILE_SEPARATOR,
            documentRenameInput("Question / answer", allowFileSeparator = false).error,
        )
    }
}

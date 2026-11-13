// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTextTest {
    @Test fun joinsPdfLinesWithSpaces() {
        assertEquals(
            "The model learns a representation of the input.",
            ReadAloudText.prepare("The model learns\na representation\r\nof the   input.\n"),
        )
    }

    @Test fun rejoinsLineEndHyphenationBeforeLowercase() {
        assertEquals("for example, this", ReadAloudText.prepare("for exam-\nple, this"))
        assertEquals("for example", ReadAloudText.prepare("for exam- \r\n  ple"))
    }

    @Test fun keepsHyphenBeforeUppercaseOrDigits() {
        assertEquals("Anti-Inflammatory drugs", ReadAloudText.prepare("Anti-\nInflammatory drugs"))
        assertEquals("from 1990-2000", ReadAloudText.prepare("from 1990-\n2000"))
    }

    @Test fun dropsSoftHyphensAndPdfiumHyphenMarkers() {
        assertEquals("representation", ReadAloudText.prepare("represen­\ntation"))
        assertEquals("representation", ReadAloudText.prepare("represen\u0002\r\ntation"))
        assertEquals("hyphenation", ReadAloudText.prepare("hyphen­ation"))
    }

    @Test fun keepsDashesBetweenWordsAndExpandsLigatures() {
        assertEquals("a - b", ReadAloudText.prepare("a -\nb"))
        assertEquals("the first official flow", ReadAloudText.prepare("the ﬁrst oﬃcial ﬂow"))
    }

    @Test fun collapsesWhitespace() {
        assertEquals("a b c", ReadAloudText.prepare(" \ta  b \n\n c\t"))
        assertEquals("", ReadAloudText.prepare(" \n\t "))
    }

    @Test fun firstChunkIsTheFirstSentence() {
        val text = "Neural networks generalize surprisingly well in practice, even overparameterized ones. " +
            "This paper studies why. It finds three effects."
        val chunks = ReadAloudText.chunks(text)
        assertEquals(
            listOf(
                "Neural networks generalize surprisingly well in practice, even overparameterized ones.",
                "This paper studies why. It finds three effects.",
            ),
            chunks,
        )
    }

    @Test fun shortOpeningSentencesJoinTheFirstChunk() {
        val chunks = ReadAloudText.chunks("1. Introduction. Deep learning changed how we model language.")
        assertEquals(listOf("1. Introduction. Deep learning changed how we model language."), chunks)
    }

    @Test fun laterChunksStayWithinTheLimitAndKeepEveryWord() {
        val sentence = "This sentence has exactly some words to fill the chunk nicely. "
        val text = ReadAloudText.prepare(sentence.repeat(60))
        val chunks = ReadAloudText.chunks(text)
        assertTrue(chunks.first().length <= ReadAloudText.FIRST_CHUNK_MAX_CHARS)
        chunks.drop(1).forEach { assertTrue(it.length <= ReadAloudText.CHUNK_MAX_CHARS) }
        assertTrue("later chunks pack several sentences", chunks[1].length > 1000)
        assertEquals(text, chunks.joinToString(" "))
        // Every chunk ends at a sentence boundary.
        chunks.forEach { assertTrue(it, it.endsWith(".")) }
    }

    @Test fun overlongSentencesSplitAtClausesOrSpaces() {
        val clause = "the encoder maps every token to a vector, "
        val text = ("First " + clause.repeat(40)).trim().removeSuffix(",") + "."
        val chunks = ReadAloudText.chunks(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.first().length <= ReadAloudText.FIRST_CHUNK_MAX_CHARS)
        assertTrue(chunks.first().endsWith(","))
        chunks.drop(1).forEach { assertTrue(it.length <= ReadAloudText.CHUNK_MAX_CHARS) }
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test fun cutsAWordLongerThanTheLimit() {
        val chunks = ReadAloudText.chunks("x".repeat(50), firstMax = 20, restMax = 20)
        assertEquals(listOf("x".repeat(20), "x".repeat(20), "x".repeat(10)), chunks)
    }

    @Test fun abbreviationsDecimalsAndInitialsDoNotEndSentences() {
        assertEquals(
            listOf(
                "Results, e.g. in Fig. 3 and Eq. 2.5, follow J. Smith et al. closely.",
                "The rest is noise.",
            ),
            ReadAloudText.sentences(
                "Results, e.g. in Fig. 3 and Eq. 2.5, follow J. Smith et al. closely. The rest is noise.",
            ),
        )
    }

    @Test fun questionsExclamationsAndClosingQuotesEndSentences() {
        assertEquals(
            listOf("Why does it work?", "\"It just does.\"", "Wow!", "(Really.)", "Yes"),
            ReadAloudText.sentences("Why does it work? \"It just does.\" Wow! (Really.) Yes"),
        )
    }

    @Test fun nothingToRead() {
        assertEquals(emptyList<String>(), ReadAloudText.chunks(""))
    }
}

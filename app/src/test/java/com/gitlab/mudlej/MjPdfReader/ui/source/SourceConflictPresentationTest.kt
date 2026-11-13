// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceConflictPresentationTest {

    @Test
    fun twoWayConflictExposesMarkerAndBothSideRanges() {
        val source = """
            before
            <<<<<<< FEDORA_CURRENT
            Fedora text
            =======
            Phone text
            >>>>>>> PHONE_INCOMING
            after
        """.trimIndent()

        val presentation = parseSourceConflictPresentation(source)

        val block = presentation.blocks.single()
        assertTrue(block.complete)
        assertEquals(
            "Fedora text\n",
            source.contents(block.rangesFor(ConflictTextRole.FEDORA_CURRENT)),
        )
        assertEquals(
            "Phone text\n",
            source.contents(block.rangesFor(ConflictTextRole.PHONE_INCOMING)),
        )
        assertEquals(
            listOf(
                ConflictMarkerKind.START,
                ConflictMarkerKind.SEPARATOR,
                ConflictMarkerKind.END,
            ),
            block.highlights.mapNotNull(ConflictHighlightRange::markerKind),
        )
        assertFalse(presentation.hasMalformedMarkers)
    }

    @Test
    fun diff3ConflictExposesSharedBaseWithoutChangingEitherSide() {
        val source = """
            <<<<<<< FEDORA_CURRENT
            Fedora current
            ||||||| SHARED_BASE
            Common ancestor
            =======
            Phone incoming
            >>>>>>> PHONE_INCOMING
        """.trimIndent()

        val presentation = parseSourceConflictPresentation(source)
        val block = presentation.blocks.single()

        assertEquals(
            "Fedora current\n",
            source.contents(block.rangesFor(ConflictTextRole.FEDORA_CURRENT)),
        )
        assertEquals(
            "Common ancestor\n",
            source.contents(block.rangesFor(ConflictTextRole.SHARED_BASE)),
        )
        assertEquals(
            "Phone incoming\n",
            source.contents(block.rangesFor(ConflictTextRole.PHONE_INCOMING)),
        )
    }

    @Test
    fun multipleBlocksProvideOrderedWrappingNavigationOffsets() {
        val source = completeConflict("one") + "\ncontext\n" + completeConflict("two")
        val presentation = parseSourceConflictPresentation(source)
        val first = source.indexOf("<<<<<<<")
        val second = source.indexOf("<<<<<<<", first + 1)

        assertEquals(listOf(first, second), presentation.navigationOffsets)
        assertEquals(
            second,
            presentation.conflictOffset(first, ConflictNavigationDirection.NEXT),
        )
        assertEquals(
            first,
            presentation.conflictOffset(second, ConflictNavigationDirection.NEXT),
        )
        assertEquals(
            second,
            presentation.conflictOffset(first, ConflictNavigationDirection.PREVIOUS),
        )
        assertNull(
            presentation.conflictOffset(
                second,
                ConflictNavigationDirection.NEXT,
                wrap = false,
            ),
        )
    }

    @Test
    fun incompleteAndOrphanMarkersRemainVisibleAsMalformed() {
        val source = """
            =======
            orphan separator
            <<<<<<< FEDORA_CURRENT
            unfinished Fedora text
            ||||||| SHARED_BASE
            ancestor without a separator or end
        """.trimIndent()

        val presentation = parseSourceConflictPresentation(source)

        assertEquals(1, presentation.orphanMarkers.size)
        assertEquals(ConflictMarkerKind.SEPARATOR, presentation.orphanMarkers.single().markerKind)
        assertEquals(ConflictTextRole.MALFORMED_MARKER, presentation.orphanMarkers.single().role)
        assertFalse(presentation.blocks.single().complete)
        assertTrue(presentation.hasMalformedMarkers)
        assertEquals(
            listOf(source.indexOf("======="), source.indexOf("<<<<<<<")),
            presentation.navigationOffsets,
        )
    }

    @Test
    fun nestedStartClosesTheFirstBlockAndBeginsAnotherIssue() {
        val source = """
            <<<<<<< FEDORA_CURRENT
            first
            <<<<<<< FEDORA_CURRENT
            second
            =======
            phone
            >>>>>>> PHONE_INCOMING
        """.trimIndent()

        val presentation = parseSourceConflictPresentation(source)

        assertEquals(2, presentation.blocks.size)
        assertFalse(presentation.blocks[0].complete)
        assertTrue(presentation.blocks[1].complete)
        assertEquals(
            listOf(source.indexOf("<<<<<<<"), source.indexOf("<<<<<<<", 1)),
            presentation.navigationOffsets,
        )
    }

    @Test
    fun duplicateStructuralMarkerIsHighlightedAsMalformedWithoutHidingContent() {
        val source = """
            <<<<<<< FEDORA_CURRENT
            Fedora
            ||||||| SHARED_BASE
            base one
            ||||||| DUPLICATE_BASE
            base two
            =======
            phone
            >>>>>>> PHONE_INCOMING
        """.trimIndent()

        val presentation = parseSourceConflictPresentation(source)
        val block = presentation.blocks.single()

        assertFalse(block.complete)
        assertEquals(
            1,
            block.highlights.count { it.role == ConflictTextRole.MALFORMED_MARKER },
        )
        assertEquals(
            "base one\nbase two\n",
            source.contents(block.rangesFor(ConflictTextRole.SHARED_BASE)),
        )
    }

    @Test
    fun crlfRangesExcludeCarriageReturnsFromMarkerHighlights() {
        val source = "<<<<<<< FEDORA_CURRENT\r\nfedora\r\n=======\r\nphone\r\n>>>>>>> PHONE_INCOMING\r\n"

        val presentation = parseSourceConflictPresentation(source)

        presentation.highlights
            .filter { it.markerKind != null }
            .forEach { highlight ->
                assertFalse(source.substring(highlight.range.start, highlight.range.endExclusive).endsWith("\r"))
            }
        assertTrue(presentation.blocks.single().complete)
    }

    @Test
    fun proseAndIndentedMarkerExamplesAreNotStructural() {
        val source = "A sentence quotes <<<<<<< here.\n  <<<<<<< indented example\n======= prose"

        val presentation = parseSourceConflictPresentation(source)

        assertTrue(presentation.blocks.isEmpty())
        assertTrue(presentation.orphanMarkers.isEmpty())
        assertTrue(presentation.highlights.isEmpty())
    }

    @Test
    fun parsingMutableInputNeverAltersTheSource() {
        val source = StringBuilder(completeConflict("stable"))
        val before = source.toString()

        parseSourceConflictPresentation(source)

        assertEquals(before, source.toString())
    }

    @Test
    fun selectingEitherSideReturnsExactTextWithoutMarkers() {
        val source = """
            before
            <<<<<<< FEDORA_CURRENT
            Fedora line one
            Fedora line two
            ||||||| SHARED_BASE
            old line
            =======
            Phone line
            >>>>>>> PHONE_INCOMING
            after
        """.trimIndent()
        val block = parseSourceConflictPresentation(source).blocks.single()

        assertEquals(
            "Fedora line one\nFedora line two\n",
            block.selectedSide(source, ConflictTextRole.FEDORA_CURRENT),
        )
        assertEquals(
            "Phone line\n",
            block.selectedSide(source, ConflictTextRole.PHONE_INCOMING),
        )
        assertNull(block.selectedSide(source, ConflictTextRole.SHARED_BASE))
    }

    @Test
    fun selectingAnEmptySideIsAValidDeletion() {
        val source = """
            before
            <<<<<<< FEDORA_CURRENT
            =======
            Phone addition
            >>>>>>> PHONE_INCOMING
            after
        """.trimIndent()
        val block = parseSourceConflictPresentation(source).blocks.single()

        assertTrue(block.complete)
        assertEquals("", block.selectedSide(source, ConflictTextRole.FEDORA_CURRENT))
        assertEquals(
            "Phone addition\n",
            block.selectedSide(source, ConflictTextRole.PHONE_INCOMING),
        )
    }

    @Test
    fun emptySourceHasNoNavigationTarget() {
        val presentation = parseSourceConflictPresentation("")

        assertNull(presentation.conflictOffset(0, ConflictNavigationDirection.NEXT))
        assertNull(presentation.conflictOffset(0, ConflictNavigationDirection.PREVIOUS))
    }

    private fun String.contents(ranges: List<ConflictTextRange>): String = buildString {
        ranges.forEach { range ->
            append(this@contents.substring(range.start, range.endExclusive))
        }
    }

    private fun completeConflict(label: String): String = """
        <<<<<<< FEDORA_CURRENT
        Fedora $label
        =======
        Phone $label
        >>>>>>> PHONE_INCOMING
    """.trimIndent()
}

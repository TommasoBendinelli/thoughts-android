// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import org.junit.Assert.*
import org.junit.Test

class OverviewCommentPageDestinationTest {
    private fun fragment(page: Int = 84, hash: String = "current", offset: Float = 0f) =
        LogicalCommentPdfLocation(
            pdfFilename = "concepts.pdf", pdfSha256 = hash, page = page, status = "exact",
            regions = listOf(listOf(10f, offset, 100f, offset + 10f)),
        )

    @Test fun elevenHighlightFragmentsOpenAsOnePageWithAllRegions() {
        val fragments = (0..10).map { fragment(offset = it * 12f) }
        val destination = overviewCommentPageDestinations(fragments + fragments.first()).single()
        val target = requireNotNull(destination.navigationLocation("current"))
        assertEquals(83, target.pageIndex)
        assertEquals(fragments.flatMap { it.regions }, target.regions)
        assertTrue(fragments.all { it.regions.size == 1 })
    }

    @Test fun differentPagesAndDocumentsRemainSeparateChoices() {
        val first = fragment()
        val choices = overviewCommentPageDestinations(listOf(
            first, fragment(page = 85), first.copy(pdfFilename = "other.pdf"), first,
        ))
        assertEquals(listOf("concepts.pdf" to 84, "concepts.pdf" to 85, "other.pdf" to 84),
            choices.map { it.labelLocation.pdfFilename to it.labelLocation.page })
    }

    @Test fun oldRevisionAndUnavailableFragmentsCannotContaminateTheTarget() {
        val current = fragment(offset = 24f)
        val unavailable = fragment(offset = 48f).copy(status = "unavailable")
        val destination = overviewCommentPageDestinations(listOf(
            unavailable, fragment(hash = "old"), current,
        )).single()
        assertNotEquals("unavailable", destination.labelLocation.status)
        assertEquals(current.regions, destination.navigationLocation("current")!!.regions)
        assertNull(destination.navigationLocation("unknown"))
        assertNull(destination.navigationLocation(null))
    }

    @Test fun exactPlacementWinsOverPageOnlyAndRetainsItsPoint() {
        val exact = fragment().copy(point = listOf(10f, 20f))
        val pageOnly = fragment().copy(status = "page_only", regions = emptyList())
        val destination = overviewCommentPageDestinations(listOf(pageOnly, exact)).single()
        assertEquals(exact, destination.navigationLocation("current"))
    }

    @Test fun contentsEntryIsNotOfferedBesideTheEditedHeading() {
        val contents = fragment(page = 1).copy(duplicate = true)
        val runningHead = fragment(offset = 700f).copy(duplicate = true)
        val heading = fragment(offset = 300f)
        val destination = overviewCommentPageDestinations(listOf(contents, runningHead, heading)).single()
        assertEquals(84, destination.labelLocation.page)
        // A running head on the heading's own page does not widen the focus rectangle.
        assertEquals(heading.regions, destination.navigationLocation("current")!!.regions)
    }

    @Test fun duplicateOnlyPlacementsRemainOpenable() {
        val contents = fragment(page = 1).copy(duplicate = true)
        val destination = overviewCommentPageDestinations(listOf(contents)).single()
        assertEquals(contents, destination.navigationLocation("current"))
    }

    @Test fun unavailableDestinationCannotOpenAndInvalidPagesAreNotGuessed() {
        val unavailable = fragment().copy(status = "unavailable")
        assertNull(overviewCommentPageDestinations(listOf(unavailable)).single().navigationLocation("current"))
        assertTrue(overviewCommentPageDestinations(listOf(fragment(page = 0), fragment(page = -1))).isEmpty())
    }
}

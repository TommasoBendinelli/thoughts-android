// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewCardAreasTest {
    private fun box(page: Int, left: Float, top: Float, right: Float, bottom: Float) =
        ReviewAreaBox(page, left, top, right, bottom)

    @Test
    fun `lines of one selected passage are one area, separate boxes stay separate`() {
        val lines = listOf(
            box(3, .5f, .30f, .9f, .315f),
            box(3, .1f, .317f, .9f, .332f),
            box(3, .1f, .334f, .4f, .349f),
        )
        val separate = box(3, .1f, .6f, .5f, .7f)
        val sideBySide = box(3, .6f, .6f, .9f, .7f)
        val nextPage = box(4, .1f, .1f, .5f, .2f)
        val areas = reviewEditableAreas(lines + separate + sideBySide + nextPage)
        assertEquals(4, areas.size)
        assertEquals(lines, areas[0].stored)
        assertEquals(box(3, .1f, .30f, .9f, .349f), areas[0].box)
        assertEquals(listOf(separate), areas[1].stored)
        assertEquals(listOf(sideBySide), areas[2].stored)
        assertEquals(4, areas[3].box.page)
    }

    @Test
    fun `unchanged areas keep their stored regions, moved ones become the box`() {
        val lines = listOf(box(3, .5f, .30f, .9f, .315f), box(3, .1f, .317f, .9f, .332f))
        val other = box(4, .1f, .1f, .5f, .2f)
        val session = ReviewAreaEditSession(reviewEditableAreas(lines + other))
        val shown = session.initialBoxes
        assertFalse(session.changesRegions(shown))
        assertEquals(lines + other, session.regions(shown))
        // Float noise from the page-point round trip is not a change.
        val noisy = shown.map { it.copy(left = it.left + 0.00001f) }
        assertFalse(session.changesRegions(noisy))

        val moved = box(3, .2f, .5f, .6f, .6f)
        val saved = session.regions(listOf(moved, other))
        assertEquals(listOf(moved, other), saved)
        assertTrue(session.changesRegions(listOf(moved, other)))
    }

    @Test
    fun `add and remove areas on several pages, the last area is protected`() {
        val first = box(2, .1f, .1f, .5f, .2f)
        val session = ReviewAreaEditSession(reviewEditableAreas(listOf(first)))
        assertFalse(session.canRemove)
        assertFalse(session.remove(0))
        assertEquals(1, session.count)

        session.added()
        val added = box(5, .3f, .4f, .5f, .55f)
        assertTrue(session.canRemove)
        assertEquals(listOf(first, added), session.regions(listOf(first, added)))
        assertTrue(session.changesRegions(listOf(first, added)))

        // Remove the original area: only the new one, on another page, remains.
        assertTrue(session.remove(0))
        assertEquals(listOf(added), session.regions(listOf(added)))
        assertFalse(session.canRemove)
        assertFalse(session.remove(0))
        assertEquals(5, reviewPrimaryPage(2, session.regions(listOf(added))))
    }

    @Test
    fun `saving needs exactly the editor's boxes, all valid`() {
        val session = ReviewAreaEditSession(reviewEditableAreas(listOf(box(1, .1f, .1f, .2f, .2f))))
        assertTrue(runCatching { session.regions(emptyList()) }.isFailure)
        assertTrue(runCatching { session.regions(listOf(box(1, .1f, .1f, .2f, .2f), box(1, .3f, .3f, .4f, .4f))) }.isFailure)
        assertTrue(runCatching { session.regions(listOf(box(1, .3f, .1f, .2f, .2f))) }.isFailure)
        assertTrue(runCatching { session.regions(listOf(box(0, .1f, .1f, .2f, .2f))) }.isFailure)
    }

    @Test
    fun `primary page stays while it has an area, and its box covers that page's areas`() {
        val regions = listOf(box(4, .1f, .1f, .3f, .2f), box(3, .2f, .5f, .4f, .6f), box(3, .5f, .7f, .9f, .8f))
        assertEquals(3, reviewPrimaryPage(3, regions))
        assertEquals(box(3, .2f, .5f, .9f, .8f), reviewPrimaryBox(3, regions))
        assertEquals(4, reviewPrimaryPage(7, regions))
    }

    @Test
    fun `saved areas keep their stored lines and text until moved, and new boxes are rectangles`() {
        val lines = listOf(ReviewAreaBox(2, .1f, .1f, .9f, .12f), ReviewAreaBox(2, .1f, .12f, .6f, .14f))
        val drawn = ReviewAreaBox(3, .2f, .2f, .5f, .4f)
        val session = ReviewAreaEditSession(listOf(
            ReviewEditableArea(ReviewAreaBox.union(lines), lines, "selected words"),
            ReviewEditableArea(drawn),
        ))
        session.added()
        val added = ReviewAreaBox(4, .1f, .1f, .3f, .3f)
        val areas = session.areas(listOf(ReviewAreaBox.union(lines), drawn, added))
        assertEquals(listOf(lines, listOf(drawn), listOf(added)), areas.map { it.regions })
        assertEquals(listOf("selected words", null, null), areas.map { it.text })
        assertEquals(listOf(false, true, true), areas.map { it.rectangle })
        // Moving the selected passage makes it a box without its text.
        val moved = session.areas(listOf(ReviewAreaBox.union(lines).copy(top = .05f), drawn, added)).first()
        assertEquals(1, moved.regions.size)
        assertEquals(null, moved.text)
        assertTrue(moved.rectangle)
    }
}

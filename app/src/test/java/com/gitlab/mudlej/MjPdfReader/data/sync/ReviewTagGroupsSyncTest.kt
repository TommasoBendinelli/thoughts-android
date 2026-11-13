// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewTagGroupsSyncTest {
    private val layout = ReviewTagGroups.Layout(
        listOf(ReviewTagGroups.Group("g1", "Interest", listOf("cool"))), "2026-10-08T21:00:00.000Z", "phone",
    )
    private val upload = ReviewTagGroups.uploadOf(layout)

    private fun row(id: String, state: String = ThoughtsOutboxEntity.STATE_PENDING, type: String = ReviewTagGroups.ENTITY_TYPE) =
        ThoughtsOutboxEntity(id, id.hashCode().toLong(), type, ReviewTagGroups.ENTITY_ID, ReviewTagGroups.COLLECTION,
            null, "2026-10-08T20:00:00.000Z", "{}", 0L, deliveryState = state)

    @Test fun thePendingLayoutWaitsForAServerThatAcceptsIt() {
        val older = listOf(row("older"))
        for (capabilities in listOf(emptyList(), listOf(REVIEW_TAG_MERGE_CAPABILITY))) {
            val plan = reviewTagGroupsOutboxPlan(capabilities, targeted = false, pending = upload, undelivered = older)
            assertNull(plan.upload)
            assertEquals(emptyList<String>(), plan.superseded)
        }
        // Targeted Syncs (one PDF) leave it for the next full Sync.
        assertNull(reviewTagGroupsOutboxPlan(listOf(REVIEW_TAG_GROUPS_CAPABILITY), true, upload, older).upload)
    }

    @Test fun onlyTheLatestLayoutIsSent() {
        val undelivered = listOf(row("older"), row("blocked", ThoughtsOutboxEntity.STATE_BLOCKED), row(upload.operationId),
            row("merge", type = "review_tag_merge"))
        val plan = reviewTagGroupsOutboxPlan(listOf(REVIEW_TAG_GROUPS_CAPABILITY), false, upload, undelivered)
        assertEquals(upload, plan.upload)
        assertEquals(listOf("older", "blocked"), plan.superseded)
        // Overtaken by a later record from elsewhere: nothing is pending, older layouts are retired.
        val none = reviewTagGroupsOutboxPlan(listOf(REVIEW_TAG_GROUPS_CAPABILITY), false, null, undelivered)
        assertNull(none.upload)
        assertEquals(listOf("older", "blocked", upload.operationId), none.superseded)
    }

    @Test fun theUploadIsTheWholeRecordUnderAStableOperation() {
        assertEquals("groups", upload.payload.get("layout_id").asString)
        assertEquals(layout, ReviewTagGroups.parse(upload.payload))
        assertEquals(upload.operationId, ReviewTagGroups.uploadOf(layout.copy()).operationId)
        assertNotEquals(upload.operationId, ReviewTagGroups.uploadOf(layout.copy(updatedAt = "2026-10-08T21:00:00.001Z")).operationId)
        assertEquals("review_tag_groups", ReviewTagGroups.ENTITY_TYPE)
        assertEquals("review-tags", ReviewTagGroups.COLLECTION)
        assertEquals("review_tag_groups_v1", REVIEW_TAG_GROUPS_CAPABILITY)
    }

    @Test fun aLayoutWithSlidersWaitsForAServerThatKeepsScale() {
        val sliders = ReviewTagGroups.uploadOf(ReviewTagGroups.Layout(
            listOf(ReviewTagGroups.Group("g1", "Understanding", listOf("a", "b"), scale = listOf("a", "b"))),
            "2026-10-15T12:00:00.000Z", "phone",
        ))
        val older = listOf(row("older"))
        val plain = reviewTagGroupsOutboxPlan(listOf(REVIEW_TAG_GROUPS_CAPABILITY), false, sliders, older)
        assertNull(plain.upload)
        assertEquals(emptyList<String>(), plain.superseded)
        val scales = reviewTagGroupsOutboxPlan(
            listOf(REVIEW_TAG_GROUPS_CAPABILITY, ReviewTagGroups.SCALES_CAPABILITY), false, sliders, older)
        assertEquals(sliders, scales.upload)
        assertEquals(listOf("older"), scales.superseded)
        // Layouts without sliders still go to servers that only know groups.
        assertEquals(upload, reviewTagGroupsOutboxPlan(listOf(REVIEW_TAG_GROUPS_CAPABILITY), false, upload, older).upload)
        assertEquals("review_tag_scales_v1", ReviewTagGroups.SCALES_CAPABILITY)
    }

    @Test fun aLayoutWithSectionsWaitsForAServerThatKeepsSection() {
        fun layout(section: String?) = ReviewTagGroups.uploadOf(ReviewTagGroups.Layout(
            listOf(
                ReviewTagGroups.Group("g1", "Structure", listOf("a", "b"), scale = listOf("a", "b"), section = section),
                ReviewTagGroups.Group("g2", "Source", listOf("me")),
            ),
            "2026-10-22T12:00:00.000Z", "phone",
        ))
        val sections = layout("Work left")
        assertEquals(true, reviewTagGroupsUseSections(sections))
        assertEquals(false, reviewTagGroupsUseSections(layout(null)))
        val older = listOf(row("older"))
        val scalesOnly = listOf(REVIEW_TAG_GROUPS_CAPABILITY, ReviewTagGroups.SCALES_CAPABILITY)
        // Kept back (still pending locally, nothing retired) until the server keeps sections.
        val waiting = reviewTagGroupsOutboxPlan(scalesOnly, false, sections, older)
        assertNull(waiting.upload)
        assertEquals(emptyList<String>(), waiting.superseded)
        val all = scalesOnly + ReviewTagGroups.SECTIONS_CAPABILITY
        assertEquals(sections, reviewTagGroupsOutboxPlan(all, false, sections, older).upload)
        assertEquals(listOf("older"), reviewTagGroupsOutboxPlan(all, false, sections, older).superseded)
        // Sections alone are not enough for sliders, and a targeted Sync still waits.
        assertNull(reviewTagGroupsOutboxPlan(listOf(REVIEW_TAG_GROUPS_CAPABILITY, ReviewTagGroups.SECTIONS_CAPABILITY), false, sections, older).upload)
        assertNull(reviewTagGroupsOutboxPlan(all, true, sections, older).upload)
        // The same layout without sections goes to a server without the capability.
        assertEquals(layout(null), reviewTagGroupsOutboxPlan(scalesOnly, false, layout(null), older).upload)
        assertEquals("review_tag_sections_v1", ReviewTagGroups.SECTIONS_CAPABILITY)
    }

    @Test fun aLayoutWithPhasesWaitsForAServerThatKeepsPhase() {
        fun layout(phase: String?, extra: Map<String, com.google.gson.JsonElement> = emptyMap()) = ReviewTagGroups.uploadOf(ReviewTagGroups.Layout(
            listOf(
                ReviewTagGroups.Group("g1", "Recall", listOf("a", "b"), scale = listOf("a", "b"), phase = phase, extra = extra),
                ReviewTagGroups.Group("g2", "Understanding", listOf("c", "d"), scale = listOf("c", "d")),
            ),
            "2026-11-03T12:00:00.000Z", "phone",
        ))
        val phases = layout("before_and_after")
        assertEquals(true, reviewTagGroupsUsePhases(phases))
        assertEquals(false, reviewTagGroupsUsePhases(layout(null)))
        // A later client's phase, kept unchanged, also needs the capability.
        val unknown = layout(null, mapOf("phase" to com.google.gson.JsonPrimitive("during")))
        assertEquals(true, reviewTagGroupsUsePhases(unknown))
        val older = listOf(row("older"))
        val withoutPhases = listOf(REVIEW_TAG_GROUPS_CAPABILITY, ReviewTagGroups.SCALES_CAPABILITY, ReviewTagGroups.SECTIONS_CAPABILITY)
        // Kept back (still pending locally, nothing retired) until the server keeps phases.
        for (pending in listOf(phases, unknown)) {
            val waiting = reviewTagGroupsOutboxPlan(withoutPhases, false, pending, older)
            assertNull(waiting.upload)
            assertEquals(emptyList<String>(), waiting.superseded)
        }
        val all = withoutPhases + ReviewTagGroups.PHASES_CAPABILITY
        assertEquals(phases, reviewTagGroupsOutboxPlan(all, false, phases, older).upload)
        assertEquals(listOf("older"), reviewTagGroupsOutboxPlan(all, false, phases, older).superseded)
        // Phases alone are not enough for sliders, and a targeted Sync still waits.
        assertNull(reviewTagGroupsOutboxPlan(listOf(REVIEW_TAG_GROUPS_CAPABILITY, ReviewTagGroups.PHASES_CAPABILITY), false, phases, older).upload)
        assertNull(reviewTagGroupsOutboxPlan(all, true, phases, older).upload)
        // The same layout without phases goes to a server without the capability.
        assertEquals(layout(null), reviewTagGroupsOutboxPlan(withoutPhases, false, layout(null), older).upload)
        assertEquals("review_tag_phases_v1", ReviewTagGroups.PHASES_CAPABILITY)
    }
}

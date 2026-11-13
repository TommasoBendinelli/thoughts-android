// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Edit
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Group
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Slider (scale) groups: one step per answer, free tags independent, unknown fields kept. */
class ReviewTagScalesTest {
    private val understanding = listOf("I don't get it", "Partially", "I get it")
    private lateinit var directory: File
    private val previousGroups = ReviewTagGroups.file
    private val previousMerges = ReviewTagMerges.file

    @Before fun setUp() {
        directory = Files.createTempDirectory("tag-scales").toFile()
        ReviewTagGroups.file = { File(directory, "tag-groups.json") }
        ReviewTagMerges.file = { File(directory, "tag-merges.json") }
    }

    @After fun tearDown() {
        ReviewTagGroups.file = previousGroups
        ReviewTagMerges.file = previousMerges
        directory.deleteRecursively()
    }

    @Test fun pickingAStepReplacesTheScalesOtherStep() {
        val chosen = listOf("Recalled", "Partially", "notation")
        assertEquals("Partially", ReviewTagScales.selectedStep(understanding, chosen))
        val next = ReviewTagScales.toggle(chosen, understanding, "I get it")
        assertEquals(listOf("Recalled", "notation", "I get it"), next)
        assertEquals("I get it", ReviewTagScales.selectedStep(understanding, next))
        // select() does the same without the clearing tap (dragging the thumb).
        assertEquals(next, ReviewTagScales.select(next, understanding, "I get it"))
    }

    @Test fun tappingTheChosenStepClearsTheScale() {
        val chosen = listOf("I get it", "notation")
        val cleared = ReviewTagScales.toggle(chosen, understanding, "I get it")
        assertEquals(listOf("notation"), cleared)
        assertNull(ReviewTagScales.selectedStep(understanding, cleared))
        assertEquals(listOf("notation"), ReviewTagScales.select(chosen, understanding, null))
        // An unset scale: the first tap sets it.
        assertEquals(listOf("notation", "Partially"), ReviewTagScales.toggle(cleared, understanding, "Partially"))
    }

    @Test fun freeTagsAndOtherScalesStayIndependent() {
        val recall = listOf("Forgot", "Recalled")
        var chosen = listOf("Recalled", "notation", "worth a re-read")
        chosen = ReviewTagScales.toggle(chosen, understanding, "Partially")
        assertEquals(listOf("Recalled", "notation", "worth a re-read", "Partially"), chosen)
        chosen = ReviewTagScales.toggle(chosen, recall, "Forgot")
        assertEquals(listOf("notation", "worth a re-read", "Partially", "Forgot"), chosen)
        assertEquals("Partially", ReviewTagScales.selectedStep(understanding, chosen))
        assertEquals("Forgot", ReviewTagScales.selectedStep(recall, chosen))
    }

    @Test fun onlyStepsDropsFreeTagsAndKeepsMergedSteps() {
        val groups = listOf(
            Group("u", "Understanding", understanding + "notation", scale = understanding),
            Group("s", "Study", listOf("proof")),
        )
        val chosen = listOf("Got it", "notation", "proof", "correct", "Partially")
        // Free tags (of a slider's group, a chip group, or none) leave; an old name of a step stays.
        assertEquals(listOf("Got it", "Partially"),
            ReviewTagScales.onlySteps(chosen, groups, mapOf("Got it" to "I get it")))
    }

    @Test fun mergedNamesLandOnTheirStep() {
        val mapping = mapOf("Got it" to "I get it", "correct" to "I get it", "Half" to "Partially")
        // The last answer was tagged with an old name: the slider sits at the step it became.
        assertEquals("I get it", ReviewTagScales.selectedStep(understanding, listOf("Got it"), mapping))
        assertEquals(listOf("Partially"), ReviewTagScales.chosenSteps(understanding, listOf("Half", "x"), mapping))
        // Picking another step removes the old name too; tapping the step it became clears it.
        assertEquals(listOf("x", "Partially"), ReviewTagScales.toggle(listOf("Got it", "x"), understanding, "Partially", mapping))
        assertEquals(listOf("x"), ReviewTagScales.toggle(listOf("Got it", "x"), understanding, "I get it", mapping))
    }

    @Test fun legacyAnswersWithTwoStepsShowTheFirstAndKeepOneOnceTouched() {
        val chosen = listOf("I get it", "I don't get it")
        assertEquals(listOf("I don't get it", "I get it"), ReviewTagScales.chosenSteps(understanding, chosen))
        assertEquals("I don't get it", ReviewTagScales.selectedStep(understanding, chosen))
        assertEquals(listOf("Partially"), ReviewTagScales.toggle(chosen, understanding, "Partially"))
        // Tapping the shown step clears every step of the scale.
        assertEquals(emptyList<String>(), ReviewTagScales.toggle(chosen, understanding, "I don't get it"))
    }

    @Test fun theTagCellNamesSliderStepsFirst() {
        val groups = listOf(
            Group("g1", "Understanding", understanding + "notation", scale = understanding),
            Group("g2", "Recall", listOf("Forgot", "Recalled"), scale = listOf("Forgot", "Recalled")),
            Group("g3", "Material", listOf("proof")),
        )
        assertEquals(listOf("I get it", "Recalled", "proof", "notation"),
            ReviewTagScales.summaryTags(listOf("proof", "Recalled", "notation", "Got it"), groups, mapOf("Got it" to "I get it")))
        assertEquals(emptyList<String>(), ReviewTagScales.summaryTags(emptyList(), groups))
    }

    @Test fun scaleIsParsedAndWrittenBackWithUnknownFieldsKept() {
        val payload = JsonParser.parseString("""
            {"layout_id":"groups","schema_version":1,
             "groups":[{"group_id":"g1","name":"Understanding","tags":["I don't get it","Partially","I get it","notation"],
                        "scale":["I don't get it","Partially","I get it"],"color":"#336699","display":{"compact":true}},
                       {"group_id":"g2","name":"Study","tags":["proof"]}],
             "updated_at":"2026-10-15T12:00:00.000Z","origin_id":"desktop","layout_hint":["a",1]}
        """).asJsonObject
        val layout = ReviewTagGroups.parse(payload)!!
        assertEquals(understanding, layout.groups[0].scale)
        assertEquals(listOf("notation"), layout.groups[0].freeTags)
        assertTrue(layout.groups[0].isScale)
        assertFalse(layout.groups[1].isScale)
        assertNull(layout.groups[1].scale)
        assertTrue(layout.usesScales)
        // Written back unchanged, unknown fields included.
        assertEquals(payload, layout.payload())
        // An old record without scale stays without it.
        val plain = payload.deepCopy().apply { getAsJsonArray("groups")[0].asJsonObject.remove("scale") }
        assertEquals(plain, ReviewTagGroups.parse(plain)!!.payload())
    }

    @Test fun localEditsKeepUnknownFieldsOfTheRecordAndItsGroups() {
        ReviewTagGroups.installServer(JsonParser.parseString("""
            {"layout_id":"groups","schema_version":1,
             "groups":[{"group_id":"g1","name":"Understanding","tags":["a","b"],"scale":["a","b"],"color":"red"},
                       {"group_id":"g2","name":"Study","tags":["proof"],"future":{"x":1}}],
             "updated_at":"2026-10-15T12:00:00.000Z","origin_id":"desktop","layout_hint":"keep me"}
        """).asJsonObject)
        ReviewTagGroups.edit(Edit.Rename("g2", "Studying"), "phone", Instant.parse("2026-10-15T12:01:00Z"))
        ReviewTagGroups.edit(Edit.MoveTag("c", "g1"), "phone", Instant.parse("2026-10-15T12:02:00Z"))
        val sent = ReviewTagGroups.pendingUpload()!!.payload
        assertEquals("keep me", sent.get("layout_hint").asString)
        val groups = sent.getAsJsonArray("groups").map { it.asJsonObject }
        assertEquals("red", groups[0].get("color").asString)
        assertEquals(listOf("a", "b"), groups[0].getAsJsonArray("scale").map { it.asString })
        assertEquals(listOf("a", "b", "c"), groups[0].getAsJsonArray("tags").map { it.asString })
        assertEquals(1, groups[1].getAsJsonObject("future").get("x").asInt)
        assertEquals("Studying", groups[1].get("name").asString)
        assertFalse(groups[1].has("scale"))
        // Reading the saved file back gives the same record.
        assertEquals(sent, ReviewTagGroups.layout()!!.payload())
    }

    @Test fun showAsSliderSetsAndOrdersTheSteps() {
        val groups = listOf(
            Group("g1", "Understanding", listOf("I get it", "notation", "I don't get it")),
            Group("g2", "Other stuff", listOf("Partially")),
        )
        val slider = ReviewTagGroups.applyEdit(groups, Edit.SetScale("g1", understanding))
        // A step from another group moves here; the group's non-steps stay as free tags.
        assertEquals(listOf("I get it", "notation", "I don't get it", "Partially"), slider[0].tags)
        assertEquals(understanding, slider[0].scale)
        assertEquals(listOf("notation"), slider[0].freeTags)
        assertEquals(emptyList<String>(), slider[1].tags)
        // Reordering keeps the tags; fewer steps leave the rest as free tags.
        val reordered = ReviewTagGroups.applyEdit(slider, Edit.SetScale("g1", listOf("I get it", "I don't get it")))
        assertEquals(listOf("I get it", "I don't get it"), reordered[0].scale)
        assertEquals(setOf("notation", "Partially"), reordered[0].freeTags.toSet())
        // Show as tags: the scale goes, every tag stays.
        val tags = ReviewTagGroups.applyEdit(reordered, Edit.SetScale("g1", null))
        assertNull(tags[0].scale)
        assertEquals(slider[0].tags, tags[0].tags)
    }

    @Test fun aStepMovedAwayLeavesTheScaleAndMergedStepsCollapse() {
        val groups = listOf(Group("g1", "Understanding", understanding, scale = understanding), Group("g2", "Study", emptyList()))
        val moved = ReviewTagGroups.applyEdit(groups, Edit.MoveTag("Partially", "g2"))
        assertEquals(listOf("I don't get it", "I get it"), moved[0].scale)
        assertEquals(listOf("Partially"), moved[1].tags)
        assertNull(moved[1].scale)
        // Moving a step into its own group makes it a free tag of the group.
        val free = ReviewTagGroups.applyEdit(groups, Edit.MoveTag("Partially", "g1"))
        assertEquals(listOf("I don't get it", "I get it"), free[0].scale)
        assertEquals(listOf("Partially"), free[0].freeTags)
        // Merged names: the step shows under its final name, once.
        val mapping = mapOf("Got it" to "I get it")
        val merged = ReviewTagGroups.resolved(
            listOf(Group("g1", "U", listOf("Got it", "I get it", "Partially"), scale = listOf("Partially", "Got it", "I get it"))), mapping)
        assertEquals(listOf("Partially", "I get it"), merged[0].scale)
        assertEquals(listOf("I get it", "Partially"), merged[0].tags)
    }

    @Test fun stepsMissingFromTagsAreListedAndStepsOfAnEarlierGroupDropped() {
        val payload = ReviewTagGroups.Layout(listOf(
            Group("g1", "Study", listOf("Partially")),
            Group("g2", "Understanding", listOf("I get it"), scale = understanding),
        ), "2026-10-15T12:00:00.000Z", "desktop").payload()
        val layout = ReviewTagGroups.parse(payload)!!
        assertEquals(listOf("I get it", "I don't get it"), layout.groups[1].tags)
        assertEquals(listOf("I don't get it", "I get it"), layout.groups[1].scale)
    }

    @Test fun malformedScalesAreRefused() {
        fun with(scale: String) = JsonParser.parseString("""
            {"layout_id":"groups","schema_version":1,"groups":[{"group_id":"g1","name":"U","tags":["a"],"scale":$scale}],
             "updated_at":"2026-10-15T12:00:00.000Z","origin_id":"desktop"}""").asJsonObject
        assertTrue(ReviewTagGroups.parse(with("[\"a\"]"))!!.usesScales)
        assertNull(ReviewTagGroups.parse(with("\"a\"")))
        assertNull(ReviewTagGroups.parse(with("[\" \"]")))
        assertNull(ReviewTagGroups.parse(with("[\"${"x".repeat(201)}\"]")))
        // An explicit null is a chip group.
        assertFalse(ReviewTagGroups.parse(with("null"))!!.usesScales)
    }
}

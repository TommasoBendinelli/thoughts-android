// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Edit
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Group
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Problem
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Phases: when a review asks a group (before the answer, before and after, after), kept through every rewrite. */
class ReviewTagPhasesTest {
    private lateinit var directory: File
    private val previousGroups = ReviewTagGroups.file
    private val previousMerges = ReviewTagMerges.file

    @Before fun setUp() {
        directory = Files.createTempDirectory("tag-phases").toFile()
        ReviewTagGroups.file = { File(directory, "tag-groups.json") }
        ReviewTagMerges.file = { File(directory, "tag-merges.json") }
    }

    @After fun tearDown() {
        ReviewTagGroups.file = previousGroups
        ReviewTagMerges.file = previousMerges
        directory.deleteRecursively()
    }

    /** The live layout once the server keeps phases: Feeling before, Recall before and after. */
    private val liveRecord = """
        {"layout_id":"groups","schema_version":1,
         "groups":[{"group_id":"f","name":"Feeling","tags":["Meh","Good"],"scale":["Meh","Good"],"phase":"before"},
                   {"group_id":"r","name":"Recall","tags":["Forgot","Partly recalled","Recalled"],"scale":["Forgot","Partly recalled","Recalled"],"phase":"before_and_after"},
                   {"group_id":"u","name":"Understanding","tags":["Lost","I get it"],"scale":["Lost","I get it"]},
                   {"group_id":"wl","name":"Structure","tags":["Missing steps","Fine"],"scale":["Missing steps","Fine"],"section":"Work left"}],
         "updated_at":"2026-11-03T12:00:00.000Z","origin_id":"desktop"}
    """.trimIndent()

    private fun parse(json: String) = checkNotNull(ReviewTagGroups.parse(JsonParser.parseString(json).asJsonObject))

    private fun withPhase(phase: Any?): JsonObject = JsonParser.parseString(
        """{"layout_id":"groups","schema_version":1,"groups":[{"group_id":"a","name":"A","tags":["x"]}],
            "updated_at":"2026-11-03T12:00:00.000Z","origin_id":"desktop"}""",
    ).asJsonObject.apply {
        val group = getAsJsonArray("groups")[0].asJsonObject
        when (phase) {
            null -> group.add("phase", JsonNull.INSTANCE)
            is String -> group.addProperty("phase", phase)
            is Number -> group.addProperty("phase", phase)
            is JsonObject -> group.add("phase", phase)
            else -> error("unsupported")
        }
    }

    private fun refused(problem: Problem, block: () -> Unit) {
        try {
            block()
            fail("expected $problem")
        } catch (refused: ReviewTagGroups.EditRefused) {
            assertEquals(problem, refused.problem)
        }
    }

    @Test fun phasesAreReadAndWrittenBackUnchanged() {
        val layout = parse(liveRecord)
        assertEquals(listOf("before", "before_and_after", null, null), layout.groups.map { it.phase })
        assertEquals(listOf(true, true, false, false), layout.groups.map { it.askedBefore })
        assertEquals(listOf(false, true, true, true), layout.groups.map { it.askedAfter })
        // Known now: not kept a second time among the unknown fields.
        assertTrue(layout.groups.all { "phase" !in it.extra })
        val payload = layout.payload()
        assertEquals(JsonParser.parseString(liveRecord), payload)
        assertEquals(layout, ReviewTagGroups.parse(payload))
        assertTrue(layout.usesPhases)
        // A group asked after the answer writes no field at all.
        assertFalse(payload.getAsJsonArray("groups")[2].asJsonObject.has("phase"))
        assertEquals("before", ReviewTagGroups.PHASE_BEFORE)
        assertEquals("before_and_after", ReviewTagGroups.PHASE_BEFORE_AND_AFTER)
    }

    @Test fun anUnknownPhaseIsKeptUnchangedAndTheGroupCountsAsAfter() {
        for (value in listOf<Any>("during", "Before", " before", 3, JsonObject().apply { addProperty("when", "later") })) {
            val record = withPhase(value)
            val layout = parse(record.toString())
            val group = layout.groups.single()
            assertNull(value.toString(), group.phase)
            assertFalse(value.toString(), group.askedBefore)
            assertTrue(value.toString(), group.askedAfter)
            // Written back exactly as it came, and still a record that needs the capability.
            assertEquals(value.toString(), record, layout.payload())
            assertTrue(value.toString(), layout.usesPhases)
        }
        // A null phase is no phase.
        val none = parse(withPhase(null).toString())
        assertNull(none.groups.single().phase)
        assertFalse(none.usesPhases)
        assertFalse(parse(withPhase("before").toString().replace(",\"phase\":\"before\"", "")).usesPhases)
    }

    @Test fun phaseIsSetClearedAndReplacesAnUnknownOne() {
        val groups = listOf(Group("a", "Feeling", listOf("x")), Group("b", "Recall", listOf("y"), scale = listOf("y")))
        val set = ReviewTagGroups.applyEdit(groups, Edit.SetPhase("a", "before"))
        assertEquals(listOf("before", null), set.map { it.phase })
        val both = ReviewTagGroups.applyEdit(set, Edit.SetPhase("b", "before_and_after"))
        assertEquals(listOf("before", "before_and_after"), both.map { it.phase })
        assertEquals(listOf(null, "before_and_after"), ReviewTagGroups.applyEdit(both, Edit.SetPhase("a", null)).map { it.phase })
        refused(Problem.INVALID) { ReviewTagGroups.applyEdit(groups, Edit.SetPhase("a", "during")) }
        refused(Problem.NO_SUCH_GROUP) { ReviewTagGroups.applyEdit(groups, Edit.SetPhase("zz", "before")) }
        assertEquals(Problem.INVALID, ReviewTagGroups.problemOf(listOf(Group("a", "A", emptyList(), phase = "during"))))
        // "Ask" on a group with a later client's phase replaces it, also with After the answer.
        val unknown = parse(withPhase("during").toString()).groups
        val replaced = ReviewTagGroups.applyEdit(unknown, Edit.SetPhase("a", "before")).single()
        assertEquals("before", replaced.phase)
        assertFalse("phase" in replaced.extra)
        val cleared = ReviewTagGroups.applyEdit(unknown, Edit.SetPhase("a", null)).single()
        assertNull(cleared.phase)
        assertFalse("phase" in cleared.extra)
    }

    @Test fun everyOtherEditKeepsThePhases() {
        val groups = parse(liveRecord).groups
        fun phases(after: List<Group>) = after.associate { it.id to it.phase }
        val expected = phases(groups)
        val edits = listOf(
            Edit.MoveTag("Fine", "u"),
            Edit.MoveTags(listOf("Meh", "Lost"), "wl"),
            Edit.SetScale("r", listOf("Recalled", "Forgot")),
            Edit.Rename("f", "Mood"),
            Edit.MoveGroup("r", +1),
            Edit.SetSection("f", "Before"),
        )
        for (edit in edits) assertEquals(edit.toString(), expected, phases(ReviewTagGroups.applyEdit(groups, edit)))
        assertNull(ReviewTagGroups.applyEdit(groups, Edit.AddGroup("Later", id = "n")).last().phase)
        assertEquals(expected, phases(ReviewTagGroups.resolved(groups, mapOf("Good" to "Meh"))))
    }

    @Test fun theRecallGroupIsTheFirstBeforeAndAfterSliderWithSteps() {
        val layout = parse(liveRecord)
        assertEquals("r", ReviewTagGroups.recallGroup(layout.groups)?.id)
        // Before only, a chip group or a slider without steps is not one.
        assertNull(ReviewTagGroups.recallGroup(listOf(
            Group("f", "Feeling", listOf("a", "b"), scale = listOf("a", "b"), phase = "before"),
            Group("c", "Chips", listOf("x"), phase = "before_and_after"),
            Group("e", "Empty", emptyList(), scale = emptyList(), phase = "before_and_after"),
            Group("u", "Understanding", listOf("y"), scale = listOf("y")),
        )))
        assertEquals("r2", ReviewTagGroups.recallGroup(listOf(
            Group("c", "Chips", listOf("x"), phase = "before_and_after"),
            Group("r2", "Recall", listOf("y", "z"), scale = listOf("y", "z"), phase = "before_and_after"),
            Group("r3", "Recall again", listOf("w"), scale = listOf("w"), phase = "before_and_after"),
        ))?.id)
        assertEquals(listOf("f", "r"), ReviewAnswerPhases.beforeGroups(layout.groups).map { it.id })
    }

    @Test fun localEditsAndServerRecordsKeepPhasesAndUnknownOnes() {
        val record = JsonParser.parseString(liveRecord).asJsonObject.apply {
            getAsJsonArray("groups")[2].asJsonObject.add("phase", JsonPrimitive("during"))
        }
        ReviewTagGroups.installServer(record)
        assertEquals(listOf("before", "before_and_after", null, null), ReviewTagGroups.groups().map { it.phase })
        val edited = checkNotNull(ReviewTagGroups.edit(Edit.SetPhase("wl", "before"), "phone", Instant.parse("2026-11-03T13:00:00Z")))
        assertEquals(listOf("before", "before_and_after", null, "before"), edited.groups.map { it.phase })
        val pending = checkNotNull(ReviewTagGroups.pendingUpload()).payload.getAsJsonArray("groups")
        assertEquals("during", pending[2].asJsonObject.get("phase").asString)
        assertEquals("before", pending[3].asJsonObject.get("phase").asString)
        assertEquals(edited, ReviewTagGroups.layout())
        // An edit that changes nothing saves nothing.
        assertEquals(edited, ReviewTagGroups.edit(Edit.SetPhase("wl", "before"), "phone", Instant.parse("2026-11-03T13:01:00Z")))
    }
}

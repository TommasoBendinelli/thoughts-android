// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Edit
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Group
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Problem
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class ReviewTagGroupsTest {
    private lateinit var directory: File
    private val previousGroups = ReviewTagGroups.file
    private val previousMerges = ReviewTagMerges.file

    @Before fun setUp() {
        directory = Files.createTempDirectory("tag-groups").toFile()
        ReviewTagGroups.file = { File(directory, "tag-groups.json") }
        ReviewTagMerges.file = { File(directory, "tag-merges.json") }
    }

    @After fun tearDown() {
        ReviewTagGroups.file = previousGroups
        ReviewTagMerges.file = previousMerges
        directory.deleteRecursively()
    }

    private fun record(vararg groups: Group, updatedAt: String = "2026-10-08T21:00:00.000Z", origin: String = "desktop") =
        ReviewTagGroups.Layout(groups.toList(), updatedAt, origin).payload()

    private fun group(id: String, name: String, vararg tags: String) = Group(id, name, tags.toList())

    private fun JsonObject.with(change: JsonObject.() -> Unit) = deepCopy().apply(change)

    private fun refused(problem: Problem, block: () -> Unit) {
        try {
            block()
            fail("Expected $problem")
        } catch (refused: ReviewTagGroups.EditRefused) {
            assertEquals(problem, refused.problem)
        }
    }

    @Test fun parsesTheSpecRecordAndWritesItBackUnchanged() {
        val payload = JsonParser.parseString("""
            {"layout_id":"groups","schema_version":1,
             "groups":[{"group_id":"g1","name":"Understanding","tags":["correct","wrong","Slowly understanding it"]},
                       {"group_id":"g2","name":"Interest","tags":["interesting","cool"]}],
             "updated_at":"2026-10-08T21:00:00.000Z","origin_id":"desktop"}
        """).asJsonObject
        val layout = ReviewTagGroups.parse(payload)!!
        assertEquals(listOf("Understanding", "Interest"), layout.groups.map { it.name })
        assertEquals(listOf("correct", "wrong", "Slowly understanding it"), layout.groups[0].tags)
        assertEquals(payload, layout.payload())
    }

    @Test fun recordsThatBreakTheRulesAreRefused() {
        val valid = record(group("g1", "Understanding", "correct"), group("g2", "Interest", "cool"))
        assertNotNull(ReviewTagGroups.parse(valid))
        val broken = listOf(
            valid.with { addProperty("layout_id", "other") },
            valid.with { addProperty("schema_version", 2) },
            valid.with { remove("updated_at") },
            record(group("g1", "Other", "correct")),
            record(group("g1", " other ", "correct")),
            record(group("g1", "Interest"), group("g2", "INTEREST")),
            record(group("g1", "Interest"), group("g1", "Work")),
            record(group("", "Interest")),
            record(group("x".repeat(65), "Interest")),
            record(group("g1", "   ")),
            record(group("g1", "n".repeat(81))),
            record(group("g1", "Interest", "t".repeat(201))),
            record(group("g1", "Interest", "")),
            record(*(1..51).map { group("g$it", "Group $it") }.toTypedArray()),
            record(group("g1", "A", *(1..600).map { "a$it" }.toTypedArray()),
                group("g2", "B", *(1..401).map { "b$it" }.toTypedArray())),
            valid.with { getAsJsonArray("groups")[0].asJsonObject.add("tags", JsonArray().apply { add(JsonObject()) }) },
        )
        broken.forEachIndexed { index, payload -> assertNull("record $index", ReviewTagGroups.parse(payload)) }
        assertNotNull(ReviewTagGroups.parse(record(group("g1", "n".repeat(80), "t".repeat(200)))))
        assertNotNull(ReviewTagGroups.parse(record(*(1..50).map { group("g$it", "Group $it") }.toTypedArray())))
    }

    @Test fun aTagInSeveralGroupsStaysInTheFirst() {
        val layout = ReviewTagGroups.parse(record(group("g1", "A", "cool", "cool", "x"), group("g2", "B", "cool", "y")))!!
        assertEquals(listOf(listOf("cool", "x"), listOf("y")), layout.groups.map { it.tags })
    }

    @Test fun withoutARecordEveryTagIsInOther() {
        val sections = ReviewTagGroups.sections(listOf("correct", "wrong", "notation"))
        assertEquals(1, sections.size)
        assertNull(sections.single().group)
        assertEquals(listOf("correct", "wrong", "notation"), sections.single().tags)
    }

    @Test fun groupsComeFirstThenOtherWhichIsHiddenWhenEmpty() {
        val groups = listOf(group("g1", "Understanding", "wrong", "correct"), group("g2", "Interest"), group("g3", "Work", "new one", "Draft"))
        val known = listOf("correct", "notation", "wrong", "skip", "Skip")
        val sections = ReviewTagGroups.sections(groups, known, emptyMap())
        assertEquals(listOf("Understanding", "Interest", "Work", null), sections.map { it.group?.name })
        // Most used first; tags not seen on this phone yet follow alphabetically.
        assertEquals(listOf("correct", "wrong"), sections[0].tags)
        assertEquals(emptyList<String>(), sections[1].tags)
        assertEquals(listOf("Draft", "new one"), sections[2].tags)
        // Skip is not a tag and never shows.
        assertEquals(listOf("notation"), sections[3].tags)
        val allGrouped = ReviewTagGroups.sections(groups, listOf("correct", "wrong"), emptyMap())
        assertEquals(listOf("Understanding", "Interest", "Work"), allGrouped.map { it.group?.name })
    }

    @Test fun mergedTagsShowUnderTheGroupOfTheirFinalName() {
        val mapping = mapOf("Good" to "correct", "oops" to "wrong", "meh" to "cool")
        val groups = listOf(group("g1", "Understanding", "Good", "correct", "oops", "skip"), group("g2", "Interest", "wrong", "meh"))
        val sections = ReviewTagGroups.sections(groups, listOf("correct", "wrong", "cool", "notation", "Good"), mapping)
        assertEquals(listOf("correct", "wrong"), sections[0].tags)
        // "wrong" is already in the first group (via "oops"), so it collapses there.
        assertEquals(listOf("cool"), sections[1].tags)
        assertEquals(listOf("notation"), sections[2].tags)
    }

    @Test fun mergeMappingIsReadFromTheRecordedMerges() {
        ReviewTagMerges.record(listOf("Good"), "correct", "phone", Instant.parse("2026-10-05T10:00:00Z"))
        ReviewTagGroups.installServer(record(group("g1", "Understanding", "Good")))
        val sections = ReviewTagGroups.sections(listOf("correct", "notation"))
        assertEquals(listOf("correct"), sections[0].tags)
        assertEquals(listOf("notation"), sections[1].tags)
    }

    @Test fun moveTagsHereMovesSeveralTagsInOneEdit() {
        var groups = listOf(group("g1", "Understanding", "correct", "wrong"), group("g2", "Interest", "cool"))
        groups = ReviewTagGroups.applyEdit(groups, Edit.MoveTags(listOf("wrong", "cool", "notation"), "g1"))
        assertEquals(listOf(listOf("correct", "wrong", "cool", "notation"), emptyList()), groups.map { it.tags })
        // Into Other: the tags leave every group.
        groups = ReviewTagGroups.applyEdit(groups, Edit.MoveTags(listOf("correct", "cool"), null))
        assertEquals(listOf(listOf("wrong", "notation"), emptyList()), groups.map { it.tags })
        // Saved as one record write.
        ReviewTagGroups.edit(Edit.AddGroup("Interest", id = "g2"), "phone", Instant.parse("2026-10-10T10:00:00Z"))
        val saved = ReviewTagGroups.edit(Edit.MoveTags(listOf("cool", "fun"), "g2"), "phone", Instant.parse("2026-10-10T10:00:01Z"))
        assertEquals(listOf("cool", "fun"), saved!!.groups.single().tags)
        assertEquals("2026-10-10T10:00:01.000Z", saved.updatedAt)
        refused(Problem.NO_SUCH_GROUP) { ReviewTagGroups.applyEdit(groups, Edit.MoveTags(listOf("cool"), "missing")) }
    }

    @Test fun editsGiveAWholeValidRecord() {
        var groups = ReviewTagGroups.applyEdit(emptyList(), Edit.AddGroup(" Understanding  now ", id = "g1"))
        assertEquals(listOf(group("g1", "Understanding now")), groups)
        groups = ReviewTagGroups.applyEdit(groups, Edit.AddGroup("Interest", tag = "cool", id = "g2"))
        groups = ReviewTagGroups.applyEdit(groups, Edit.MoveTag("correct", "g1"))
        groups = ReviewTagGroups.applyEdit(groups, Edit.MoveTag("cool", "g1"))
        assertEquals(listOf(listOf("correct", "cool"), emptyList()), groups.map { it.tags })
        groups = ReviewTagGroups.applyEdit(groups, Edit.MoveTag("cool", null))
        assertEquals(listOf(listOf("correct"), emptyList()), groups.map { it.tags })
        groups = ReviewTagGroups.applyEdit(groups, Edit.MoveGroup("g2", -1))
        assertEquals(listOf("g2", "g1"), groups.map { it.id })
        assertEquals(groups, ReviewTagGroups.applyEdit(groups, Edit.MoveGroup("g2", -1)))
        groups = ReviewTagGroups.applyEdit(groups, Edit.Rename("g1", "understanding NOW"))
        assertEquals("understanding NOW", groups[1].name)
        groups = ReviewTagGroups.applyEdit(groups, Edit.DeleteGroup("g1"))
        // The deleted group's tags simply leave the record and show in Other.
        assertEquals(listOf(group("g2", "Interest")), groups)
        assertEquals(listOf("correct"), ReviewTagGroups.sections(groups, listOf("correct"), emptyMap()).last().tags)
        assertNotNull(ReviewTagGroups.parse(ReviewTagGroups.Layout(groups, "2026-10-08T21:00:00.000Z", "phone").payload()))
    }

    @Test fun editsThatWouldBreakTheRecordAreRefused() {
        val groups = listOf(group("g1", "Interest", "cool"))
        refused(Problem.NAME_RESERVED) { ReviewTagGroups.applyEdit(groups, Edit.AddGroup("other")) }
        refused(Problem.NAME_TAKEN) { ReviewTagGroups.applyEdit(groups, Edit.AddGroup("INTEREST")) }
        refused(Problem.NAME_EMPTY) { ReviewTagGroups.applyEdit(groups, Edit.Rename("g1", "  ")) }
        refused(Problem.NAME_TOO_LONG) { ReviewTagGroups.applyEdit(groups, Edit.AddGroup("n".repeat(81))) }
        refused(Problem.TAG_TOO_LONG) { ReviewTagGroups.applyEdit(groups, Edit.MoveTag("t".repeat(201), "g1")) }
        refused(Problem.NO_SUCH_GROUP) { ReviewTagGroups.applyEdit(groups, Edit.MoveTag("x", "missing")) }
        refused(Problem.TOO_MANY_GROUPS) {
            ReviewTagGroups.applyEdit((1..50).map { group("g$it", "Group $it") }, Edit.AddGroup("One more"))
        }
        // Renaming a group to its own name in other letters is allowed.
        assertEquals("interest", ReviewTagGroups.applyEdit(groups, Edit.Rename("g1", "interest"))[0].name)
    }

    @Test fun editsMapMergedNamesAndDropSkip() {
        val groups = listOf(group("g1", "Understanding", "Good", "skip", "correct"))
        val next = ReviewTagGroups.applyEdit(groups, Edit.MoveTag("oops", "g1"), mapOf("Good" to "correct", "oops" to "wrong"))
        assertEquals(listOf("correct", "wrong"), next.single().tags)
        assertEquals(next, ReviewTagGroups.applyEdit(next, Edit.MoveTag("Skip", "g1")))
    }

    @Test fun localEditsSaveTheWholeRecordWithAFreshTimestamp() {
        val first = ReviewTagGroups.edit(Edit.AddGroup("Interest", tag = "cool"), "phone", Instant.parse("2026-10-08T21:00:00Z"))!!
        assertEquals("2026-10-08T21:00:00.000Z", first.updatedAt)
        assertTrue(ReviewTagGroups.pending())
        // The clock went back: the new record is still later than the one it replaces.
        val second = ReviewTagGroups.edit(Edit.AddGroup("Work"), "phone", Instant.parse("2026-10-08T20:00:00Z"))!!
        assertEquals("2026-10-08T21:00:00.001Z", second.updatedAt)
        assertEquals(listOf("Interest", "Work"), ReviewTagGroups.groups().map { it.name })
        val saved = JsonParser.parseString(File(directory, "tag-groups.json").readText()).asJsonObject
        assertEquals(second, ReviewTagGroups.parse(saved.getAsJsonObject("record")))
        val upload = ReviewTagGroups.pendingUpload()!!
        assertEquals(second.payload(), upload.payload)
        assertEquals(second.updatedAt, upload.clientTimestamp)
        assertEquals(upload, ReviewTagGroups.uploadOf(second))
        // An edit that changes nothing saves nothing.
        assertEquals(second, ReviewTagGroups.edit(Edit.MoveTag("notation", null), "phone", Instant.parse("2026-10-09T00:00:00Z")))
        assertEquals(second, ReviewTagGroups.layout())
    }

    @Test fun nothingIsSentUntilTheFirstEdit() {
        assertNull(ReviewTagGroups.pendingUpload())
        assertNull(ReviewTagGroups.edit(Edit.MoveTag("cool", null), "phone"))
        assertNull(ReviewTagGroups.pendingUpload())
        assertFalse(File(directory, "tag-groups.json").exists())
    }

    @Test fun serverRecordsReplaceTheLocalOneWhenNothingIsPending() {
        ReviewTagGroups.installServer(record(group("g1", "Interest", "cool")))
        assertEquals(listOf("Interest"), ReviewTagGroups.groups().map { it.name })
        assertFalse(ReviewTagGroups.pending())
        assertNull(ReviewTagGroups.pendingUpload())
        // Even an earlier server record wins: the server decides which write was last.
        ReviewTagGroups.installServer(record(group("g2", "Work"), updatedAt = "2026-10-01T00:00:00.000Z"))
        assertEquals(listOf("Work"), ReviewTagGroups.groups().map { it.name })
        // Records that break the rules, or none at all, change nothing.
        ReviewTagGroups.installServer(record(group("g3", "Other")))
        ReviewTagGroups.installServer(null)
        assertEquals(listOf("Work"), ReviewTagGroups.groups().map { it.name })
    }

    @Test fun aPendingEditStaysUntilAcceptedOrOvertaken() {
        ReviewTagGroups.installServer(record(group("g1", "Interest", "cool"), updatedAt = "2026-10-08T20:00:00.000Z"))
        val local = ReviewTagGroups.edit(Edit.AddGroup("Work", id = "g2"), "phone", Instant.parse("2026-10-08T21:00:00Z"))!!
        // The server still has the earlier record: keep the edit and send it.
        ReviewTagGroups.installServer(record(group("g1", "Interest", "cool"), updatedAt = "2026-10-08T20:00:00.000Z"))
        assertTrue(ReviewTagGroups.pending())
        assertEquals(local, ReviewTagGroups.layout())
        // The server now holds it: accepted.
        ReviewTagGroups.installServer(local.payload())
        assertFalse(ReviewTagGroups.pending())
        assertEquals(local, ReviewTagGroups.layout())
        // A later edit made elsewhere overtakes a pending one: last write wins.
        ReviewTagGroups.edit(Edit.Rename("g2", "Work progress"), "phone", Instant.parse("2026-10-08T22:00:00Z"))
        ReviewTagGroups.installServer(record(group("g9", "Desktop"), updatedAt = "2026-10-08T23:00:00.000Z"))
        assertFalse(ReviewTagGroups.pending())
        assertEquals(listOf("Desktop"), ReviewTagGroups.groups().map { it.name })
    }

    @Test fun laterWritesWinByTimeThenOrigin() {
        fun layout(at: String, origin: String) = ReviewTagGroups.Layout(emptyList(), at, origin)
        assertTrue(ReviewTagGroups.newer(layout("2026-10-08T21:00:00.001Z", "a"), layout("2026-10-08T21:00:00Z", "b")))
        assertFalse(ReviewTagGroups.newer(layout("2026-10-08T21:00:00Z", "b"), layout("2026-10-08T21:00:00.001Z", "a")))
        assertTrue(ReviewTagGroups.newer(layout("2026-10-08T21:00:00.000Z", "b"), layout("2026-10-08T21:00:00Z", "a")))
    }
}

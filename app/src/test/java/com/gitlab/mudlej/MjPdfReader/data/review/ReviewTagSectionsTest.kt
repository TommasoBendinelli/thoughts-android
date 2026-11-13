// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Edit
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Group
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Problem
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

/** Sections: a display header shared by adjacent groups, kept through every rewrite of the record. */
class ReviewTagSectionsTest {
    private lateinit var directory: File
    private val previousGroups = ReviewTagGroups.file
    private val previousMerges = ReviewTagMerges.file

    @Before fun setUp() {
        directory = Files.createTempDirectory("tag-sections").toFile()
        ReviewTagGroups.file = { File(directory, "tag-groups.json") }
        ReviewTagMerges.file = { File(directory, "tag-merges.json") }
    }

    @After fun tearDown() {
        ReviewTagGroups.file = previousGroups
        ReviewTagMerges.file = previousMerges
        directory.deleteRecursively()
    }

    /** The user's layout of 2026-10-22, with the sections it is meant to get. */
    private val userRecord = """
        {"layout_id":"groups","schema_version":1,
         "groups":[{"group_id":"r","name":"Recall","tags":["Forgot","Recalled"],"scale":["Forgot","Recalled"]},
                   {"group_id":"u","name":"Understanding","tags":["Lost","Got it"],"scale":["Lost","Got it"]},
                   {"group_id":"i","name":"Interest","tags":["Meh","Wow"],"scale":["Meh","Wow"]},
                   {"group_id":"e","name":"Study effort","tags":["Skimmed","Studied"],"scale":["Skimmed","Studied"]},
                   {"group_id":"wl1","name":"Structure","tags":["Missing steps","Fine"],"scale":["Missing steps","Fine"],"section":"Work left"},
                   {"group_id":"wl2","name":"Style","tags":["Messy","Clean"],"scale":["Messy","Clean"],"section":"Work left"},
                   {"group_id":"wl3","name":"Content","tags":["Gaps","Complete"],"scale":["Gaps","Complete"],"section":"Work left"},
                   {"group_id":"wd1","name":"Structure done","tags":["Reordered"],"scale":["Reordered"],"section":"Work done"},
                   {"group_id":"wd2","name":"Style done","tags":["Polished"],"scale":["Polished"],"section":"Work done"},
                   {"group_id":"wd3","name":"Content done","tags":["Added"],"scale":["Added"],"section":"Work done"},
                   {"group_id":"s","name":"Source","tags":["Me","AI"],"scale":["Me","AI"]}],
         "updated_at":"2026-10-22T12:00:00.000Z","origin_id":"desktop"}
    """.trimIndent()

    private fun parse(json: String) = checkNotNull(ReviewTagGroups.parse(JsonParser.parseString(json).asJsonObject))

    private fun withSection(section: Any?): JsonObject = JsonParser.parseString(
        """{"layout_id":"groups","schema_version":1,"groups":[{"group_id":"a","name":"A","tags":["x"]}],
            "updated_at":"2026-10-22T12:00:00.000Z","origin_id":"desktop"}""",
    ).asJsonObject.apply {
        val group = getAsJsonArray("groups")[0].asJsonObject
        when (section) {
            null -> group.add("section", com.google.gson.JsonNull.INSTANCE)
            is String -> group.addProperty("section", section)
            is Number -> group.addProperty("section", section)
            is JsonObject -> group.add("section", section)
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

    @Test fun sectionsAreReadAndWrittenBackUnchanged() {
        val layout = parse(userRecord)
        assertEquals(listOf(null, null, null, null, "Work left", "Work left", "Work left", "Work done", "Work done", "Work done", null),
            layout.groups.map { it.section })
        // Known now: not kept a second time among the unknown fields.
        assertTrue(layout.groups.all { "section" !in it.extra })
        val payload = layout.payload()
        assertEquals(JsonParser.parseString(userRecord), payload)
        assertEquals(layout, ReviewTagGroups.parse(payload))
        assertTrue(layout.usesSections)
        // A group without a section writes no field at all.
        assertFalse(payload.getAsJsonArray("groups")[0].asJsonObject.has("section"))
    }

    @Test fun aSectionIsTrimmedAndAnythingElseCountsAsNone() {
        assertEquals("Work left", parse(withSection("  Work left ").toString()).groups[0].section)
        assertEquals("s".repeat(60), parse(withSection("s".repeat(60)).toString()).groups[0].section)
        for (value in listOf<Any?>(null, "", "   ", "s".repeat(61), 3, JsonObject())) {
            val layout = parse(withSection(value).toString())
            assertNull(value.toString(), layout.groups[0].section)
            assertFalse(value.toString(), layout.usesSections)
            assertFalse(value.toString(), layout.payload().getAsJsonArray("groups")[0].asJsonObject.has("section"))
        }
        // Exact match: case matters.
        val groups = listOf(Group("a", "A", emptyList(), section = "Work left"), Group("b", "B", emptyList(), section = "work left"))
        assertEquals(listOf<Int?>(0, 1), ReviewTagGroups.sectionRuns(groups))
    }

    @Test fun adjacentGroupsOfOneSectionShareAHeaderAndARunThatComesBackGetsAnother() {
        assertEquals(listOf(null, null, null, null, 0, 0, 0, 1, 1, 1, null), ReviewTagGroups.sectionRuns(parse(userRecord).groups))
        fun g(id: String, section: String?) = Group(id, id, emptyList(), section = section)
        assertEquals(listOf<Int?>(0, 0, null, 1, 2, 2),
            ReviewTagGroups.sectionRuns(listOf(g("a", "S"), g("b", "S"), g("c", null), g("d", "S"), g("e", "T"), g("f", "T"))))
        assertEquals(emptyList<Int?>(), ReviewTagGroups.sectionRuns(emptyList()))
        assertEquals(listOf("Work left", "Work done"), ReviewTagGroups.sectionNames(parse(userRecord).groups))
    }

    @Test fun sectionIsSetTrimmedAndCleared() {
        val groups = listOf(Group("a", "Structure", listOf("x")), Group("b", "Style", listOf("y")))
        val set = ReviewTagGroups.applyEdit(groups, Edit.SetSection("a", "  Work left  "))
        assertEquals(listOf("Work left", null), set.map { it.section })
        assertEquals(listOf(null, null), ReviewTagGroups.applyEdit(set, Edit.SetSection("a", null)).map { it.section })
        assertEquals(listOf(null, null), ReviewTagGroups.applyEdit(set, Edit.SetSection("a", "   ")).map { it.section })
        assertEquals("s".repeat(60), ReviewTagGroups.applyEdit(groups, Edit.SetSection("b", "s".repeat(60)))[1].section)
        refused(Problem.SECTION_TOO_LONG) { ReviewTagGroups.applyEdit(groups, Edit.SetSection("b", "s".repeat(61))) }
        refused(Problem.NO_SUCH_GROUP) { ReviewTagGroups.applyEdit(groups, Edit.SetSection("zz", "Work left")) }
        // A section that is not trimmed or is blank breaks the record.
        assertEquals(Problem.INVALID, ReviewTagGroups.problemOf(listOf(Group("a", "A", emptyList(), section = " S"))))
        assertEquals(Problem.INVALID, ReviewTagGroups.problemOf(listOf(Group("a", "A", emptyList(), section = ""))))
        assertEquals(Problem.SECTION_TOO_LONG, ReviewTagGroups.problemOf(listOf(Group("a", "A", emptyList(), section = "s".repeat(61)))))
    }

    @Test fun everyEditKeepsTheSections() {
        val groups = parse(userRecord).groups
        fun sections(after: List<Group>) = after.associate { it.id to it.section }
        val expected = sections(groups)
        val edits = listOf(
            Edit.MoveTag("Fine", "wd1"),
            Edit.MoveTag("Fine", null),
            Edit.MoveTag("brand new", "wl2"),
            Edit.MoveTags(listOf("Me", "Gaps"), "wl3"),
            Edit.SetScale("wl1", listOf("Fine", "Missing steps", "Polished")),
            Edit.SetScale("wl2", null),
            Edit.Rename("wl1", "Structure left"),
            Edit.MoveGroup("wl2", -1),
            Edit.MoveGroup("wd1", +1),
        )
        for (edit in edits) assertEquals(edit.toString(), expected, sections(ReviewTagGroups.applyEdit(groups, edit)))
        // A new group has no section; the others keep theirs.
        val added = ReviewTagGroups.applyEdit(groups, Edit.AddGroup("Later", "Fine", id = "n"))
        assertNull(added.last().section)
        assertEquals(expected, sections(added.dropLast(1)))
        val deleted = ReviewTagGroups.applyEdit(groups, Edit.DeleteGroup("wl2"))
        assertEquals(expected - "wl2", sections(deleted))
        // Merged names (Compact tags) are resolved without touching the sections.
        assertEquals(expected, sections(ReviewTagGroups.resolved(groups, mapOf("Fine" to "Missing steps"))))
    }

    @Test fun localEditsAndServerRecordsKeepSectionsAndUnknownFields() {
        val record = JsonParser.parseString(userRecord).asJsonObject.apply {
            getAsJsonArray("groups")[4].asJsonObject.addProperty("color", "teal")
            addProperty("future", true)
        }
        ReviewTagGroups.installServer(record)
        ReviewTagMerges.record(listOf("Messy"), "Clean", "phone", Instant.parse("2026-10-22T12:30:00Z"))
        val edited = checkNotNull(ReviewTagGroups.edit(Edit.MoveTag("Me", "wl1"), "phone", Instant.parse("2026-10-22T13:00:00Z")))
        assertEquals(listOf(null, null, null, null, "Work left", "Work left", "Work left", "Work done", "Work done", "Work done", null),
            edited.groups.map { it.section })
        val pending = checkNotNull(ReviewTagGroups.pendingUpload()).payload
        val structure = pending.getAsJsonArray("groups")[4].asJsonObject
        assertEquals("Work left", structure.get("section").asString)
        assertEquals("teal", structure.get("color").asString)
        assertTrue(pending.get("future").asBoolean)
        // Read back from storage, then a section edit: a fresh record with the new section.
        assertEquals(edited, ReviewTagGroups.layout())
        val moved = checkNotNull(ReviewTagGroups.edit(Edit.SetSection("s", "Work done"), "phone", Instant.parse("2026-10-22T13:01:00Z")))
        assertEquals("Work done", moved.groups.last().section)
        assertEquals(listOf(null, null, null, null, 0, 0, 0, 1, 1, 1, 1), ReviewTagGroups.sectionRuns(moved.groups))
        // An edit that changes nothing saves nothing.
        assertEquals(moved, ReviewTagGroups.edit(Edit.SetSection("s", " Work done "), "phone", Instant.parse("2026-10-22T13:02:00Z")))
    }
}

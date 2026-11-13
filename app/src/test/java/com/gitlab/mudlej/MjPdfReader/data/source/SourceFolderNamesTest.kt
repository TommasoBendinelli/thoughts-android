// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFolderNamesTest {
    /** Identical in the server, desktop and Android tests (server tests/fixtures/source_folder_names_vectors.json). */
    private val vectors = JsonParser.parseString(
        requireNotNull(javaClass.classLoader!!.getResource("source-folder-names/source_folder_names_vectors.json")).readText(),
    ).asJsonObject
    private val renames = listOf(
        SourceFolderNames.Rename("f1", "docs-template", "docs-template-c8ee33"),
        SourceFolderNames.Rename("f2", "docs-Benchmark", "docs-benchmark-f9d64d"),
        SourceFolderNames.Rename("f3", "docs-Benchmark-Conf", "docs-benchmark-conf-086044"),
    )
    private val map = SourceFolderNames.toMap(renames)

    /** A vector map as written (chains included: Rule P itself never rescans its output). */
    private fun vectorMap(group: com.google.gson.JsonObject) =
        group.getAsJsonArray("map").associate { it.asJsonObject.get("old").asString to it.asJsonObject.get("new").asString }

    @Test fun `shared vectors - Rule P on strings`() {
        var cases = 0
        for (group in vectors.getAsJsonArray("rule_p")) {
            val map = vectorMap(group.asJsonObject)
            for (case in group.asJsonObject.getAsJsonArray("cases")) {
                val input = case.asJsonObject.get("input").asString
                val expected = case.asJsonObject.get("output").asString
                assertEquals(case.asJsonObject.get("note")?.asString ?: input, expected, SourceFolderNames.rewrite(input, map))
                assertEquals(input, expected != input, SourceFolderNames.contains(input, map))
                cases++
            }
        }
        assertTrue(cases >= 20)
    }

    @Test fun `shared vectors - Rule P on JSON values`() {
        for (group in vectors.getAsJsonArray("rule_p_values")) {
            val map = vectorMap(group.asJsonObject)
            for (case in group.asJsonObject.getAsJsonArray("cases")) {
                val output = case.asJsonObject.get("output")
                assertEquals(output, SourceFolderNames.rewriteJsonValues(case.asJsonObject.get("input"), map))
            }
        }
    }

    @Test fun `shared vectors - Rule P on manifest text`() {
        var cases = 0
        for (group in vectors.getAsJsonArray("rule_p_manifests")) {
            val map = vectorMap(group.asJsonObject)
            for (case in group.asJsonObject.getAsJsonArray("cases")) {
                assertEquals(case.asJsonObject.get("note")?.asString, case.asJsonObject.get("output").asString,
                    SourceFolderNames.rewrite(case.asJsonObject.get("input").asString, map))
                cases++
            }
        }
        assertTrue(cases >= 1)
    }

    @Test fun `shared vectors - maps Fedora sends are accepted`() {
        // Every new name Fedora computes passes parse(); a map with a chain is refused as a whole.
        val valid = Regex(vectors.get("valid_folder_regex").asString)
        val first = vectors.getAsJsonArray("rule_p")[0].asJsonObject
        val parsed = SourceFolderNames.parse(first.getAsJsonArray("map"))
        assertEquals(first.getAsJsonArray("map").size(), parsed.size)
        assertTrue(parsed.all { valid.matches(it.new) })
        assertTrue(SourceFolderNames.parse(vectors.getAsJsonArray("rule_p")[1].asJsonObject.getAsJsonArray("map")).isEmpty())
        for (case in vectors.getAsJsonArray("folder_name")) {
            val new = case.asJsonObject.get("new").asString
            assertEquals(new, SourceFolderNames.parse(JsonParser.parseString(
                """[{"folder_id":"f","old":"${case.asJsonObject.get("old").asString}","new":"$new"}]""")).single().new)
        }
    }

    @Test fun `a legacy Project id is never rewritten`() {
        // Legacy Projects are called source:docs-<folder>; their default folder is sha256 of that id.
        for (kept in listOf("source:docs-template", "project:source:docs-template", "source:docs-template/x",
            "library_project:source:docs-template", "\"source:docs-template\"", "resource:docs-template")) {
            assertEquals(kept, SourceFolderNames.rewrite(kept, map))
            assertFalse(kept, SourceFolderNames.contains(kept, map))
        }
        assertEquals("project:docs-template-c8ee33", SourceFolderNames.rewrite("project:docs-template", map))
        assertEquals("Source:docs-template-c8ee33", SourceFolderNames.rewrite("Source:docs-template", map))
        assertEquals("source: docs-template-c8ee33", SourceFolderNames.rewrite("source: docs-template", map))
        assertEquals("source:docs-template docs-template-c8ee33/a.tex source:docs-Benchmark",
            SourceFolderNames.rewrite("source:docs-template docs-template/a.tex source:docs-Benchmark", map))
        val project = JsonParser.parseString("""{"id":"source:docs-template","project_id":"source:docs-template",
            "source:docs-template":{"path":"docs-template"},"source_folders":[{"path":"docs-template"}]}""").asJsonObject
        val rewritten = SourceFolderNames.rewriteJsonValues(project, map).asJsonObject
        assertEquals("source:docs-template", rewritten.get("id").asString)
        assertEquals("source:docs-template", rewritten.get("project_id").asString)
        assertEquals("docs-template-c8ee33", rewritten.getAsJsonObject("source:docs-template").get("path").asString)
        assertEquals("docs-template-c8ee33", rewritten.getAsJsonArray("source_folders")[0].asJsonObject.get("path").asString)
    }

    @Test fun `one pass never rescans its output`() {
        // A chain would turn a into c if output were scanned again; parse refuses chains, rewrite stays one pass.
        val chained = mapOf("docs-a" to "docs-b", "docs-b" to "docs-c")
        assertEquals("docs-b/x docs-c/y", SourceFolderNames.rewrite("docs-a/x docs-b/y", chained))
    }

    @Test fun `json values and keys follow, source text does not`() {
        val value = JsonParser.parseString("""{
            "source_file":"docs-template/a.tex",
            "contents":"see docs-template/a.tex",
            "base_contents":"docs-template",
            "note":"line one\ndocs-template/a.tex",
            "files":{"docs-template/a.tex":{"project":"docs-template"},"docs-template-c8ee33/b.tex":{}},
            "list":["project:docs-template", 3, true, null]
        }""").asJsonObject
        val rewritten = SourceFolderNames.rewriteJsonValues(value, map).asJsonObject
        assertEquals("docs-template-c8ee33/a.tex", rewritten.get("source_file").asString)
        assertEquals("see docs-template/a.tex", rewritten.get("contents").asString)
        assertEquals("docs-template", rewritten.get("base_contents").asString)
        assertEquals("line one\ndocs-template/a.tex", rewritten.get("note").asString)
        val files = rewritten.getAsJsonObject("files")
        assertEquals(setOf("docs-template-c8ee33/a.tex", "docs-template-c8ee33/b.tex"), files.keySet())
        assertEquals("docs-template-c8ee33", files.getAsJsonObject("docs-template-c8ee33/a.tex").get("project").asString)
        assertEquals("project:docs-template-c8ee33", rewritten.getAsJsonArray("list")[0].asString)
        // The input is not modified, and an unchanged value is returned as is.
        assertEquals("docs-template/a.tex", value.get("source_file").asString)
        assertSame(rewritten, SourceFolderNames.rewriteJsonValues(rewritten, map))
    }

    @Test fun `a key already in its new spelling wins`() {
        val value = JsonParser.parseString("""{"docs-template":"old","docs-template-c8ee33":"new"}""").asJsonObject
        val rewritten = SourceFolderNames.rewriteJsonValues(value, map).asJsonObject
        assertEquals(setOf("docs-template-c8ee33"), rewritten.keySet())
        assertEquals("new", rewritten.get("docs-template-c8ee33").asString)
    }

    @Test fun `parse keeps valid one-to-one maps only`() {
        fun parse(text: String) = SourceFolderNames.parse(JsonParser.parseString(text))
        assertEquals(1, parse("""[{"folder_id":"f","old":"docs-a","new":"docs-a-123456"},{"old":"x","new":"docs-y"},{"old":"docs-b","new":"-bad"}]""").size)
        assertTrue(parse("""[{"old":"docs-a","new":"docs-c"},{"old":"docs-b","new":"docs-c"}]""").isEmpty())
        assertTrue(parse("""[{"old":"docs-a","new":"docs-b"},{"old":"docs-b","new":"docs-c"}]""").isEmpty())
        assertTrue(SourceFolderNames.parse(null).isEmpty())
        assertTrue(parse("""{"old":"docs-a"}""").isEmpty())
        // Old names may be any folder name the validators accept; new ones are strict.
        assertEquals("docs-_x", parse("""[{"old":"docs-_x","new":"docs-x-abcdef"}]""").single().old)
        assertTrue(parse("""[{"old":"docs-x","new":"docs-_x"}]""").isEmpty())
    }

    @Test fun `local read-through only after a migration started`() {
        try {
            assertEquals("docs-template/a.tex", SourceFolderNames.followLocal("docs-template/a.tex"))
            val before = SourceFolderNames.generation
            SourceFolderNames.installLocal(map)
            assertTrue(SourceFolderNames.generation > before)
            assertEquals("docs-template-c8ee33/a.tex", SourceFolderNames.followLocal("docs-template/a.tex"))
            val request = JsonParser.parseString("""{"source_file":"docs-template/a.tex","contents":"docs-template"}""").asJsonObject
            val followed = SourceFolderNames.followLocal(request)
            assertEquals("docs-template-c8ee33/a.tex", followed.get("source_file").asString)
            assertEquals("docs-template", followed.get("contents").asString)
            assertFalse(SourceFolderNames.localRenames.isEmpty())
        } finally {
            SourceFolderNames.installLocal(emptyMap())
        }
    }
}

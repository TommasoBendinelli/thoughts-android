// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.sync.defaultFolderDocumentOutput
import com.gitlab.mudlej.MjPdfReader.data.sync.defaultSourceFolder
import com.gitlab.mudlej.MjPdfReader.data.sync.emptyProjectConfiguration
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DefaultFolderTest {
    private val h = "a".repeat(64)
    private val legacy = ".thoughts/projects/$h/documents"
    private val canonical = ".thoughts/projects/$h/default"

    private fun r(text: String) = DefaultFolder.rewriteLegacyDefaultFolder(text)

    @Test fun `Rule R rewrites relative, absolute, parent-relative and quoted paths`() {
        assertEquals("$canonical/notes.md", r("$legacy/notes.md"))
        assertEquals(canonical, r(legacy))
        assertEquals("/Users/example/docs/$canonical/x.pdf", r("/Users/example/docs/$legacy/x.pdf"))
        assertEquals("../$canonical/x.pdf", r("../$legacy/x.pdf"))
        assertEquals("""{"path":"$canonical","file":"$canonical/a.tex"}""", r("""{"path":"$legacy","file":"$legacy/a.tex"}"""))
        assertEquals("'$canonical'", r("'$legacy'"))
        assertEquals("$canonical\\x", r("$legacy\\x"))
        assertEquals("$canonical x\n$canonical\t", r("$legacy x\n$legacy\t"))
        // JSON as these apps write it does not escape '/'; a nested JSON string escapes its quotes.
        val nested = JsonObject().apply { addProperty("payload", """{"source_file":"$legacy/a.md"}""") }.toString()
        assertTrue(nested.contains("$legacy/a.md"))
        assertEquals(nested.replace(legacy, canonical), r(nested))
        val escaped = JsonObject().apply { addProperty("payload", """{"owner":"$legacy"}""") }.toString()
        assertTrue(escaped.contains("$legacy\\\""))
        assertEquals(escaped.replace(legacy, canonical), r(escaped))
        // Two hashes in one text.
        val other = ".thoughts/projects/${"b".repeat(64)}/documents"
        assertEquals("$canonical/a $other".replace(other, other.replace("/documents", "/default")), r("$legacy/a $other"))
    }

    @Test fun `Rule R has no false positives and is idempotent`() {
        for (text in listOf(
            "documents/notes.md", "my documents", "$canonical/documents/notes.md",
            ".thoughts/projects/${"a".repeat(63)}/documents/x", ".thoughts/projects/${"a".repeat(65)}/documents/x",
            ".thoughts/projects/${"A".repeat(64)}/documents/x", "$legacy" + "2/x", "${legacy}_old", "$legacy.bak",
            ".thoughts/projects/$h/document/x", "docs-paper/documents/x.tex",
        )) assertEquals(text, text, r(text))
        assertEquals(r("$legacy/a"), r(r("$legacy/a")))
        assertFalse(DefaultFolder.containsLegacy("$canonical/a"))
        assertTrue(DefaultFolder.containsLegacy("x $legacy"))
        assertTrue(DefaultFolder.containsCanonical("x $canonical/a"))
        assertFalse(DefaultFolder.containsCanonical("$legacy/default/a"))
    }

    @Test fun `owners of both spellings`() {
        assertEquals(canonical, SourceOwner.of("$canonical/notes.md"))
        assertEquals(legacy, SourceOwner.of("$legacy/notes.md"))
        assertTrue(SourceOwner.isDefaultFolder(canonical))
        assertEquals("notes.md", SourceOwner.relative("$canonical/notes.md"))
        assertEquals(DefaultFolder.CANONICAL, DefaultFolder.segmentOf(canonical))
        assertEquals(DefaultFolder.LEGACY, DefaultFolder.segmentOf(legacy))
        assertEquals(null, DefaultFolder.segmentOf("docs-paper"))
        assertEquals("$legacy/a/b.md", DefaultFolder.legacySpelling("$canonical/a/b.md"))
        assertEquals("$legacy/a/b.md", DefaultFolder.legacySpelling("$legacy/a/b.md"))
        assertEquals("docs-x/default/a.md", DefaultFolder.legacySpelling("docs-x/default/a.md"))
    }

    @Test fun `Library PDF names do not change with the folder name`() {
        // Rule 4: the same document_<hash>.pdf before and after the rename.
        val before = defaultFolderDocumentOutput("$legacy/notes/a.md")
        assertEquals(before, defaultFolderDocumentOutput("$canonical/notes/a.md"))
        assertEquals("document_78dafde3991335de51b3.pdf", before)
    }

    private fun project(id: String, path: String?) = JsonObject().apply {
        addProperty("id", id)
        if (path != null) add("project_configuration", JsonParser.parseString(
            """{"default_folder_id":"f","source_folders":[{"folder_id":"f","path":"$path"},{"folder_id":"g","path":"docs-x"}]}"""))
    }

    @Test fun `the manifest names the default folder, the Library segment names new ones`() {
        assertEquals(canonical, defaultSourceFolder(project("p", canonical)))
        assertEquals(legacy, defaultSourceFolder(project("p", legacy)))
        assertEquals(DefaultFolder.path("p", DefaultFolder.LEGACY), defaultSourceFolder(project("p", null)))
        assertEquals(DefaultFolder.path("p", DefaultFolder.CANONICAL), defaultSourceFolder(project("p", null), DefaultFolder.CANONICAL))
        val legacyLibrary = JsonArray().apply { add(project("p", legacy)) }
        val renamed = JsonArray().apply { add(project("p", legacy)); add(project("q", ".thoughts/projects/${"c".repeat(64)}/default")) }
        assertEquals(DefaultFolder.LEGACY, DefaultFolder.librarySegment(legacyLibrary))
        assertEquals(DefaultFolder.LEGACY, DefaultFolder.librarySegment(JsonArray()))
        assertEquals(DefaultFolder.CANONICAL, DefaultFolder.librarySegment(renamed))
        val created = emptyProjectConfiguration("new:x", "X", DefaultFolder.CANONICAL)
        assertEquals(DefaultFolder.path("new:x", DefaultFolder.CANONICAL), DefaultFolder.configuredDefaultFolder(created))
        assertEquals(DefaultFolder.path("new:x", DefaultFolder.LEGACY),
            DefaultFolder.configuredDefaultFolder(emptyProjectConfiguration("new:x", "X")))
    }

    @Test fun `on disk the default folder wins when it exists`() {
        val root = Files.createTempDirectory("default-folder").toFile()
        try {
            assertEquals(File(root, DefaultFolder.path("p", DefaultFolder.LEGACY)), DefaultFolder.locate(root, "p"))
            File(root, DefaultFolder.path("p", DefaultFolder.CANONICAL)).mkdirs()
            assertEquals(File(root, DefaultFolder.path("p", DefaultFolder.CANONICAL)), DefaultFolder.locate(root, "p"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `JSON values are rewritten except source text`() {
        val value = JsonParser.parseString("""{"source_file":"$legacy/a.md","contents":"see $legacy/a.md",
            "base_contents":"x $legacy","list":["$legacy/b.md",1,true,null],"text":"line\n$legacy/c.md",
            "nested":{"synctex_path":"$legacy/a.tex","n":1.50}}""")
        val rewritten = DefaultFolder.rewriteJsonValues(value).asJsonObject
        assertEquals("$canonical/a.md", rewritten.get("source_file").asString)
        assertEquals("see $legacy/a.md", rewritten.get("contents").asString)
        assertEquals("x $legacy", rewritten.get("base_contents").asString)
        assertEquals("$canonical/b.md", rewritten.getAsJsonArray("list")[0].asString)
        assertEquals("line\n$legacy/c.md", rewritten.get("text").asString)
        assertEquals("$canonical/a.tex", rewritten.getAsJsonObject("nested").get("synctex_path").asString)
        assertTrue(rewritten.toString().contains("1.50"))
        val kept = DefaultFolder.rewriteJsonValues(value) { it == "synctex_path" }.asJsonObject
        assertEquals("$legacy/a.tex", kept.getAsJsonObject("nested").get("synctex_path").asString)
        // The input is never changed and an unaffected value is returned as is.
        assertEquals("$legacy/a.md", value.asJsonObject.get("source_file").asString)
        val plain = JsonParser.parseString("""{"a":["docs-x/a.md"]}""")
        assertSame(plain, DefaultFolder.rewriteJsonValues(plain))
    }
}

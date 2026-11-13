// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ObjectReferenceIndexTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("object-reference-index").toFile()
    }

    @After
    fun tearDown() {
        ObjectReferenceIndex.forgetLoadedIndexes()
        root.deleteRecursively()
    }

    private val a = "a".repeat(64)
    private val b = "0123456789abcdef".repeat(4)
    private val c = "c".repeat(64)

    /** The tree walk cleanup used before; the streaming reader must agree with it. */
    private fun treeWalk(text: String): List<String>? {
        val tree = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        val found = linkedSetOf<String>()
        fun walk(value: JsonElement) {
            when {
                value.isJsonObject -> value.asJsonObject.entrySet().forEach { walk(it.value) }
                value.isJsonArray -> value.asJsonArray.forEach(::walk)
                value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                    value.asString.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }?.let(found::add)
            }
        }
        walk(tree)
        return found.toList()
    }

    private fun streamed(text: String) = ObjectReferenceIndex.sha256StringValues(text.byteInputStream())?.toList()

    @Test
    fun streamingReaderAgreesWithTheTreeWalk() {
        val documents = listOf(
            """{"x":"$a","nested":{"list":["$b",1,true,null,{"deep":["$c"]}]}}""",
            """["$a","$a","${a.uppercase()}","${a}0","short",{"$c":"named only"}]""",
            """{"number":${"1".repeat(64)},"unquoted":$b}""",
            """{'single':'$a'} """,
            """{"a":"$a"}garbage""",
            """{"a":"$a"} {"b":"$b"}""",
            """{"a":"$a",}""",
            """{"a":["$a"}""",
            """{"a":"$a"""",
            "[]",
            "{}\n\n",
        )
        for (document in documents) assertEquals(document, treeWalk(document), streamed(document))
    }

    @Test
    fun journalSurvivesRestartAndATornRecord() {
        val journal = File(root, "index.bin")
        val objects = File(root, "objects").apply { mkdirs() }
        val raw = """{"plan":"$a","sources":["$b"]}""".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        File(objects, digest).writeBytes(raw)
        val counters = ObjectReferenceIndex.Counters()
        val index = ObjectReferenceIndex.at(journal)
        assertArrayEquals(arrayOf(a, b), index.references(digest, File(objects, digest), 1_000, counters))
        assertEquals(1L, counters.parsed)
        journal.appendBytes(ByteArray(40) { 7 })

        ObjectReferenceIndex.forgetLoadedIndexes()
        File(objects, digest).delete()
        val restarted = ObjectReferenceIndex.at(journal)
        assertArrayEquals(arrayOf(a, b), restarted.cached(digest)!!.references)
        assertEquals(32L + 5 + 64, journal.length())
        assertNull(restarted.cached(c))
    }

    @Test
    fun leavesAndOversizedObjectsNameNothing() {
        val objects = File(root, "objects").apply { mkdirs() }
        val index = ObjectReferenceIndex.at(File(root, "index.bin"))
        val counters = ObjectReferenceIndex.Counters()
        File(objects, a).writeText("plain $b text")
        File(objects, c).writeText("""{"x":"$b"}""")
        assertEquals(0, index.references(a, File(objects, a), 1_000, counters).size)
        assertEquals(0, index.references(c, File(objects, c), 4, counters).size)
        assertEquals(0L, counters.parsed)
    }
}

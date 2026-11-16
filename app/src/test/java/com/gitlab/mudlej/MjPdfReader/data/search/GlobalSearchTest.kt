// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** The index's SQL on a real SQLite over JDBC. */
internal class JdbcGlobalSearchSql(val connection: Connection) : GlobalSearchSql {
    override fun execute(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.execute()
        }
    }

    override fun query(sql: String, args: List<Any?>): List<List<Any?>> = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getObject(it) })
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        try { return block().also { connection.commit() } }
        catch (error: Throwable) { connection.rollback(); throw error }
        finally { connection.autoCommit = true }
    }

    override fun lastInsertRowId(): Long =
        query("SELECT last_insert_rowid()").first().first().toString().toLong()
}

class GlobalSearchQueryTest {

    @Test
    fun everyWordMatchesSeparatelyAndInAnyOrder() {
        val query = GlobalSearchQuery.parse("example generalize")
        assertTrue(query.matches("Example Paper, Models Generalizes: A Study"))
        assertTrue(query.matches("Papers show that models generalize better than the example"))
        assertFalse(query.matches("Example Paper"))
    }

    @Test
    fun fileNameUnderscoresSeparateWords() {
        val query = GlobalSearchQuery.parse("paper example")
        assertTrue(query.matches("Example_Paper_ebe6aaf90a9e.pdf"))
    }

    @Test
    fun caseAndAccentsAreIgnored() {
        val query = GlobalSearchQuery.parse("Zurich resume")
        assertTrue(query.matches("Résumé written in ZÜRICH"))
    }

    @Test
    fun wordFormsShareAStem() {
        assertEquals(listOf("generaliz"), GlobalSearchQuery.parse("generalizes").stems)
        assertEquals(listOf("generaliz"), GlobalSearchQuery.parse("generalized").stems)
        assertTrue(GlobalSearchQuery.parse("generalized").matches("it generalizes"))
    }

    @Test
    fun matchExpressionIsQuotedPrefixTerms() {
        assertEquals("\"sft*\" \"generaliz*\"", GlobalSearchQuery.parse("SFT, generalize!").matchExpression)
        assertTrue(GlobalSearchQuery.parse("  ").isEmpty)
    }

    @Test
    fun snippetCentersOnTheMatchedWords() {
        val text = "word ".repeat(80) + "RL generalizes better than SFT " + "tail ".repeat(80)
        val snippet = GlobalSearchQuery.parse("sft generalize").snippet(text, maxChars = 80)
        assertTrue(snippet.text, snippet.text.contains("generalizes better than SFT"))
        assertEquals(2, snippet.matches.size)
        snippet.matches.forEach { assertTrue(snippet.text.substring(it.first, it.last + 1).lowercase().let { w -> w == "sft" || w.startsWith("generaliz") }) }
    }
}

class GlobalSearchIndexTest {
    private lateinit var connection: Connection
    private lateinit var index: GlobalSearchIndex

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        index = GlobalSearchIndex(JdbcGlobalSearchSql(connection)).also { it.ensureSchema() }
    }

    @After
    fun tearDown() = connection.close()

    private fun page(ref: String, page: Int, text: String) = GlobalSearchEntry(GlobalSearchKind.PAGE, ref, page, "", text)

    @Test
    fun findsPagesWithEveryWordByPrefixIgnoringAccents() {
        index.replace(GlobalSearchItem("pdf:a", "1"), listOf(
            page("a", 1, "Introduction"),
            page("a", 2, "RL generalizes better than SFT on unseen rules"),
        ))
        index.replace(GlobalSearchItem("pdf:b", "1"), listOf(page("b", 1, "SFT memorizes; café résumé")))
        val hits = index.search(GlobalSearchQuery.parse("sft generalize"), GlobalSearchKind.PAGE, 10)
        assertEquals(listOf("a" to 2), hits.map { it.ref to it.position })
        assertEquals(listOf("b"), index.search(GlobalSearchQuery.parse("resume cafe"), GlobalSearchKind.PAGE, 10).map { it.ref })
    }

    @Test
    fun replaceAndRemoveDropOldPages() {
        index.replace(GlobalSearchItem("pdf:a", "1"), listOf(page("a", 1, "old words")))
        index.replace(GlobalSearchItem("pdf:a", "2"), listOf(page("a", 1, "new words")))
        assertEquals(0, index.search(GlobalSearchQuery.parse("old"), GlobalSearchKind.PAGE, 10).size)
        assertEquals(mapOf("pdf:a" to "2"), index.items())
        index.remove("pdf:a")
        assertEquals(0, index.search(GlobalSearchQuery.parse("new"), GlobalSearchKind.PAGE, 10).size)
        assertEquals(0, index.entryCount())
        assertTrue(index.items().isEmpty())
    }

    @Test
    fun planReadsOnlyChangedItemsAndRemovesMissingOnesOfItsKind() {
        val plan = planGlobalSearchUpdate(
            wanted = listOf(GlobalSearchItem("pdf:a", "1"), GlobalSearchItem("pdf:b", "2")),
            indexed = mapOf("pdf:a" to "1", "pdf:b" to "1", "pdf:gone" to "1", "card:x" to "1"),
            prefixes = setOf("pdf:"),
        )
        assertEquals(listOf("pdf:b"), plan.changed.map { it.key })
        assertEquals(listOf("pdf:gone"), plan.removed)
    }

    @Test
    fun schemaSurvivesReopen() {
        index.replace(GlobalSearchItem("pdf:a", "1"), listOf(page("a", 1, "kept")))
        GlobalSearchIndex(JdbcGlobalSearchSql(connection)).ensureSchema()
        assertEquals(1, index.search(GlobalSearchQuery.parse("kept"), GlobalSearchKind.PAGE, 10).size)
    }
}

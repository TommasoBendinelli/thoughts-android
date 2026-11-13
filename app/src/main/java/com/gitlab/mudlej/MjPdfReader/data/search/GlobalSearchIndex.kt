// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.search

/** The few SQL calls the index needs, so host tests can run it on JDBC SQLite. */
interface GlobalSearchSql {
    fun execute(sql: String, args: List<Any?> = emptyList())
    fun query(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>
    fun <T> transaction(block: () -> T): T
    /** The rowid of the last INSERT on this connection. */
    fun lastInsertRowId(): Long
}

enum class GlobalSearchKind(val wire: String) {
    PAGE("page"),
    CARD("card"),
    SOURCE("source"),
    ;

    companion object {
        fun of(wire: String): GlobalSearchKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** One indexed unit: a PDF page, a review card or a block of source lines. */
data class GlobalSearchEntry(
    val kind: GlobalSearchKind,
    /** PDF path, card id or source path. */
    val ref: String,
    /** PDF page (1-based) or the block's first source line (1-based); 0 for cards. */
    val position: Int,
    /** Matched before the body: a card's question or a source file's path (first block only). */
    val title: String,
    val body: String,
)

/** One item the index holds entries for, with the identity it was indexed at. */
data class GlobalSearchItem(val key: String, val identity: String)

data class GlobalSearchRow(
    val kind: GlobalSearchKind,
    val ref: String,
    val position: Int,
    val title: String,
    val body: String,
)

/**
 * The on-device full-text index (SQLite FTS4, unicode61 without diacritics). Each item (a PDF,
 * a card, a source file) is replaced as a whole when its identity changes and removed when it
 * is gone, so an update re-reads only what changed.
 */
class GlobalSearchIndex(private val sql: GlobalSearchSql) {

    fun ensureSchema() {
        val version = sql.query("PRAGMA user_version").firstOrNull()?.firstOrNull()?.toString()?.toIntOrNull() ?: 0
        if (version == SCHEMA_VERSION) return
        sql.transaction {
            sql.execute("DROP TABLE IF EXISTS entries")
            sql.execute("DROP TABLE IF EXISTS items")
            sql.execute("DROP TABLE IF EXISTS item_rows")
            sql.execute(
                "CREATE VIRTUAL TABLE entries USING fts4(kind, ref, position, title, body, " +
                    "notindexed=kind, notindexed=ref, notindexed=position, " +
                    "tokenize=unicode61 \"remove_diacritics=1\", prefix=\"2,3\")",
            )
            sql.execute("CREATE TABLE items(key TEXT PRIMARY KEY, identity TEXT NOT NULL, row_count INTEGER NOT NULL)")
            sql.execute("CREATE TABLE item_rows(key TEXT NOT NULL, docid INTEGER NOT NULL)")
            sql.execute("CREATE INDEX item_rows_key ON item_rows(key)")
            sql.execute("PRAGMA user_version = $SCHEMA_VERSION")
        }
    }

    /** Every indexed item key with its identity. */
    fun items(): Map<String, String> = sql.query("SELECT key, identity FROM items")
        .associate { it[0].toString() to it[1].toString() }

    /** Replaces the entries of [item] with [entries] in one transaction. */
    fun replace(item: GlobalSearchItem, entries: List<GlobalSearchEntry>) {
        sql.transaction {
            deleteRows(item.key)
            for (entry in entries) {
                sql.execute(
                    "INSERT INTO entries(kind, ref, position, title, body) VALUES (?, ?, ?, ?, ?)",
                    listOf(entry.kind.wire, entry.ref, entry.position, entry.title, entry.body),
                )
                sql.execute("INSERT INTO item_rows(key, docid) VALUES (?, ?)", listOf(item.key, sql.lastInsertRowId()))
            }
            sql.execute(
                "INSERT OR REPLACE INTO items(key, identity, row_count) VALUES (?, ?, ?)",
                listOf(item.key, item.identity, entries.size),
            )
        }
    }

    fun remove(key: String) {
        sql.transaction {
            deleteRows(key)
            sql.execute("DELETE FROM items WHERE key = ?", listOf(key))
        }
    }

    private fun deleteRows(key: String) {
        sql.execute("DELETE FROM entries WHERE docid IN (SELECT docid FROM item_rows WHERE key = ?)", listOf(key))
        sql.execute("DELETE FROM item_rows WHERE key = ?", listOf(key))
    }

    /** Rows of [kind] matching every stem of [query], at most [limit]. */
    fun search(query: GlobalSearchQuery, kind: GlobalSearchKind, limit: Int): List<GlobalSearchRow> {
        if (query.isEmpty) return emptyList()
        return sql.query(
            "SELECT kind, ref, position, title, body FROM entries WHERE entries MATCH ? AND kind = ? LIMIT ?",
            listOf(query.matchExpression, kind.wire, limit),
        ).mapNotNull { row ->
            val rowKind = GlobalSearchKind.of(row[0].toString()) ?: return@mapNotNull null
            GlobalSearchRow(rowKind, row[1].toString(), row[2].toString().toIntOrNull() ?: 0, row[3]?.toString().orEmpty(), row[4]?.toString().orEmpty())
        }
    }

    fun entryCount(): Int = sql.query("SELECT COUNT(*) FROM item_rows").firstOrNull()?.firstOrNull()?.toString()?.toIntOrNull() ?: 0

    companion object {
        const val SCHEMA_VERSION = 1
        const val PDF_PREFIX = "pdf:"
        const val CARD_PREFIX = "card:"
        const val SOURCE_PREFIX = "source:"
    }
}

/** What an update must do: items to (re)index, in the given order, and keys to remove. */
data class GlobalSearchPlan(val changed: List<GlobalSearchItem>, val removed: List<String>)

/**
 * Compares [wanted] items with the [indexed] identities. Only keys starting with one of
 * [prefixes] are removed, so a partial update (cards only) leaves PDFs alone.
 */
fun planGlobalSearchUpdate(
    wanted: List<GlobalSearchItem>,
    indexed: Map<String, String>,
    prefixes: Set<String>,
): GlobalSearchPlan {
    val wantedKeys = wanted.mapTo(HashSet()) { it.key }
    return GlobalSearchPlan(
        changed = wanted.filter { indexed[it.key] != it.identity },
        removed = indexed.keys.filter { key -> key !in wantedKeys && prefixes.any(key::startsWith) }.sorted(),
    )
}

/** Source text split into blocks at blank lines, at most [maxLines] each: (first line 1-based, text). */
fun globalSearchSourceBlocks(text: String, maxLines: Int = 30): List<Pair<Int, String>> {
    val lines = text.lines()
    val blocks = mutableListOf<Pair<Int, String>>()
    var start = -1
    val current = StringBuilder()
    var count = 0
    fun flush() {
        if (start >= 0 && current.isNotBlank()) blocks += (start + 1) to current.toString().trimEnd()
        start = -1; current.setLength(0); count = 0
    }
    lines.forEachIndexed { index, line ->
        if (line.isBlank()) { flush(); return@forEachIndexed }
        if (start < 0) start = index
        current.append(line).append('\n')
        count++
        if (count >= maxLines) flush()
    }
    flush()
    return blocks
}

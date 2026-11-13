// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.search

import android.database.sqlite.SQLiteDatabase

/** [GlobalSearchSql] on the platform SQLite (FTS4 and unicode61 since API 21). */
class AndroidGlobalSearchSql(private val db: SQLiteDatabase) : GlobalSearchSql {

    override fun execute(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map(::bindable).toTypedArray())
    }

    override fun query(sql: String, args: List<Any?>): List<List<Any?>> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
            val rows = ArrayList<List<Any?>>(cursor.count.coerceAtLeast(0))
            while (cursor.moveToNext()) {
                rows += (0 until cursor.columnCount).map { column ->
                    if (cursor.isNull(column)) null else cursor.getString(column)
                }
            }
            rows
        }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransactionNonExclusive()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun lastInsertRowId(): Long =
        db.rawQuery("SELECT last_insert_rowid()", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun bindable(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        else -> value
    }
}

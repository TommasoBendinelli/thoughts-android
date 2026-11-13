// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.entity

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "ReadingUsageDocument")
data class ReadingUsageDocument(
    @PrimaryKey val documentKey: String,
    val uri: String,
    val title: String,
    val pageCount: Int,
    val lastReadAtMillis: Long,
)

@Entity(
    tableName = "ReadingUsagePageTotal",
    primaryKeys = ["documentKey", "pageIndex"],
    foreignKeys = [
        ForeignKey(
            entity = ReadingUsageDocument::class,
            parentColumns = ["documentKey"],
            childColumns = ["documentKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentKey")],
)
data class ReadingUsagePageTotal(
    val documentKey: String,
    val pageIndex: Int,
    val durationMillis: Long,
)

@Entity(
    tableName = "ReadingUsagePageDay",
    primaryKeys = ["documentKey", "pageIndex", "epochDay"],
    foreignKeys = [
        ForeignKey(
            entity = ReadingUsageDocument::class,
            parentColumns = ["documentKey"],
            childColumns = ["documentKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentKey"), Index("epochDay")],
)
data class ReadingUsagePageDay(
    val documentKey: String,
    val pageIndex: Int,
    val epochDay: Long,
    val durationMillis: Long,
)

@Dao
interface ReadingUsageDao {
    @Query("SELECT * FROM ReadingUsageDocument")
    fun findAllDocuments(): List<ReadingUsageDocument>

    @Query("SELECT * FROM ReadingUsageDocument WHERE documentKey = :documentKey")
    fun findDocument(documentKey: String): ReadingUsageDocument?

    @Query("SELECT * FROM ReadingUsagePageTotal")
    fun findAllPageTotals(): List<ReadingUsagePageTotal>

    @Query("SELECT * FROM ReadingUsagePageTotal WHERE documentKey = :documentKey")
    fun findPageTotals(documentKey: String): List<ReadingUsagePageTotal>

    @Query("SELECT * FROM ReadingUsagePageDay WHERE epochDay >= :earliestEpochDay")
    fun findPageDaysSince(earliestEpochDay: Long): List<ReadingUsagePageDay>

    @Query("SELECT * FROM ReadingUsagePageDay WHERE documentKey = :documentKey")
    fun findPageDays(documentKey: String): List<ReadingUsagePageDay>

    @Query("SELECT durationMillis FROM ReadingUsagePageTotal WHERE documentKey = :documentKey AND pageIndex = :pageIndex")
    fun findPageTotalMillis(documentKey: String, pageIndex: Int): Long?

    @Query("SELECT durationMillis FROM ReadingUsagePageDay WHERE documentKey = :documentKey AND pageIndex = :pageIndex AND epochDay = :epochDay")
    fun findPageDayMillis(documentKey: String, pageIndex: Int, epochDay: Long): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertDocument(document: ReadingUsageDocument): Long

    @Query(
        """
        UPDATE ReadingUsageDocument
        SET uri = :uri, title = :title, pageCount = :pageCount, lastReadAtMillis = :lastReadAtMillis
        WHERE documentKey = :documentKey
        """
    )
    fun updateDocument(
        documentKey: String,
        uri: String,
        title: String,
        pageCount: Int,
        lastReadAtMillis: Long,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPageTotal(total: ReadingUsagePageTotal): Long

    @Query("UPDATE ReadingUsagePageTotal SET durationMillis = :durationMillis WHERE documentKey = :documentKey AND pageIndex = :pageIndex")
    fun updatePageTotal(documentKey: String, pageIndex: Int, durationMillis: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPageDay(day: ReadingUsagePageDay): Long

    @Query("UPDATE ReadingUsagePageDay SET durationMillis = :durationMillis WHERE documentKey = :documentKey AND pageIndex = :pageIndex AND epochDay = :epochDay")
    fun updatePageDay(documentKey: String, pageIndex: Int, epochDay: Long, durationMillis: Long)

    @Query("DELETE FROM ReadingUsagePageDay WHERE epochDay < :earliestEpochDay")
    fun pruneDaysBefore(earliestEpochDay: Long)

    @Query("DELETE FROM ReadingUsageDocument WHERE documentKey = :documentKey")
    fun deleteDocument(documentKey: String): Int
}

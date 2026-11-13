// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Source-derived fields are separate from canonical content and pending user mutations. */
@Entity(tableName = "CommentSourceState")
data class CommentSourceState(
    @androidx.room.PrimaryKey val id: String,
    val inputJson: String,
    val outputJson: String,
)

@Entity(tableName = "CommentImportCheckpoint")
data class CommentImportCheckpoint(
    @androidx.room.PrimaryKey val path: String,
    val signature: String,
)

/** Recoverable mutations awaiting source reconciliation and outbox capture. */
@Entity(tableName = "CommentMutationJournal")
data class CommentMutationJournal(
    @androidx.room.PrimaryKey val id: String,
    val payloadJson: String,
)

@Dao
interface CommentReadStateDao {
    @Query("SELECT * FROM CommentMutationJournal ORDER BY id")
    suspend fun journals(): List<CommentMutationJournal>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putJournals(rows: List<CommentMutationJournal>)

    @Query("DELETE FROM CommentMutationJournal WHERE id = :id")
    suspend fun removeJournal(id: String)

    @Transaction
    suspend fun migrateJournals(rows: List<CommentMutationJournal>, marker: String) {
        if (checkpoint(marker) != null) return
        putJournals(rows)
        putCheckpoint(CommentImportCheckpoint(marker, "complete"))
    }

    @Query("SELECT * FROM CommentSourceState")
    suspend fun sourceStates(): List<CommentSourceState>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSourceStates(rows: List<CommentSourceState>)

    @Query("SELECT signature FROM CommentImportCheckpoint WHERE path = :path")
    suspend fun checkpoint(path: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCheckpoint(row: CommentImportCheckpoint)

    @Transaction
    suspend fun importSourceStates(rows: List<CommentSourceState>, checkpoint: CommentImportCheckpoint) {
        putSourceStates(rows)
        putCheckpoint(checkpoint)
    }
}

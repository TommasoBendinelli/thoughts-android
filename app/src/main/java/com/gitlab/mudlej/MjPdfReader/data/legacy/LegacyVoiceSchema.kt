// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.legacy

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

// Storage compatibility only: the retired feature has no DAO or producer.
@Entity(tableName = "VoiceCapture")
data class LegacyVoiceCaptureEntity(
    @androidx.room.PrimaryKey val captureId: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val originId: String = "thoughts-android",
    val revision: Long = 1,
    val totalDurationMs: Long = 0,
    val transcript: String = "",
    @ColumnInfo(defaultValue = "[]") val detectedLanguagesJson: String = "[]",
    @ColumnInfo(defaultValue = "{}") val transcriptionJson: String = "{}",
    val error: String? = null,
    val active: Boolean = false,
    val deleted: Boolean = false,
    val pendingUpload: Boolean = false,
    val assignedCollection: String? = null,
    val assignedEntityId: String? = null,
    val assignmentRevision: Long = 0,
    val assignmentPending: Boolean = false,
    val serverSequence: Long = 0,
)

@Entity(
    tableName = "VoicePart",
    primaryKeys = ["captureId", "partIndex"],
    indices = [Index("captureId")],
)
data class LegacyVoicePartEntity(
    val captureId: String,
    val partIndex: Int,
    val filePath: String,
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val mediaType: String = "audio/mp4",
    val durationMs: Long = 0,
    val uploaded: Boolean = false,
)

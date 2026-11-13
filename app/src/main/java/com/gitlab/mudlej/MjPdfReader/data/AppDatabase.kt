// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import android.content.Context
import androidx.room.AutoMigration
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfAnnotationSaveDestination
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfAnnotationSaveDestinationDao
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecordDao
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntryDao
import com.gitlab.mudlej.MjPdfReader.data.entity.UserBookmark
import com.gitlab.mudlej.MjPdfReader.data.entity.UserBookmarkDao
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingUsageDocument
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingUsagePageDay
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingUsagePageTotal
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingUsageDao
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalCommentStateEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsProjectionEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncDao
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncStateEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSourceRequestEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSourceSnapshotEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsBuildRequestEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncEventEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncRunEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncStepEntity
import com.gitlab.mudlej.MjPdfReader.data.legacy.LegacyVoiceCaptureEntity
import com.gitlab.mudlej.MjPdfReader.data.legacy.LegacyVoicePartEntity
import androidx.room.Database
import androidx.room.RenameTable
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import java.io.File

@Database(
    entities = [
        com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSourceState::class,
        com.gitlab.mudlej.MjPdfReader.data.annotation.CommentMutationJournal::class,
        com.gitlab.mudlej.MjPdfReader.data.annotation.CommentImportCheckpoint::class,
        com.gitlab.mudlej.MjPdfReader.data.source.CurrentSourceAnnotation::class,
        com.gitlab.mudlej.MjPdfReader.data.review.ReviewRecordEntity::class,
        com.gitlab.mudlej.MjPdfReader.data.review.ReviewStateEntity::class,
        com.gitlab.mudlej.MjPdfReader.data.review.ReviewMetaEntity::class,
        com.gitlab.mudlej.MjPdfReader.data.review.ReviewSnapshotEntity::class,
        com.gitlab.mudlej.MjPdfReader.data.review.ReviewDashboardEntity::class,
        PdfRecord::class,
        PdfAnnotationSaveDestination::class,
        ScannedPdfEntry::class,
        UserBookmark::class,
        ReadingUsageDocument::class,
        ReadingUsagePageTotal::class,
        ReadingUsagePageDay::class,
        ThoughtsSyncStateEntity::class,
        ThoughtsOutboxEntity::class,
        ThoughtsLocalCommentStateEntity::class,
        ThoughtsProjectionEntity::class,
        com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsCommentScopeEntity::class,
        ThoughtsSourceSnapshotEntity::class,
        ThoughtsSourceRequestEntity::class,
        ThoughtsBuildRequestEntity::class,
        ThoughtsSyncRunEntity::class,
        ThoughtsSyncStepEntity::class,
        ThoughtsSyncEventEntity::class,
        LegacyVoiceCaptureEntity::class,
        LegacyVoicePartEntity::class,
    ],
    version = 35,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.MyAutoMigration::class),
        AutoMigration(from = 1, to = 3, spec = AppDatabase.MyAutoMigration::class),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
    ]
)
@TypeConverters(DataConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun commentReadStateDao(): com.gitlab.mudlej.MjPdfReader.data.annotation.CommentReadStateDao
    abstract fun pdfRecordDao(): PdfRecordDao
    abstract fun pdfAnnotationSaveDestinationDao(): PdfAnnotationSaveDestinationDao
    abstract fun scannedPdfEntryDao(): ScannedPdfEntryDao
    abstract fun userBookmarkDao(): UserBookmarkDao
    abstract fun readingUsageDao(): ReadingUsageDao
    abstract fun thoughtsSyncDao(): ThoughtsSyncDao
    abstract fun currentSourceAnnotationDao(): com.gitlab.mudlej.MjPdfReader.data.source.CurrentSourceAnnotationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        const val DATABASE_NAME = "app-db.db"

        fun getInstance(context: Context): AppDatabase {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val moved = moveDatabaseOutOfCacheDir(context)
                val databaseName = if (moved) {
                    DATABASE_NAME
                } else {
                    File(context.cacheDir, DATABASE_NAME).absolutePath
                }
                val created = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_7_TO_8)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_10_TO_11)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_21_TO_22)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_22_TO_23)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_23_TO_24)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_24_TO_25)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_25_TO_26)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_26_TO_27)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_27_TO_28)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_28_TO_29)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_29_TO_30)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_30_TO_31)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_31_TO_32)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_32_TO_33)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_33_TO_34)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_34_TO_35)
                    .build()
                INSTANCE = created
                return created
            }
        }

        private fun moveDatabaseOutOfCacheDir(context: Context): Boolean {
            val newDbFile = context.getDatabasePath(DATABASE_NAME)
            val newDbDir = newDbFile.parentFile ?: return true
            val oldDbFile = File(context.cacheDir, DATABASE_NAME)
            if (!oldDbFile.exists() || newDbFile.exists()) {
                return true
            }

            val copied = runCatching {
                newDbDir.mkdirs()
                for (suffix in listOf("-wal", "-shm", "")) {
                    val oldFile = File(context.cacheDir, DATABASE_NAME + suffix)
                    if (oldFile.exists()) {
                        oldFile.copyTo(File(newDbDir, DATABASE_NAME + suffix), overwrite = true)
                    }
                }
            }.isSuccess

            for (suffix in listOf("", "-wal", "-shm")) {
                if (copied) {
                    File(context.cacheDir, DATABASE_NAME + suffix).delete()
                } else {
                    File(newDbDir, DATABASE_NAME + suffix).delete()
                }
            }
            return copied
        }
    }

    @RenameTable(fromTableName = "SavedLocation", toTableName = "PdfRecord")
    class MyAutoMigration : AutoMigrationSpec

}

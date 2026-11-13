// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AppDatabaseMigrations {
    // Proposal history is retired: drop received and queued history events.
    val MIGRATION_34_TO_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM ThoughtsProjection WHERE entityType = 'proposal_event'")
            db.execSQL("DELETE FROM ThoughtsOutbox WHERE entityType = 'proposal_event'")
        }
    }

    val MIGRATION_33_TO_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS CommentMutationJournal (id TEXT NOT NULL PRIMARY KEY, payloadJson TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS CommentSourceState (id TEXT NOT NULL PRIMARY KEY, inputJson TEXT NOT NULL, outputJson TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS CommentImportCheckpoint (path TEXT NOT NULL PRIMARY KEY, signature TEXT NOT NULL)")
        }
    }

    val MIGRATION_32_TO_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `CurrentSourceAnnotation` (`partition` TEXT NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, `generation` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, PRIMARY KEY(`partition`, `kind`, `id`))")
        }
    }
    val MIGRATION_7_TO_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `PdfAnnotationSaveDestination` (
                    `sourceKey` TEXT NOT NULL,
                    `destinationUri` TEXT NOT NULL,
                    `lastSavedHash` TEXT,
                    `lastSavedAt` TEXT NOT NULL,
                    PRIMARY KEY(`sourceKey`)
                )
                """.trimIndent()
            )
            if (tableExists(db, "PdfSaveTarget")) {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `PdfAnnotationSaveDestination` (`sourceKey`, `destinationUri`, `lastSavedHash`, `lastSavedAt`)
                    SELECT `sourceKey`, `targetUri`, `lastSavedHash`, `lastSavedAt` FROM `PdfSaveTarget`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `PdfSaveTarget`")
            }
        }

        private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$tableName'").use { cursor ->
                return cursor.moveToFirst()
            }
        }
    }

    val MIGRATION_10_TO_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `UserBookmark` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("UPDATE `UserBookmark` SET `sortOrder` = `pageIndex` WHERE `sortOrder` < 0")
        }
    }

    val MIGRATION_21_TO_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `ThoughtsSyncState` ADD COLUMN `artifactRevision` TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL("DELETE FROM `ThoughtsProjection` WHERE `deleted` = 1")
            db.execSQL("UPDATE `ThoughtsSyncState` SET `projectionBootstrapped` = 0")
        }
    }

    val MIGRATION_22_TO_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_runs` (
                    `id` TEXT NOT NULL,
                    `traceId` TEXT NOT NULL,
                    `retryOf` TEXT,
                    `target` TEXT,
                    `kind` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `phase` TEXT NOT NULL,
                    `displayStatus` TEXT NOT NULL,
                    `startedAtEpochMillis` INTEGER NOT NULL,
                    `updatedAtEpochMillis` INTEGER NOT NULL,
                    `displayMessage` TEXT NOT NULL,
                    `eventsJson` TEXT NOT NULL,
                    `eventTimestampsJson` TEXT NOT NULL,
                    `buildProgressJson` TEXT NOT NULL,
                    `buildCheckedAtEpochMillis` INTEGER NOT NULL,
                    `attempt` INTEGER NOT NULL,
                    `planJson` TEXT NOT NULL,
                    `transferredBytes` INTEGER NOT NULL,
                    `totalBytes` INTEGER NOT NULL,
                    `schedulerKind` TEXT NOT NULL,
                    `schedulerId` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_runs_traceId` ON `sync_runs` (`traceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_runs_state_updatedAtEpochMillis` ON `sync_runs` (`state`, `updatedAtEpochMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_runs_target_updatedAtEpochMillis` ON `sync_runs` (`target`, `updatedAtEpochMillis`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_steps` (
                    `runId` TEXT NOT NULL,
                    `ordinal` INTEGER NOT NULL,
                    `phase` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `attempt` INTEGER NOT NULL,
                    `requestId` TEXT NOT NULL,
                    `startedAtEpochMillis` INTEGER NOT NULL,
                    `updatedAtEpochMillis` INTEGER NOT NULL,
                    `deadlineEpochMillis` INTEGER NOT NULL,
                    `factsJson` TEXT NOT NULL,
                    PRIMARY KEY(`runId`, `ordinal`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_steps_runId_state` ON `sync_steps` (`runId`, `state`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `runId` TEXT NOT NULL,
                    `phase` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `attempt` INTEGER NOT NULL,
                    `message` TEXT NOT NULL,
                    `errorCode` TEXT NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_events_runId_createdAtEpochMillis` ON `sync_events` (`runId`, `createdAtEpochMillis`)")
        }
    }

    val MIGRATION_23_TO_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `sync_runs` ADD COLUMN `serverState` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `sync_runs` ADD COLUMN `serverTerminal` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `sync_runs` ADD COLUMN `lastServerSequence` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `sync_runs` ADD COLUMN `streamState` TEXT NOT NULL DEFAULT 'offline'")
            db.execSQL("ALTER TABLE `sync_runs` ADD COLUMN `legacyEventsImported` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'phone'")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `serverSequence` INTEGER")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `serverTimestampEpochMillis` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `receivedTimestampEpochMillis` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `component` TEXT NOT NULL DEFAULT 'phone'")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `buildId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `completed` INTEGER")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `total` INTEGER")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `bytesCompleted` INTEGER")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `bytesTotal` INTEGER")
            db.execSQL("ALTER TABLE `sync_events` ADD COLUMN `metadataJson` TEXT NOT NULL DEFAULT '{}'")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_events_runId_origin_serverSequence` ON `sync_events` (`runId`, `origin`, `serverSequence`)",
            )
        }
    }

    val MIGRATION_24_TO_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `sync_runs` ADD COLUMN `transferMetricsAvailable` INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE `sync_runs` ADD COLUMN `uploadBytesCompleted` INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE `sync_runs` ADD COLUMN `uploadBytesTotal` INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE `sync_runs` ADD COLUMN `downloadBytesCompleted` INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE `sync_runs` ADD COLUMN `downloadBytesTotal` INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE `sync_runs` ADD COLUMN `transferPlanId` TEXT NOT NULL DEFAULT ''",
            )
        }
    }
    val MIGRATION_25_TO_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS ThoughtsLocalCommentState (entityId TEXT NOT NULL PRIMARY KEY, operationId TEXT NOT NULL, revision INTEGER NOT NULL, deviceSequence INTEGER NOT NULL, deleted INTEGER NOT NULL, payloadJson TEXT NOT NULL)")
            // Read historical payloads once during migration, never during comment opening.
            val newest = mutableMapOf<String, Pair<Long, Long>>()
            db.query("SELECT entityId, operationId, deviceSequence, payloadJson FROM ThoughtsOutbox WHERE entityType = 'logical_comment' AND collection = 'comments' AND deliveryState IN ('pending', 'blocked', 'accepted', 'reconciled') ORDER BY deviceSequence").use { cursor ->
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(3)
                    val payload = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: continue
                    val revision = runCatching { payload.get("revision").asLong }.getOrNull() ?: continue
                    val id = cursor.getString(0)
                    val sequence = cursor.getLong(2)
                    val previous = newest[id]
                    if (previous != null && (revision < previous.first || (revision == previous.first && sequence < previous.second))) continue
                    val deleted = runCatching { payload.get("deleted")?.asBoolean == true }.getOrDefault(false)
                    db.execSQL("INSERT OR REPLACE INTO ThoughtsLocalCommentState (entityId, operationId, revision, deviceSequence, deleted, payloadJson) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(id, cursor.getString(1), revision, sequence, if (deleted) 1 else 0, raw))
                    newest[id] = revision to sequence
                }
            }
        }
    }

    val MIGRATION_26_TO_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS ThoughtsCommentScope (scopeKey TEXT NOT NULL, entityId TEXT NOT NULL, recordSource TEXT NOT NULL, PRIMARY KEY(scopeKey, entityId, recordSource))")
            // One upgrade-time pass; subsequent writes maintain the index transactionally.
            for ((source, query) in listOf(
                "projection" to "SELECT entityId, payloadJson FROM ThoughtsProjection WHERE entityType = 'logical_comment' AND collection = 'comments'",
                "local" to "SELECT entityId, payloadJson FROM ThoughtsLocalCommentState",
            )) db.query(query).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    for (key in com.gitlab.mudlej.MjPdfReader.data.sync.logicalCommentScopeKeys(id, cursor.getString(1))) {
                        db.execSQL("INSERT OR REPLACE INTO ThoughtsCommentScope VALUES (?, ?, ?)", arrayOf(key, id, source))
                    }
                }
            }
        }
    }

    val MIGRATION_28_TO_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS ReviewRecord (path TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, source INTEGER NOT NULL, entityId TEXT NOT NULL, cardId TEXT NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ReviewRecord_cardId ON ReviewRecord(cardId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ReviewRecord_kind_source ON ReviewRecord(kind, source)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ReviewState (cardId TEXT NOT NULL PRIMARY KEY, pdfStem TEXT NOT NULL, active INTEGER NOT NULL, eligibleAt INTEGER NOT NULL, dirty INTEGER NOT NULL, documentId TEXT NOT NULL, dueAt INTEGER, reviewCount INTEGER NOT NULL, currentMark TEXT NOT NULL, deleted INTEGER NOT NULL, retained INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ReviewState_active_eligibleAt_pdfStem ON ReviewState(active, eligibleAt, pdfStem)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ReviewMeta (id INTEGER NOT NULL PRIMARY KEY, generation INTEGER NOT NULL)")
        }
    }

    val MIGRATION_27_TO_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Include legacy PDF-only comments identified by origin.collection.
            db.execSQL("DELETE FROM ThoughtsCommentScope")
            MIGRATION_26_TO_27.migrate(db)
        }
    }

    val MIGRATION_29_TO_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (table in listOf("ThoughtsProjection", "ThoughtsOutbox")) {
                db.execSQL("ALTER TABLE $table ADD COLUMN proposalSourceFile TEXT NOT NULL DEFAULT ''")
                // Build the lookup once without materializing complete source snapshots as JSON trees.
                db.compileStatement("UPDATE $table SET proposalSourceFile = ? WHERE rowid = ?").use { update ->
                    db.query("SELECT rowid, payloadJson FROM $table WHERE entityType = 'proposal_event' AND collection = 'proposals'").use { cursor ->
                        while (cursor.moveToNext()) {
                            update.bindString(1, com.gitlab.mudlej.MjPdfReader.data.sync.proposalHistorySourceFile(cursor.getString(1)))
                            update.bindLong(2, cursor.getLong(0))
                            update.executeUpdateDelete()
                        }
                    }
                }
                db.execSQL("CREATE INDEX index_${table}_entityType_collection_proposalSourceFile ON $table(entityType, collection, proposalSourceFile)")
            }
        }
    }

    val MIGRATION_30_TO_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS ReviewSnapshot (cardId TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, payload TEXT NOT NULL)")
        }
    }

    val MIGRATION_31_TO_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS ReviewDashboard (cardId TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, payload TEXT NOT NULL)")
        }
    }

}

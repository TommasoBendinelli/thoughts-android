// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ThoughtsSyncDao {
    @Query("SELECT * FROM ThoughtsSyncState WHERE id = 1")
    suspend fun state(): ThoughtsSyncStateEntity?

    @Query("SELECT * FROM ThoughtsSyncState WHERE id = 1")
    fun observeState(): Flow<ThoughtsSyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: ThoughtsSyncStateEntity)

    @Query("SELECT * FROM ThoughtsOutbox WHERE operationId = :operationId LIMIT 1")
    suspend fun operation(operationId: String): ThoughtsOutboxEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutboxRow(operation: ThoughtsOutboxEntity): Long

    @Transaction
    suspend fun insertOutbox(operation: ThoughtsOutboxEntity): Long {
        // Proposal history is retired: it is never uploaded.
        if (operation.entityType == "proposal_event") return -1L
        val inserted = insertOutboxRow(operation)
        if (inserted != -1L && operation.entityType == "logical_comment" && operation.collection == "comments") {
            val payload = com.google.gson.JsonParser.parseString(operation.payloadJson).asJsonObject
            retainLatestLocalComment(ThoughtsLocalCommentStateEntity(
                operation.entityId, operation.operationId, payload.get("revision").asLong,
                operation.deviceSequence, payload.get("deleted")?.asBoolean == true, operation.payloadJson,
            ))
        }
        return inserted
    }

    /** Retain old requests as history, without pretending that the server accepted them. */
    @Query("UPDATE ThoughtsOutbox SET deliveryState = 'superseded', failureCode = 'feature_retired', failureDetail = 'Thoughts Voice Shortcut was removed' WHERE entityType IN ('voice_capture', 'voice_assignment') AND deliveryState IN ('pending', 'blocked')")
    suspend fun retireVoiceOperations(): Int

    @Query("SELECT * FROM ThoughtsOutbox WHERE deliveryState = 'pending' ORDER BY deviceSequence LIMIT :limit")
    suspend fun pending(limit: Int): List<ThoughtsOutboxEntity>

    @Query("UPDATE ThoughtsOutbox SET deliveryState = 'pending', failureCode = '', failureDetail = '' WHERE operationId = :operationId AND deliveryState = 'blocked' AND failureCode = 'review_source_delete_unsupported'")
    suspend fun retryUnsupportedReviewSource(operationId: String): Int

    @Query("UPDATE ThoughtsOutbox SET deliveryState = 'pending', failureCode = '', failureDetail = '' WHERE operationId = :operationId AND deliveryState = 'blocked' AND failureCode = 'review_source_replacement_unsupported'")
    suspend fun retryUnsupportedReviewReplacement(operationId: String): Int

    @Query("UPDATE ThoughtsOutbox SET deliveryState = 'pending', failureCode = '', failureDetail = '' WHERE operationId = :operationId AND entityType = 'review_card' AND deliveryState = 'blocked' AND failureCode = 'http_409'")
    suspend fun retryChangedReviewPassage(operationId: String): Int

    @Query("UPDATE ThoughtsOutbox SET deliveryState = 'pending', failureCode = '', failureDetail = '' WHERE operationId = :operationId AND deliveryState = 'blocked' AND failureCode = 'http_422'")
    suspend fun retryCompatibleNote(operationId: String): Int

    @Query("UPDATE ThoughtsOutbox SET deliveryState = 'pending', failureCode = '', failureDetail = '' WHERE operationId = :operationId AND entityType = 'agent_proposal_resolution' AND deliveryState = 'blocked' AND failureCode = 'http_409' AND failureDetail = 'source bundle manifest is unavailable'")
    suspend fun retryUnavailableProposalManifest(operationId: String): Int

    @Query("SELECT * FROM ThoughtsOutbox WHERE entityType = :entityType AND deliveryState IN ('pending', 'blocked') ORDER BY deviceSequence")
    suspend fun undeliveredByEntityType(entityType: String): List<ThoughtsOutboxEntity>

    @Query("SELECT * FROM ThoughtsOutbox WHERE entityType = :entityType ORDER BY deviceSequence")
    fun observeByEntityType(entityType: String): Flow<List<ThoughtsOutboxEntity>>

    /** Proposal decisions and their undos: an undone decision no longer decides its proposals. */
    @Query("SELECT * FROM ThoughtsOutbox WHERE entityType IN ('agent_proposal_resolution', 'agent_proposal_undo') ORDER BY deviceSequence")
    fun observeAgentProposalDecisions(): Flow<List<ThoughtsOutboxEntity>>

    @Query("SELECT * FROM ThoughtsOutbox WHERE deliveryState IN ('pending', 'blocked') ORDER BY deviceSequence")
    suspend fun undelivered(): List<ThoughtsOutboxEntity>

    @Query("SELECT * FROM ThoughtsOutbox WHERE deliveryState IN ('pending', 'blocked') ORDER BY deviceSequence")
    fun observeUndelivered(): Flow<List<ThoughtsOutboxEntity>>

    @Query("SELECT * FROM ThoughtsOutbox ORDER BY deviceSequence")
    suspend fun allOutbox(): List<ThoughtsOutboxEntity>

    @Query("SELECT * FROM ThoughtsOutbox WHERE entityType = 'logical_comment' AND collection = 'comments' AND deliveryState IN ('accepted', 'reconciled') ORDER BY deviceSequence")
    suspend fun completedLogicalComments(): List<ThoughtsOutboxEntity>

    @Query("UPDATE ThoughtsOutbox SET deviceSequence = :deviceSequence WHERE operationId = :operationId")
    suspend fun updateOutboxDeviceSequence(operationId: String, deviceSequence: Long): Int

    @Query("UPDATE ThoughtsOutbox SET deviceSequence = :deviceSequence, deliveryState = :deliveryState, failureCode = :failureCode, failureDetail = :failureDetail WHERE operationId = :operationId")
    suspend fun finishOutboxDeviceSequenceReconciliation(
        operationId: String,
        deviceSequence: Long,
        deliveryState: String,
        failureCode: String,
        failureDetail: String,
    ): Int

    @Query("UPDATE ThoughtsOutbox SET deliveryState = 'accepted', serverSequence = :serverSequence, failureCode = '', failureDetail = '', lastAttemptEpochMillis = :acceptedAtEpochMillis, acceptedAtEpochMillis = :acceptedAtEpochMillis WHERE operationId = :operationId")
    suspend fun markAccepted(
        operationId: String,
        serverSequence: Long,
        acceptedAtEpochMillis: Long,
    )

    @Query("UPDATE ThoughtsOutbox SET deliveryState = 'blocked', failureCode = :failureCode, failureDetail = :failureDetail, lastAttemptEpochMillis = :attemptedAtEpochMillis WHERE operationId = :operationId")
    suspend fun markBlocked(
        operationId: String,
        failureCode: String,
        failureDetail: String,
        attemptedAtEpochMillis: Long,
    )

    /** Retain the original request and the evidence explaining its replacement/completion. */
    @Query("UPDATE ThoughtsOutbox SET deliveryState = :state, serverSequence = :serverSequence, failureCode = :reason, failureDetail = :detail, lastAttemptEpochMillis = :now WHERE operationId = :operationId")
    suspend fun reconcileDeletion(
        operationId: String,
        state: String,
        serverSequence: Long?,
        reason: String,
        detail: String,
        now: Long,
    )

    @Query("SELECT COUNT(*) FROM ThoughtsOutbox WHERE deliveryState = 'blocked'")
    suspend fun blockedOperationCount(): Int

    /** Cards made in an HTML attachment that the server refused (html_attachment_unknown) since [since]. */
    @Query("SELECT COUNT(*) FROM ThoughtsOutbox WHERE entityType = 'review_card' AND deliveryState = 'superseded' AND failureCode = 'html_attachment_unknown' AND lastAttemptEpochMillis >= :since")
    suspend fun attachmentCardsNotSentSince(since: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProjection(projection: ThoughtsProjectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommentScopes(scopes: List<ThoughtsCommentScopeEntity>)

    @Query("DELETE FROM ThoughtsCommentScope WHERE entityId = :entityId AND recordSource = :source")
    suspend fun deleteCommentScopes(entityId: String, source: String)

    @Transaction
    suspend fun putProjection(projection: ThoughtsProjectionEntity) {
        // Proposal history is retired; only current proposals are kept.
        if (projection.entityType == "proposal_event") return
        insertProjection(projection)
        if (projection.entityType == "logical_comment" && projection.collection == "comments") {
            deleteCommentScopes(projection.entityId, "projection")
            insertCommentScopes(logicalCommentScopeKeys(projection.entityId, projection.payloadJson).map {
                ThoughtsCommentScopeEntity(it, projection.entityId, "projection")
            })
        }
    }

    @Query("DELETE FROM ThoughtsProjection WHERE entityType = :entityType AND collection = :collection AND entityId = :entityId")
    suspend fun deleteProjection(
        entityType: String,
        collection: String,
        entityId: String,
    )

    @Query("DELETE FROM ThoughtsProjection")
    suspend fun deleteProjections()

    @androidx.room.RawQuery
    suspend fun scopedLogicalCommentProjections(query: androidx.sqlite.db.SupportSQLiteQuery): List<ThoughtsProjectionEntity>

    @androidx.room.RawQuery
    suspend fun scopedLocalCommentStates(query: androidx.sqlite.db.SupportSQLiteQuery): List<ThoughtsLocalCommentStateEntity>

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = 'logical_comment' AND collection = 'comments' ORDER BY entityId")
    suspend fun logicalCommentProjections(): List<ThoughtsProjectionEntity>

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = 'annotation' ORDER BY collection, entityId")
    suspend fun annotationProjections(): List<ThoughtsProjectionEntity>

    @Query("SELECT COUNT(*) FROM ThoughtsProjection")
    suspend fun projectionCount(): Int

    @Query("SELECT p.payloadJson FROM ThoughtsProjection p LEFT JOIN ThoughtsLocalCommentState l ON l.entityId=p.entityId WHERE p.entityType='logical_comment' AND p.deleted=0 AND COALESCE(l.deleted,0)=0")
    fun linkingStatusPayloads(): List<String>


    @Query("SELECT * FROM ThoughtsLocalCommentState ORDER BY entityId")
    fun observeLocalCommentStates(): Flow<List<ThoughtsLocalCommentStateEntity>>

    @Query("SELECT * FROM ThoughtsLocalCommentState WHERE entityId = :entityId")
    suspend fun localCommentState(entityId: String): ThoughtsLocalCommentStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocalCommentState(state: ThoughtsLocalCommentStateEntity)

    @Transaction
    suspend fun putLocalCommentState(state: ThoughtsLocalCommentStateEntity) {
        insertLocalCommentState(state)
        deleteCommentScopes(state.entityId, "local")
        insertCommentScopes(logicalCommentScopeKeys(state.entityId, state.payloadJson).map {
            ThoughtsCommentScopeEntity(it, state.entityId, "local")
        })
    }

    @Transaction
    suspend fun retainLatestLocalComment(state: ThoughtsLocalCommentStateEntity) {
        val previous = localCommentState(state.entityId)
        if (previous == null || state.revision > previous.revision ||
            (state.revision == previous.revision && state.deviceSequence >= previous.deviceSequence)) {
            putLocalCommentState(state)
        }
    }

    @Query("SELECT state.* FROM ThoughtsLocalCommentState AS state INNER JOIN ThoughtsOutbox AS operation ON operation.operationId = state.operationId WHERE state.deleted = 1 AND operation.deliveryState IN ('accepted', 'reconciled') ORDER BY state.entityId")
    suspend fun completedLocalCommentDeletions(): List<ThoughtsLocalCommentStateEntity>

    @Query("SELECT state.* FROM ThoughtsLocalCommentState AS state INNER JOIN ThoughtsOutbox AS operation ON operation.operationId = state.operationId WHERE operation.deliveryState IN ('pending', 'blocked') ORDER BY state.deviceSequence")
    suspend fun pendingLocalCommentStates(): List<ThoughtsLocalCommentStateEntity>

    @Query("SELECT state.* FROM ThoughtsLocalCommentState AS state INNER JOIN ThoughtsOutbox AS operation ON operation.operationId = state.operationId WHERE state.deleted = 0 AND operation.deliveryState = 'accepted' ORDER BY state.deviceSequence")
    suspend fun acceptedLocalCommentStates(): List<ThoughtsLocalCommentStateEntity>

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = 'proposal' AND collection = 'proposals' AND deleted = 0 ORDER BY entityId")
    suspend fun currentProposalProjections(): List<ThoughtsProjectionEntity>

    // These consumers must never materialize proposal history or unrelated annotation payloads.
    @Query("SELECT * FROM ThoughtsProjection WHERE entityType IN ('annotation', 'resolution') ORDER BY entityType, collection, entityId")
    suspend fun sidecarProjections(): List<ThoughtsProjectionEntity>

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = 'review_tag_merge' AND deleted = 0 ORDER BY entityId")
    suspend fun reviewTagMergeProjections(): List<ThoughtsProjectionEntity>

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = 'pdf_link_override' AND collection = 'link-overrides' AND deleted = 0 ORDER BY entityId")
    suspend fun pdfLinkOverrideProjections(): List<ThoughtsProjectionEntity>

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = 'agent_conversation' AND collection = 'agent-conversations' AND deleted = 0 ORDER BY entityId")
    suspend fun agentConversationProjections(): List<ThoughtsProjectionEntity>

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = 'review_card_feedback' AND collection = 'review-feedback' AND deleted = 0 ORDER BY entityId")
    suspend fun reviewCardFeedbackProjections(): List<ThoughtsProjectionEntity>

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = 'document_feedback' AND collection = 'document-feedback' AND deleted = 0 ORDER BY entityId")
    suspend fun documentFeedbackProjections(): List<ThoughtsProjectionEntity>

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = 'review_tag_groups' AND collection = 'review-tags' AND entityId = 'groups' AND deleted = 0 LIMIT 1")
    suspend fun reviewTagGroupsProjection(): ThoughtsProjectionEntity?

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = 'review_card' AND deleted = 0 ORDER BY collection, entityId")
    suspend fun reviewCardProjections(): List<ThoughtsProjectionEntity>

    @Query("SELECT entityType, entityId FROM ThoughtsProjection WHERE entityType IN ('review_card', 'review_event', 'review_exclusion') AND deleted = 0 ORDER BY entityType, collection, entityId")
    suspend fun reviewProjectionIdentities(): List<ReviewProjectionIdentity>

    // ReviewRecord is the durable installed state; missing/different rows also recover an
    // interruption after advancing the Sync cursor but before updating the review index.
    @Query("""
        SELECT p.* FROM ThoughtsProjection p LEFT JOIN ReviewRecord r
        ON r.path = :reviewRoot || '/' || CASE p.entityType
            WHEN 'review_card' THEN 'cards' WHEN 'review_event' THEN 'events'
            WHEN 'review_exclusion' THEN 'exclusions' END || '/server/' || p.entityId || '.json'
        WHERE p.entityType IN ('review_card', 'review_event', 'review_exclusion') AND p.deleted = 0
          AND (r.path IS NULL OR r.source != 1 OR r.payload != p.payloadJson)
        ORDER BY p.entityType, p.collection, p.entityId
    """)
    suspend fun changedReviewProjections(reviewRoot: String): List<ThoughtsProjectionEntity>

    @Query("SELECT * FROM ThoughtsProjection ORDER BY entityType, collection, entityId")
    suspend fun projections(): List<ThoughtsProjectionEntity>

    @Query("SELECT * FROM ThoughtsProjection WHERE entityType = :entityType AND collection = :collection AND entityId = :entityId LIMIT 1")
    suspend fun projection(
        entityType: String,
        collection: String,
        entityId: String,
    ): ThoughtsProjectionEntity?

    @Query("SELECT * FROM ThoughtsSourceSnapshot WHERE path = :path")
    suspend fun sourceSnapshot(path: String): ThoughtsSourceSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSourceSnapshot(snapshot: ThoughtsSourceSnapshotEntity)

    @Query("SELECT * FROM ThoughtsSourceRequest WHERE requestId = :requestId")
    suspend fun sourceRequest(requestId: String): ThoughtsSourceRequestEntity?

    @Query("SELECT * FROM ThoughtsSourceRequest ORDER BY deviceSequence")
    suspend fun sourceRequests(): List<ThoughtsSourceRequestEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceRequest(request: ThoughtsSourceRequestEntity): Long

    @Query("UPDATE ThoughtsSourceRequest SET deviceSequence = :deviceSequence WHERE requestId = :requestId")
    suspend fun updateSourceRequestDeviceSequence(requestId: String, deviceSequence: Long): Int

    @Query("SELECT * FROM ThoughtsBuildRequest WHERE requestId = :requestId")
    suspend fun buildRequest(requestId: String): ThoughtsBuildRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBuildRequest(request: ThoughtsBuildRequestEntity)

    @Query("DELETE FROM ThoughtsBuildRequest WHERE requestId = :requestId")
    suspend fun deleteBuildRequest(requestId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSyncRun(run: ThoughtsSyncRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSyncRuns(runs: List<ThoughtsSyncRunEntity>)

    @Query("SELECT * FROM sync_runs WHERE startedAtEpochMillis >= :cutoff ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    suspend fun syncRuns(cutoff: Long, limit: Int): List<ThoughtsSyncRunEntity>

    @Query("SELECT * FROM sync_runs WHERE startedAtEpochMillis >= :cutoff AND target IS NULL ORDER BY updatedAtEpochMillis DESC LIMIT 1")
    suspend fun latestGlobalSyncRun(cutoff: Long): ThoughtsSyncRunEntity?

    @Query("SELECT * FROM sync_runs WHERE startedAtEpochMillis >= :cutoff AND target IS NULL ORDER BY updatedAtEpochMillis DESC LIMIT 1")
    fun observeLatestGlobalSyncRun(cutoff: Long): Flow<ThoughtsSyncRunEntity?>

    @Query("SELECT * FROM sync_runs WHERE startedAtEpochMillis >= :cutoff AND target = :target ORDER BY updatedAtEpochMillis DESC LIMIT 1")
    suspend fun latestTargetSyncRun(cutoff: Long, target: String): ThoughtsSyncRunEntity?

    @Query("SELECT * FROM sync_runs WHERE startedAtEpochMillis >= :cutoff AND displayStatus IN (:statuses) ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    suspend fun pendingSyncRuns(
        cutoff: Long,
        statuses: List<String>,
        limit: Int,
    ): List<ThoughtsSyncRunEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM sync_runs WHERE startedAtEpochMillis >= :cutoff AND displayStatus IN (:statuses))")
    fun observeHasPendingSyncRuns(cutoff: Long, statuses: List<String>): Flow<Boolean>

    @Query("SELECT * FROM sync_runs WHERE legacyEventsImported = 0 ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    suspend fun unimportedSyncRuns(limit: Int): List<ThoughtsSyncRunEntity>

    @Query("SELECT * FROM sync_runs WHERE id = :runId LIMIT 1")
    suspend fun syncRun(runId: String): ThoughtsSyncRunEntity?

    @Query("SELECT * FROM sync_runs WHERE id = :runId LIMIT 1")
    fun observeSyncRun(runId: String): Flow<ThoughtsSyncRunEntity?>

    @Query("DELETE FROM sync_events WHERE origin = 'diagnostic' AND (createdAtEpochMillis < :cutoff OR runId NOT IN (SELECT id FROM sync_runs))")
    suspend fun pruneDiagnostics(cutoff: Long)

    @Query("DELETE FROM sync_runs WHERE startedAtEpochMillis < :cutoff")
    suspend fun pruneSyncRuns(cutoff: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSyncStep(step: ThoughtsSyncStepEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun appendSyncEvent(event: ThoughtsSyncEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun appendSyncEvents(events: List<ThoughtsSyncEventEntity>): List<Long>

    @Query("SELECT attempt FROM sync_runs WHERE id = :runId LIMIT 1")
    suspend fun diagnosticRunAttempt(runId: String): Int?

    @Query("SELECT COUNT(*) FROM sync_events WHERE runId = :runId AND origin = 'diagnostic'")
    suspend fun diagnosticEventCount(runId: String): Int

    @Query("SELECT * FROM sync_events WHERE runId = :runId ORDER BY id")
    suspend fun syncEvents(runId: String): List<ThoughtsSyncEventEntity>

    @Query("SELECT * FROM (SELECT * FROM sync_events WHERE runId = :runId ORDER BY id DESC LIMIT :limit) ORDER BY id")
    suspend fun recentSyncEvents(runId: String, limit: Int): List<ThoughtsSyncEventEntity>

    @Query("SELECT * FROM sync_events WHERE runId = :runId AND origin = 'phone' AND errorCode = 'pdf_build_pending' ORDER BY id DESC LIMIT 1")
    suspend fun pendingPdfBuildEvent(runId: String): ThoughtsSyncEventEntity?


    @Query("SELECT * FROM sync_events WHERE runId IN (:runIds) ORDER BY runId, id")
    suspend fun syncEventsForRuns(runIds: List<String>): List<ThoughtsSyncEventEntity>

    @Query("SELECT * FROM (SELECT * FROM sync_events WHERE runId = :runId AND origin != 'diagnostic' ORDER BY id DESC LIMIT 1000) ORDER BY createdAtEpochMillis, id")
    fun observeSyncEvents(runId: String): Flow<List<ThoughtsSyncEventEntity>>

    @Query("UPDATE sync_runs SET serverState = :serverState, serverTerminal = :terminal, lastServerSequence = :sequence, updatedAtEpochMillis = CASE WHEN updatedAtEpochMillis < :receivedAt THEN :receivedAt ELSE updatedAtEpochMillis END WHERE id = :runId AND lastServerSequence < :sequence")
    suspend fun advanceServerCursor(
        runId: String,
        serverState: String,
        terminal: Boolean,
        sequence: Long,
        receivedAt: Long,
    ): Int

    @Query("UPDATE sync_runs SET streamState = :streamState WHERE id = :runId")
    suspend fun updateStreamState(runId: String, streamState: String)

    @Query("UPDATE sync_runs SET legacyEventsImported = 1 WHERE id = :runId")
    suspend fun markLegacyEventsImported(runId: String)

    @Transaction
    suspend fun persistServerEvent(event: ThoughtsSyncEventEntity, terminal: Boolean) {
        val inserted = appendSyncEvent(event)
        if (inserted != -1L) {
            advanceServerCursor(
                event.runId,
                event.state,
                terminal,
                requireNotNull(event.serverSequence),
                event.receivedTimestampEpochMillis,
            )
        }
    }
}

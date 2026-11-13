// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A current proposal row with status `removed_outside` (agent-proposal spec sections 10.2, 10.3):
 * its wrapper vanished from canonical source without a decision. Listed under Issues as
 * "Removed outside Thoughts" with Restore and Dismiss, like an "Anchor removed" comment.
 */
internal data class RemovedOutsideProposal(
    val id: String,
    val sourceFile: String,
    /** 1-based line of the wrapper in the last source that had it; 0 when unknown. */
    val line: Int,
    val comment: String,
    val before: String,
    val after: String,
    /** accept | reject | edited | file_deleted: a hint, never a decision. */
    val looksLike: String,
    val removedAt: String,
    val createdAt: String,
    /** PDFs that showed the proposal, for grouping in Overview; may be empty. */
    val pdfFilenames: List<String>,
    /** The row as synced (plus its projection revision), the base of Dismiss. */
    val rowJson: String,
) : java.io.Serializable {
    fun row(): JsonObject = JsonParser.parseString(rowJson).asJsonObject

    fun snapshot(): ProposalRestore.Snapshot? = ProposalRestore.Snapshot.fromJson(row().getAsJsonObject("snapshot"))

    companion object {
        const val STATUS = "removed_outside"

        fun from(record: JsonObject, pdfFilenames: List<String> = emptyList()): RemovedOutsideProposal? {
            if (record.text("status") != STATUS) return null
            val snapshot = record.get("snapshot")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val parsed = ProposalRestore.Snapshot.fromJson(snapshot) ?: return null
            val removal = record.get("removal")?.takeIf { it.isJsonObject }?.asJsonObject
            return RemovedOutsideProposal(
                id = record.text("proposal_id").ifBlank { parsed.proposalId },
                sourceFile = record.text("source_file").ifBlank { parsed.sourceFile },
                line = removal?.get("source_line")?.let { runCatching { it.asInt }.getOrNull() }?.coerceAtLeast(0) ?: 0,
                comment = parsed.comment,
                before = parsed.before,
                after = parsed.after,
                looksLike = removal?.text("looks_like").orEmpty(),
                removedAt = removal?.text("removed_at").orEmpty(),
                createdAt = parsed.createdAt,
                pdfFilenames = pdfFilenames,
                rowJson = record.toString(),
            )
        }

        private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }
}

/**
 * Dismiss of a proposal removed outside Thoughts (agent-proposal spec section 10.6): the only client
 * operation on a `proposal` row, with the row's revision as base and its payload retired as
 * `dismissed`. No source changes. Fedora refuses it once the row is no longer `removed_outside`.
 */
internal data class RemovedProposalDismissal(
    val proposalId: String,
    val baseRevision: Long,
    val clientTimestamp: String,
    val payload: JsonObject,
) {
    companion object {
        const val ENTITY_TYPE = "proposal"
        const val COLLECTION = "proposals"
        /** Added to current proposal rows read for the UI; never part of a payload sent. */
        const val PROJECTION_REVISION = "_projection_revision"
        private val ID = Regex("[a-z0-9][a-z0-9._:-]{0,127}")

        fun of(row: JsonObject, now: String): RemovedProposalDismissal {
            val id = row.get("proposal_id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            require(ID.matches(id)) { "Invalid agent proposal identity" }
            require(row.get("status")?.takeIf { it.isJsonPrimitive }?.asString == RemovedOutsideProposal.STATUS) {
                "The proposal is no longer removed outside Thoughts"
            }
            val revision = runCatching { (row.get(PROJECTION_REVISION) ?: row.get("revision"))?.asLong }.getOrNull()
            require(revision != null && revision > 0) { "The removed proposal has no revision" }
            val payload = row.deepCopy().apply {
                remove(PROJECTION_REVISION)
                addProperty("status", "dismissed")
                addProperty("kind", "dismissed")
                addProperty("deleted", true)
                addProperty("revision", revision + 1)
                addProperty("updated_at", now)
            }
            return RemovedProposalDismissal(id, revision, now, payload)
        }
    }
}

/**
 * Restore Proposal on the phone: the section 10.6 edit applied to the phone's copy of the source
 * (including unsynced edits) and saved through the source edit queue, as
 * [LocalSourceComments.restoreAnchor] does for comments. No proposal operation is sent: the server
 * sets the row `pending` again when it sees the wrapper.
 */
internal class RemovedProposalRestorer internal constructor(
    private val sources: OfflineSourceBundleStore,
    private val save: (SourceSnapshot, String, String, String) -> Unit,
) {
    constructor(context: Context) : this(
        OfflineSourceBundleStore(context),
        { snapshot, filename, hash, contents ->
            SourceEditQueue(context, scheduleRequests = false).saveForNextSync(snapshot, filename, hash, contents); Unit
        },
    )

    data class Outcome(
        val restored: Boolean,
        val alreadyPresent: Boolean = false,
        /** sources_unavailable, or a spec refusal code (source_missing, id_in_source, ambiguous, text_changed, not_restorable). */
        val refusal: String? = null,
        val sourceFile: String = "",
        val line: Int = 0,
    )

    fun restore(proposal: RemovedOutsideProposal): Outcome {
        val snapshot = proposal.snapshot() ?: return Outcome(false, refusal = "not_restorable", sourceFile = proposal.sourceFile, line = proposal.line)
        val path = proposal.sourceFile
        val metadata = sources.librarySourceMetadata()
            ?: return Outcome(false, refusal = "sources_unavailable", sourceFile = path, line = proposal.line)
        val source = sources.sourceSnapshot(path, proposal.line.coerceAtLeast(1))
        if (source == null && path in metadata.trackedSourcePaths() && sources.sourceWorkingFile(path)?.exists() != false) {
            // Tracked but not readable here: not proof that the file is gone.
            return Outcome(false, refusal = "sources_unavailable", sourceFile = path, line = proposal.line)
        }
        return when (val result = ProposalRestore.restore(path, source?.contents, snapshot, proposal.line)) {
            is ProposalRestore.Result.AlreadyPresent -> Outcome(true, alreadyPresent = true, sourceFile = path, line = result.line)
            is ProposalRestore.Result.Refused -> Outcome(false, refusal = result.code, sourceFile = path,
                line = result.line.takeIf { it > 0 } ?: proposal.line)
            is ProposalRestore.Result.Restored -> {
                source!!
                val filename = source.bundlePdfFilename ?: proposal.pdfFilenames.firstOrNull()
                    ?: return Outcome(false, refusal = "sources_unavailable", sourceFile = path, line = proposal.line)
                save(source, filename, source.bundlePdfSha256.orEmpty(), result.output)
                SourceCommentChanges.changes.tryEmit(Unit)
                Outcome(true, sourceFile = path, line = result.line)
            }
        }
    }
}

// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceReviewComment
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.gitlab.mudlej.MjPdfReader.data.source.CommentAnchorState
import com.gitlab.mudlej.MjPdfReader.core.io.parseCommentTimestamp
import com.google.gson.JsonParser

internal const val AGENT_PROPOSAL_SUBTYPE = "AgentRemove"

internal data class DocumentCommentProjection(
    val documents: List<OverviewCommentDocument>,
    val orphanedSourceCommentCount: Int,
)

internal fun isAgentProposal(
    comment: OverviewComment,
    proposalIds: Set<String>,
): Boolean = comment.subtype == AGENT_PROPOSAL_SUBTYPE && comment.id in proposalIds

/**
 * Applies the source bundle's authoritative comment projections and agent proposals to the
 * canonical PDF-comment sidecars.
 */
internal fun projectDocumentComments(
    commentDocuments: List<OverviewCommentDocument>,
    proposals: List<OfflineAgentProposal>,
    sourceComments: List<OfflineSourceReviewComment>,
    sourceCommentDocuments: Set<String>,
    logicalComments: List<OfflineLogicalComment> = emptyList(),
    logicalDocumentFilename: String? = null,
    logicalDocumentPdfSha256: String? = null,
    overviewPdfSha256ByFilename: Map<String, String> = emptyMap(),
): DocumentCommentProjection {
    var orphanedSourceCommentCount = 0
    val proposalIds = proposals.mapTo(linkedSetOf(), OfflineAgentProposal::id)
    val combined = linkedMapOf<String, MutableList<OverviewComment>>()
    proposals.forEach { proposal ->
        val correspondence = proposal.correspondence
        val locations = correspondence?.locations.orEmpty()
        // LaTeX copies (contents entries, running heads) usually come first; list the real change.
        val filename = locations.firstPrimaryOrNull()?.pdfFilename
            ?: proposal.pdfFilenames.firstOrNull()
            ?: return@forEach
        val stem = filename.removeSuffix(".pdf")
        val quad = if (correspondence == null) {
            proposal.quads.firstOrNull { it.pdfFilename == filename && !it.duplicate }
                ?: proposal.quads.firstOrNull { it.pdfFilename == filename }
        } else null
        val exactLocation = locations.firstPrimaryOrNull { it.pdfFilename == filename }
        val sourceTarget = correspondence?.sourceTargets?.firstOrNull { it.role == "after" }
            ?: correspondence?.sourceTargets?.firstOrNull()
        val selectedText = if (proposal.kind == "edit") {
            "${proposal.beforePreview} → ${proposal.afterPreview.ifBlank { "[delete]" }}"
        } else {
            proposal.beforePreview
        }
        combined.getOrPut(stem) { mutableListOf() }.add(
            OverviewComment(
                documentStem = stem,
                id = proposal.id,
                parentId = proposal.parentId.orEmpty(),
                contents = proposal.comment,
                selectedText = selectedText,
                author = "Agent",
                sourceFile = sourceTarget?.sourceFile ?: proposal.sourceFile,
                sourceLine = sourceTarget?.lineStart ?: proposal.line,
                anchorStatus = correspondence?.state ?: when {
                    proposal.suppressed -> "source_only"
                    quad != null -> "anchored"
                    else -> "needs_anchor"
                },
                pageIndex = exactLocation?.pageIndex ?: quad?.page?.minus(1) ?: -1,
                subtype = AGENT_PROPOSAL_SUBTYPE,
                created = proposal.createdAt.orEmpty(),
                modified = "",
                activityMillis = parseCommentTimestamp(proposal.createdAt)?.toEpochMilli() ?: 0,
                sourceContext = selectedText,
                pdfRect = exactLocation?.regions?.firstOrNull()
                    ?: exactLocation?.point?.takeIf { it.size == 2 }?.let { point ->
                        listOf(point[0], point[1], point[0], point[1])
                    }
                    ?: quad?.let { listOf(it.left, it.top, it.right, it.bottom) }.orEmpty(),
                logicalLocations = locations,
                reviewCorrespondence = correspondence,
            ),
        )
    }
    val logicalById = logicalComments.associateBy { it.id }
    val deletedRoots = logicalComments.filter { it.deleted && it.parentId == null }.mapTo(hashSetOf()) { it.id }
    logicalComments.filterNot { it.deleted || it.threadRootId in deletedRoots }.forEach { logical ->
        val placement = logicalById[logical.threadRootId] ?: logical
        val placementPayload = runCatching { JsonParser.parseString(placement.payloadJson).asJsonObject }.getOrNull() ?: return@forEach
        val placedOrigin = placementPayload.get("pdf_placement")?.takeIf { it.isJsonObject }?.asJsonObject
        val originCollection = placedOrigin?.string("collection")?.takeIf { collection ->
            placedOrigin.string("pdf_sha256") == (if (logicalDocumentFilename == "$collection.pdf") logicalDocumentPdfSha256 else overviewPdfSha256ByFilename["$collection.pdf"])
        } ?: placement.originCollection
        val matchingLocation = logicalDocumentFilename?.let { filename ->
            placement.locations.firstPrimaryOrNull { placement ->
                placement.pdfFilename.equals(filename, ignoreCase = true) &&
                    logicalDocumentPdfSha256 != null &&
                    placement.pdfSha256 == logicalDocumentPdfSha256 &&
                    placement.status != "unavailable"
            }
        }
        if (logicalDocumentFilename != null && matchingLocation == null &&
            originCollection != logicalDocumentFilename.removeSuffix(".pdf") &&
            placement.locations.none { it.pdfFilename == logicalDocumentFilename }) return@forEach
        val stem = logicalDocumentFilename?.removeSuffix(".pdf")
            ?: originCollection.takeIf(String::isNotBlank)
            ?: placement.locations.firstOrNull()?.pdfFilename?.removeSuffix(".pdf")
            ?: if (logical.scopeState == "standalone" && logicalDocumentFilename == null) "" else return@forEach
        val payload = runCatching { JsonParser.parseString(logical.payloadJson).asJsonObject }
            .getOrNull() ?: return@forEach
        payload.getAsJsonObject("scope")
            ?.string("anchor_id")
            ?.takeIf(String::isNotBlank)
            ?.let { sourceAnchorId ->
                combined[stem]?.removeAll { comment ->
                    !isAgentProposal(comment, proposalIds) &&
                        comment.id == sourceAnchorId &&
                        comment.anchorStatus == "source_projected"
                }
            }
        // A published mapping is usable only for the installed revision of that PDF.
        val revisionBoundLocations = placement.locations.map { location ->
            val currentHash = if (logicalDocumentFilename == location.pdfFilename) logicalDocumentPdfSha256
                else overviewPdfSha256ByFilename[location.pdfFilename]
            if (currentHash != null && currentHash != location.pdfSha256) location.copy(status = "unavailable") else location
        }
        val currentLocation = matchingLocation ?: revisionBoundLocations.firstPrimaryOrNull {
            logicalDocumentFilename == null && it.pdfFilename.removeSuffix(".pdf").equals(stem, ignoreCase = true) && it.status == "exact"
        }
        val currentPdfSha256 = logicalDocumentPdfSha256 ?: overviewPdfSha256ByFilename["$stem.pdf"]
        val override = placementPayload.get("pdf_placement")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.takeIf { it.string("pdf_sha256") == currentPdfSha256 && it.string("collection") == stem }
        val origin = override ?: placementPayload.get("origin")?.takeIf { it.isJsonObject }?.asJsonObject
        val capturedOriginMatches = currentPdfSha256 != null && origin?.string("pdf_sha256") == currentPdfSha256 &&
            origin.string("collection") == stem && placement.originPageIndex >= 0
        val rawGeometry = origin?.get("raw_pdf_geometry")?.takeIf { it.isJsonObject }?.asJsonObject
        val capturedPage = rawGeometry?.get("page")?.asInt?.minus(1) ?: placement.originPageIndex
        val effectivePage = if (override != null) capturedPage else currentLocation?.pageIndex ?: if (capturedOriginMatches) capturedPage else -1
        val capturedLocation = if (capturedOriginMatches && capturedPage >= 0) LogicalCommentPdfLocation(
            pdfFilename = "$stem.pdf", pdfSha256 = currentPdfSha256, page = capturedPage + 1,
            status = "page_only",
        ) else null
        val effectiveLocations = revisionBoundLocations.let { locations ->
            if (capturedLocation != null && locations.none {
                    it.pdfFilename == capturedLocation.pdfFilename && it.pdfSha256 == capturedLocation.pdfSha256 && it.status != "unavailable"
                }) locations.filterNot { it.pdfFilename == capturedLocation.pdfFilename } + capturedLocation else locations
        }
        val location = payload.get("location")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let(CommentLocation::fromJson)
        combined.getOrPut(stem) { mutableListOf() }.add(
            OverviewComment(
                documentStem = stem,
                id = logical.id,
                parentId = logical.parentId.orEmpty(),
                contents = payload.string("contents"),
                selectedText = payload.string("selected_text"),
                author = payload.string("author"),
                sourceFile = logical.sourceFile,
                sourceLine = logical.sourceLine,
                // "Anchor removed" wins over every other status; replies follow their root.
                anchorStatus = if (CommentAnchorState.isAnchorRemovedPayload(placementPayload)) CommentAnchorState.DISPLAY_STATUS
                    else if (payload.string("source_authority") == "client-v1") logical.scopeState else logical.correspondence?.state ?: logical.scopeState,
                pageIndex = effectivePage,
                subtype = payload.string("subtype").ifBlank { "Text" },
                created = payload.string("created_at"),
                modified = payload.string("updated_at"),
                activityMillis = parseCommentTimestamp(payload.string("updated_at"))?.toEpochMilli() ?: 0,
                priority = CommentPriority.fromWireValue(payload.string("priority")),
                location = location,
                color = logicalCommentColor(payload),
                sourceContext = payload.string("selected_text"),
                chatGptUrl = normalizeChatGptUrl(payload.string("chatgpt_url")).orEmpty(),
                pdfRect = if (capturedOriginMatches && (currentLocation == null || override != null))
                    rawGeometry?.getAsJsonArray("rect")?.map { it.asFloat }.orEmpty() else emptyList(),
                logicalPayloadJson = logical.payloadJson,
                noteDetails = if (payload.string("subtype") == "Note") NoteDetails.fromJson(payload) else null,
                logicalLocations = if (logicalDocumentFilename == null) effectiveLocations else effectiveLocations.filter {
                    it.pdfFilename == logicalDocumentFilename && it.pdfSha256 == logicalDocumentPdfSha256
                },
                reviewCorrespondence = logical.correspondence,
            ),
        )
    }
    return DocumentCommentProjection(
        documents = combined.entries.sortedBy { it.key }.map { (stem, comments) ->
            OverviewCommentDocument(stem, comments)
        },
        orphanedSourceCommentCount = orphanedSourceCommentCount,
    )
}

private fun com.google.gson.JsonObject.string(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

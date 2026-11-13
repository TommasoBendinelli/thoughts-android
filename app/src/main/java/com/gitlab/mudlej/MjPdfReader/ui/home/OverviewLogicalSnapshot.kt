// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.google.gson.JsonParser

/** A deferred catalogue may predate a saved edit, creation, or deletion. */
internal fun OfflineOverviewSourceSnapshot.retainingNewerLogicalComments(
    current: List<OfflineLogicalComment>,
): OfflineOverviewSourceSnapshot {
    val incoming = logicalComments.associateBy { it.id }
    return withLogicalPayloads(current.filter { comment ->
        val received = incoming[comment.id]
        received == null || received.revision < comment.revision
    }.map { JsonParser.parseString(it.payloadJson).asJsonObject })
}

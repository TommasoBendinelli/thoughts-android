// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence

/** Join cached projections, never serialize or parse the library's full source manifests. */
internal fun combineProjectBundles(id: String, bundles: List<Pair<OfflineSourceBundle, Boolean>>): OfflineSourceBundle {
    val sources = linkedMapOf<String, OfflineBundleSource>()
    val resources = linkedMapOf<String, OfflineBundleResource>()
    val resourceStale = hashMapOf<String, Boolean>()
    val pdfs = linkedMapOf<String, OfflineBundlePdf>()
    val proposals = linkedMapOf<String, OfflineAgentProposal>()
    val reviews = linkedMapOf<String, OfflineSourceReviewComment>()
    val logical = linkedMapOf<String, OfflineLogicalComment>()
    val correspondences = linkedMapOf<String, ReviewCorrespondence>()
    for ((bundle, stale) in bundles) {
        for ((path, source) in bundle.sources) {
            val previous = sources.putIfAbsent(path, source)
            check(previous == null || previous == source) { "Project artifacts disagree about source $path" }
        }
        for ((path, resource) in bundle.resources) {
            val previous = resources[path]
            val same = previous == null || previous.copy(pdfFilenames = resource.pdfFilenames) == resource
            check(same || resource.kind == "shared_style" && (stale || resourceStale[path] == true)) {
                "Project artifacts disagree about resource $path"
            }
            val incoming = previous == null || same || resourceStale[path] == true && !stale ||
                resourceStale[path] == stale && resource.sha256 < previous.sha256
            val selected = if (incoming) resource else requireNotNull(previous)
            resources[path] = selected.copy(pdfFilenames = (previous?.pdfFilenames.orEmpty() + resource.pdfFilenames).distinct().sorted())
            resourceStale[path] = if (same && previous != null) stale && resourceStale[path] == true else if (incoming) stale else resourceStale[path] == true
        }
        for ((filename, pdf) in bundle.pdfs) {
            check(pdfs.putIfAbsent(filename, pdf) == null) { "Duplicate project publication $filename" }
        }
        for ((key, value) in bundle.reviewCorrespondences) {
            correspondences[key] = mergeProjectCorrespondence(correspondences[key], value)!!
        }
        for ((key, value) in bundle.proposals) {
            // An authored proposal can occur in multiple PDFs of its owning project,
            // observed at different revisions. Its identifier names the proposal
            // while objectSha256 names the revision, so one stale Library entry must
            // not take the reader down: settle on one revision deterministically,
            // carry the union of the placements, and let a real disagreement surface
            // through the correspondence instead of an exception.
            val previous = proposals[key]
            if (previous == null) {
                proposals[key] = value
                continue
            }
            // One revision must win, and for equal authored content the choice must
            // not depend on the order the bundles were read in. A real content
            // disagreement keeps the first revision seen rather than letting a
            // digest silently swap the authored text.
            val agrees = sameProposalRevision(previous, value)
            val base = if (agrees && value.objectSha256 < previous.objectSha256) value else previous
            proposals[key] = base.copy(
                bundleId = value.bundleId,
                pdfFilenames = (previous.pdfFilenames + value.pdfFilenames).distinct(),
                quads = (previous.quads + value.quads).distinct(),
                correspondence = mergeProjectCorrespondence(previous.correspondence, value.correspondence))
        }
        for ((key, value) in bundle.reviewComments) {
            val previous = reviews[key]
            check(previous == null || previous.copy(pdfFilenames = value.pdfFilenames) == value) { "Conflicting review comment $key" }
            reviews[key] = value.copy(pdfFilenames = (previous?.pdfFilenames.orEmpty() + value.pdfFilenames).distinct())
        }
        for ((key, value) in bundle.logicalComments) {
            val previous = logical[key]
            check(previous == null || previous.copy(correspondence = value.correspondence) == value) { "Conflicting logical comment $key" }
            logical[key] = value.copy(correspondence = mergeProjectCorrespondence(previous?.correspondence, value.correspondence))
        }
    }
    return OfflineSourceBundle(id, pdfs, sources, resources, proposals, reviews,
        bundles.flatMap { it.first.reviewCommentDocuments }.toSet(), logical, correspondences,
        bundles.flatMap { it.first.componentObjectIds }.toSet())
}

/**
 * Compare authored content only. bundleId, objectSha256, pdfFilenames and quads
 * describe how this bundle carries the proposal, not what the author wrote.
 */
private fun sameProposalRevision(a: OfflineAgentProposal, b: OfflineAgentProposal): Boolean =
    a.copy(bundleId = b.bundleId, objectSha256 = b.objectSha256, pdfFilenames = b.pdfFilenames,
        quads = b.quads, correspondence = b.correspondence) == b

private fun mergeProjectCorrespondence(a: ReviewCorrespondence?, b: ReviewCorrespondence?): ReviewCorrespondence? {
    if (a == null) return b
    if (b == null) return a
    check(a.copy(state = b.state, reason = b.reason, sourceBundleSha256 = b.sourceBundleSha256, locations = b.locations) == b) {
        "Conflicting correspondence ${b.reviewKey}"
    }
    val locations = (a.locations + b.locations).distinct()
    val conflict = a.state != b.state
    return b.copy(locations = locations, sourceBundleSha256 = if (a.sourceBundleSha256 == b.sourceBundleSha256) b.sourceBundleSha256 else "",
        state = if (conflict) "needs_attention" else b.state,
        reason = if (conflict) "Project artifacts disagree about the correspondence state." else b.reason)
}

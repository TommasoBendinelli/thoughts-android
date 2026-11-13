// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderFailure
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.isCanonicalPublishedPdfFilename
import com.gitlab.mudlej.MjPdfReader.data.sync.PdfBuildErrorReport

internal data class PdfBuildErrorPage(
    val pdfFilename: String?,
    val source: String,
    val message: String,
    val logReference: String,
    val buildId: String,
    val fallbackTarget: String?,
) {
    val actionTarget: String?
        get() = pdfFilename ?: fallbackTarget
}

internal data class PdfBuildErrorNavigation(
    val index: Int,
    val total: Int,
) {
    val displayIndex: Int
        get() = index + 1

    val canGoBack: Boolean
        get() = index > 0

    val canGoNext: Boolean
        get() = index + 1 < total
}

internal fun pdfBuildErrorNavigation(
    index: Int,
    total: Int,
): PdfBuildErrorNavigation {
    require(total > 0) { "An error viewer requires at least one error" }
    require(index in 0 until total) { "Error index is outside the viewer" }
    return PdfBuildErrorNavigation(index, total)
}

internal fun pdfBuildErrorPages(
    snapshot: PhoneRenderStatusSnapshot,
): List<PdfBuildErrorPage> {
    val jobs = snapshot.jobs
        .filter { it.stage in ERROR_STAGES }
        .sortedByDescending(PhoneRenderJobStatus::activityAtEpochMillis)
    val latestActivityByPdf = mutableMapOf<String, Long>()
    jobs.forEach { status ->
        val filenames = status.failedDocuments.map(PhoneRenderFailure::pdfFilename) +
            status.target.takeUnless { status.affectsAll }.orEmpty()
        filenames.filter(::isSafeErrorPdfFilename).forEach { filename ->
            latestActivityByPdf[filename] = maxOf(
                latestActivityByPdf[filename] ?: Long.MIN_VALUE,
                status.activityAtEpochMillis,
            )
        }
    }
    val pages = jobs.flatMap { status ->
        errorPagesForStatus(status).filter { page ->
            page.pdfFilename == null ||
                status.activityAtEpochMillis >= latestActivityByPdf.getValue(page.pdfFilename)
        }
    }
    return pages.distinctBy(PdfBuildErrorPage::deduplicationKey)
}

internal fun pdfBuildErrorPages(
    report: PdfBuildErrorReport,
): List<PdfBuildErrorPage> {
    val fallbackTarget = normalizedActionTarget(report.target)
    val pages = report.failedDocuments.mapNotNull { failure ->
        failure.toErrorPage(
            report.logReference,
            report.buildId,
            fallbackTarget,
        )
    }
    return if (pages.isNotEmpty()) {
        pages.distinctBy(PdfBuildErrorPage::deduplicationKey)
    } else {
        listOf(
            PdfBuildErrorPage(
                pdfFilename = fallbackTarget?.takeUnless { it == PhoneRenderQueue.ACTION_ALL },
                source = "",
                message = report.message,
                logReference = report.logReference,
                buildId = report.buildId,
                fallbackTarget = fallbackTarget,
            )
        )
    }
}

private fun errorPagesForStatus(status: PhoneRenderJobStatus): List<PdfBuildErrorPage> {
    val fallbackTarget = normalizedActionTarget(status.target)
    val attributed = status.failedDocuments.mapNotNull { failure ->
        failure.toErrorPage(
            status.logReference,
            status.buildId,
            fallbackTarget,
        )
    }
    if (attributed.isNotEmpty()) {
        return attributed
    }
    return listOf(
        PdfBuildErrorPage(
            pdfFilename = fallbackTarget?.takeUnless { it == PhoneRenderQueue.ACTION_ALL },
            source = "",
            message = status.message,
            logReference = status.logReference,
            buildId = status.buildId,
            fallbackTarget = fallbackTarget,
        )
    )
}

private fun PhoneRenderFailure.toErrorPage(
    logReference: String,
    buildId: String,
    fallbackTarget: String?,
): PdfBuildErrorPage? {
    val filename = pdfFilename.takeIf(::isSafeErrorPdfFilename) ?: return null
    return PdfBuildErrorPage(
        pdfFilename = filename,
        source = source,
        message = message,
        logReference = logReference,
        buildId = buildId,
        fallbackTarget = fallbackTarget,
    )
}

private fun normalizedActionTarget(target: String): String? {
    return when {
        target == PhoneRenderQueue.ACTION_ALL -> PhoneRenderQueue.ACTION_ALL
        isSafeErrorPdfFilename(target) -> target
        else -> null
    }
}

private fun isSafeErrorPdfFilename(filename: String): Boolean =
    filename.length <= 512 && isCanonicalPublishedPdfFilename(filename)

private fun PdfBuildErrorPage.deduplicationKey(): List<String?> = listOf(
    pdfFilename,
    source,
    message,
    logReference,
    buildId,
)

private val ERROR_STAGES = setOf(
    PhoneRenderStage.BLOCKED,
    PhoneRenderStage.ATTENTION,
    PhoneRenderStage.PARTIAL,
    PhoneRenderStage.FAILED,
)

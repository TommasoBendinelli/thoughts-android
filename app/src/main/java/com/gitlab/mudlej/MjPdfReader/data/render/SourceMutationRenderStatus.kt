// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState
import com.gitlab.mudlej.MjPdfReader.data.source.scopePdfFilenames

internal fun pendingSourceRenderStatuses(
    mutations: List<PendingSourceMutation>,
    target: String? = null,
    pendingMessage: String,
    conflictTitle: (String) -> String = { path -> "TeX conflict in $path" },
    failureTitle: (String) -> String = { path -> "TeX change failed in $path" },
): List<PhoneRenderJobStatus> = mutations
    .asSequence()
    .filter { mutation ->
        mutation.state != SourceMutationState.APPLIED
    }
    .flatMap { mutation ->
        val details = sourceMutationStatusDetails(mutation, conflictTitle, failureTitle)
        mutation.scopePdfFilenames()
            .asSequence()
            .filter { filename -> target == null || filename == target }
            .map { filename ->
                PhoneRenderJobStatus(
                    requestId = "local-source:${mutation.requestId}:$filename",
                    target = filename,
                    stage = if (mutation.state in setOf(
                            SourceMutationState.CONFLICT,
                            SourceMutationState.FAILED,
                        )
                    ) {
                        PhoneRenderStage.BLOCKED
                    } else {
                        PhoneRenderStage.SOURCE_SYNC_NEEDED
                    },
                    message = details.joinToString("\n").ifBlank { pendingMessage },
                    bannerEligible = false,
                )
            }
    }
    .toList()

internal fun sourceMutationStatusDetails(
    mutation: PendingSourceMutation,
    conflictTitle: (String) -> String,
    failureTitle: (String) -> String,
): List<String> {
    val title = when (mutation.state) {
        SourceMutationState.CONFLICT -> conflictTitle(mutation.sourceFile)
        SourceMutationState.FAILED -> failureTitle(mutation.sourceFile)
        else -> return emptyList()
    }
    val diagnostic = if (mutation.state == SourceMutationState.CONFLICT) {
        mutation.rawError.ifBlank { mutation.error }
    } else {
        mutation.error.ifBlank { mutation.rawError }
    }
    return listOf(title, diagnostic)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}

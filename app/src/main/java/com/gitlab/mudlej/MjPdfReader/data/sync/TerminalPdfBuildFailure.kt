// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

internal enum class TerminalPdfBuildStatus {
    ATTENTION,
    FAILED,
    CANCELLED;

    companion object {
        fun fromWireStatus(status: String): TerminalPdfBuildStatus? = when (status.lowercase()) {
            "attention", "needs_attention" -> ATTENTION
            "failed", "failure", "error", "compiler_failure" -> FAILED
            "cancelled", "canceled" -> CANCELLED
            else -> null
        }
    }
}

internal data class TerminalPdfBuildFailure(
    val buildId: String,
    val status: TerminalPdfBuildStatus,
    val detail: String,
    val logReference: String = "",
) {
    fun technicalMessage(): String {
        val identity = buildId.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
        val explanation = detail.ifBlank { "The server reported a terminal PDF build failure." }
        val log = logReference.takeIf { reference ->
            reference.isNotBlank() && reference !in explanation
        }?.let { "; log $it" }.orEmpty()
        return "Server PDF build$identity ${status.name.lowercase()}: $explanation$log"
    }
}

/**
 * A Fedora build already reached a terminal outcome. Retrying the sync cannot repair that build
 * by itself. Retain the build failure while independent transfers finish; a transfer failure
 * takes priority in the final status.
 */
internal class TerminalPdfBuildException(
    val failures: List<TerminalPdfBuildFailure>,
    val texSaved: Boolean = true,
) : Exception(failures.joinToString("\n", transform = TerminalPdfBuildFailure::technicalMessage)) {
    init {
        require(failures.isNotEmpty())
    }

    val primaryStatus: TerminalPdfBuildStatus
        get() = when {
            failures.any { it.status == TerminalPdfBuildStatus.FAILED } ->
                TerminalPdfBuildStatus.FAILED
            failures.any { it.status == TerminalPdfBuildStatus.CANCELLED } ->
                TerminalPdfBuildStatus.CANCELLED
            else -> TerminalPdfBuildStatus.ATTENTION
        }
}

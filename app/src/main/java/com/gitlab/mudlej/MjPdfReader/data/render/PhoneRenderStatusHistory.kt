// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.tbend.thoughts.sdk.LibraryEntry
import org.tbend.thoughts.sdk.RenderStateDocumentRecord
import org.tbend.thoughts.sdk.RenderStateRecord
import java.time.Instant

data class FedoraStatusSnapshot(
    val schemaVersion: Int = 2,
    val status: String,
    val buildId: String? = null,
    val checkedAtEpochMillis: Long,
    val lastSuccessfulBuildAtEpochMillis: Long = 0,
    val refreshed: Boolean = true,
    val refreshError: String = "",
)

data class ExplicitPdfRenderStatus(
    val filename: String,
    val status: String,
    val reason: String = "",
    val buildId: String = "",
    val publicationSha256: String? = null,
    val publicationSizeBytes: Long = -1,
    val publicationBuildId: String? = null,
    val checkedAtEpochMillis: Long,
    val lastSuccessfulBuildAtEpochMillis: Long = 0,
    val refreshed: Boolean = true,
    val refreshError: String = "",
) {
    val needsRebuild: Boolean
        get() = status in setOf("out_of_sync", "attention", "error")

    fun localPublicationState(localLibrary: SourceLinkedPdfIndexSnapshot): LocalPublicationState {
        val expectedSha256 = publicationSha256.orEmpty()
        if (status != "synced" || !expectedSha256.matches(SHA256)) {
            return LocalPublicationState.UNKNOWN
        }
        return if (
            localLibrary.activatedPdfSha256ByFilename[filename] == expectedSha256
        ) {
            LocalPublicationState.CURRENT
        } else {
            LocalPublicationState.SYNC_PENDING
        }
    }

    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

enum class LocalPublicationState {
    CURRENT,
    SYNC_PENDING,
    UNKNOWN,
}

class PhoneRenderStatusHistory(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun apply(
        snapshot: PhoneRenderStatusSnapshot,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): PhoneRenderStatusSnapshot {
        val previous = verifiedTimes()
        val (updatedSnapshot, updatedTimestamps) = applyVerifiedTimes(
            snapshot,
            previous,
            nowEpochMillis,
        )
        if (updatedTimestamps != previous) {
            writeVerifiedTimes(updatedTimestamps)
        }
        return updatedSnapshot
    }

    fun pdfRenderStatus(filename: String): ExplicitPdfRenderStatus? {
        val encoded = preferences.getString("$REMOTE_STATUS_PREFIX$filename", null) ?: return null
        return runCatching {
            gson.fromJson(encoded, ExplicitPdfRenderStatus::class.java)
        }.getOrNull()?.takeIf { it.filename == filename && it.status in REMOTE_STATUSES }
    }

    fun pdfRenderStatuses(): List<ExplicitPdfRenderStatus> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith(REMOTE_STATUS_PREFIX) }
        .mapNotNull { pdfRenderStatus(it.removePrefix(REMOTE_STATUS_PREFIX)) }
        .toList()

    fun fedoraStatusSnapshot(): FedoraStatusSnapshot? {
        val encoded = preferences.getString(FEDORA_STATUS_SNAPSHOT, null) ?: return null
        return runCatching {
            gson.fromJson(encoded, FedoraStatusSnapshot::class.java)
        }.getOrNull()?.takeIf {
            it.schemaVersion in 1..2 &&
                it.checkedAtEpochMillis >= 0 &&
                (it.status in GLOBAL_STATUSES || (!it.refreshed && it.status.isBlank()))
        }
    }

    fun replaceFedoraStatusSnapshot(state: RenderStateRecord): Boolean {
        val checkedAtEpochMillis = state.observedAt?.toEpochMillisOrNull() ?: return false
        val lastSuccessfulBuildAtEpochMillis = when (val encoded = state.lastSuccessfulBuildAt) {
            null -> 0
            else -> encoded.toEpochMillisOrNull() ?: return false
        }
        val documents = state.documents ?: return false
        val statuses = renderStateDocumentStatuses(documents, checkedAtEpochMillis) ?: return false
        if (state.status !in GLOBAL_STATUSES) return false
        val snapshot = FedoraStatusSnapshot(
            status = state.status,
            buildId = state.buildId,
            checkedAtEpochMillis = checkedAtEpochMillis,
            lastSuccessfulBuildAtEpochMillis = lastSuccessfulBuildAtEpochMillis,
        )
        preferences.edit().apply {
            preferences.all.keys
                .filter { it.startsWith(REMOTE_STATUS_PREFIX) }
                .forEach(::remove)
            statuses.forEach { status ->
                putString("$REMOTE_STATUS_PREFIX${status.filename}", gson.toJson(status))
            }
            putString(FEDORA_STATUS_SNAPSHOT, gson.toJson(snapshot))
        }.apply()
        return true
    }

    fun markFedoraStatusRefreshFailed(error: String) {
        val detail = error.take(1_000)
        val previous = fedoraStatusSnapshot()
        val snapshot = previous?.copy(refreshed = false, refreshError = detail)
            ?: FedoraStatusSnapshot(
                status = "",
                checkedAtEpochMillis = 0,
                refreshed = false,
                refreshError = detail,
            )
        val statuses = pdfRenderStatuses().map {
            it.copy(refreshed = false, refreshError = detail)
        }
        preferences.edit().apply {
            statuses.forEach { status ->
                putString("$REMOTE_STATUS_PREFIX${status.filename}", gson.toJson(status))
            }
            putString(FEDORA_STATUS_SNAPSHOT, gson.toJson(snapshot))
        }.apply()
    }

    fun savePdfRenderStatus(status: ExplicitPdfRenderStatus) {
        require(status.filename.isNotBlank() && status.status in REMOTE_STATUSES)
        preferences.edit()
            .putString("$REMOTE_STATUS_PREFIX${status.filename}", gson.toJson(status))
            .apply()
    }

    fun saveDocumentSyncStatus(
        filename: String,
        renderStatus: JsonObject?,
        publication: LibraryEntry,
        checkedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        if (renderStatus == null) {
            removePdfRenderStatuses(listOf(filename))
            return
        }
        val status = renderStatus.string("status")
        require(status in REMOTE_STATUSES && publication.filename == filename)
        val build = renderStatus.getAsJsonObject("build")
        val publishedAt = renderStatus.getAsJsonObject("publication")
            ?.string("published_at")
            .orEmpty()
            .ifBlank { publication.publishedAt.orEmpty() }
        savePdfRenderStatus(
            ExplicitPdfRenderStatus(
                filename = filename,
                status = status,
                reason = renderStatus.string("reason"),
                buildId = build?.string("id").orEmpty(),
                publicationSha256 = publication.sha256,
                publicationSizeBytes = publication.sizeBytes,
                publicationBuildId = publication.buildId,
                checkedAtEpochMillis = checkedAtEpochMillis,
                lastSuccessfulBuildAtEpochMillis = publishedAt.toEpochMillisOrNull() ?: 0,
            ),
        )
    }

    fun replacePdfRenderStatuses(statuses: List<ExplicitPdfRenderStatus>) {
        require(statuses.map(ExplicitPdfRenderStatus::filename).distinct().size == statuses.size)
        require(statuses.all { it.filename.isNotBlank() && it.status in REMOTE_STATUSES })
        preferences.edit().apply {
            preferences.all.keys
                .filter { it.startsWith(REMOTE_STATUS_PREFIX) }
                .forEach(::remove)
            statuses.forEach { status ->
                putString("$REMOTE_STATUS_PREFIX${status.filename}", gson.toJson(status))
            }
        }.apply()
    }

    fun removePdfRenderStatuses(filenames: Collection<String>) {
        if (filenames.isEmpty()) return
        preferences.edit().apply {
            filenames.filter(String::isNotBlank).forEach { filename ->
                remove("$REMOTE_STATUS_PREFIX$filename")
            }
        }.apply()
    }

    fun markPdfRenderRefreshFailed(
        filename: String,
        error: String,
    ): ExplicitPdfRenderStatus? {
        val previous = pdfRenderStatus(filename) ?: return null
        return previous.copy(
            refreshed = false,
            refreshError = error.take(1_000),
        ).also(::savePdfRenderStatus)
    }

    private fun verifiedTimes(): Map<String, Long> {
        return preferences.all.mapNotNull { (key, value) ->
            val timestamp = value as? Long
            if (key.startsWith(VERIFIED_PREFIX) && timestamp != null) {
                key.removePrefix(VERIFIED_PREFIX) to timestamp
            } else {
                null
            }
        }.toMap()
    }

    private fun writeVerifiedTimes(verifiedTimes: Map<String, Long>) {
        preferences.edit().apply {
            preferences.all.keys
                .filter { it.startsWith(VERIFIED_PREFIX) }
                .forEach(::remove)
            verifiedTimes.forEach { (requestId, timestamp) ->
                putLong("$VERIFIED_PREFIX$requestId", timestamp)
            }
        }.apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "phone_render_status"
        private const val VERIFIED_PREFIX = "verified:"
        private const val REMOTE_STATUS_PREFIX = "remote-status:"
        private const val FEDORA_STATUS_SNAPSHOT = "fedora-status-snapshot-v1"
        private val REMOTE_STATUSES = setOf("synced", "out_of_sync", "building", "attention", "error")
        private val GLOBAL_STATUSES = setOf("current", "changed", "building", "attention")

        private fun JsonObject.string(name: String): String = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            .orEmpty()
    }
}

internal fun mergeExplicitPdfRenderStatuses(
    snapshot: PhoneRenderStatusSnapshot,
    statuses: List<ExplicitPdfRenderStatus>,
    localLibrary: SourceLinkedPdfIndexSnapshot = SourceLinkedPdfIndexSnapshot(),
): PhoneRenderStatusSnapshot {
    val replaced = statuses.mapTo(hashSetOf(), ExplicitPdfRenderStatus::filename)
    val retained = snapshot.jobs.filterNot {
        it.requestId.startsWith("remote-status:") && it.target in replaced
    }
    val explicit = statuses.map { status ->
        val localState = status.localPublicationState(localLibrary)
        PhoneRenderJobStatus(
            requestId = "remote-status:${status.filename}",
            target = status.filename,
            stage = when (status.status) {
                "synced" -> when (localState) {
                    LocalPublicationState.CURRENT -> PhoneRenderStage.UPDATED
                    LocalPublicationState.SYNC_PENDING -> PhoneRenderStage.SYNC_PENDING
                    LocalPublicationState.UNKNOWN -> PhoneRenderStage.UNVERIFIED
                }
                "out_of_sync" -> PhoneRenderStage.OUT_OF_SYNC
                "building" -> PhoneRenderStage.REBUILDING
                "attention" -> PhoneRenderStage.ATTENTION
                else -> PhoneRenderStage.FAILED
            },
            message = buildString {
                append(
                    when {
                        status.status == "synced" && localState == LocalPublicationState.SYNC_PENDING ->
                            "Ready on the server; sync this device to activate the verified PDF."
                        status.status == "synced" && localState == LocalPublicationState.UNKNOWN ->
                            "The server reports this PDF as current, but its publication identity is unavailable."
                        else -> status.reason
                    }
                )
                if (!status.refreshed && status.refreshError.isNotBlank()) {
                    if (isNotEmpty()) append(' ')
                    append("Not refreshed: ${status.refreshError}")
                }
            },
            completedAtEpochMillis = status.checkedAtEpochMillis,
            lastSuccessfulBuildAtEpochMillis = status.lastSuccessfulBuildAtEpochMillis,
            bannerEligible = false,
            buildId = status.buildId,
        )
    }
    return PhoneRenderStatusSnapshot(retained + explicit)
}

internal fun renderStateDocumentStatuses(
    documents: List<RenderStateDocumentRecord>,
    checkedAtEpochMillis: Long,
): List<ExplicitPdfRenderStatus>? {
    if (checkedAtEpochMillis <= 0 || documents.map { it.filename }.distinct().size != documents.size) {
        return null
    }
    val supported = setOf("synced", "out_of_sync", "building", "attention", "error")
    if (documents.any { document ->
            !isCanonicalPublishedPdfFilename(document.filename) ||
                document.status !in supported ||
                document.publication?.filename?.let { it != document.filename } == true
        }
    ) {
        return null
    }
    return documents.map { document ->
        val lastSuccessfulBuildAtEpochMillis = when (
            val encoded = document.lastSuccessfulBuildAt ?: document.publication?.publishedAt
        ) {
            null -> 0
            else -> encoded.toEpochMillisOrNull() ?: return null
        }
        ExplicitPdfRenderStatus(
            filename = document.filename,
            status = document.status,
            reason = document.reason,
            buildId = document.buildId.orEmpty(),
            publicationSha256 = document.publication?.sha256,
            publicationSizeBytes = document.publication?.sizeBytes ?: -1,
            publicationBuildId = document.publication?.buildId,
            checkedAtEpochMillis = checkedAtEpochMillis,
            lastSuccessfulBuildAtEpochMillis = lastSuccessfulBuildAtEpochMillis,
        )
    }
}

private fun String.toEpochMillisOrNull(): Long? = runCatching {
    Instant.parse(this).toEpochMilli()
}.getOrNull()?.takeIf { it > 0 }

internal fun applyVerifiedTimes(
    snapshot: PhoneRenderStatusSnapshot,
    previous: Map<String, Long>,
    nowEpochMillis: Long,
): Pair<PhoneRenderStatusSnapshot, Map<String, Long>> {
    val retained = mutableMapOf<String, Long>()
    val jobs = snapshot.jobs.map { status ->
        if (status.stage != PhoneRenderStage.UPDATED &&
            status.stage != PhoneRenderStage.ATTENTION &&
            status.stage != PhoneRenderStage.PARTIAL
        ) {
            return@map status.copy(verifiedAtEpochMillis = 0)
        }
        val verifiedAt = previous[status.requestId]
            ?.takeIf { it > 0 }
            ?: nowEpochMillis
        retained[status.requestId] = verifiedAt
        status.copy(verifiedAtEpochMillis = verifiedAt)
    }
    return PhoneRenderStatusSnapshot(jobs) to retained
}

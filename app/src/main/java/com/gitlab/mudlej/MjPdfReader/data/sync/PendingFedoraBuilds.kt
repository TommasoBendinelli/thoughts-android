// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.gitlab.mudlej.MjPdfReader.data.render.isCanonicalPublishedPdfFilename
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A Fedora build that was still queued or running when a Sync ended, and the PDFs it makes
 * ([documents]; empty when Fedora did not name them).
 */
internal data class PendingFedoraBuild(val buildId: String, val documents: List<String> = emptyList())

private val ACTIVE_BUILD_STATUSES = setOf("queued", "running")

/**
 * The builds still pending after a Sync observed [observed]. Sync no longer waits for Fedora's
 * builds (13 November 2026: one Sync spent 44 of its 63 s waiting); it ends and leaves these to
 * [RemoteWorkWatch]. A whole-Library Sync asked Fedora about every document, so what it saw
 * replaces [previous]; a one-document Sync only updates the builds it saw.
 */
internal fun pendingFedoraBuildsAfter(
    previous: List<PendingFedoraBuild>,
    observed: List<SyncBuildProgress>,
    wholeLibrary: Boolean,
): List<PendingFedoraBuild> {
    val active = observed.filter { it.serverStatus in ACTIVE_BUILD_STATUSES && it.buildId.isNotBlank() }
        .groupBy(SyncBuildProgress::buildId)
        .map { (id, progress) ->
            PendingFedoraBuild(
                id,
                progress.flatMap { it.documents + listOf(it.target) }
                    .filter(::isCanonicalPublishedPdfFilename)
                    .distinct()
                    .sorted(),
            )
        }
    if (wholeLibrary) return active
    val seen = observed.mapTo(hashSetOf(), SyncBuildProgress::buildId)
    return previous.filter { it.buildId !in seen } + active
}

/** How many PDFs [builds] still make: the named ones once each, a build that names none as one. */
internal fun pendingFedoraPdfCount(builds: Collection<PendingFedoraBuild>): Int =
    builds.flatMapTo(hashSetOf()) { it.documents }.size + builds.count { it.documents.isEmpty() }

/** "1 PDF still building on the server", "3 PDFs still building on the server". */
internal fun pdfsStillBuildingMessage(count: Int): String =
    if (count == 1) "1 PDF still building on the server" else "$count PDFs still building on the server"

/**
 * The Fedora builds a Sync left running, so that [RemoteWorkWatch] fetches their PDFs once they
 * finish, and the Library can say how many PDFs are still building. Survives the process: a build
 * that finishes while Thoughts is closed is fetched by the watch's first check when it opens.
 */
internal class PendingFedoraBuildStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
    )

    fun builds(): List<PendingFedoraBuild> = synchronized(LOCK) { read() }

    fun pdfCount(): Int = pendingFedoraPdfCount(builds())

    /** Records what a Sync saw of Fedora's builds ([pendingFedoraBuildsAfter]). */
    fun recordObserved(observed: List<SyncBuildProgress>, wholeLibrary: Boolean) = synchronized(LOCK) {
        write(pendingFedoraBuildsAfter(read(), observed, wholeLibrary))
    }

    /** Forgets [buildIds]: they finished, and the Sync that fetches their PDFs has been started. */
    fun remove(buildIds: Collection<String>) = synchronized(LOCK) {
        write(read().filter { it.buildId !in buildIds })
    }

    private fun read(): List<PendingFedoraBuild> = runCatching {
        val encoded = preferences.getString(KEY_BUILDS, null) ?: return emptyList()
        JsonParser.parseString(encoded).asJsonArray.mapNotNull { element ->
            val build = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = build.get("build_id")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return@mapNotNull null
            val documents = build.get("documents")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { value -> value.takeIf { it.isJsonPrimitive }?.asString?.takeIf(::isCanonicalPublishedPdfFilename) }
                .orEmpty()
            PendingFedoraBuild(id, documents)
        }
    }.getOrDefault(emptyList())

    private fun write(builds: List<PendingFedoraBuild>) {
        if (builds == read()) return
        val encoded = JsonArray().apply {
            builds.take(MAX_BUILDS).forEach { build ->
                add(JsonObject().apply {
                    addProperty("build_id", build.buildId)
                    add("documents", JsonArray().apply { build.documents.take(MAX_DOCUMENTS).forEach(::add) })
                })
            }
        }
        preferences.edit().apply {
            if (builds.isEmpty()) remove(KEY_BUILDS) else putString(KEY_BUILDS, encoded.toString())
        }.commit()
    }

    companion object {
        private const val PREFERENCES = "thoughts_pending_fedora_builds"
        private const val KEY_BUILDS = "builds"
        private const val MAX_BUILDS = 16
        private const val MAX_DOCUMENTS = 512
        private val LOCK = Any()
    }
}

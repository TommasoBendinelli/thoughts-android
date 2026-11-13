// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import java.io.*
import java.util.UUID

/** Lossless private cache of a validated project projection, never an input to sync/build. */
internal object SourceProjectionDiskCache {
    private fun cache(file: File) = File(file.parentFile, "overview-objects-v1.bin")
    private val allowed = setOf(
        "java.lang.Number", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Boolean",
        "java.util.ArrayList", "java.util.LinkedHashMap", "java.util.HashMap", "java.util.LinkedHashSet", "java.util.HashSet",
        "java.util.Arrays\$ArrayList", "kotlin.collections.EmptyList", "kotlin.collections.EmptySet", "kotlin.collections.EmptyMap",
        "com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot",
        "com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal",
        "com.gitlab.mudlej.MjPdfReader.data.source.OfflineProposalQuad",
        "com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceReviewComment",
        "com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment",
        "com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation",
        "com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence",
        "com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewSourceTarget",
        "com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewPlacementProof",
    )
    private fun allowed(name: String): Boolean = name in allowed || name == "java.lang.String" || name == "java.lang.Object" ||
        name.startsWith("java.util.Collections\$") ||
        (name.startsWith("[L") && name.endsWith(';') && allowed(name.substring(2, name.length - 1)))

    fun read(file: File, bundleId: String): OfflineOverviewSourceSnapshot? = runCatching {
        val stored = cache(file)
        if (!file.isFile || stored.length() !in 1..(64L * 1024 * 1024)) return@runCatching null
        object : ObjectInputStream(stored.inputStream().buffered()) {
            override fun resolveClass(desc: ObjectStreamClass): Class<*> {
                require(allowed(desc.name)) { "Unexpected projection cache type" }
                return super.resolveClass(desc)
            }
            override fun resolveProxyClass(interfaces: Array<out String>): Class<*> = error("No projection proxies")
        }.use { input ->
            require(input.readUTF() == bundleId && input.readLong() == file.length() && input.readLong() == file.lastModified())
            (input.readObject() as? OfflineOverviewSourceSnapshot)?.takeIf { it.bundleId == bundleId }
        }
    }.getOrNull()

    fun write(file: File, snapshot: OfflineOverviewSourceSnapshot) {
        val id = snapshot.bundleId ?: return
        if (!file.isFile) return
        val destination = cache(file)
        val temporary = File(file.parentFile, ".projection-${UUID.randomUUID()}.tmp")
        runCatching {
            ObjectOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeUTF(id); output.writeLong(file.length()); output.writeLong(file.lastModified())
                output.writeObject(snapshot)
            }
            check(temporary.renameTo(destination))
        }
        temporary.delete()
    }
}

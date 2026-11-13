// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Permanent tag merges ("Compact tags"). Recorded answers keep the tags they were given; every
 * reader shows a merged tag under its new name. Merges sync as `review_tag_merge` entities, so
 * the phone, the server and the desktop all agree, and they cannot be undone.
 */
object ReviewTagMerges {
    const val ENTITY_TYPE = "review_tag_merge"
    const val COLLECTION = "review-tags"

    data class Merge(val id: String, val from: List<String>, val to: String, val createdAt: String, val originId: String) {
        fun payload(): JsonObject = JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("merge_id", id)
            add("from", JsonArray().apply { from.forEach(::add) })
            addProperty("to", to)
            addProperty("created_at", createdAt)
            addProperty("origin_id", originId)
        }
    }

    /** Overridable for tests; the app keeps merges beside its other review records. */
    @Volatile var file: () -> File = { File(ThoughtsLocalStore.reviewIncoming().parentFile, "tag-merges.json") }
    @Volatile private var cached: Triple<File, Long, Pair<List<Merge>, Map<String, String>>>? = null

    fun merges(): List<Merge> = load().first

    /** Old tag → the name it is now shown under; values are never keys themselves. */
    fun mapping(): Map<String, String> = load().second

    fun resolve(tag: String): String = mapping()[tag] ?: tag

    fun apply(tags: List<String>): List<String> {
        val mapping = mapping()
        return if (mapping.isEmpty()) tags else tags.map { mapping[it] ?: it }.distinct()
    }

    /** Records that [from] are now [to]; [to] itself is dropped from [from]. */
    @Synchronized
    fun record(from: Collection<String>, to: String, originId: String, now: Instant = Instant.now()): Merge {
        val sources = from.map { it.trim() }.filter { it.isNotEmpty() && it != to }.distinct()
        require(to.isNotBlank() && sources.isNotEmpty()) { "Choose at least one other tag to merge." }
        val merge = Merge(UUID.randomUUID().toString(), sources, to, now.toString(), originId)
        write(merges() + merge)
        return merge
    }

    /** Adds merges made elsewhere, as delivered by Sync; known ones are kept as they are. */
    @Synchronized
    fun installServer(payloads: List<JsonObject>) {
        val known = merges()
        val incoming = payloads.mapNotNull(::parse).distinctBy { it.id }.filter { merge -> known.none { it.id == merge.id } }
        if (incoming.isNotEmpty()) write(known + incoming)
    }

    internal fun mappingOf(merges: List<Merge>): Map<String, String> {
        val mapping = linkedMapOf<String, String>()
        for (merge in merges.sortedWith(compareBy<Merge> { it.createdAt }.thenBy { it.id })) {
            val target = mapping[merge.to] ?: merge.to
            for (source in merge.from) {
                if (source == target) continue
                mapping[source] = target
                // Keep every value final, so a later merge of a merge target carries its sources along.
                mapping.entries.filter { it.value == source }.forEach { it.setValue(target) }
            }
        }
        return mapping
    }

    private fun parse(payload: JsonObject): Merge? = runCatching {
        Merge(
            id = payload.get("merge_id").asString.also { require(it.isNotBlank()) },
            from = payload.getAsJsonArray("from").map { it.asString.trim() }.filter(String::isNotEmpty),
            to = payload.get("to").asString.trim().also { require(it.isNotEmpty()) },
            createdAt = payload.get("created_at")?.asString.orEmpty(),
            originId = payload.get("origin_id")?.asString.orEmpty(),
        ).takeIf { it.from.isNotEmpty() }
    }.getOrNull()

    private fun load(): Pair<List<Merge>, Map<String, String>> {
        val target = runCatching(file).getOrNull() ?: return emptyList<Merge>() to emptyMap()
        val stamp = if (target.isFile) target.lastModified() xor target.length() else -1L
        cached?.takeIf { it.first == target && it.second == stamp }?.let { return it.third }
        val merges = runCatching {
            JsonParser.parseString(target.readText()).asJsonObject.getAsJsonArray("merges")
                .mapNotNull { parse(it.asJsonObject) }
        }.getOrDefault(emptyList())
        val value = merges to mappingOf(merges)
        cached = Triple(target, stamp, value)
        return value
    }

    private fun write(merges: List<Merge>) {
        val target = file()
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(JsonObject().apply {
            addProperty("schema_version", 1)
            add("merges", JsonArray().apply { merges.forEach { add(it.payload()) } })
        }.toString())
        check(temporary.renameTo(target)) { "Could not save the tag merge." }
        cached = null
    }
}

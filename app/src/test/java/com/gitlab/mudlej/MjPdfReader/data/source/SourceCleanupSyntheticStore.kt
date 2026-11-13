// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * A Library-sized retained PDF generation: project readings name document packages,
 * packages name review plans, sources and SyncTeX, plans name renderer evidence and
 * evidence names fonts. Orphans are referenced by nothing and must be archived.
 */
internal class SourceCleanupSyntheticStore(
    root: File,
    private val privateDirectory: File,
    documents: Int,
    planBytes: Int,
    documentsPerProject: Int = 20,
) {
    val reader = File(root, "thoughts/current-pdfs/.thoughts-project-sources.json")
    val generationParent: File = reader.parentFile.parentFile
    private val previous = File(generationParent, ".thoughts-library-previous")
    private val objects = File(privateDirectory, "objects")
    private val cache = File(privateDirectory, "object-cache")
    val graphObjects = linkedSetOf<String>()
    val orphans = linkedSetOf<String>()
    var jsonBytes = 0L
        private set

    init {
        reader.parentFile.mkdirs()
        File(previous, "objects").mkdirs()
        objects.mkdirs()
        cache.mkdirs()
        val filler = "Passage text with enough words to look like a real source passage. ".repeat(8)
        val packages = (0 until documents).map { index ->
            val sources = (0 until 3).map { k ->
                put(if (k == 2) cache else objects, "\\section{Document $index part $k}\n$filler\n".toByteArray())
            }
            val synctex = put(objects, ByteArray(2048) { (it * 31 + index).toByte() }.also { it[0] = 0x1f })
            val font = put(cache, ByteArray(4096) { (it * 7 + index).toByte() }.also { it[0] = 0 })
            val evidence = put(objects, json(JsonObject().apply {
                addProperty("kind", "renderer_evidence")
                add("fonts", JsonArray().apply { add(font) })
            }))
            val plan = put(objects, json(JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("kind", "basic_source_passages")
                addProperty("evidence_object", evidence)
                add("passages", JsonArray().apply {
                    var size = 0
                    var passage = 0
                    while (size < planBytes) {
                        val text = "$filler $index/$passage"
                        add(JsonObject().apply {
                            addProperty("source_sha256", sources[passage % sources.size])
                            addProperty("passage_id", sha256("passage-$index-$passage".toByteArray()))
                            addProperty("text", text)
                            add("rect", JsonArray().apply { listOf(0.1, 0.2, 0.3, 0.4).forEach(::add) })
                        })
                        size += text.length + 220
                        passage++
                    }
                })
            }))
            val pack = put(File(previous, "objects"), json(JsonObject().apply {
                addProperty("kind", "thoughts-document-component-package")
                addProperty("pdf_sha256", sha256("pdf-$index".toByteArray()))
                addProperty("page_count", 3)
                addProperty("basic_review_object", plan)
                addProperty("synctex_object", synctex)
                add("sources", JsonArray().apply { sources.forEach { add(JsonObject().apply { addProperty("object", it) }) } })
            }))
            graphObjects += sources + listOf(synctex, font, evidence, plan)
            orphans += put(objects, json(JsonObject().apply {
                addProperty("kind", "basic_source_passages")
                addProperty("superseded", index)
                addProperty("source_sha256", sha256("missing-$index".toByteArray()))
            }))
            orphans += put(objects, "retired source $index\n$filler".toByteArray())
            pack
        }
        packages.chunked(documentsPerProject).forEachIndexed { project, chunk ->
            File(previous, "project_$project.thoughts-project").writeText(JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("kind", "thoughts-project-reading")
                addProperty("project_id", "project-$project")
                add("documents", JsonArray().apply {
                    chunk.forEachIndexed { index, pack -> add(JsonObject().apply {
                        addProperty("document_id", "document-$project-$index")
                        addProperty("package_sha256", pack)
                    }) }
                })
            }.toString())
        }
    }

    fun put(directory: File, bytes: ByteArray): String {
        if (bytes.firstOrNull() == '{'.code.toByte()) jsonBytes += bytes.size
        return sha256(bytes).also { File(directory, it).writeBytes(bytes) }
    }

    /** The cleanup's retained-generation closure exactly as 874f0e13 computed it. */
    fun legacyClosure(seeds: Set<String>): Set<String> {
        val result = seeds.toMutableSet()
        val pending = ArrayDeque<String>()
        fun collect(value: JsonElement) {
            when {
                value.isJsonObject -> value.asJsonObject.entrySet().forEach { collect(it.value) }
                value.isJsonArray -> value.asJsonArray.forEach(::collect)
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                    val hash = value.asString
                    if (hash.matches(Regex("[0-9a-f]{64}")) && result.add(hash)) pending.add(hash)
                }
            }
        }
        generationParent.listFiles().orEmpty().filter { it.isDirectory &&
            (it.name.startsWith(".thoughts-library-stage-") || it.name == "current_pdfs" || it.name == ".thoughts-library-previous")
        }.forEach { directory ->
            directory.listFiles().orEmpty().filter { it.name.endsWith(".thoughts-project") }.forEach { manifest ->
                val reading = JsonParser.parseString(manifest.readText()).asJsonObject
                collect(reading)
                reading.getAsJsonArray("documents").forEach { entry ->
                    val hash = entry.asJsonObject.get("package_sha256").asString
                    collect(JsonParser.parseString(File(directory, "objects/$hash").readText()))
                }
            }
        }
        while (pending.isNotEmpty()) {
            val hash = pending.removeFirst()
            val file = listOf(File(objects, hash), File(cache, hash)).firstOrNull { it.isFile } ?: continue
            val first = file.inputStream().use { it.read() }
            if (first != '{'.code && first != '['.code) continue
            collect(JsonParser.parseString(file.readText()))
        }
        return result
    }

    companion object {
        fun json(value: JsonElement): ByteArray = value.toString().toByteArray(StandardCharsets.UTF_8)
        fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it) }
    }
}

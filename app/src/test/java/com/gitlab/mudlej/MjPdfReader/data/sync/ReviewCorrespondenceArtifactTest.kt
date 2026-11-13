// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test

class ReviewCorrespondenceArtifactTest {
    @Test
    fun compilerSegmentsSurviveInitialMergeAndRepeatedDownloads() {
        val artifact = javaClass.getResourceAsStream("/compiler-comments/artifact.json")!!
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        assertPreservedLocations(listOf(artifact))
    }

    @Test
    fun publishedLibrarySegmentsSurviveSyncMerge() {
        val directory = System.getenv("THOUGHTS_TEST_ARTIFACT_DIRECTORY")
        assumeTrue("Optional audit of downloaded publication artifacts", directory != null)
        val artifacts = File(directory!!).listFiles()!!
            .filter { it.name.endsWith(".source.json") }.sortedBy { it.name }
            .map { file -> file.reader().use { JsonParser.parseReader(it).asJsonObject } }
        check(artifacts.isNotEmpty())
        assertPreservedLocations(artifacts)
    }

    @Test
    fun rejectsDifferentGeometryForTheSameCompilerSegment() {
        val artifact = javaClass.getResourceAsStream("/compiler-comments/artifact.json")!!
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val record = artifact.getAsJsonArray("review_correspondences")
            .map { it.asJsonObject }.first { it.getAsJsonArray("locations").size() > 1 }
        val incoming = record.deepCopy()
        incoming.getAsJsonArray("locations")[0].asJsonObject.addProperty("page", 999)
        assertThrows(IllegalStateException::class.java) {
            mergeReviewCorrespondenceArtifacts(record, incoming)
        }
    }

    @Test
    fun mergedOutputOwnsItsMutableGeometryAndCanonicalFields() {
        val artifact = javaClass.getResourceAsStream("/compiler-comments/artifact.json")!!
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        for ((field, merge) in listOf(
            "review_correspondences" to ::mergeReviewCorrespondenceArtifacts,
            "logical_comments" to ::mergeLogicalCommentArtifacts,
        )) {
            val input = artifact.getAsJsonArray(field).map { it.asJsonObject }
                .first { it.getAsJsonArray("locations").size() > 0 }.deepCopy()
            // Also exercise nested canonical fields independent of the fixture's schema.
            input.add("extension", JsonObject().apply { addProperty("value", "original") })
            val original = input.deepCopy()
            val first = merge(null, input)
            val firstBefore = first.deepCopy()
            val repeated = merge(first, input)
            assertEquals(original, input)
            assertEquals(firstBefore, first)
            repeated.getAsJsonArray("locations")[0].asJsonObject.apply {
                addProperty("page", 999)
                getAsJsonObject("proof")?.addProperty("verified", false)
            }
            repeated.getAsJsonObject("extension").addProperty("value", "modified")
            assertEquals(original, input)
            assertEquals(firstBefore, first)
            input.getAsJsonObject("extension").addProperty("value", "input changed")
            assertEquals("modified", repeated.getAsJsonObject("extension").get("value").asString)
        }
    }

    @Test
    fun avoidingGeometryCopiesPreservesSerializedFieldOrder() {
        val input = correspondence("Concepts.pdf", "a".repeat(64), 6)
        input.addProperty("after_locations", "extension")
        assertEquals(input.toString(), mergeReviewCorrespondenceArtifacts(null, input).toString())
        assertEquals(input.toString(), mergeReviewCorrespondenceArtifacts(input, input).toString())
    }

    @Test
    fun canonicalDisagreementsStillFailAndStateDisagreementsRemainUnresolved() {
        val previous = correspondence("Concepts.pdf", "a".repeat(64), 6)
        val incoming = correspondence("SDPO.pdf", "b".repeat(64), 803)
        incoming.addProperty("state", "needs_attention")
        assertEquals("needs_attention", mergeReviewCorrespondenceArtifacts(previous, incoming).get("state").asString)
        incoming.getAsJsonArray("source_targets")[0].asJsonObject.addProperty("byte_end", 30)
        assertThrows(IllegalStateException::class.java) {
            mergeReviewCorrespondenceArtifacts(previous, incoming)
        }
    }

    private fun assertPreservedLocations(artifacts: List<JsonObject>) {
        assertPreservedLocations(artifacts, "review_correspondences", "review_key", ::mergeReviewCorrespondenceArtifacts)
        assertPreservedLocations(artifacts, "logical_comments", "comment_id", ::mergeLogicalCommentArtifacts)
    }

    private fun assertPreservedLocations(
        artifacts: List<JsonObject>,
        field: String,
        identity: String,
        merge: (JsonObject?, JsonObject) -> JsonObject,
    ) {
        val merged = linkedMapOf<String, JsonObject>()
        val expected = linkedMapOf<String, MutableSet<com.google.gson.JsonElement>>()
        artifacts.forEach { artifact ->
            artifact.getAsJsonArray(field).forEach { element ->
                val record = element.asJsonObject
                val key = record.get(identity).asString
                expected.getOrPut(key) { linkedSetOf() }.addAll(record.getAsJsonArray("locations"))
                merged[key] = merge(merged[key], record)
                merged[key] = merge(merged[key], record)
            }
        }
        assertEquals(expected.keys, merged.keys)
        expected.forEach { (key, locations) ->
            assertEquals(key, locations, merged.getValue(key).getAsJsonArray("locations").toSet())
            assertEquals(key, locations.size, merged.getValue(key).getAsJsonArray("locations").size())
        }
    }

    @Test
    fun mergesDistinctRevisionBoundLocationsForOneIdentity() {
        val concepts = correspondence("Concepts.pdf", "a".repeat(64), 6)
        val sdpo = correspondence("SDPO.pdf", "b".repeat(64), 803)

        val merged = mergeReviewCorrespondenceArtifacts(concepts, sdpo)
        val locations = merged.getAsJsonArray("locations")

        assertEquals(2, locations.size())
        assertEquals(
            listOf("Concepts.pdf" to 6, "SDPO.pdf" to 803),
            locations.map { location ->
                location.asJsonObject.get("pdf_filename").asString to
                    location.asJsonObject.get("page").asInt
            },
        )
    }

    private fun correspondence(filename: String, pdfSha256: String, page: Int): JsonObject {
        val proof = JsonObject().apply {
            addProperty("method", "named_anchor_text_alignment")
            addProperty("resolver_version", 2)
            addProperty("source_sha256", "c".repeat(64))
            addProperty("source_bundle_sha256", "d".repeat(64))
            addProperty("build_sha256", "f".repeat(64))
            addProperty("pdf_sha256", pdfSha256)
            addProperty("extracted_text_sha256", "e".repeat(64))
            addProperty("candidate_count", 1)
            addProperty("uniqueness", "unique")
            addProperty("verified", true)
            addProperty("reason", "unique")
        }
        return JsonObject().apply {
            addProperty("schema_version", 2)
            addProperty("review_key", "comment:shared-comment")
            addProperty("kind", "comment")
            addProperty("item_id", "shared-comment")
            addProperty("thread_root_id", "shared-comment")
            addProperty("lifecycle", "active")
            addProperty("state", "exact_text")
            addProperty("reason", "verified")
            addProperty("source_bundle_sha256", "d".repeat(64))
            add("source_targets", JsonArray().apply { add(sourceTarget()) })
            add("legacy_aliases", JsonArray())
            add("locations", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("pdf_filename", filename)
                    addProperty("pdf_sha256", pdfSha256)
                    addProperty("source_bundle_sha256", "d".repeat(64))
                    addProperty("build_sha256", "f".repeat(64))
                    addProperty("page_index", page - 1)
                    addProperty("page", page)
                    addProperty("role", "target")
                    addProperty("placement_kind", "exact_text")
                    add("regions", JsonArray().apply {
                        add(JsonArray().apply { listOf(0.1, 0.2, 0.7, 0.22).forEach(::add) })
                    })
                    add("point", JsonArray())
                    add("proof", proof)
                })
            })
        }
    }

    private fun sourceTarget() = JsonObject().apply {
        addProperty("role", "target")
        addProperty("source_file", "docs-test/tex/source/main.tex")
        addProperty("source_sha256", "c".repeat(64))
        addProperty("byte_start", 10)
        addProperty("byte_end", 20)
        addProperty("line_start", 2)
        addProperty("line_end", 2)
        addProperty("column_start", 1)
        addProperty("column_end", 11)
        addProperty("text_sha256", "f".repeat(64))
        addProperty("render_intent", "text")
        addProperty("anchor_id", "shared-comment")
    }
}

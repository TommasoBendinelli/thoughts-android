// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val BASIC_REVIEW_FINGERPRINT_METHOD = "android-pdf-renderer-rgb-96-v1"

data class BasicReviewResult(val added: Int, val skipped: Int, val errors: List<String> = emptyList())

/** No source mutation, network request, or model is involved in basic generation. */
internal suspend fun generateBasicReviewDocument(file: File, expectedSha256: String, store: ReviewStore, plan: com.google.gson.JsonObject? = null): BasicReviewResult {
    require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "PDF identity is unavailable. Sync the Library first." }
    require(file.isFile && basicPdfSha256(file) == expectedSha256) { "PDF is missing or changed. Sync the Library first." }
    val fingerprints = mutableListOf<String>()
    val candidates = plan?.getAsJsonArray("cards")?.deepCopy() ?: com.google.gson.JsonArray()
    val hasPlan = candidates.size() > 0
    require(hasPlan || com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex().capabilityFor(file) ==
        com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability.NOT_SOURCE_LINKED) {
        "The verified source passage plan is unavailable. Sync before generating source-backed cards."
    }
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { pdf ->
            require(pdf.pageCount > 0) { "The PDF has no pages." }
            repeat(pdf.pageCount) { index ->
                currentCoroutineContext().ensureActive()
                pdf.openPage(index).use { page ->
                    val width = (page.width * 96.0 / 72.0).toInt().coerceAtLeast(1)
                    val height = (page.height * 96.0 / 72.0).toInt().coerceAtLeast(1)
                    require(width.toLong() * height <= 32_000_000) { "Page ${index + 1} is too large to render." }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val digest = MessageDigest.getInstance("SHA-256")
                        digest.update(BASIC_REVIEW_FINGERPRINT_METHOD.toByteArray())
                        digest.update(ByteBuffer.allocate(8).putInt(width).putInt(height).array())
                        val emptyRows = BooleanArray(height)
                        val pixels = IntArray(width)
                        val rgb = ByteArray(width * 3)
                        repeat(height) { y ->
                            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                            pixels.forEachIndexed { x, pixel ->
                                rgb[x * 3] = (pixel shr 16).toByte()
                                rgb[x * 3 + 1] = (pixel shr 8).toByte()
                                rgb[x * 3 + 2] = pixel.toByte()
                            }
                            emptyRows[y] = pixels.all { p -> ((p shr 16) and 255) > 245 && ((p shr 8) and 255) > 245 && (p and 255) > 245 }
                            digest.update(rgb)
                        }
                        fingerprints += digest.digest().joinToString("") { "%02x".format(it) }
                        if (!hasPlan) {
                            val cuts = basicWhitespaceCuts(emptyRows)
                            cuts.zipWithNext().forEach { (top, bottom) ->
                                val regions = com.google.gson.JsonArray().apply {
                                  val count = kotlin.math.ceil((bottom-top)/.5).toInt().coerceAtLeast(1)
                                  repeat(count) { part -> add(com.google.gson.JsonObject().apply {
                                    addProperty("page", index + 1)
                                    add("rect", com.google.gson.JsonArray().apply { add(0f); add(top+(bottom-top)*part/count); add(1f); add(top+(bottom-top)*(part+1)/count) })
                                }) } }
                                candidates.add(com.google.gson.JsonObject().apply {
                                    addProperty("key", MessageDigest.getInstance("SHA-256")
                                        .digest("$expectedSha256:${index + 1}:$top:$bottom".toByteArray()).joinToString("") { "%02x".format(it) })
                                    add("regions", regions)
                                })
                            }
                        }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
    currentCoroutineContext().ensureActive()
    require(basicPdfSha256(file) == expectedSha256) { "The PDF changed during generation. Try again." }
    return store.createBasicCards(file.nameWithoutExtension, expectedSha256, fingerprints, candidates = limitBasicCardExtent(candidates, expectedSha256)).getOrThrow()
}

internal fun basicPdfSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Prefer paragraph-sized whitespace; without a safe gap preserve the whole page. */
internal fun basicWhitespaceCuts(emptyRows: BooleanArray): List<Float> {
    val height = emptyRows.size
    if (height == 0) return listOf(0f, 1f)
    val gaps = mutableListOf<Float>()
    var start = -1
    for (y in 0..height) {
        if (y < height && emptyRows[y]) { if (start < 0) start = y }
        else if (start >= 0) {
            val middle = (start + y) / (2f * height)
            if (start > 0 && y < height && y - start >= height * .012 && middle > .25 && middle < .75) gaps += middle
            start = -1
        }
    }
    val cut = gaps.minByOrNull { kotlin.math.abs(it - .5f) }
    return if (cut == null) listOf(0f, 1f) else listOf(0f, cut, 1f)
}

/** Limit the total answer extent; partial passages retain PDF snapshots, not whole-source anchors. */
internal fun limitBasicCardExtent(candidates: com.google.gson.JsonArray, pdfSha256: String): com.google.gson.JsonArray {
    val result = com.google.gson.JsonArray()
    for (value in candidates) {
        val candidate = value.asJsonObject
        if (candidate.getAsJsonObject("source_anchor") != null) { result.add(candidate.deepCopy()); continue }
        val pages = candidate.getAsJsonArray("regions").groupBy { it.asJsonObject.get("page").asInt }.toSortedMap()
        val bounds = pages.mapValues { (_, regions) ->
            regions.minOf { it.asJsonObject.getAsJsonArray("rect")[1].asDouble } to
                regions.maxOf { it.asJsonObject.getAsJsonArray("rect")[3].asDouble }
        }
        if (bounds.values.sumOf { it.second - it.first } <= .5 + 1e-9) {
            result.add(candidate.deepCopy())
            continue
        }
        for ((page, regions) in pages) {
            val (top, bottom) = bounds.getValue(page)
            val count = kotlin.math.ceil((bottom - top) / .5 - 1e-9).toInt().coerceAtLeast(1)
            repeat(count) { index ->
                val a = top + (bottom - top) * index / count
                val b = top + (bottom - top) * (index + 1) / count
                val clipped = com.google.gson.JsonArray()
                for (region in regions) {
                    val r = region.asJsonObject.getAsJsonArray("rect")
                    // Android stores coordinates as floats; discard zero-height rounding slivers.
                    if (maxOf(a, r[1].asDouble).toFloat() < minOf(b, r[3].asDouble).toFloat()) clipped.add(com.google.gson.JsonObject().apply {
                        addProperty("page", page)
                        add("rect", com.google.gson.JsonArray().apply {
                            add(r[0]); add(maxOf(a, r[1].asDouble)); add(r[2]); add(minOf(b, r[3].asDouble))
                        })
                    })
                }
                if (clipped.size() > 0) {
                    val identity = com.google.gson.JsonArray().apply {
                        add("basic-card-limit-v1"); add(pdfSha256); add(candidate.get("key")); add(clipped)
                    }
                    result.add(com.google.gson.JsonObject().apply {
                        addProperty("key", MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray())
                            .joinToString("") { "%02x".format(it) })
                        add("regions", clipped)
                    })
                }
            }
        }
    }
    return result
}

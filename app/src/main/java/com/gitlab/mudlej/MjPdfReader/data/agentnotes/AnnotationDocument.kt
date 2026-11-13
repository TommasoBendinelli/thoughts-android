// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class AnnotationTool(val wireName: String) {
    PEN("pen"),
    HIGHLIGHTER("highlighter");

    companion object {
        fun fromWire(name: String?): AnnotationTool? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * One stroke in image pixels. [points] holds x and y interleaved; [color] is 0xRRGGBB (alpha is
 * [opacity]); [width] is the drawn width in image pixels.
 */
class AnnotationStroke(
    val tool: AnnotationTool,
    val color: Int,
    val width: Float,
    val opacity: Float,
    val points: FloatArray,
) {
    val pointCount: Int get() = points.size / 2

    // Bounding box, for a quick eraser rejection.
    val left: Float
    val top: Float
    val right: Float
    val bottom: Float

    init {
        require(points.size >= 2 && points.size % 2 == 0) { "A stroke needs at least one point" }
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in points.indices step 2) {
            l = min(l, points[i]); r = max(r, points[i])
            t = min(t, points[i + 1]); b = max(b, points[i + 1])
        }
        left = l; top = t; right = r; bottom = b
    }

    /** The bytes this stroke adds to the stroke document, to keep it under the server's limit. */
    internal val jsonBytes: Int by lazy { strokeJson(this).length + 1 }
}

/**
 * A numbered marker ("1", "2", ...) the user drops on the note so handwriting on paper ("1: ...")
 * can be grounded at that place. [x], [y] are its centre in image pixels; [color] is 0xRRGGBB.
 */
class AnnotationMarker(
    val x: Float,
    val y: Float,
    val number: Int,
    val color: Int,
) {
    internal val jsonBytes: Int by lazy { markerJson(this).length + 1 }
}

/**
 * Collects the points of a stroke while the finger moves, dropping points closer than
 * [minDistance] image pixels to the last kept one (the end point is always kept).
 */
class StrokePointsBuilder(private val minDistance: Float = 1.5f) {
    private var data = FloatArray(64)
    private var size = 0
    private var pendingX = Float.NaN
    private var pendingY = Float.NaN

    val pointCount: Int get() = size / 2 + if (pendingX.isNaN()) 0 else 1

    fun add(x: Float, y: Float) {
        if (size == 0) {
            append(x, y)
            return
        }
        val lastX = data[size - 2]
        val lastY = data[size - 1]
        if (hypot(x - lastX, y - lastY) < minDistance) {
            // Too close to keep now, but it may be where the stroke ends.
            pendingX = x; pendingY = y
            return
        }
        append(x, y)
        pendingX = Float.NaN; pendingY = Float.NaN
    }

    /** The points so far, including the latest even when it was too close (for live drawing). */
    fun snapshot(): FloatArray {
        if (pendingX.isNaN()) return data.copyOf(size)
        return data.copyOf(size + 2).also { it[size] = pendingX; it[size + 1] = pendingY }
    }

    /** The stroke's points; the last position is kept even when it was close to the previous one. */
    fun build(): FloatArray = snapshot()

    private fun append(x: Float, y: Float) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x
        data[size++] = y
    }
}

/**
 * The strokes and numbered markers drawn on an agent note, with undo/redo (an erasure is undone as
 * a whole) and the stroke-document JSON of protocol/agent-notes.md ("Stroke document (schema 1)").
 * Markers go in an extra top-level "markers" array, written only when there are any (the server
 * checks the known fields and stores the document as sent).
 *
 * [imageWidth] x [imageHeight] is the pixel space of the reply image (the decoded note image);
 * [sourceWidth] x [sourceHeight] is the note image as sent.
 */
class AnnotationDocument(
    val imageWidth: Int,
    val imageHeight: Int,
    val sourceWidth: Int = imageWidth,
    val sourceHeight: Int = imageHeight,
) {
    private sealed interface Action {
        class Add(val stroke: AnnotationStroke) : Action
        class AddMarker(val marker: AnnotationMarker) : Action
        /** Strokes and markers removed by one eraser gesture, with the index each had. */
        class Erase(
            val removed: List<Pair<Int, AnnotationStroke>>,
            val removedMarkers: List<Pair<Int, AnnotationMarker>> = emptyList(),
        ) : Action
    }

    private class EraseGesture {
        val strokes = mutableListOf<Pair<Int, AnnotationStroke>>()
        val markers = mutableListOf<Pair<Int, AnnotationMarker>>()
    }

    private val strokeList = ArrayList<AnnotationStroke>()
    private val markerList = ArrayList<AnnotationMarker>()
    private val undoStack = ArrayList<Action>()
    private val redoStack = ArrayList<Action>()
    private var erasing: EraseGesture? = null

    var pointCount = 0
        private set
    private var jsonBytes = HEADER_BYTES

    /** Changed since the last [markSaved] (e.g. the draft file). */
    var dirty = false
        private set

    /** Bumped on every change, so a view knows to redraw its cached strokes. */
    var revision = 0L
        private set

    val strokes: List<AnnotationStroke> get() = strokeList
    val markers: List<AnnotationMarker> get() = markerList
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val isEmpty: Boolean get() = strokeList.isEmpty() && markerList.isEmpty()

    /** The number the next marker gets: one more than the highest present, from 1. */
    val nextMarkerNumber: Int get() = (markerList.maxOfOrNull { it.number } ?: 0) + 1

    /** The drawn radius of a marker, in image pixels: it scales with the image like the strokes. */
    val markerRadius: Float get() = markerRadiusFor(imageWidth)

    /** Whether one more stroke of [points] coordinates fits within the server's limits. */
    fun canAdd(stroke: AnnotationStroke): Boolean =
        strokeList.size + 1 <= MAX_STROKES &&
            pointCount + stroke.pointCount <= MAX_POINTS &&
            jsonBytes + stroke.jsonBytes <= MAX_JSON_BYTES

    /** Adds a stroke; false (and nothing changes) when the limits would be exceeded. */
    fun add(stroke: AnnotationStroke): Boolean {
        endErase()
        if (!canAdd(stroke)) return false
        insert(strokeList.size, stroke)
        undoStack += Action.Add(stroke)
        redoStack.clear()
        changed()
        return true
    }

    /**
     * Adds a marker numbered [nextMarkerNumber] centred at ([x], [y]) image pixels. Returns it, or
     * null (and nothing changes) when the point is outside the image or the limits would be exceeded.
     */
    fun addMarker(x: Float, y: Float, color: Int): AnnotationMarker? {
        endErase()
        if (x !in 0f..imageWidth.toFloat() || y !in 0f..imageHeight.toFloat()) return null
        val marker = AnnotationMarker(x, y, nextMarkerNumber, color and 0xFFFFFF)
        if (!canAdd(marker)) return null
        insertMarker(markerList.size, marker)
        undoStack += Action.AddMarker(marker)
        redoStack.clear()
        changed()
        return marker
    }

    fun canAdd(marker: AnnotationMarker): Boolean =
        markerList.size + 1 <= MAX_MARKERS && jsonBytes + marker.jsonBytes <= MAX_JSON_BYTES

    /** Starts an eraser gesture: everything it removes is undone at once. */
    fun beginErase() {
        endErase()
        erasing = EraseGesture()
    }

    /**
     * Removes every stroke whose drawn outline, and every marker whose circle, comes within
     * [radius] of the eraser segment ([x0], [y0]) – ([x1], [y1]), all in image pixels. Returns how
     * many strokes and markers were removed.
     */
    fun eraseSegment(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float): Int {
        val gesture = erasing ?: EraseGesture().also { erasing = it }
        var removed = 0
        var index = strokeList.size - 1
        while (index >= 0) {
            val stroke = strokeList[index]
            if (hits(stroke, x0, y0, x1, y1, radius)) {
                remove(index)
                gesture.strokes += index to stroke
                removed++
            }
            index--
        }
        val markerReach = radius + markerRadius
        index = markerList.size - 1
        while (index >= 0) {
            val marker = markerList[index]
            if (pointSegmentDistance(marker.x, marker.y, x0, y0, x1, y1) <= markerReach) {
                removeMarker(index)
                gesture.markers += index to marker
                removed++
            }
            index--
        }
        if (removed > 0) changed()
        return removed
    }

    /** Ends an eraser gesture, recording it for undo if it removed anything. */
    fun endErase() {
        val gesture = erasing ?: return
        erasing = null
        if (gesture.strokes.isEmpty() && gesture.markers.isEmpty()) return
        undoStack += Action.Erase(gesture.strokes.toList(), gesture.markers.toList())
        redoStack.clear()
    }

    fun undo(): Boolean {
        endErase()
        val action = undoStack.removeLastOrNull() ?: return false
        when (action) {
            is Action.Add -> remove(strokeList.lastIndexOf(action.stroke))
            is Action.AddMarker -> removeMarker(markerList.lastIndexOf(action.marker))
            // Re-insert in reverse order of removal, so every stroke lands at its old index.
            is Action.Erase -> {
                action.removed.asReversed().forEach { (index, stroke) ->
                    insert(index.coerceAtMost(strokeList.size), stroke)
                }
                action.removedMarkers.asReversed().forEach { (index, marker) ->
                    insertMarker(index.coerceAtMost(markerList.size), marker)
                }
            }
        }
        redoStack += action
        changed()
        return true
    }

    fun redo(): Boolean {
        endErase()
        val action = redoStack.removeLastOrNull() ?: return false
        when (action) {
            is Action.Add -> insert(strokeList.size, action.stroke)
            is Action.AddMarker -> insertMarker(markerList.size, action.marker)
            is Action.Erase -> {
                action.removed.forEach { (_, stroke) ->
                    val index = strokeList.indexOf(stroke)
                    if (index >= 0) remove(index)
                }
                action.removedMarkers.forEach { (_, marker) ->
                    val index = markerList.indexOf(marker)
                    if (index >= 0) removeMarker(index)
                }
            }
        }
        undoStack += action
        changed()
        return true
    }

    fun markSaved() {
        dirty = false
    }

    /**
     * The stroke document (schema 1): points with one decimal, only the strokes still present, and
     * the markers (when there are any) as {"x", "y", "number", "color"}.
     */
    fun toJson(): String = buildString {
        append("{\"schema_version\":1")
        append(",\"image_width\":").append(imageWidth)
        append(",\"image_height\":").append(imageHeight)
        append(",\"source_width\":").append(sourceWidth)
        append(",\"source_height\":").append(sourceHeight)
        append(",\"strokes\":[")
        strokeList.forEachIndexed { index, stroke ->
            if (index > 0) append(',')
            append(strokeJson(stroke))
        }
        append(']')
        if (markerList.isNotEmpty()) {
            append(",\"markers\":[")
            markerList.forEachIndexed { index, marker ->
                if (index > 0) append(',')
                append(markerJson(marker))
            }
            append(']')
        }
        append('}')
    }

    /** This document's strokes in another pixel space (a draft made at another decoded size). */
    fun scaledTo(width: Int, height: Int, sourceWidth: Int = this.sourceWidth, sourceHeight: Int = this.sourceHeight): AnnotationDocument {
        val result = AnnotationDocument(width, height, sourceWidth, sourceHeight)
        val sx = if (imageWidth > 0) width.toFloat() / imageWidth else 1f
        val sy = if (imageHeight > 0) height.toFloat() / imageHeight else 1f
        strokeList.forEach { stroke ->
            val points = FloatArray(stroke.points.size) { i -> stroke.points[i] * if (i % 2 == 0) sx else sy }
            result.addLoaded(AnnotationStroke(stroke.tool, stroke.color, stroke.width * (sx + sy) / 2f, stroke.opacity, points))
        }
        markerList.forEach { marker ->
            result.addLoaded(AnnotationMarker(marker.x * sx, marker.y * sy, marker.number, marker.color))
        }
        return result
    }

    private fun addLoaded(stroke: AnnotationStroke) {
        if (canAdd(stroke)) insert(strokeList.size, stroke)
    }

    private fun addLoaded(marker: AnnotationMarker) {
        if (canAdd(marker)) insertMarker(markerList.size, marker)
    }

    private fun insertMarker(index: Int, marker: AnnotationMarker) {
        markerList.add(index, marker)
        jsonBytes += marker.jsonBytes
    }

    private fun removeMarker(index: Int) {
        if (index < 0) return
        jsonBytes -= markerList.removeAt(index).jsonBytes
    }

    private fun insert(index: Int, stroke: AnnotationStroke) {
        strokeList.add(index, stroke)
        pointCount += stroke.pointCount
        jsonBytes += stroke.jsonBytes
    }

    private fun remove(index: Int) {
        if (index < 0) return
        val stroke = strokeList.removeAt(index)
        pointCount -= stroke.pointCount
        jsonBytes -= stroke.jsonBytes
    }

    private fun changed() {
        dirty = true
        revision++
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_STROKES = 5000
        const val MAX_POINTS = 200_000
        const val MAX_MARKERS = 99
        /** The server refuses stroke documents above 2 MiB; keep a margin for the header. */
        const val MAX_JSON_BYTES = 2 * 1024 * 1024 - 4096
        private const val HEADER_BYTES = 160

        const val HIGHLIGHTER_OPACITY = 0.35f
        const val HIGHLIGHTER_WIDTH_FACTOR = 4f

        /** The pen width for an image: thin enough to write with, visible when the note is fit to a phone. */
        fun penWidthFor(imageWidth: Int): Float = max(2f, imageWidth / 250f)

        /**
         * A marker's radius for an image: 3% of its width (about 11 dp when the note is fit to a
         * 360 dp phone), at least 14 px.
         */
        fun markerRadiusFor(imageWidth: Int): Float = max(14f, imageWidth * 0.03f)

        /**
         * Reads a stroke document back (drafts). Unknown tools and malformed strokes are skipped; a
         * document over the limits keeps the strokes that fit.
         */
        fun fromJson(json: String): AnnotationDocument {
            val root = JsonParser.parseString(json).asJsonObject
            require(root.long("schema_version") == SCHEMA_VERSION.toLong()) { "Unknown stroke document version" }
            val document = AnnotationDocument(
                root.long("image_width").toInt(),
                root.long("image_height").toInt(),
                root.long("source_width").toInt().takeIf { it > 0 } ?: root.long("image_width").toInt(),
                root.long("source_height").toInt().takeIf { it > 0 } ?: root.long("image_height").toInt(),
            )
            root.get("strokes")?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.forEach { element ->
                val stroke = element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let(::parseStroke) ?: return@forEach
                document.addLoaded(stroke)
            }
            root.get("markers")?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.forEach { element ->
                val marker = element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let(::parseMarker) ?: return@forEach
                document.addLoaded(marker)
            }
            return document
        }

        private fun parseMarker(json: JsonObject): AnnotationMarker? = runCatching {
            val x = json.get("x")?.asFloat ?: return null
            val y = json.get("y")?.asFloat ?: return null
            val number = json.get("number")?.asInt?.takeIf { it >= 1 } ?: return null
            val color = parseColor(json.string("color")) ?: return null
            if (!x.isFinite() || !y.isFinite()) return null
            AnnotationMarker(x, y, number, color)
        }.getOrNull()

        private fun parseStroke(json: JsonObject): AnnotationStroke? = runCatching {
            val tool = AnnotationTool.fromWire(json.string("tool")) ?: return null
            val color = parseColor(json.string("color")) ?: return null
            val points = json.getAsJsonArray("points") ?: return null
            val coordinates = FloatArray(points.size() * 2)
            points.forEachIndexed { i, point ->
                val pair = point.asJsonArray
                coordinates[2 * i] = pair[0].asFloat
                coordinates[2 * i + 1] = pair[1].asFloat
            }
            if (coordinates.isEmpty()) return null
            AnnotationStroke(
                tool,
                color,
                json.get("width")?.asFloat ?: 1f,
                json.get("opacity")?.asFloat ?: 1f,
                coordinates,
            )
        }.getOrNull()

        /** "#RRGGBB" for an 0xRRGGBB / 0xAARRGGBB colour (alpha is the stroke's opacity). */
        fun colorHex(color: Int): String = String.format(java.util.Locale.ROOT, "#%06X", color and 0xFFFFFF)

        fun parseColor(hex: String?): Int? {
            val digits = hex?.removePrefix("#") ?: return null
            if (digits.length != 6) return null
            return digits.toIntOrNull(16)
        }

        /** Whether the eraser segment comes within reach of the stroke's drawn outline. */
        internal fun hits(stroke: AnnotationStroke, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float): Boolean {
            val reach = radius + stroke.width / 2f
            if (max(x0, x1) + reach < stroke.left || min(x0, x1) - reach > stroke.right ||
                max(y0, y1) + reach < stroke.top || min(y0, y1) - reach > stroke.bottom
            ) return false
            val p = stroke.points
            if (p.size == 2) return pointSegmentDistance(p[0], p[1], x0, y0, x1, y1) <= reach
            for (i in 0 until p.size - 2 step 2) {
                if (segmentDistance(p[i], p[i + 1], p[i + 2], p[i + 3], x0, y0, x1, y1) <= reach) return true
            }
            return false
        }

        internal fun pointSegmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0f) return hypot(px - ax, py - ay)
            val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }

        internal fun segmentDistance(
            ax: Float, ay: Float, bx: Float, by: Float,
            cx: Float, cy: Float, dx: Float, dy: Float,
        ): Float {
            if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
            return min(
                min(pointSegmentDistance(ax, ay, cx, cy, dx, dy), pointSegmentDistance(bx, by, cx, cy, dx, dy)),
                min(pointSegmentDistance(cx, cy, ax, ay, bx, by), pointSegmentDistance(dx, dy, ax, ay, bx, by)),
            )
        }

        private fun segmentsIntersect(
            ax: Float, ay: Float, bx: Float, by: Float,
            cx: Float, cy: Float, dx: Float, dy: Float,
        ): Boolean {
            fun cross(ox: Float, oy: Float, px: Float, py: Float, qx: Float, qy: Float) =
                (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
            val d1 = cross(cx, cy, dx, dy, ax, ay)
            val d2 = cross(cx, cy, dx, dy, bx, by)
            val d3 = cross(ax, ay, bx, by, cx, cy)
            val d4 = cross(ax, ay, bx, by, dx, dy)
            return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        }
    }
}

/** A number with exactly one decimal, locale-independent ("12.0", "-0.5"). */
internal fun oneDecimal(value: Float): String {
    val tenths = (value.toDouble() * 10.0).roundToLong()
    val sign = if (tenths < 0) "-" else ""
    val magnitude = abs(tenths)
    return "$sign${magnitude / 10}.${magnitude % 10}"
}

/** Opacity with up to two decimals ("1.0", "0.35"). */
internal fun opacityText(value: Float): String {
    val hundredths = (value.toDouble() * 100.0).roundToLong().coerceIn(0, 100)
    val whole = hundredths / 100
    val fraction = hundredths % 100
    return when {
        fraction == 0L -> "$whole.0"
        fraction % 10 == 0L -> "$whole.${fraction / 10}"
        else -> "$whole.${String.format(java.util.Locale.ROOT, "%02d", fraction)}"
    }
}

internal fun markerJson(marker: AnnotationMarker): String = buildString {
    append("{\"x\":").append(oneDecimal(marker.x))
    append(",\"y\":").append(oneDecimal(marker.y))
    append(",\"number\":").append(marker.number)
    append(",\"color\":\"").append(AnnotationDocument.colorHex(marker.color)).append("\"}")
}

internal fun strokeJson(stroke: AnnotationStroke): String = buildString {
    append("{\"tool\":\"").append(stroke.tool.wireName).append('"')
    append(",\"color\":\"").append(AnnotationDocument.colorHex(stroke.color)).append('"')
    append(",\"opacity\":").append(opacityText(stroke.opacity))
    append(",\"width\":").append(oneDecimal(stroke.width))
    append(",\"points\":[")
    val p = stroke.points
    for (i in p.indices step 2) {
        if (i > 0) append(',')
        append('[').append(oneDecimal(p[i])).append(',').append(oneDecimal(p[i + 1])).append(']')
    }
    append("]}")
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationDocumentTest {
    private fun pen(vararg xy: Float, width: Float = 6f, color: Int = 0x000000) =
        AnnotationStroke(AnnotationTool.PEN, color, width, 1f, xy)

    private fun highlighter(vararg xy: Float, width: Float = 24f) =
        AnnotationStroke(AnnotationTool.HIGHLIGHTER, 0xFB8C00, width, AnnotationDocument.HIGHLIGHTER_OPACITY, xy)

    @Test fun drawUndoRedo() {
        val document = AnnotationDocument(100, 100)
        val a = pen(0f, 0f, 10f, 10f)
        val b = pen(20f, 20f, 30f, 30f)
        assertTrue(document.add(a))
        assertTrue(document.add(b))
        assertEquals(listOf(a, b), document.strokes)
        assertEquals(4, document.pointCount)
        assertTrue(document.dirty)

        assertTrue(document.undo())
        assertEquals(listOf(a), document.strokes)
        assertTrue(document.canRedo)
        assertTrue(document.redo())
        assertEquals(listOf(a, b), document.strokes)
        assertFalse(document.redo())

        document.undo()
        // A new stroke after undo drops the redo history.
        val c = pen(50f, 50f)
        document.add(c)
        assertFalse(document.canRedo)
        assertEquals(listOf(a, c), document.strokes)
        assertTrue(document.undo())
        assertTrue(document.undo())
        assertFalse(document.undo())
        assertTrue(document.isEmpty)
        assertEquals(0, document.pointCount)
    }

    @Test fun eraserRemovesWholeStrokesAndOneUndoRestoresTheGesture() {
        val document = AnnotationDocument(200, 200)
        val a = pen(0f, 10f, 100f, 10f)
        val b = pen(0f, 50f, 100f, 50f)
        val c = pen(0f, 90f, 100f, 90f)
        listOf(a, b, c).forEach(document::add)

        document.beginErase()
        // A vertical drag from y=40 to y=100 at x=50 crosses b and c, not a.
        assertEquals(1, document.eraseSegment(50f, 40f, 50f, 60f, 2f))
        assertEquals(1, document.eraseSegment(50f, 60f, 50f, 100f, 2f))
        document.endErase()
        assertEquals(listOf(a), document.strokes)

        assertTrue(document.undo())
        // Both strokes come back at their places, in order.
        assertEquals(listOf(a, b, c), document.strokes)
        assertTrue(document.redo())
        assertEquals(listOf(a), document.strokes)
        assertTrue(document.undo())
        assertEquals(listOf(a, b, c), document.strokes)
        // Undo before that removes the last drawn stroke.
        assertTrue(document.undo())
        assertEquals(listOf(a, b), document.strokes)
    }

    @Test fun eraserMissDoesNotCreateAnUndoStep() {
        val document = AnnotationDocument(200, 200)
        val a = pen(0f, 10f, 100f, 10f)
        document.add(a)
        document.beginErase()
        assertEquals(0, document.eraseSegment(150f, 150f, 160f, 160f, 4f))
        document.endErase()
        assertTrue(document.undo())
        assertTrue(document.isEmpty)
        assertFalse(document.undo())
    }

    @Test fun hitTestAccountsForStrokeWidth() {
        val thin = pen(0f, 100f, 100f, 100f, width = 10f)
        // Reach = eraser radius 2 + half width 5 = 7.
        assertFalse(AnnotationDocument.hits(thin, 50f, 112f, 60f, 112f, 2f))
        assertTrue(AnnotationDocument.hits(thin, 50f, 106f, 60f, 106f, 2f))
        val wide = highlighter(0f, 100f, 100f, 100f, width = 40f)
        assertTrue(AnnotationDocument.hits(wide, 50f, 112f, 60f, 112f, 2f))
        // Beyond the end of the stroke, measured to the end point.
        assertFalse(AnnotationDocument.hits(thin, 110f, 100f, 120f, 100f, 2f))
        assertTrue(AnnotationDocument.hits(thin, 106f, 100f, 120f, 100f, 2f))
        // A crossing segment hits even though no endpoint is near.
        assertTrue(AnnotationDocument.hits(thin, 50f, 0f, 50f, 200f, 0.1f))
        // A single-point stroke (a dot).
        val dot = pen(10f, 10f, width = 6f)
        assertTrue(AnnotationDocument.hits(dot, 14f, 10f, 14f, 10f, 2f))
        assertFalse(AnnotationDocument.hits(dot, 16f, 10f, 16f, 10f, 2f))
    }

    @Test fun serializesTheDocumentsStrokeFormatExactly() {
        val document = AnnotationDocument(1200, 1800, 2400, 3600)
        document.add(pen(10f, 20f, 30.25f, 40.04f, width = 4.8f, color = 0xE53935))
        document.add(highlighter(0f, 0f, 100f, 0.06f, width = 19.2f))
        assertEquals(
            """{"schema_version":1,"image_width":1200,"image_height":1800,"source_width":2400,"source_height":3600,""" +
                """"strokes":[{"tool":"pen","color":"#E53935","opacity":1.0,"width":4.8,"points":[[10.0,20.0],[30.3,40.0]]},""" +
                """{"tool":"highlighter","color":"#FB8C00","opacity":0.35,"width":19.2,"points":[[0.0,0.0],[100.0,0.1]]}]}""",
            document.toJson(),
        )
        // It is valid JSON with the keys of protocol/agent-notes.md.
        val json = JsonParser.parseString(document.toJson()).asJsonObject
        assertEquals(setOf("schema_version", "image_width", "image_height", "source_width", "source_height", "strokes"), json.keySet())
        assertEquals(setOf("tool", "color", "opacity", "width", "points"), json.getAsJsonArray("strokes")[0].asJsonObject.keySet())
    }

    @Test fun emptyDocumentAndNegativeCoordinates() {
        val document = AnnotationDocument(10, 20)
        assertEquals(
            """{"schema_version":1,"image_width":10,"image_height":20,"source_width":10,"source_height":20,"strokes":[]}""",
            document.toJson(),
        )
        assertEquals("-0.5", oneDecimal(-0.46f))
        assertEquals("0.0", oneDecimal(0.04f))
        assertEquals("1.0", opacityText(1f))
        assertEquals("0.5", opacityText(0.5f))
        assertEquals("0.35", opacityText(0.35f))
    }

    @Test fun roundTripForDrafts() {
        val document = AnnotationDocument(1200, 1800, 2400, 3600)
        document.add(pen(10f, 20f, 30.5f, 40.5f, width = 4.8f, color = 0x1E88E5))
        document.add(highlighter(1f, 2f))
        val json = document.toJson()
        val parsed = AnnotationDocument.fromJson(json)
        assertEquals(json, parsed.toJson())
        assertEquals(1200, parsed.imageWidth)
        assertEquals(3600, parsed.sourceHeight)
        assertEquals(AnnotationTool.HIGHLIGHTER, parsed.strokes[1].tool)
        assertEquals(0x1E88E5, parsed.strokes[0].color)
        // A loaded draft is not "changed", and has no undo history.
        assertFalse(parsed.dirty)
        assertFalse(parsed.canUndo)
    }

    @Test fun parsingSkipsUnknownToolsAndMalformedStrokes() {
        val parsed = AnnotationDocument.fromJson(
            """{"schema_version":1,"image_width":10,"image_height":10,"source_width":10,"source_height":10,"strokes":[""" +
                """{"tool":"laser","color":"#000000","opacity":1.0,"width":1.0,"points":[[1.0,1.0]]},""" +
                """{"tool":"pen","color":"red","opacity":1.0,"width":1.0,"points":[[1.0,1.0]]},""" +
                """{"tool":"pen","color":"#000000","opacity":1.0,"width":1.0,"points":[]},""" +
                """{"tool":"pen","color":"#00FF00","opacity":1.0,"width":2.0,"points":[[1.0,2.0],[3.0,4.0]]}]}""",
        )
        assertEquals(1, parsed.strokes.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), parsed.strokes[0].points, 0f)
    }

    @Test fun scalingMapsADraftToAnotherDecodedSize() {
        val document = AnnotationDocument(1000, 2000)
        document.add(pen(100f, 200f, 300f, 400f, width = 8f))
        val scaled = document.scaledTo(500, 1000, 1000, 2000)
        assertArrayEquals(floatArrayOf(50f, 100f, 150f, 200f), scaled.strokes[0].points, 0.001f)
        assertEquals(4f, scaled.strokes[0].width, 0.001f)
        assertEquals(1000, scaled.sourceWidth)
    }

    @Test fun pointDecimationDropsPointsCloserThanOneAndAHalfPixels() {
        val builder = StrokePointsBuilder(1.5f)
        builder.add(0f, 0f)
        builder.add(0.5f, 0f)
        builder.add(1f, 0f)
        builder.add(2f, 0f)
        builder.add(2.2f, 0f)
        builder.add(5f, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 2f, 0f, 5f, 0f), builder.build(), 0f)
        // The end of the stroke is kept even when close to the previous point.
        builder.add(5.3f, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 2f, 0f, 5f, 0f, 5.3f, 0f), builder.build(), 0f)
        assertEquals(4, builder.pointCount)
    }

    @Test fun strokeCountLimitIsEnforced() {
        val document = AnnotationDocument(100, 100)
        repeat(AnnotationDocument.MAX_STROKES) { assertTrue(document.add(pen(1f, 1f))) }
        assertFalse(document.add(pen(2f, 2f)))
        assertEquals(AnnotationDocument.MAX_STROKES, document.strokes.size)
        // Undo frees room again.
        document.undo()
        assertTrue(document.add(pen(2f, 2f)))
    }

    @Test fun pointLimitIsEnforced() {
        val document = AnnotationDocument(100, 100)
        val tooMany = FloatArray((AnnotationDocument.MAX_POINTS + 1) * 2)
        assertFalse(document.add(AnnotationStroke(AnnotationTool.PEN, 0, 2f, 1f, tooMany)))
        assertTrue(document.isEmpty)
        assertFalse(document.canUndo)
    }

    @Test fun serializedSizeStaysUnderTheServersLimit() {
        val document = AnnotationDocument(9000, 9000)
        // 1000-point strokes of large coordinates: about 17 bytes a point.
        var added = 0
        while (true) {
            val points = FloatArray(2000) { i -> 8000.55f + (i % 7) }
            if (!document.add(AnnotationStroke(AnnotationTool.PEN, 0, 2f, 1f, points))) break
            added++
        }
        assertTrue(document.pointCount < AnnotationDocument.MAX_POINTS)
        val bytes = document.toJson().toByteArray().size
        assertTrue("$bytes bytes", bytes <= 2 * 1024 * 1024)
        assertTrue(added > 50)
    }
    @Test fun markersAreNumberedFromOneAndUndoneWithTheStrokes() {
        val document = AnnotationDocument(1000, 1000)
        assertEquals(1, document.nextMarkerNumber)
        val first = document.addMarker(100f, 200f, 0xE53935)!!
        assertEquals(1, first.number)
        assertFalse(document.isEmpty)
        assertTrue(document.dirty)
        val stroke = pen(0f, 0f, 10f, 10f)
        document.add(stroke)
        val second = document.addMarker(300f, 400f, 0x1E88E5)!!
        assertEquals(2, second.number)
        assertEquals(listOf(1, 2), document.markers.map { it.number })

        // One history for strokes and markers.
        assertTrue(document.undo())
        assertEquals(listOf(1), document.markers.map { it.number })
        assertEquals(listOf(stroke), document.strokes)
        assertTrue(document.undo())
        assertTrue(document.strokes.isEmpty())
        assertTrue(document.redo())
        assertTrue(document.redo())
        assertEquals(listOf(first, second), document.markers)
        // The next number is one more than the highest present.
        document.undo()
        assertEquals(2, document.addMarker(5f, 5f, 0)!!.number)
        assertFalse(document.canRedo)
        document.undo()
        document.undo()
        document.undo()
        assertTrue(document.isEmpty)
        assertEquals(1, document.nextMarkerNumber)
    }

    @Test fun markersOutsideTheImageOrOverTheLimitAreRefused() {
        val document = AnnotationDocument(100, 50)
        assertEquals(null, document.addMarker(-1f, 10f, 0))
        assertEquals(null, document.addMarker(10f, 51f, 0))
        assertFalse(document.canUndo)
        repeat(AnnotationDocument.MAX_MARKERS) { assertTrue(document.addMarker(1f, 1f, 0) != null) }
        assertEquals(null, document.addMarker(2f, 2f, 0))
        assertEquals(AnnotationDocument.MAX_MARKERS, document.markers.size)
        document.undo()
        assertEquals(AnnotationDocument.MAX_MARKERS, document.addMarker(2f, 2f, 0)!!.number)
    }

    @Test fun eraserRemovesMarkersItTouchesAndUndoRestoresThem() {
        val document = AnnotationDocument(1000, 1000)
        val a = pen(0f, 500f, 1000f, 500f)
        document.add(a)
        val one = document.addMarker(100f, 100f, 0)!!
        val two = document.addMarker(900f, 900f, 0)!!
        val three = document.addMarker(100f, 900f, 0)!!
        assertEquals(30f, document.markerRadius, 0.001f)

        document.beginErase()
        // 25 px from the centre of marker 2: within its radius 30 + eraser 2.
        assertEquals(1, document.eraseSegment(925f, 900f, 925f, 900f, 2f))
        // 40 px from marker 1: out of reach.
        assertEquals(0, document.eraseSegment(140f, 100f, 140f, 100f, 2f))
        // Crosses the stroke and marker 3.
        assertEquals(2, document.eraseSegment(100f, 450f, 100f, 950f, 2f))
        document.endErase()
        assertEquals(listOf(one), document.markers)
        assertTrue(document.strokes.isEmpty())
        // The next number continues after the highest present.
        assertEquals(2, document.nextMarkerNumber)

        assertTrue(document.undo())
        assertEquals(listOf(one, two, three), document.markers)
        assertEquals(listOf(a), document.strokes)
        assertTrue(document.redo())
        assertEquals(listOf(one), document.markers)
        assertTrue(document.undo())
        assertEquals(listOf(one, two, three), document.markers)
    }

    @Test fun markersSerializeAlongsideTheStrokesAndRoundTrip() {
        val document = AnnotationDocument(1200, 1800, 2400, 3600)
        document.add(pen(10f, 20f, width = 4.8f))
        document.addMarker(100.04f, 250.26f, 0xE53935)
        document.addMarker(5f, 6f, 0x000000)
        val json = document.toJson()
        assertEquals(
            """{"schema_version":1,"image_width":1200,"image_height":1800,"source_width":2400,"source_height":3600,""" +
                """"strokes":[{"tool":"pen","color":"#000000","opacity":1.0,"width":4.8,"points":[[10.0,20.0]]}],""" +
                """"markers":[{"x":100.0,"y":250.3,"number":1,"color":"#E53935"},{"x":5.0,"y":6.0,"number":2,"color":"#000000"}]}""",
            json,
        )
        val parsed = AnnotationDocument.fromJson(json)
        assertEquals(json, parsed.toJson())
        assertEquals(listOf(1, 2), parsed.markers.map { it.number })
        assertEquals(0xE53935, parsed.markers[0].color)
        assertFalse(parsed.dirty)
        assertFalse(parsed.canUndo)
        assertEquals(3, parsed.nextMarkerNumber)
    }

    @Test fun documentsWithoutMarkersParseAndMalformedMarkersAreSkipped() {
        val withoutMarkers = AnnotationDocument.fromJson(
            """{"schema_version":1,"image_width":10,"image_height":10,"source_width":10,"source_height":10,"strokes":[]}""",
        )
        assertTrue(withoutMarkers.markers.isEmpty())
        assertTrue(withoutMarkers.isEmpty)
        assertFalse(withoutMarkers.toJson().contains("markers"))
        val parsed = AnnotationDocument.fromJson(
            """{"schema_version":1,"image_width":10,"image_height":10,"strokes":[],"markers":[""" +
                """{"x":1.0,"y":2.0,"number":0,"color":"#000000"},""" +
                """{"x":1.0,"number":3,"color":"#000000"},""" +
                """{"x":1.0,"y":2.0,"number":4,"color":"blue"},""" +
                """"not a marker",""" +
                """{"x":3.0,"y":4.0,"number":7,"color":"#1E88E5"}]}""",
        )
        assertEquals(1, parsed.markers.size)
        assertEquals(7, parsed.markers[0].number)
        assertEquals(8, parsed.nextMarkerNumber)
    }

    @Test fun scalingMovesMarkers() {
        val document = AnnotationDocument(1000, 2000)
        document.addMarker(100f, 400f, 0x43A047)
        val scaled = document.scaledTo(500, 1000, 1000, 2000)
        val marker = scaled.markers.single()
        assertEquals(50f, marker.x, 0.001f)
        assertEquals(200f, marker.y, 0.001f)
        assertEquals(1, marker.number)
        assertEquals(0x43A047, marker.color)
        assertEquals(15f, scaled.markerRadius, 0.001f)
        // A small image keeps a readable minimum radius.
        assertEquals(14f, AnnotationDocument.markerRadiusFor(100), 0.001f)
    }
}

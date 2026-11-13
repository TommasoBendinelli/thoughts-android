// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.barteksc.pdfviewer.PDFView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory viewer fixtures only: no comment store, app preferences or sync mutations. */
@RunWith(AndroidJUnit4::class)
class CommentHighlightAppearanceTest {
    private fun withViewer(crop: com.github.barteksc.pdfviewer.model.CropBounds? = null, block: (PDFView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var view: PDFView
        val bytes = instrumentation.context.assets.open("compiler-comments/corpus.pdf").use { it.readBytes() }
        instrumentation.runOnMainSync {
            view = PDFView(instrumentation.targetContext, null)
            view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 800, 1000)
            view.fromBytes(bytes).apply {
                if (crop != null) cropMargins(true).cachedCropMargins(
                    com.github.barteksc.pdfviewer.model.CropMargins.single(crop))
            }.onLoad { ready.countDown() }.load()
        }
        assertTrue("Fixture loads", ready.await(15, TimeUnit.SECONDS))
        try { instrumentation.runOnMainSync { block(view) } }
        finally { instrumentation.runOnMainSync { view.recycle() } }
    }

    private fun draw(view: PDFView, method: String, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 1000, config)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        PDFView::class.java.getDeclaredMethod(method, Canvas::class.java, Set::class.java)
            .apply { isAccessible = true }.invoke(view, canvas, setOf(0))
        return bitmap
    }

    private fun record(contents: String = "Comment", rects: List<RectF> = listOf(RectF(100f, 600f, 400f, 620f))) =
        PDFView.ManagedPdfComment(0, PDFView.PdfComment.SUBTYPE_HIGHLIGHT, "root", rects,
            Color.YELLOW, contents, "", "", "", "", "")

    private fun bubbleCount(bitmap: Bitmap): Int {
        val seen = BooleanArray(bitmap.width * bitmap.height)
        val queue = java.util.ArrayDeque<Int>()
        fun yellow(i: Int): Boolean {
            val c = bitmap.getPixel(i % bitmap.width, i / bitmap.width)
            return Color.red(c) > 180 && Color.green(c) > 180 && Color.blue(c) < 120
        }
        var count = 0
        for (i in seen.indices) {
            if (seen[i] || !yellow(i)) continue
            queue.add(i); seen[i] = true
            var area = 0
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst(); area++
                val x = p % bitmap.width; val y = p / bitmap.width
                for (n in intArrayOf(if (x > 0) p-1 else -1, if (x+1 < bitmap.width) p+1 else -1,
                    if (y > 0) p-bitmap.width else -1, if (y+1 < bitmap.height) p+bitmap.width else -1)) {
                    if (n >= 0 && !seen[n] && yellow(n)) { seen[n] = true; queue.add(n) }
                }
            }
            if (area > 20) count++
        }
        return count
    }

    @Test fun savedRawOriginStrikethroughRemainsVisibleAfterLocalReload() = withViewer { view ->
        val hash = "a".repeat(64)
        val payload = """{"subtype":"StrikeOut","contents":"","origin":{"collection":"fixture","pdf_sha256":"$hash","geometry_format":"pdf_points_bottom_left","raw_pdf_geometry":{"page":1,"rect":[100,620,400,600],"quad_points":[100,620,400,620,100,600,400,600]}}}"""
        val logical = com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment(
            id = "strike", threadRootId = "strike", parentId = null, revision = 1, deleted = false,
            scopeState = "needs_attention", sourceFile = "", sourceLine = 0,
            originCollection = "fixture", originPageIndex = 0, payloadJson = payload)
        val sizes = view.commentPageSizes()
        assertTrue("New raw origins have no compiled placement yet",
            logicalCommentOverlaySpecs(listOf(logical), "fixture.pdf", hash).isEmpty())
        val reloaded = resolveStoredPdfComments(emptyList(), listOf(logical), "fixture.pdf", hash) { sizes[it] }
        assertEquals(1, reloaded.size)
        assertEquals(PDFView.PdfComment.SUBTYPE_STRIKEOUT, reloaded.single().subtype)
        view.setManagedPdfComments(managedPdfCommentRecords(reloaded))
        assertEquals("strike", view.allPdfComments.single().name)
        assertTrue("Never reuse raw coordinates on a rebuilt PDF",
            resolveStoredPdfComments(emptyList(), listOf(logical), "fixture.pdf", "b".repeat(64)) { sizes[it] }.isEmpty())
    }

    @Test fun strikethroughDrawsOnlyALineAndKeepsItsType() = withViewer { view ->
        val rect = RectF(100f, 600f, 400f, 620f)
        view.setManagedPdfComments(listOf(PDFView.ManagedPdfComment(0,
            PDFView.PdfComment.SUBTYPE_STRIKEOUT, "strike", listOf(rect),
            Color.RED, "", "", "", "", "", "")))
        val transform = PDFView::class.java.getDeclaredMethod("managedPdfRectToDocument", Int::class.javaPrimitiveType, RectF::class.java)
            .apply { isAccessible = true }
        val bounds = transform.invoke(view, 0, rect) as RectF
        val bitmap = draw(view, "drawPdfCommentMarkers")
        val x = bounds.centerX().toInt()
        assertEquals(Color.RED, bitmap.getPixel(x, bounds.centerY().toInt()))
        assertEquals(Color.WHITE, bitmap.getPixel(x, (bounds.top + bounds.height() * .2f).toInt()))
        assertEquals(Color.WHITE, bitmap.getPixel(x, (bounds.bottom - bounds.height() * .2f).toInt()))
        assertEquals(PDFView.PdfComment.SUBTYPE_STRIKEOUT, view.allPdfComments.single().subtype)
        bitmap.recycle()
    }

    @Test fun oneNavigationBubbleForManyWordRegions() = withViewer { view ->
        val regions = (0..5).map { RectF(0.15f + it*0.11f, 0.45f, 0.21f + it*0.11f, 0.47f) }
        assertTrue(view.setLogicalCommentMarker(0, regions))
        assertEquals("One logical comment, six words", 1, bubbleCount(draw(view, "drawLogicalCommentMarker")))
    }

    @Test fun bubbleSizeScalesWithPdfZoom() = withViewer { view ->
        val method = PDFView::class.java.getDeclaredMethod("logicalCommentMarker", Int::class.javaPrimitiveType, RectF::class.java)
            .apply { isAccessible = true }
        fun width(zoom: Float): Float {
            view.zoomTo(zoom)
            val bubble = method.invoke(view, 0, RectF(.3f, .4f, .5f, .42f))!!
            fun field(name: String) = bubble.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(bubble)
            return field("right") - field("left")
        }
        assertEquals("Doubling PDF zoom doubles the visible bubble", 2f, width(2f)/width(1f), .01f)
    }

    @Test fun plainHighlightHasNoCommentBubble() = withViewer { view ->
        view.setManagedPdfComments(listOf(record(contents = "")))
        assertEquals(0, bubbleCount(draw(view, "drawPdfCommentMarkers")))
    }

    @Test fun navigationDoesNotDuplicateAnAlreadyRenderedComment() = withViewer { view ->
        view.setManagedPdfComments(listOf(record()))
        view.setLogicalCommentMarker(0, listOf(RectF(.2f,.4f,.4f,.42f)), "root")
        assertEquals(0, bubbleCount(draw(view, "drawLogicalCommentMarker")))
        assertEquals(1, bubbleCount(draw(view, "drawPdfCommentMarkers")))
    }

    @Test fun fallbackHighlightsShareOneBubbleAndKeepEveryRegionClickable() = withViewer { view ->
        val overlays = (0..4).map { PDFView.LogicalCommentOverlay("root", 0,
            .15f + it*.12f, .45f, .21f + it*.12f, .47f, false, false, Float.NaN, Float.NaN, true, Color.YELLOW) }
        view.setLogicalCommentOverlays(overlays)
        assertEquals(1, bubbleCount(draw(view, "drawLogicalCommentMarker")))
        val transform = PDFView::class.java.getDeclaredMethod("normalizedTopLeftRectToDocument", Int::class.javaPrimitiveType, RectF::class.java)
            .apply { isAccessible = true }
        for (overlay in overlays) {
            val rect = transform.invoke(view, 0, RectF(overlay.left,overlay.top,overlay.right,overlay.bottom)) as RectF
            assertEquals("root", view.logicalCommentAt(rect.centerX()+view.currentXOffset, rect.centerY()+view.currentYOffset))
        }
        view.setLogicalCommentOverlays(overlays.map { PDFView.LogicalCommentOverlay("root", 0,
            it.left,it.top,it.right,it.bottom,false,false,Float.NaN,Float.NaN,false,Color.YELLOW) })
        assertEquals(0, bubbleCount(draw(view, "drawLogicalCommentMarker")))
    }

    @Test fun continuationAndReplyDoNotAddBubbles() = withViewer { view ->
        val root = record()
        val continuation = PDFView.ManagedPdfComment(1, root.subtype, PDFView.managedCommentFragmentName("root", 1), root.pdfRects,
            root.color, "", "", "", "", "", "")
        val reply = PDFView.ManagedPdfComment(0, root.subtype, "reply", root.pdfRects, root.color, "Reply", "", "", "", "root", "")
        view.setManagedPdfComments(listOf(root, continuation, reply))
        assertEquals(1, bubbleCount(draw(view, "drawPdfCommentMarkers")))
        view.setManagedPdfComments(listOf(record(contents=""), reply))
        assertEquals("A thread with a reply still has its root bubble", 1, bubbleCount(draw(view, "drawPdfCommentMarkers")))
    }

    @Test fun translucentHighlightStaysPaleOnOpaqueSurface() = withViewer { view ->
        view.setManagedPdfComments(listOf(record()))
        val method = PDFView::class.java.getDeclaredMethod("managedPdfRectToDocument", Int::class.javaPrimitiveType, RectF::class.java)
            .apply { isAccessible = true }
        val bounds = method.invoke(view, 0, record().pdfRects.first()) as RectF
        for (config in listOf(Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565)) {
            val bitmap = draw(view, "drawPdfCommentMarkers", config)
            val color = bitmap.getPixel(bounds.centerX().toInt(), bounds.centerY().toInt())
            assertTrue("Pale yellow on $config, actual ${Integer.toHexString(color)}",
                Color.red(color) >= 235 && Color.green(color) >= 220 && Color.blue(color) >= 160)
        }
    }

    @Test fun overlappingRegionsDoNotDarkenTheSameHighlight() = withViewer { view ->
        val rect = RectF(100f, 600f, 400f, 620f)
        fun sample(rects: List<RectF>): Int {
            view.setManagedPdfComments(listOf(record(rects = rects)))
            val method = PDFView::class.java.getDeclaredMethod("managedPdfRectToDocument", Int::class.javaPrimitiveType, RectF::class.java)
                .apply { isAccessible = true }
            val bounds = method.invoke(view, 0, rect) as RectF
            return draw(view, "drawPdfCommentMarkers").getPixel(bounds.centerX().toInt(), bounds.centerY().toInt())
        }
        assertEquals(sample(listOf(rect)), sample(List(4) { RectF(rect) }))
    }

    @Test fun textRemainsReadableWhenSelectedInLightAndNightModes() = withViewer { view ->
        val comment = record()
        view.setManagedPdfComments(listOf(comment))
        val transform = PDFView::class.java.getDeclaredMethod("managedPdfRectToDocument", Int::class.javaPrimitiveType, RectF::class.java)
            .apply { isAccessible = true }
        val bounds = transform.invoke(view, 0, comment.pdfRects.first()) as RectF
        val render = PDFView::class.java.getDeclaredMethod("drawPdfCommentMarkers", Canvas::class.java, Set::class.java)
            .apply { isAccessible = true }
        for (night in listOf(false, true)) for (selected in listOf(false, true)) {
            view.setNightMode(night)
            view.setSelectedPdfComment(if (selected) PDFView.PdfComment(0, -1, comment.subtype, comment.name,
                null, comment.pdfRects.first(), comment.contents, "", "", "", "") else null)
            val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (night) Color.BLACK else Color.WHITE)
            val ink = android.graphics.Paint().apply { color = if (night) Color.WHITE else Color.BLACK }
            canvas.drawRect(bounds.left, bounds.top, bounds.centerX(), bounds.bottom, ink)
            render.invoke(view, canvas, setOf(0))
            val textPixel = bitmap.getPixel((bounds.left + bounds.width()*.25f).toInt(), bounds.centerY().toInt())
            val background = bitmap.getPixel((bounds.left + bounds.width()*.75f).toInt(), bounds.centerY().toInt())
            assertEquals(255, Color.alpha(textPixel))
            assertEquals(255, Color.alpha(background))
            assertTrue("Readable ink: night=$night selected=$selected",
                kotlin.math.abs(Color.red(textPixel) - Color.red(background)) >= 160)
        }
    }

    /** Samples full page coordinates, so the checks remain valid at different zooms. */
    private fun normalizedPixel(view: PDFView, bitmap: Bitmap, x: Float, y: Float): Int {
        val transform = PDFView::class.java.getDeclaredMethod("normalizedTopLeftRectToDocument",
            Int::class.javaPrimitiveType, RectF::class.java).apply { isAccessible = true }
        val bounds = transform.invoke(view, 0, RectF(x, y, x + .0001f, y + .0001f)) as RectF
        return bitmap.getPixel(bounds.left.toInt(), bounds.top.toInt())
    }

    private val lineWords get() = listOf(RectF(.1f,.2f,.16f,.22f), RectF(.17f,.2f,.23f,.22f),
        RectF(.1f,.24f,.16f,.26f), RectF(.4f,.2f,.46f,.22f))

    private fun assertContinuousLines(view: PDFView, bitmap: Bitmap) {
        val word = normalizedPixel(view, bitmap, .13f, .21f)
        assertNotEquals("Word is painted", Color.WHITE, word)
        assertEquals("Inter-word space has exactly the same fill", word,
            normalizedPixel(view, bitmap, .165f, .21f))
        assertEquals("Line break stays clear", Color.WHITE, normalizedPixel(view, bitmap, .13f, .23f))
        assertEquals("Distant region stays separate", Color.WHITE, normalizedPixel(view, bitmap, .32f, .21f))
    }

    @Test fun managedWordsAndFragmentsFormContinuousLinesAtBothZooms() = withViewer { view ->
        val size = view.getPagePointSize(0)
        val words = lineWords.map { RectF(it.left*size.width, (1-it.bottom)*size.height,
            it.right*size.width, (1-it.top)*size.height) }
        // Separate persisted fragments belonging to the same logical comment.
        view.setManagedPdfComments(words.mapIndexed { i, rect -> PDFView.ManagedPdfComment(0,
            PDFView.PdfComment.SUBTYPE_HIGHLIGHT, if (i == 0) "root" else PDFView.managedCommentFragmentName("root", i),
            listOf(rect), Color.YELLOW, "", "", "", "", "", "") })
        for (zoom in listOf(1f, 2f)) {
            view.zoomTo(zoom)
            assertContinuousLines(view, draw(view, "drawPdfCommentMarkers"))
        }
        assertEquals("Source word regions are retained", 4, words.size)
    }

    @Test fun fallbackWordsUseTheSameContinuousLines() = withViewer { view ->
        view.setLogicalCommentOverlays(lineWords.map { PDFView.LogicalCommentOverlay("root", 0,
            it.left,it.top,it.right,it.bottom,false,false,Float.NaN,Float.NaN,false,Color.YELLOW) })
        for (zoom in listOf(1f, 2f)) {
            view.zoomTo(zoom)
            assertContinuousLines(view, draw(view, "drawLogicalCommentMarker"))
        }
    }

    @Test fun proposalsJoinSpacesForBothRolesAndSelectionStates() = withViewer { view ->
        val size = view.getPagePointSize(0)
        for (role in listOf("before", "after")) for (selected in listOf(false, true)) {
            val overlays = lineWords.map { PDFView.AgentProposalOverlay("proposal", role, 0,
                it.left*size.width,it.top*size.height,it.right*size.width,it.bottom*size.height,selected) }
            // Duplicate source regions must not compound opacity.
            view.setAgentProposalOverlays(overlays + overlays.first())
            for (zoom in listOf(1f, 2f)) {
                view.zoomTo(zoom)
                assertContinuousLines(view, draw(view, "drawAgentProposalOverlays"))
            }
        }
    }

    @Test fun adjacentIndependentProposalsAndCommentsDoNotJoin() = withViewer { view ->
        val size = view.getPagePointSize(0)
        view.setAgentProposalOverlays(lineWords.take(2).mapIndexed { i, r ->
            PDFView.AgentProposalOverlay("proposal-$i", "after", 0,
                r.left*size.width,r.top*size.height,r.right*size.width,r.bottom*size.height,true) })
        assertEquals(Color.WHITE, normalizedPixel(view, draw(view, "drawAgentProposalOverlays"), .165f, .21f))
        view.setLogicalCommentOverlays(lineWords.take(2).mapIndexed { i, r -> PDFView.LogicalCommentOverlay("root-$i", 0,
            r.left,r.top,r.right,r.bottom,false,false,Float.NaN,Float.NaN,false,Color.YELLOW) })
        assertEquals(Color.WHITE, normalizedPixel(view, draw(view, "drawLogicalCommentMarker"), .165f, .21f))
    }

    /**
     * Marker chrome pixels: the pencil ink and outline. The threshold is loose
     * because the outline is a thin anti-aliased stroke, which never reaches
     * full darkness at high zoom, while these draws paint nothing else.
     */
    private fun markerPixels(bitmap: Bitmap) = buildList {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val c = bitmap.getPixel(x, y)
            if ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3 < 150) add(x to y)
        }
    }

    /**
     * The proposals that own drawn marker ink.
     *
     * <p>Counting connected ink components is unreliable: a marker's outline is
     * a thin anti-aliased stroke that breaks up at high zoom. Instead each inked
     * pixel is attributed through the viewer's own marker hit geometry, and the
     * distinct owners are the markers actually on screen. Anti-aliasing inside a
     * marker cannot invent an owner, and a marker drawn twice would still report
     * one owner, so pair this with the layout count when duplication matters.
     */
    private fun drawnMarkerOwners(view: PDFView, bitmap: Bitmap): Set<String> =
        markerPixels(bitmap).mapNotNull {
            view.agentProposalMarkerAt(it.first + view.currentXOffset, it.second + view.currentYOffset)
        }.toSet()

    /** Marker rectangles the viewer will draw, straight from its own layout. */
    private fun markerLayout(view: PDFView): List<Pair<String, Int>> {
        val markers = PDFView::class.java.getDeclaredMethod("agentProposalMarkers")
            .apply { isAccessible = true }.invoke(view) as List<*>
        return markers.map { marker ->
            val markerClass = marker!!.javaClass
            val id = markerClass.getDeclaredField("proposalId").apply { isAccessible = true }.get(marker) as String
            val page = markerClass.getDeclaredField("pageIndex").apply { isAccessible = true }.getInt(marker)
            id to page
        }
    }

    private fun firstMarkerBounds(view: PDFView): RectF {
        val markers = PDFView::class.java.getDeclaredMethod("agentProposalMarkers")
            .apply { isAccessible = true }.invoke(view) as List<*>
        val marker = markers.single()!!
        val bounds = marker.javaClass.getDeclaredField("bounds")
            .apply { isAccessible = true }.get(marker)
        fun edge(name: String) = bounds.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.getFloat(bounds)
        return RectF(edge("left"), edge("top"), edge("right"), edge("bottom"))
    }

    private fun assertMarkerFollowsFirstFragment(
        view: PDFView,
        crop: com.github.barteksc.pdfviewer.model.CropBounds = com.github.barteksc.pdfviewer.model.CropBounds.fullPage(),
    ) {
        val size = view.getPagePointSize(0)
        for (zoom in listOf(1f, 2f)) {
            view.zoomTo(zoom)
            view.setAgentProposalOverlays(listOf(
                PDFView.AgentProposalOverlay("p", "after", 0,
                    size.width*.6f, size.height*.7f, size.width*.7f, size.height*.72f, false),
                PDFView.AgentProposalOverlay("p", "before", 0,
                    size.width*.3f, size.height*.3f, size.width*.4f, size.height*.32f, false)))
            val marker = firstMarkerBounds(view)
            val page = PDFView::class.java.getDeclaredMethod("pageDocumentBounds", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(view, 0) as RectF
            val expectedLeft = page.left + (.3f - crop.left) / crop.width * page.width()
            val expectedTop = page.top + (.3f - crop.top) / crop.height * page.height()
            assertEquals("First fragment's horizontal anchor at zoom $zoom", expectedLeft, marker.left, .1f)
            assertEquals("Marker directly above first fragment at zoom $zoom", expectedTop - 4f, marker.bottom, .1f)
            assertEquals("p", view.agentProposalMarkerAt(
                marker.centerX() + view.currentXOffset, marker.centerY() + view.currentYOffset))
        }
    }

    @Test fun markerUsesTopOriginAndFirstFragment() = withViewer {
        assertMarkerFollowsFirstFragment(it)
    }

    @Test fun markerFollowsFragmentWithAsymmetricMarginCrop() = withViewer(
        com.github.barteksc.pdfviewer.model.CropBounds.of(.1f, .15f, .85f, .9f)
    ) {
        assertMarkerFollowsFirstFragment(it,
            com.github.barteksc.pdfviewer.model.CropBounds.of(.1f, .15f, .85f, .9f))
    }

    @Test fun onePencilMarkerPerProposalAnchoredAtItsFirstDocumentFragment() = withViewer { view ->
        val size = view.getPagePointSize(0)
        // Three fragments of one proposal, including a duplicate: still one marker.
        // The anchor stays near the page's left edge so it is on screen at both zooms.
        for (zoom in listOf(1f, 2f)) {
            view.zoomTo(zoom)
            view.setAgentProposalOverlays(listOf(
                PDFView.AgentProposalOverlay("proposal", "after", 0,
                    size.width*.5f, size.height*.6f, size.width*.6f, size.height*.62f, false),
                PDFView.AgentProposalOverlay("proposal", "before", 0,
                    size.width*.05f, size.height*.3f, size.width*.15f, size.height*.32f, false),
                PDFView.AgentProposalOverlay("proposal", "after", 0,
                    size.width*.5f, size.height*.6f, size.width*.6f, size.height*.62f, false)))
            assertEquals("Zoom $zoom: one marker for the proposal",
                listOf("proposal" to 0), markerLayout(view))
            val bitmap = draw(view, "drawAgentProposalOverlays")
            assertEquals("Zoom $zoom: the marker is drawn",
                setOf("proposal"), drawnMarkerOwners(view, bitmap))
            bitmap.recycle()
        }
    }

    @Test fun markerAnchorsAboveTheFirstFragmentNotTheOneFirstInInputOrder() = withViewer { view ->
        val size = view.getPagePointSize(0)
        view.zoomTo(1f)
        // The lower fragment comes first in input order; the higher one is first
        // in document order and must win the anchor.
        view.setAgentProposalOverlays(listOf(
            PDFView.AgentProposalOverlay("proposal", "after", 0,
                size.width*.05f, size.height*.7f, size.width*.15f, size.height*.72f, false),
            PDFView.AgentProposalOverlay("proposal", "before", 0,
                size.width*.05f, size.height*.2f, size.width*.15f, size.height*.22f, false)))
        val bitmap = draw(view, "drawAgentProposalOverlays")
        val inked = markerPixels(bitmap).filter {
            view.agentProposalMarkerAt(it.first + view.currentXOffset, it.second + view.currentYOffset) == "proposal"
        }
        assertTrue("Marker ink is present", inked.isNotEmpty())
        val markerTop = inked.minOf { it.second }
        // The higher fragment's top is at 20% of a 1035px-tall page; the lower
        // one is at 70%. The marker must sit with the upper fragment.
        assertTrue("Marker anchored high on the page, top was $markerTop",
            markerTop < view.height * 0.4f)
        bitmap.recycle()
    }

    @Test fun twoProposalsOnOnePageKeepTheirOwnMarkers() = withViewer { view ->
        val size = view.getPagePointSize(0)
        view.zoomTo(1f)
        view.setAgentProposalOverlays(listOf("one", "two").map {
            PDFView.AgentProposalOverlay(it, "after", 0,
                size.width*.2f, size.height*.3f, size.width*.3f, size.height*.32f, false)
        } + PDFView.AgentProposalOverlay("two", "after", 0,
                size.width*.2f, size.height*.7f, size.width*.3f, size.height*.72f, false))
        assertEquals("Each proposal keeps one marker",
            listOf("one" to 0, "two" to 0), markerLayout(view).sortedBy { it.first })
        val bitmap = draw(view, "drawAgentProposalOverlays")
        assertEquals("Both markers are drawn, each owning its own tap",
            setOf("one", "two"), drawnMarkerOwners(view, bitmap))
        bitmap.recycle()
    }

    @Test fun pencilMarkerStaysTappableWhenZoomedOutAndHitsItsOwnProposal() = withViewer { view ->
        val size = view.getPagePointSize(0)
        view.zoomTo(1f)
        view.setAgentProposalOverlays(listOf(
            PDFView.AgentProposalOverlay("proposal", "after", 0,
                size.width*.2f, size.height*.35f, size.width*.3f, size.height*.37f, false)))
        val bitmap = draw(view, "drawAgentProposalOverlays")
        val ink = markerPixels(bitmap).filter {
            view.agentProposalMarkerAt(it.first + view.currentXOffset, it.second + view.currentYOffset) == "proposal"
        }
        assertTrue("A marker is drawn", ink.isNotEmpty())
        val centerX = ink.map { it.first }.average().toFloat()
        val centerY = ink.map { it.second }.average().toFloat()
        assertEquals("proposal", view.agentProposalMarkerAt(
            centerX + view.currentXOffset, centerY + view.currentYOffset))
        // The touch target is bounded: it does not reach far across the page.
        assertNull(view.agentProposalMarkerAt(
            centerX + view.currentXOffset - 120f * view.resources.displayMetrics.density,
            centerY + view.currentYOffset))
        bitmap.recycle()
    }

    @Test fun markerStaysAnchoredOnTheFirstPageAfterScrollingToALaterFragment() = withViewer { view ->
        if (view.pageCount < 2) return@withViewer
        val size = view.getPagePointSize(0)
        view.zoomTo(1f)
        // The proposal spans page 0 then page 1, so its anchor is on page 0.
        view.setAgentProposalOverlays(listOf(
            PDFView.AgentProposalOverlay("proposal", "after", 0,
                size.width*.05f, size.height*.2f, size.width*.15f, size.height*.22f, false),
            PDFView.AgentProposalOverlay("proposal", "after", 1,
                size.width*.05f, size.height*.2f, size.width*.15f, size.height*.22f, false)))

        assertEquals("One marker for the whole proposal, anchored on page 0",
            listOf("proposal" to 0), markerLayout(view))
        val onFirstPage = draw(view, "drawAgentProposalOverlays")
        assertEquals("The first page draws the marker",
            setOf("proposal"), drawnMarkerOwners(view, onFirstPage))
        onFirstPage.recycle()

        // Scrolling to the later fragment must not move or duplicate the marker.
        view.jumpTo(1)
        assertEquals("Scrolling never duplicates or re-anchors the marker",
            listOf("proposal" to 0), markerLayout(view))
        view.jumpTo(0)
    }

    @Test fun nativeHighlightAndSelectedOutlineUseWholeLines() = withViewer { view ->
        view.enableAnnotationRendering(true)
        val size = view.getPagePointSize(0)
        val words = lineWords.map { RectF(it.left*size.width, (1-it.bottom)*size.height,
            it.right*size.width, (1-it.top)*size.height) }
        val file = PDFView::class.java.getDeclaredField("pdfFile").apply { isAccessible = true }.get(view)
        val cacheField = file.javaClass.getDeclaredField("highlightAnnotationCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(file) as MutableMap<Int, List<com.shockwave.pdfium.PdfDocument.HighlightAnnotation>>
        cache[0] = words.mapIndexed { i, rect -> com.shockwave.pdfium.PdfDocument.HighlightAnnotation(
            i, "root", rect, "Word", Color.YELLOW, true) }
        view.setSelectedHighlightAnnotation(PDFView.HighlightAnnotation(0, 0, "root", null, words.first(), "Word"))
        val selected = PDFView::class.java.getDeclaredMethod("drawSelectedHighlightAnnotation", Canvas::class.java)
            .apply { isAccessible = true }
        for (zoom in listOf(1f, 2f)) {
            view.zoomTo(zoom)
            assertContinuousLines(view, draw(view, "drawHighlightAnnotationOverlays"))
            val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            selected.invoke(view, canvas)
            assertContinuousLines(view, bitmap)
            assertEquals("No internal outline between adjacent words",
                normalizedPixel(view, bitmap, .13f, .21f), normalizedPixel(view, bitmap, .16f, .21f))
        }
    }

    @Test fun benchmarkCommentDrawingWhenRequested() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("measureRendering") == "true")
        withViewer { view ->
            val render = PDFView::class.java.getDeclaredMethod("drawPdfCommentMarkers", Canvas::class.java, Set::class.java)
                .apply { isAccessible = true }
            val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val results = mutableListOf<String>()
            for (count in listOf(1, 20)) {
                val comments = (0 until count).map { index ->
                    val regions = (0 until 27).map { word ->
                        val left = 70f + (word % 9) * 48f
                        val bottom = 650f - (word / 9) * 18f - index * 3f
                        RectF(left, bottom, left + 40f, bottom + 12f)
                    }
                    PDFView.ManagedPdfComment(0, PDFView.PdfComment.SUBTYPE_HIGHLIGHT, "root-$index", regions,
                        Color.YELLOW, "Comment", "", "", "", "", "")
                }
                view.setManagedPdfComments(comments)
                val times = LongArray(200)
                for (frame in -30 until times.size) {
                    canvas.drawColor(Color.WHITE)
                    val started = System.nanoTime()
                    render.invoke(view, canvas, setOf(0))
                    if (frame >= 0) times[frame] = System.nanoTime() - started
                }
                times.sort()
                results += "comments=$count regions=${count*27} median_ms=${times[100]/1e6} p95_ms=${times[190]/1e6}"
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(context.getExternalFilesDir(null), "comment-render-benchmark.txt").writeText(results.joinToString("\n"))
            results.forEach { android.util.Log.i("CommentRenderBenchmark", it) }
        }
    }
}

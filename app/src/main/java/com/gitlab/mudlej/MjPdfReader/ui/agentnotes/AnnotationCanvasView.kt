// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AnnotationDocument
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AnnotationMarker
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AnnotationStroke
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteLink
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AnnotationTool
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.StrokePointsBuilder
import java.util.IdentityHashMap
import kotlin.math.hypot

/**
 * Shows an agent note's image fit to width and lets one finger draw on it (pen, highlighter, or a
 * stroke eraser) while two fingers pan and zoom (1x to 6x). Touches are mapped through the inverse
 * view matrix, so every stroke is stored in image pixels. In [readOnly] mode one finger pans. In
 * [Mode.SCROLL] one finger pans too, and pressing still for a moment and then dragging highlights
 * (as selecting text does in a reader). In [Mode.NUMBER] a tap drops the next numbered marker
 * (1, 2, 3, ...) and a drag pans. A tap that does not move in [Mode.SCROLL] or [readOnly] on one of
 * the note's [links] opens it (through [Listener.onLinkTapped]).
 */
class AnnotationCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class Mode { SCROLL, PEN, HIGHLIGHTER, ERASER, NUMBER }

    interface Listener {
        fun onDocumentChanged()
        fun onLimitReached()
        /** A tap on one of [links]: the user wants its source. */
        fun onLinkTapped(link: AgentNoteLink) {}
    }

    var listener: Listener? = null
    var mode = Mode.PEN
        set(value) {
            finishGesture(commit = true)
            field = value
        }

    /** 0xRRGGBB of the pen, highlighter and numbered markers. */
    var color: Int = 0x000000
    var readOnly = false

    /** Rectangles of the image that open a source when tapped (the note's links). */
    var links: List<AgentNoteLink> = emptyList()
        set(value) {
            field = value
            pressedLink = null
            invalidate()
        }
    /** The link just tapped, shaded for a moment so the user sees what opens. */
    private var pressedLink: AgentNoteLink? = null
    private val releaseLink = Runnable {
        pressedLink = null
        invalidate()
    }

    var bitmap: Bitmap? = null
        private set
    var document: AnnotationDocument? = null
        private set

    private val density = resources.displayMetrics.density
    private val eraserRadiusPx = 14f * density
    /** A [Mode.NUMBER] touch that moves less than this is a tap (a marker); more is a pan. */
    private val tapSlopPx = 10f * density
    /** In [Mode.SCROLL], a finger held this long without moving starts a highlighter stroke. */
    private val holdToHighlightMs = 300L
    /** A tap this close to a link (on screen) still opens it, so small references are easy to hit. */
    private val linkSlopPx = 10f * density
    private val holdToHighlight = Runnable { startHeldHighlight() }
    /** The tool of the stroke in progress: the mode's, or the highlighter for a hold in [Mode.SCROLL]. */
    private var strokeTool = AnnotationTool.PEN

    private var userScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var positioned = false
    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val mapped = FloatArray(2)

    private enum class Gesture { NONE, PRESS, DRAW, ERASE, MARK, PAN, TRANSFORM, IGNORE }

    private var gesture = Gesture.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var builder: StrokePointsBuilder? = null
    private var livePoints: FloatArray? = null
    private var lastImageX = 0f
    private var lastImageY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var eraserVisible = false

    // Two-finger transform, relative to where it started.
    private var startDistance = 1f
    private var startScale = 1f
    private var startFocusImageX = 0f
    private var startFocusImageY = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val strokePaint = newStrokePaint()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.GRAY
    }
    private val eraserFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(48, 128, 128, 128)
    }
    private val linkFill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(64, 8, 127, 117)
    }
    private val markerFill = newMarkerFill()
    private val markerOutline = newMarkerOutline()
    private val markerText = newMarkerText()
    private val pathCache = IdentityHashMap<AnnotationStroke, Path>()
    private var cachedRevision = -1L

    fun setContent(bitmap: Bitmap, document: AnnotationDocument) {
        this.bitmap = bitmap
        this.document = document
        pathCache.clear()
        cachedRevision = -1L
        positioned = false
        userScale = 1f
        gesture = Gesture.NONE
        positionContent()
        invalidate()
    }

    /** Called after undo/redo from outside. */
    fun documentChanged() {
        invalidate()
    }

    private fun baseScale(): Float {
        val image = bitmap ?: return 1f
        if (width == 0 || image.width == 0) return 1f
        return width.toFloat() / image.width
    }

    private fun totalScale() = baseScale() * userScale

    private fun positionContent() {
        val image = bitmap ?: return
        if (width == 0 || height == 0) return
        if (!positioned) {
            offsetX = 0f
            offsetY = 0f
            positioned = true
        }
        val scale = totalScale()
        val contentWidth = image.width * scale
        val contentHeight = image.height * scale
        offsetX = if (contentWidth <= width) (width - contentWidth) / 2f else offsetX.coerceIn(width - contentWidth, 0f)
        offsetY = if (contentHeight <= height) (height - contentHeight) / 2f else offsetY.coerceIn(height - contentHeight, 0f)
        viewMatrix.setScale(scale, scale)
        viewMatrix.postTranslate(offsetX, offsetY)
        viewMatrix.invert(inverseMatrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && bitmap != null) {
            // Keep the image row at the top of the view where it was.
            val topImageY = -offsetY / (baseScaleFor(oldw) * userScale)
            offsetY = -topImageY * baseScale() * userScale
        }
        positionContent()
    }

    private fun baseScaleFor(viewWidth: Int): Float = bitmap?.takeIf { it.width > 0 }?.let { viewWidth.toFloat() / it.width } ?: 1f

    private fun toImage(x: Float, y: Float): FloatArray {
        mapped[0] = x
        mapped[1] = y
        inverseMatrix.mapPoints(mapped)
        return mapped
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val document = document ?: return false
        if (bitmap == null || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                downTouchX = event.x
                downTouchY = event.y
                val point = toImage(event.x, event.y)
                lastImageX = point[0]
                lastImageY = point[1]
                gesture = when {
                    // A pan, or a tap on a link: decided when the finger moves or lifts.
                    readOnly -> Gesture.PRESS
                    // A pan, or a highlight if the finger stays put: decided when it moves or the hold ends.
                    mode == Mode.SCROLL -> {
                        postDelayed(holdToHighlight, holdToHighlightMs)
                        Gesture.PRESS
                    }
                    // A tap or a pan: decided when the finger moves or lifts.
                    mode == Mode.NUMBER -> Gesture.MARK
                    mode == Mode.ERASER -> {
                        document.beginErase()
                        eraserVisible = true
                        eraseTo(event.x, event.y)
                        Gesture.ERASE
                    }
                    else -> {
                        strokeTool = if (mode == Mode.HIGHLIGHTER) AnnotationTool.HIGHLIGHTER else AnnotationTool.PEN
                        builder = StrokePointsBuilder().also { it.add(point[0], point[1]) }
                        livePoints = builder?.snapshot()
                        Gesture.DRAW
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(holdToHighlight)
                if (event.pointerCount == 2) {
                    // A second finger: the stroke in progress was the start of a pan or zoom.
                    finishGesture(commit = gesture == Gesture.ERASE)
                    beginTransform(event)
                    gesture = Gesture.TRANSFORM
                } else {
                    gesture = Gesture.IGNORE
                }
            }
            MotionEvent.ACTION_MOVE -> when (gesture) {
                Gesture.DRAW -> {
                    val index = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: return true
                    val builder = builder ?: return true
                    for (h in 0 until event.historySize) {
                        val point = toImage(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                        builder.add(point[0], point[1])
                    }
                    val point = toImage(event.getX(index), event.getY(index))
                    builder.add(point[0], point[1])
                    livePoints = builder.snapshot()
                    invalidate()
                }
                Gesture.ERASE -> {
                    val index = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: return true
                    for (h in 0 until event.historySize) eraseTo(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                    eraseTo(event.getX(index), event.getY(index))
                    invalidate()
                }
                Gesture.MARK, Gesture.PRESS -> {
                    val index = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: return true
                    if (hypot(event.getX(index) - downTouchX, event.getY(index) - downTouchY) > tapSlopPx) {
                        removeCallbacks(holdToHighlight)
                        // A drag: pan from where the finger went down, so the image follows it.
                        gesture = Gesture.PAN
                        offsetX += event.getX(index) - lastTouchX
                        offsetY += event.getY(index) - lastTouchY
                        lastTouchX = event.getX(index)
                        lastTouchY = event.getY(index)
                        positionContent()
                        invalidate()
                    }
                }
                Gesture.PAN -> {
                    val index = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: return true
                    offsetX += event.getX(index) - lastTouchX
                    offsetY += event.getY(index) - lastTouchY
                    lastTouchX = event.getX(index)
                    lastTouchY = event.getY(index)
                    positionContent()
                    invalidate()
                }
                Gesture.TRANSFORM -> if (event.pointerCount >= 2) transform(event)
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesture == Gesture.TRANSFORM && (readOnly || mode == Mode.SCROLL || mode == Mode.NUMBER) && event.pointerCount == 2) {
                    // Continue panning with the finger that stays.
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(remaining)
                    lastTouchX = event.getX(remaining)
                    lastTouchY = event.getY(remaining)
                    gesture = Gesture.PAN
                } else if (gesture == Gesture.TRANSFORM && event.pointerCount > 2) {
                    val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                    beginTransform(event, remaining[0], remaining[1])
                } else if (gesture == Gesture.TRANSFORM) {
                    gesture = Gesture.IGNORE
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdToHighlight)
                if (gesture == Gesture.MARK) addMarker()
                if (gesture == Gesture.PRESS) linkAt(downTouchX, downTouchY)?.let(::tapLink)
                finishGesture(commit = true)
                gesture = Gesture.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdToHighlight)
                finishGesture(commit = gesture == Gesture.ERASE)
                gesture = Gesture.NONE
            }
        }
        return true
    }

    /** The finger stayed put in [Mode.SCROLL]: the touch becomes a highlighter stroke from where it went down. */
    private fun startHeldHighlight() {
        if (gesture != Gesture.PRESS || mode != Mode.SCROLL || readOnly || document == null) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        strokeTool = AnnotationTool.HIGHLIGHTER
        val point = toImage(downTouchX, downTouchY)
        builder = StrokePointsBuilder().also { it.add(point[0], point[1]) }
        livePoints = builder?.snapshot()
        gesture = Gesture.DRAW
        invalidate()
    }

    /** The link under the screen point ([x], [y]), with [linkSlopPx] around each, or null. */
    private fun linkAt(x: Float, y: Float): AgentNoteLink? {
        val image = bitmap ?: return null
        if (links.isEmpty() || image.width == 0 || image.height == 0) return null
        val point = toImage(x, y)
        val slack = linkSlopPx / totalScale()
        return AgentNoteLink.at(links, point[0] / image.width, point[1] / image.height, slack / image.width, slack / image.height)
    }

    private fun tapLink(link: AgentNoteLink) {
        removeCallbacks(releaseLink)
        pressedLink = link
        invalidate()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        postDelayed(releaseLink, 250L)
        listener?.onLinkTapped(link)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(releaseLink)
        removeCallbacks(holdToHighlight)
        super.onDetachedFromWindow()
    }

    private fun eraseTo(x: Float, y: Float) {
        val document = document ?: return
        val point = toImage(x, y)
        val radius = eraserRadiusPx / totalScale()
        val removed = document.eraseSegment(lastImageX, lastImageY, point[0], point[1], radius)
        lastImageX = point[0]
        lastImageY = point[1]
        lastTouchX = x
        lastTouchY = y
        if (removed > 0) listener?.onDocumentChanged()
    }

    /** Drops the next numbered marker where the finger went down. */
    private fun addMarker() {
        val document = document ?: return
        val point = toImage(downTouchX, downTouchY)
        // A tap beside the image (letterboxing) adds nothing.
        if (point[0] !in 0f..document.imageWidth.toFloat() || point[1] !in 0f..document.imageHeight.toFloat()) return
        if (document.addMarker(point[0], point[1], color) != null) {
            listener?.onDocumentChanged()
        } else {
            listener?.onLimitReached()
        }
        invalidate()
    }

    private fun beginTransform(event: MotionEvent, first: Int = 0, second: Int = 1) {
        val fx = (event.getX(first) + event.getX(second)) / 2f
        val fy = (event.getY(first) + event.getY(second)) / 2f
        startDistance = hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second)).coerceAtLeast(1f)
        startScale = userScale
        val focus = toImage(fx, fy)
        startFocusImageX = focus[0]
        startFocusImageY = focus[1]
    }

    private fun transform(event: MotionEvent) {
        val fx = (event.getX(0) + event.getX(1)) / 2f
        val fy = (event.getY(0) + event.getY(1)) / 2f
        val distance = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)).coerceAtLeast(1f)
        userScale = (startScale * distance / startDistance).coerceIn(MIN_SCALE, MAX_SCALE)
        // The image point that was under the fingers' midpoint stays under it.
        val scale = totalScale()
        offsetX = fx - startFocusImageX * scale
        offsetY = fy - startFocusImageY * scale
        positionContent()
        invalidate()
    }

    /** Ends the stroke or erasure in progress; a stroke is added only when [commit]. */
    private fun finishGesture(commit: Boolean) {
        val document = document
        when (gesture) {
            Gesture.DRAW -> {
                val points = builder?.build()
                if (commit && document != null && points != null && points.isNotEmpty()) {
                    val stroke = newStroke(points)
                    if (document.add(stroke)) listener?.onDocumentChanged() else listener?.onLimitReached()
                }
            }
            Gesture.ERASE -> {
                document?.endErase()
                listener?.onDocumentChanged()
            }
            else -> Unit
        }
        builder = null
        livePoints = null
        eraserVisible = false
        invalidate()
    }

    private fun newStroke(points: FloatArray): AnnotationStroke {
        val image = bitmap!!
        val pen = AnnotationDocument.penWidthFor(image.width)
        return if (strokeTool == AnnotationTool.HIGHLIGHTER) {
            AnnotationStroke(AnnotationTool.HIGHLIGHTER, color, pen * AnnotationDocument.HIGHLIGHTER_WIDTH_FACTOR,
                AnnotationDocument.HIGHLIGHTER_OPACITY, points)
        } else {
            AnnotationStroke(AnnotationTool.PEN, color, pen, 1f, points)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val document = document ?: return
        if (cachedRevision != document.revision) {
            // Drop the paths of strokes no longer present.
            val present = document.strokes.toHashSet()
            pathCache.keys.retainAll { it in present }
            cachedRevision = document.revision
        }
        canvas.save()
        canvas.concat(viewMatrix)
        canvas.drawBitmap(image, 0f, 0f, bitmapPaint)
        val live = livePoints?.takeIf { gesture == Gesture.DRAW }?.let(::newStroke)
        drawStrokes(canvas, document.strokes, live, strokePaint, dotPaint, pathCache)
        drawMarkers(canvas, document.markers, document.markerRadius, markerFill, markerOutline, markerText)
        pressedLink?.let { link ->
            canvas.drawRect(link.left * image.width, link.top * image.height, (link.left + link.width) * image.width,
                (link.top + link.height) * image.height, linkFill)
        }
        canvas.restore()
        if (eraserVisible) {
            canvas.drawCircle(lastTouchX, lastTouchY, eraserRadiusPx, eraserFill)
            canvas.drawCircle(lastTouchX, lastTouchY, eraserRadiusPx, eraserPaint)
        }
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f

        private fun newStrokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private fun newMarkerFill() = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        private fun newMarkerOutline() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }

        private fun newMarkerText() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        /**
         * Numbered markers on top of the strokes, in image coordinates: a filled circle of
         * [radius] in the marker's colour, a thin white outline (visible on dark ink) and the
         * number in bold white, centred.
         */
        fun drawMarkers(
            canvas: Canvas,
            markers: List<AnnotationMarker>,
            radius: Float,
            fill: Paint = newMarkerFill(),
            outline: Paint = newMarkerOutline(),
            text: Paint = newMarkerText(),
        ) {
            if (markers.isEmpty()) return
            outline.strokeWidth = radius * 0.12f
            for (marker in markers) {
                fill.color = Color.rgb(Color.red(marker.color), Color.green(marker.color), Color.blue(marker.color))
                canvas.drawCircle(marker.x, marker.y, radius, fill)
                canvas.drawCircle(marker.x, marker.y, radius, outline)
                val label = marker.number.toString()
                text.textSize = radius * when (label.length) {
                    1, 2 -> 1.15f
                    else -> 0.85f
                }
                // Centre the digits vertically on the circle's centre.
                val baseline = marker.y - (text.ascent() + text.descent()) / 2f
                canvas.drawText(label, marker.x, baseline, text)
            }
        }

        private fun pathOf(stroke: AnnotationStroke): Path = Path().apply {
            val p = stroke.points
            moveTo(p[0], p[1])
            var i = 2
            while (i < p.size) {
                lineTo(p[i], p[i + 1])
                i += 2
            }
        }

        /** Highlighters first, then pens on top, in image coordinates. */
        fun drawStrokes(
            canvas: Canvas,
            strokes: List<AnnotationStroke>,
            live: AnnotationStroke?,
            strokePaint: Paint = newStrokePaint(),
            dotPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL },
            cache: MutableMap<AnnotationStroke, Path>? = null,
        ) {
            for (tool in arrayOf(AnnotationTool.HIGHLIGHTER, AnnotationTool.PEN)) {
                for (stroke in strokes) if (stroke.tool == tool) drawStroke(canvas, stroke, strokePaint, dotPaint, cache)
                if (live != null && live.tool == tool) drawStroke(canvas, live, strokePaint, dotPaint, null)
            }
        }

        private fun drawStroke(canvas: Canvas, stroke: AnnotationStroke, paint: Paint, dotPaint: Paint, cache: MutableMap<AnnotationStroke, Path>?) {
            val argb = Color.argb((stroke.opacity * 255).toInt().coerceIn(0, 255),
                Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color))
            if (stroke.pointCount == 1) {
                dotPaint.color = argb
                canvas.drawCircle(stroke.points[0], stroke.points[1], stroke.width / 2f, dotPaint)
                return
            }
            paint.color = argb
            paint.strokeWidth = stroke.width
            val path = cache?.getOrPut(stroke) { pathOf(stroke) } ?: pathOf(stroke)
            canvas.drawPath(path, paint)
        }

        /** The note image with the strokes and markers drawn on it, at the image's (decoded) pixel size. */
        fun renderComposite(image: Bitmap, document: AnnotationDocument): Bitmap {
            val output = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(image, 0f, 0f, null)
            if (document.imageWidth != image.width || document.imageHeight != image.height) {
                canvas.scale(image.width.toFloat() / document.imageWidth, image.height.toFloat() / document.imageHeight)
            }
            drawStrokes(canvas, document.strokes, null)
            drawMarkers(canvas, document.markers, document.markerRadius)
            return output
        }
    }
}

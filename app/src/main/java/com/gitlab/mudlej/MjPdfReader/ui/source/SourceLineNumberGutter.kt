// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import com.google.android.material.color.MaterialColors

/**
 * Remembers how many line breaks precede one text offset, so the gutter can number the visible
 * lines of a long document without rescanning it. Edits adjust the cached point by only the
 * changed characters; moving the point costs only the distance moved (one screen when scrolling).
 */
internal class LogicalLineCache {
    var cachedOffset = 0
        private set
    var cachedLineIndex = 0
        private set
    private var insertionPrecedesPoint = false

    /** Call with the text before replacing [removed] characters at [start]. */
    fun beforeChange(text: CharSequence, start: Int, removed: Int) {
        insertionPrecedesPoint = false
        when {
            start == 0 && removed >= text.length -> reset()
            start >= cachedOffset -> Unit
            start + removed <= cachedOffset -> {
                cachedLineIndex -= text.countLineBreaks(start, removed)
                cachedOffset -= removed
                insertionPrecedesPoint = true
            }
            else -> {
                // The cached point is inside the replaced text: move it back to the change's start.
                cachedLineIndex -= text.countLineBreaks(start, cachedOffset - start)
                cachedOffset = start
            }
        }
    }

    /** Call with the text after inserting [inserted] characters at [start]. */
    fun afterChange(text: CharSequence, start: Int, inserted: Int) {
        if (insertionPrecedesPoint) {
            cachedLineIndex += text.countLineBreaks(start, inserted)
            cachedOffset += inserted
        }
        insertionPrecedesPoint = false
    }

    /** Zero-based source line containing [offset] (the number of line breaks before it). */
    fun lineIndexAt(text: CharSequence, offset: Int): Int {
        val target = offset.coerceIn(0, text.length)
        if (cachedOffset > text.length) reset()
        cachedLineIndex = if (target >= cachedOffset) {
            cachedLineIndex + text.countLineBreaks(cachedOffset, target - cachedOffset)
        } else {
            cachedLineIndex - text.countLineBreaks(target, cachedOffset - target)
        }
        cachedOffset = target
        return cachedLineIndex
    }

    fun reset() {
        cachedOffset = 0
        cachedLineIndex = 0
        insertionPrecedesPoint = false
    }
}

/**
 * Line numbers beside a wrapping [SourceEditText]. A number is drawn on the first visual row of
 * each source line, so wrapped continuation rows stay unnumbered and numbers never drift. Only
 * the rows inside the visible part of the scrolling page are drawn.
 */
class SourceLineNumberGutter @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val dividerPaint = Paint().apply { strokeWidth = resources.displayMetrics.density }
    private val cache = LogicalLineCache()
    private var editor: SourceEditText? = null
    private var digits = 2
    private val visible = Rect()
    private val drawn = Rect()
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence, start: Int, count: Int, after: Int) =
            cache.beforeChange(text, start, count)

        override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) {
            cache.afterChange(text, start, count)
            invalidate()
        }

        override fun afterTextChanged(text: Editable) = Unit
    }
    private val viewportListener = ViewTreeObserver.OnPreDrawListener {
        // The scrolling page reuses this view's drawing; redraw when other rows become visible.
        if (getLocalVisibleRect(visible) && visible != drawn) invalidate()
        true
    }

    init {
        paint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        dividerPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun attach(target: SourceEditText) {
        editor?.removeTextChangedListener(watcher)
        editor = target
        paint.textSize = target.textSize
        cache.reset()
        target.addTextChangedListener(watcher)
        target.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidate() }
        requestLayout()
    }

    /** The document's line count decides the gutter width; only a new digit relayouts. */
    fun setLineCount(lineCount: Int) {
        val needed = maxOf(2, lineCount.coerceAtLeast(1).toString().length)
        if (needed != digits) {
            digits = needed
            requestLayout()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(viewportListener)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(viewportListener)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = paint.measureText("0".repeat(digits)).toInt()
        val width = paddingLeft + textWidth + paddingRight
        // match_parent in the editor's row: the row remeasures this view at the editor's height.
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(0, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val right = width - paddingRight.toFloat()
        canvas.drawLine(width - dividerPaint.strokeWidth, 0f, width - dividerPaint.strokeWidth, height.toFloat(), dividerPaint)
        val target = editor ?: return
        val layout = target.layout ?: return
        val text = target.text ?: return
        if (!getLocalVisibleRect(visible)) return
        drawn.set(visible)
        // Editor and gutter share a parent row; convert this view's rows to editor layout rows.
        val layoutOffsetY = target.top - top + target.totalPaddingTop
        val firstRow = layout.getLineForVertical((visible.top - layoutOffsetY).coerceAtLeast(0))
        val lastRow = layout.getLineForVertical((visible.bottom - layoutOffsetY).coerceAtLeast(0))
        var lineNumber = -1
        for (row in firstRow..lastRow) {
            val rowStart = layout.getLineStart(row)
            val startsSourceLine = rowStart == 0 || (rowStart <= text.length && text[rowStart - 1] == '\n')
            if (!startsSourceLine) continue
            lineNumber = if (lineNumber < 0) cache.lineIndexAt(text, rowStart) + 1 else lineNumber + 1
            canvas.drawText(
                lineNumber.toString(),
                right,
                (layoutOffsetY + layout.getLineBaseline(row)).toFloat(),
                paint,
            )
        }
    }
}

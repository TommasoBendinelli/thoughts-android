// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.google.android.material.color.MaterialColors
import kotlin.math.abs

/**
 * The track of a slider tag group: [steps] ticks evenly spaced, each at the middle of an equal cell
 * so the step labels (one equal-width cell each, below) line up under them. An unset scale
 * ([selected] null) has no thumb. Tapping a tick passes its index to [onTap] (the sheet clears the
 * scale when it is the chosen one); dragging sideways moves the thumb through [onDrag].
 */
class ReviewScaleTrack @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var steps: Int = 0
        set(value) { field = value; invalidate() }
    var selected: Int? = null
        set(value) { field = value; invalidate() }
    var onTap: ((Int) -> Unit)? = null
    var onDrag: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val active = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val onActive = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
    private val inactive = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest)
    private val tick = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    /** The middle of step [index]'s cell. */
    fun centerOf(index: Int): Float = width * (index + 0.5f) / steps.coerceAtLeast(1)

    private fun indexAt(x: Float): Int = (x / width * steps).toInt().coerceIn(0, (steps - 1).coerceAtLeast(0))

    override fun onDraw(canvas: Canvas) {
        if (steps <= 0) return
        val y = height / 2f
        val first = centerOf(0)
        val last = centerOf(steps - 1)
        val chosen = selected?.takeIf { it in 0 until steps }
        paint.strokeWidth = 4 * density
        if (steps > 1) {
            paint.color = inactive
            canvas.drawLine(first, y, last, y, paint)
            if (chosen != null && chosen > 0) {
                paint.color = active
                canvas.drawLine(first, y, centerOf(chosen), y, paint)
            }
        }
        for (index in 0 until steps) {
            paint.color = if (chosen != null && index <= chosen) onActive else tick
            canvas.drawCircle(centerOf(index), y, 2 * density, paint)
        }
        if (chosen != null) {
            paint.color = active
            canvas.drawCircle(centerOf(chosen), y, 9 * density, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (steps <= 0 || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > slop && abs(event.x - downX) > abs(event.y - downY)) {
                    // A sideways drag is the slider's; the sheet keeps vertical ones.
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val index = indexAt(event.x)
                    if (index != selected) onDrag?.invoke(index)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    performClick()
                    onTap?.invoke(indexAt(event.x))
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}

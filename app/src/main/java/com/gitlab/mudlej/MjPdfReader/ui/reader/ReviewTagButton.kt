// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors

/** One outlined cell of the review bar: the tag field, the interval field or Next. */
class ReviewTagButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatButton(context, attrs) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(52)
        minimumHeight = dp(52)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(6), 0, dp(6), 0)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 11, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        stateListAnimator = null
        setTone(null)
    }

    /** Neutral cells are only outlined; a tint adds a faint wash so Correct and Wrong read at a glance. */
    fun setTone(tint: Int?) {
        val text = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (tint == null) android.graphics.Color.TRANSPARENT else ColorUtils.setAlphaComponent(tint, 0x2A))
            setStroke(dp(1).coerceAtLeast(1), if (tint == null) outline else ColorUtils.blendARGB(outline, tint, 0.45f))
        }
        setTextColor(if (tint == null) text else ColorUtils.blendARGB(text, tint, 0.25f))
    }

    /**
     * A field the user can change: a small caption over the current value, in a
     * neutral outline, so it reads as a choice rather than an answer button.
     */
    fun showField(caption: String, value: String) {
        setTone(null)
        maxLines = 2
        val captionColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        text = SpannableStringBuilder().apply {
            append(caption, RelativeSizeSpan(0.78f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(captionColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append('\n')
            append(value)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }
}

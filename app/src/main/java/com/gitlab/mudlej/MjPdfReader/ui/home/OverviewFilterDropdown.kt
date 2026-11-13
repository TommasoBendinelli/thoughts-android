// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.widget.doAfterTextChanged
import com.gitlab.mudlej.MjPdfReader.R
import com.google.android.material.color.MaterialColors

/** [detail] is a second, quieter line, such as the project a document belongs to. */
internal data class OverviewFilterChoice(val value: String, val label: String, val detail: String = "")

private val LONG_HEX_RUN = Regex("[0-9a-fA-F]{20,}")

/** Shortens content hashes in file names, such as "517f9b9c…-Paper-Conference", so rows stay readable. */
internal fun compactHashRuns(label: String): String =
    LONG_HEX_RUN.replace(label) { it.value.take(8) + "…" }

/** An anchored selector; typing only narrows the menu, never the comment catalogue. */
internal fun showOverviewFilterDropdown(
    anchor: View,
    title: String,
    choices: List<OverviewFilterChoice>,
    selected: String,
    searchable: Boolean = false,
    searchHint: Int = R.string.overview_selector_find_pdf,
    emptyText: Int = R.string.overview_selector_no_pdfs,
    onSelected: (String) -> Unit,
) {
    val context = anchor.context
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val onSurface = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurface)
    val primary = MaterialColors.getColor(anchor, androidx.appcompat.R.attr.colorPrimary)
    val popup = ListPopupWindow(context).apply {
        anchorView = anchor
        isModal = true
        width = maxOf(anchor.width, dp(if (searchable) 300 else 220))
            .coerceAtMost(anchor.rootView.width - dp(24))
        setDropDownGravity(if (searchable) Gravity.START else Gravity.END)
        verticalOffset = dp(4)
        setBackgroundDrawable(GradientDrawable().apply {
            setColor(MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurfaceContainer))
            cornerRadius = dp(12).toFloat()
        })
    }
    var visibleChoices = choices
    val secondary = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurfaceVariant)
    val adapter = object : ArrayAdapter<OverviewFilterChoice>(context, 0) {
        override fun getCount() = visibleChoices.size
        override fun getItem(position: Int) = visibleChoices[position]
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPaddingRelative(dp(16), dp(10), dp(16), dp(10))
                // Rows size to their text; a fixed list-item height cut wrapped names in half.
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        setTextColor(secondary)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(android.widget.ImageView(context).apply {
                    setImageResource(R.drawable.ic_overview_filter_check)
                    imageTintList = android.content.res.ColorStateList.valueOf(primary)
                    setPaddingRelative(dp(12), 0, 0, 0)
                })
            }
            val choice = getItem(position)
            val selectedRow = choice.value == selected
            val texts = row.getChildAt(0) as LinearLayout
            (texts.getChildAt(0) as TextView).apply {
                text = compactHashRuns(choice.label)
                setTextColor(if (selectedRow) primary else onSurface)
            }
            (texts.getChildAt(1) as TextView).apply {
                text = choice.detail
                visibility = if (choice.detail.isBlank()) View.GONE else View.VISIBLE
            }
            row.getChildAt(1).visibility = if (selectedRow) View.VISIBLE else View.INVISIBLE
            row.isActivated = selectedRow
            row.contentDescription = listOf(choice.label, choice.detail).filter(String::isNotBlank).joinToString(", ")
            return row
        }
    }
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(4))
        isFocusableInTouchMode = true
        addView(TextView(context).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(onSurface)
            setPadding(0, 0, 0, dp(8))
        })
    }
    val empty = TextView(context).apply {
        setText(emptyText)
        setTextColor(onSurface)
        setPadding(0, dp(12), 0, dp(12))
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    if (searchable) {
        header.addView(AppCompatEditText(context).apply {
            setHint(searchHint)
            setSingleLine()
            textSize = 16f
            minHeight = dp(48)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.search_icon, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            doAfterTextChanged { query ->
                val text = query.toString().trim()
                visibleChoices = choices.filter {
                    it.label.contains(text, ignoreCase = true) || it.detail.contains(text, ignoreCase = true)
                }
                empty.visibility = if (visibleChoices.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(empty)
    }
    popup.setPromptView(header)
    popup.setAdapter(adapter)
    popup.setOnItemClickListener { _, _, position, _ ->
        val value = visibleChoices[position].value
        popup.dismiss()
        onSelected(value)
    }
    val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = popup.dismiss()
    }
    anchor.addOnAttachStateChangeListener(detachListener)
    popup.setOnDismissListener { anchor.removeOnAttachStateChangeListener(detachListener) }
    // A long list otherwise slides up over its own field. Keep it below the field when there is room.
    val visible = android.graphics.Rect().also(anchor::getWindowVisibleDisplayFrame)
    val anchorBottom = IntArray(2).also(anchor::getLocationOnScreen)[1] + anchor.height
    val below = visible.bottom - anchorBottom - popup.verticalOffset - dp(12)
    // Generous row estimate: a short menu keeps its natural height, a long one is limited to the room below.
    val estimated = dp(if (searchable) 130 else 60) +
        choices.sumOf { if (it.detail.isBlank()) dp(56) else dp(72) }
    if (below >= dp(240) && estimated > below) popup.height = below
    popup.show()
    header.requestFocus()
}

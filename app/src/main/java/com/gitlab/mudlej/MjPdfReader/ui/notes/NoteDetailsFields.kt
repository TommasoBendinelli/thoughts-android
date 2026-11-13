// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.notes

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NoteDetailsFields(
    private val activity: Activity,
    initial: NoteDetails,
    private val onChanged: () -> Unit = {},
) : LinearLayout(activity) {
    var value: NoteDetails = initial
        private set
    private val whereInput = TextInputEditText(activity)
    private val dateButton = MaterialButton(activity)
    private val timeButton = MaterialButton(activity)
    private val clearDate = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    private val clearTime = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    private val visibleFromButton = MaterialButton(activity)
    private val clearVisibleFrom = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    private var restoring = false

    init {
        orientation = VERTICAL
        val fields = LinearLayout(activity).apply { orientation = VERTICAL }
        val scroller = object : androidx.core.widget.NestedScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (200 * resources.displayMetrics.density).toInt()
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) cap
                    else minOf(cap, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
            }
        }.apply { visibility = View.GONE; addView(fields) }
        addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.note_details)
            setOnClickListener { scroller.visibility = if (scroller.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }, LayoutParams(-1, -2))
        addView(scroller, LayoutParams(-1, -2))
        fields.addView(LinearLayout(activity).apply {
            addView(dateButton, LayoutParams(0, -2, 1f))
            addView(clearDate, LayoutParams(-2, -2))
        }, LayoutParams(-1, -2))
        fields.addView(LinearLayout(activity).apply {
            addView(timeButton, LayoutParams(0, -2, 1f))
            addView(clearTime, LayoutParams(-2, -2))
        }, LayoutParams(-1, -2))
        fields.addView(LinearLayout(activity).apply {
            addView(visibleFromButton, LayoutParams(0, -2, 1f))
            addView(clearVisibleFrom, LayoutParams(-2, -2))
        }, LayoutParams(-1, -2))
        visibleFromButton.setOnClickListener {
            val date = runCatching { java.time.LocalDate.parse(value.visibleFrom) }.getOrNull()
                ?: java.time.LocalDate.now().plusDays(1)
            android.app.DatePickerDialog(activity, { _, year, month, day ->
                value = value.copy(visibleFrom = java.time.LocalDate.of(year, month + 1, day).toString())
                renderCalendar()
                onChanged()
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        clearVisibleFrom.setText(R.string.note_calendar_clear_short)
        clearVisibleFrom.contentDescription = activity.getString(R.string.todo_visible_from_clear)
        clearVisibleFrom.setOnClickListener {
            value = value.copy(visibleFrom = "")
            renderCalendar()
            onChanged()
        }
        dateButton.setOnClickListener {
            val date = runCatching { java.time.LocalDate.parse(value.calendarDate) }.getOrNull() ?: java.time.LocalDate.now()
            android.app.DatePickerDialog(activity, { _, year, month, day ->
                value = value.copy(calendarDate = java.time.LocalDate.of(year, month + 1, day).toString(),
                    calendarZoneId = value.calendarZoneId.ifBlank { java.time.ZoneId.systemDefault().id })
                renderCalendar()
                onChanged()
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        timeButton.setOnClickListener {
            val time = runCatching { java.time.LocalTime.parse(value.calendarTime) }.getOrNull() ?: java.time.LocalTime.of(9, 0)
            android.app.TimePickerDialog(activity, { _, hour, minute ->
                value = value.copy(calendarTime = java.time.LocalTime.of(hour, minute).toString())
                renderCalendar()
                onChanged()
            }, time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(activity)).show()
        }
        clearDate.setText(R.string.note_calendar_clear_short)
        clearDate.contentDescription = activity.getString(R.string.note_calendar_clear)
        clearDate.setOnClickListener {
            value = value.copy(calendarDate = "", calendarTime = "", calendarZoneId = "")
            renderCalendar()
            onChanged()
        }
        clearTime.setText(R.string.note_calendar_clear_short)
        clearTime.contentDescription = activity.getString(R.string.note_calendar_clear_time)
        clearTime.setOnClickListener {
            value = value.copy(calendarTime = "")
            renderCalendar()
            onChanged()
        }
        fields.addView(TextInputLayout(activity).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = activity.getString(R.string.note_check_where)
            helperText = activity.getString(R.string.note_check_where_hint)
            whereInput.setText(initial.checkWhere)
            whereInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            whereInput.maxLines = 3
            addView(whereInput, LayoutParams(-1, -2))
        }, LayoutParams(-1, -2))
        whereInput.doAfterTextChanged {
            if (!restoring) {
                value = value.copy(checkWhere = it?.toString().orEmpty())
                onChanged()
            }
        }
        renderCalendar()
    }

    fun restore(details: NoteDetails) {
        restoring = true
        value = details
        whereInput.setText(details.checkWhere)
        renderCalendar()
        restoring = false
    }

    private fun renderCalendar() {
        dateButton.text = activity.getString(R.string.note_calendar_date,
            value.copy(calendarTime = "").calendarLabel().ifBlank { activity.getString(R.string.note_calendar_unset) })
        timeButton.text = activity.getString(R.string.note_calendar_time,
            value.calendarTime.ifBlank { activity.getString(R.string.note_calendar_no_time) })
        timeButton.visibility = if (value.calendarDate.isBlank()) View.GONE else View.VISIBLE
        clearDate.visibility = timeButton.visibility
        clearTime.visibility = if (value.calendarDate.isNotBlank() && value.calendarTime.isNotBlank()) View.VISIBLE else View.GONE
        visibleFromButton.text = activity.getString(R.string.todo_visible_from,
            value.visibleFromLabel().ifBlank { activity.getString(R.string.todo_visible_from_always) })
        clearVisibleFrom.visibility = if (value.visibleFrom.isBlank()) View.GONE else View.VISIBLE
    }
}

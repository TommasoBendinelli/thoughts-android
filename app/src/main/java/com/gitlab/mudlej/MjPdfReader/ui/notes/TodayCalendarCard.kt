// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.notes

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gitlab.mudlej.MjPdfReader.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One event of today from the phone's calendars. */
internal data class TodayEvent(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
)

/**
 * A calendar on the phone the Today card can show. [own] is false for a calendar shared by another
 * person (its owner is someone else's address); subscriptions such as a workplace feed count as own.
 */
internal data class PhoneCalendar(val id: Long, val name: String, val primary: Boolean, val own: Boolean)

/** Reads today's events from CalendarContract; all-day events are dated in UTC by the provider. */
internal object TodayEvents {
    private const val GOOGLE_CALENDAR = "com.google.android.calendar"
    const val PREFS = "todo_calendar"
    private const val KEY_CALENDARS = "calendar_ids"

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Synced calendars, the account's own (primary) ones first. */
    fun calendars(context: Context): List<PhoneCalendar> {
        val result = mutableListOf<PhoneCalendar>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.ACCOUNT_NAME),
            "${CalendarContract.Calendars.SYNC_EVENTS} = 1", null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val owner = cursor.getString(3).orEmpty()
                val primary = cursor.getInt(2) == 1
                result += PhoneCalendar(cursor.getLong(0), cursor.getString(1).orEmpty(), primary,
                    own = primary || owner.isBlank() || owner.equals(cursor.getString(4).orEmpty(), ignoreCase = true) ||
                        owner.endsWith("calendar.google.com", ignoreCase = true))
            }
        }
        return result.sortedWith(compareBy<PhoneCalendar>({ !it.primary }, { it.name.lowercase() }))
    }

    /** The chosen calendars, or the user's own calendars until a choice is made. */
    fun shownCalendarIds(context: Context, calendars: List<PhoneCalendar>): Set<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_CALENDARS, null)
            ?.mapNotNull(String::toLongOrNull)?.toSet()
            ?: calendars.filter(PhoneCalendar::own).map(PhoneCalendar::id).toSet()

    fun setShownCalendarIds(context: Context, ids: Set<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_CALENDARS, ids.map(Long::toString).toSet()).apply()
    }

    fun load(
        context: Context,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        calendarIds: Set<Long>? = null,
    ): List<TodayEvent> {
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.VISIBLE,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.CALENDAR_ID,
        )
        // All-day instances span UTC midnights, so widen the window and filter by UTC date below.
        val day = 24L * 60 * 60 * 1000
        val events = mutableListOf<TodayEvent>()
        CalendarContract.Instances.query(context.contentResolver, projection, start - day, end + day)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(5) != 1) continue
                if (calendarIds != null && cursor.getLong(7) !in calendarIds) continue
                if (cursor.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val event = TodayEvent(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getLong(2),
                    cursor.getLong(3), cursor.getInt(4) == 1)
                val onToday = if (event.allDay) {
                    val first = Instant.ofEpochMilli(event.begin).atOffset(ZoneOffset.UTC).toLocalDate()
                    val after = Instant.ofEpochMilli(event.end).atOffset(ZoneOffset.UTC).toLocalDate()
                    !today.isBefore(first) && today.isBefore(maxOf(after, first.plusDays(1)))
                } else {
                    event.begin < end && maxOf(event.end, event.begin + 1) > start
                }
                if (onToday) events += event
            }
        }
        return events.distinctBy { it.eventId to it.begin }
            .sortedWith(compareBy<TodayEvent>({ !it.allDay }, { it.begin }, { it.title }))
    }

    /** Opens Google Calendar (or any calendar app) on the day of [time]. */
    fun openDay(context: Context, time: Long = System.currentTimeMillis()) {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(time.toString()).build()
        open(context, Intent(Intent.ACTION_VIEW, uri))
    }

    fun openEvent(context: Context, event: TodayEvent) {
        open(context, Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end))
    }

    private fun open(context: Context, intent: Intent) {
        val attempts = listOfNotNull(
            Intent(intent).setPackage(GOOGLE_CALENDAR),
            intent,
            context.packageManager.getLaunchIntentForPackage(GOOGLE_CALENDAR),
        )
        for (attempt in attempts) {
            try {
                context.startActivity(attempt)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        android.widget.Toast.makeText(context, R.string.todo_calendar_open_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** "Today" card above the To-dos: the day's calendar events and a button that opens the calendar. */
internal class TodayCalendarCard(
    private val activity: Activity,
    private val scope: CoroutineScope,
) : MaterialCardView(activity) {
    private val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val dateLabel = TextView(activity)
    private var loading: Job? = null

    init {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        layoutParams = MarginLayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), 0) }
        cardElevation = 0f
        radius = dp(8).toFloat()
        strokeWidth = dp(1)
        strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
        setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(4), dp(8))
        }
        column.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dateLabel.apply {
                val outValue = TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                minHeight = dp(40)
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = activity.getString(R.string.todo_calendar_choose)
                setOnClickListener { chooseCalendars() }
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.todo_open_calendar)
                setOnClickListener { TodayEvents.openDay(activity) }
            }, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        column.addView(rows, LinearLayout.LayoutParams(-1, -2))
        addView(column)
    }

    fun refresh() {
        val today = LocalDate.now()
        dateLabel.text = activity.getString(R.string.todo_calendar_today,
            today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))) + " ▾"
        if (!TodayEvents.permitted(activity)) {
            showRows(emptyList(), permitted = false)
            return
        }
        loading?.cancel()
        loading = scope.launch {
            val events = runCatching {
                withContext(Dispatchers.IO) {
                    val context = activity.applicationContext
                    TodayEvents.load(context, today,
                        calendarIds = TodayEvents.shownCalendarIds(context, TodayEvents.calendars(context)))
                }
            }.getOrDefault(emptyList())
            showRows(events, permitted = true)
        }
    }

    private fun showRows(events: List<TodayEvent>, permitted: Boolean) {
        rows.removeAllViews()
        if (!permitted) {
            rows.addView(row(activity.getString(R.string.todo_calendar_allow), accent = true) { requestAccess() })
            return
        }
        if (events.isEmpty()) {
            rows.addView(row(activity.getString(R.string.todo_calendar_empty), accent = false, onClick = null))
            return
        }
        val shown = events.take(MAX_ROWS)
        shown.forEach { event ->
            rows.addView(row(eventLabel(event), accent = false) { TodayEvents.openEvent(activity, event) })
        }
        if (events.size > shown.size) {
            rows.addView(row(activity.getString(R.string.todo_calendar_more, events.size - shown.size), accent = true) {
                TodayEvents.openDay(activity)
            })
        }
    }

    private fun eventLabel(event: TodayEvent): String {
        val title = event.title.ifBlank { activity.getString(R.string.todo_calendar_untitled) }
        if (event.allDay) return activity.getString(R.string.todo_calendar_all_day, title)
        val format = DateFormat.getTimeFormat(activity)
        return activity.getString(R.string.todo_calendar_event,
            format.format(java.util.Date(event.begin)), format.format(java.util.Date(event.end)), title)
    }

    private fun row(text: String, accent: Boolean, onClick: (() -> Unit)?): View = TextView(activity).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        if (accent) setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        val density = resources.displayMetrics.density
        minHeight = (40 * density).toInt()
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, (8 * density).toInt(), 0)
        if (onClick != null) {
            val outValue = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }
        }
    }

    private fun chooseCalendars() {
        if (!TodayEvents.permitted(activity)) {
            requestAccess()
            return
        }
        scope.launch {
            val context = activity.applicationContext
            val (calendars, shown) = withContext(Dispatchers.IO) {
                val all = runCatching { TodayEvents.calendars(context) }.getOrDefault(emptyList())
                all to TodayEvents.shownCalendarIds(context, all)
            }
            if (calendars.isEmpty()) return@launch
            val checked = BooleanArray(calendars.size) { calendars[it].id in shown }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.todo_calendar_choose)
                .setMultiChoiceItems(calendars.map(PhoneCalendar::name).toTypedArray(), checked) { _, index, isChecked ->
                    checked[index] = isChecked
                }
                .setPositiveButton(R.string.ok) { _, _ ->
                    TodayEvents.setShownCalendarIds(context, calendars.filterIndexed { index, _ -> checked[index] }.map(PhoneCalendar::id).toSet())
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun requestAccess() {
        val prefs = activity.getSharedPreferences(TodayEvents.PREFS, Context.MODE_PRIVATE)
        val asked = prefs.getBoolean(KEY_ASKED, false)
        if (asked && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)) {
            // Android no longer shows the dialog after a second refusal; the switch is in App info.
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)))
            return
        }
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        // Home's onResume refreshes the card once the permission dialog closes.
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CALENDAR), REQUEST_CODE)
    }

    private companion object {
        const val MAX_ROWS = 4
        const val REQUEST_CODE = 4711
        const val KEY_ASKED = "asked"
    }
}

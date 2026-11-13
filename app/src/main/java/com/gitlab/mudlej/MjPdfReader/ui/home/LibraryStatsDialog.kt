// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.usage.ReadingDocumentUsageSnapshot
import com.gitlab.mudlej.MjPdfReader.data.usage.ReadingPageUsageSnapshot
import com.gitlab.mudlej.MjPdfReader.data.usage.ReadingUsageRange
import com.gitlab.mudlej.MjPdfReader.data.usage.ReadingUsageSnapshot
import com.gitlab.mudlej.MjPdfReader.data.usage.UsageStatsSnapshot
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

fun showLibraryStatsDialog(
    activity: Activity,
    items: List<HomeItem>,
    usageStats: UsageStatsSnapshot,
    readingUsage: ReadingUsageSnapshot,
) {
    fun formatCount(count: Int) = String.format(Locale.US, "%,d", count)
    fun statusCount(status: ReadingStatus) = items.count { it.readingStatus == status }
    val pagesRead = items.sumOf { if (it.pageNumber > 0) it.pageNumber + 1 else 0 }
    val staticRows = listOf(
        StatsRow.Header(activity.getString(R.string.home_stats_section_usage)),
        StatsRow.Value(
            activity.getString(R.string.home_stats_usage_today),
            formatUsageDuration(activity, usageStats.todayMillis),
        ),
        StatsRow.Value(
            activity.getString(R.string.home_stats_usage_last_seven_days),
            formatUsageDuration(activity, usageStats.lastSevenDaysMillis),
        ),
        StatsRow.Value(
            activity.getString(R.string.home_stats_usage_total),
            formatUsageDuration(activity, usageStats.totalMillis),
        ),
        StatsRow.Header(activity.getString(R.string.home_stats_section_reading_time)),
        StatsRow.RangeSelector,
        StatsRow.Header(activity.getString(R.string.home_stats_section_library)),
        StatsRow.Value(activity.getString(R.string.home_stats_in_library), formatCount(items.size)),
        StatsRow.Value(activity.getString(R.string.home_stats_pages_read), formatCount(pagesRead)),
        StatsRow.Header(activity.getString(R.string.home_stats_section_status)),
        StatsRow.Value(activity.getString(R.string.home_chip_to_read), formatCount(statusCount(ReadingStatus.TO_READ))),
        StatsRow.Value(activity.getString(R.string.home_chip_reading), formatCount(statusCount(ReadingStatus.READING))),
        StatsRow.Value(activity.getString(R.string.home_chip_on_hold), formatCount(statusCount(ReadingStatus.ON_HOLD))),
        StatsRow.Value(activity.getString(R.string.home_chip_completed), formatCount(statusCount(ReadingStatus.COMPLETED))),
        StatsRow.Value(activity.getString(R.string.home_chip_abandoned), formatCount(statusCount(ReadingStatus.ABANDONED))),
        StatsRow.Value(activity.getString(R.string.home_stats_no_status), formatCount(statusCount(ReadingStatus.UNSET))),
    )

    val adapter = LibraryStatsAdapter(activity, staticRows, readingUsage)
    val recycler = RecyclerView(activity).apply {
        this.adapter = adapter
        layoutManager = LinearLayoutManager(activity)
        clipToPadding = false
        setPadding(0, 0, 0, dp(activity, 8))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.72f).toInt(),
        )
    }

    MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.home_menu_stats)
        .setView(recycler)
        .setPositiveButton(R.string.ok, null)
        .show()
}

private sealed interface StatsRow {
    data class Header(val label: String) : StatsRow
    data class Value(val label: String, val value: String) : StatsRow
    data object RangeSelector : StatsRow
    data class Document(
        val usage: ReadingDocumentUsageSnapshot,
        val durationMillis: Long,
        val expanded: Boolean,
    ) : StatsRow
    data class Page(val usage: ReadingPageUsageSnapshot, val durationMillis: Long) : StatsRow
    data object EmptyReadingUsage : StatsRow
}

private class LibraryStatsAdapter(
    private val activity: Activity,
    private val staticRows: List<StatsRow>,
    private val readingUsage: ReadingUsageSnapshot,
) : RecyclerView.Adapter<LibraryStatsAdapter.Holder>() {
    class Holder(val content: LinearLayout) : RecyclerView.ViewHolder(content)

    private var range = ReadingUsageRange.ALL_TIME
    private var expandedDocumentKey: String? = null
    private var rows: List<StatsRow> = buildRows()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    )

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val content = holder.content
        content.removeAllViews()
        content.setOnClickListener(null)
        content.isClickable = false
        when (val row = rows[position]) {
            is StatsRow.Header -> bindHeader(content, row)
            is StatsRow.Value -> bindValue(content, row)
            StatsRow.RangeSelector -> bindRangeSelector(content)
            is StatsRow.Document -> bindDocument(content, row)
            is StatsRow.Page -> bindPage(content, row)
            StatsRow.EmptyReadingUsage -> bindEmpty(content)
        }
    }

    private fun buildRows(): List<StatsRow> {
        val output = mutableListOf<StatsRow>()
        val rangeIndex = staticRows.indexOf(StatsRow.RangeSelector)
        check(rangeIndex >= 0)
        output += staticRows.take(rangeIndex + 1)
        val documents = readingUsage.documents
            .map { it to it.duration(range) }
            .filter { it.second > 0L }
            .sortedWith(
                compareByDescending<Pair<ReadingDocumentUsageSnapshot, Long>> { it.second }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title }
            )
        if (documents.isEmpty()) {
            output += StatsRow.EmptyReadingUsage
            expandedDocumentKey = null
        } else {
            if (documents.none { it.first.documentKey == expandedDocumentKey }) {
                expandedDocumentKey = null
            }
            documents.forEach { (document, duration) ->
                val expanded = document.documentKey == expandedDocumentKey
                output += StatsRow.Document(document, duration, expanded)
                if (expanded) {
                    output += document.pages
                        .map { it to it.duration(range) }
                        .filter { it.second > 0L }
                        .sortedBy { it.first.pageIndex }
                        .map { (page, pageDuration) -> StatsRow.Page(page, pageDuration) }
                }
            }
        }
        output += staticRows.drop(rangeIndex + 1)
        return output
    }

    private fun bindHeader(content: LinearLayout, row: StatsRow.Header) {
        content.addView(TextView(activity).apply {
            text = row.label
            setTextAppearance(resolveTextAppearance(activity, com.google.android.material.R.attr.textAppearanceLabelLarge))
            setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
        }, rowParams(top = 16, bottom = 2))
    }

    private fun bindValue(content: LinearLayout, row: StatsRow.Value) {
        content.addView(valueRow(row.label, row.value), rowParams(top = 4, bottom = 2))
    }

    private fun bindRangeSelector(content: LinearLayout) {
        val group = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val choices = listOf(
            ReadingUsageRange.TODAY to R.string.home_stats_usage_today,
            ReadingUsageRange.LAST_SEVEN_DAYS to R.string.home_stats_usage_last_seven_days,
            ReadingUsageRange.ALL_TIME to R.string.home_stats_usage_total,
        )
        choices.forEach { (choice, label) ->
            val button = MaterialButton(
                activity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                id = View.generateViewId()
                setText(label)
                isChecked = choice == range
                tag = choice
            }
            group.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.addOnButtonCheckedListener { toggleGroup, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selected = toggleGroup.findViewById<MaterialButton>(checkedId).tag as ReadingUsageRange
            if (selected != range) {
                range = selected
                rows = buildRows()
                notifyDataSetChanged()
            }
        }
        content.addView(group, rowParams(top = 6, bottom = 6))
    }

    private fun bindDocument(content: LinearLayout, row: StatsRow.Document) {
        val horizontal = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 48)
        }
        horizontal.addView(ImageView(activity).apply {
            setImageResource(
                if (row.expanded) R.drawable.ic_small_arrow_down else R.drawable.ic_chevron_right
            )
            setColorFilter(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        }, LinearLayout.LayoutParams(dp(activity, 32), dp(activity, 32)).apply {
            marginEnd = dp(activity, 4)
        })
        horizontal.addView(TextView(activity).apply {
            text = row.usage.title
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextAppearance(resolveTextAppearance(activity, com.google.android.material.R.attr.textAppearanceBodyMedium))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        horizontal.addView(TextView(activity).apply {
            text = formatDetailedUsageDuration(activity, row.durationMillis)
            setTypeface(typeface, Typeface.BOLD)
            setTextAppearance(resolveTextAppearance(activity, com.google.android.material.R.attr.textAppearanceBodyMedium))
        })
        content.addView(horizontal, rowParams(top = 2, bottom = 2))
        content.isClickable = true
        content.isFocusable = true
        content.setBackgroundResource(selectableBackground(activity))
        content.contentDescription = "${row.usage.title}, ${formatDetailedUsageDuration(activity, row.durationMillis)}"
        content.setOnClickListener {
            expandedDocumentKey = if (row.expanded) null else row.usage.documentKey
            rows = buildRows()
            notifyDataSetChanged()
        }
    }

    private fun bindPage(content: LinearLayout, row: StatsRow.Page) {
        val label = activity.getString(R.string.home_stats_reading_page, row.usage.pageIndex + 1)
        val value = formatDetailedUsageDuration(activity, row.durationMillis)
        content.addView(valueRow(label, value), rowParams(start = 40, top = 2, bottom = 2))
    }

    private fun bindEmpty(content: LinearLayout) {
        content.addView(TextView(activity).apply {
            setText(R.string.home_stats_reading_empty)
            setTextAppearance(resolveTextAppearance(activity, com.google.android.material.R.attr.textAppearanceBodyMedium))
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }, rowParams(top = 6, bottom = 6))
    }

    private fun valueRow(label: String, value: String): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val appearance = resolveTextAppearance(activity, com.google.android.material.R.attr.textAppearanceBodyMedium)
        addView(TextView(activity).apply {
            text = label
            setTextAppearance(appearance)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply {
            text = value
            setTextAppearance(appearance)
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun rowParams(
        start: Int = 24,
        top: Int = 0,
        bottom: Int = 0,
    ) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        marginStart = dp(activity, start)
        marginEnd = dp(activity, 24)
        topMargin = dp(activity, top)
        bottomMargin = dp(activity, bottom)
    }
}

internal enum class UsageDurationKind { UNDER_MINUTE, MINUTES, HOURS_MINUTES }

internal data class UsageDurationParts(
    val kind: UsageDurationKind,
    val hours: Long = 0L,
    val minutes: Long = 0L,
)

internal fun usageDurationParts(durationMillis: Long): UsageDurationParts {
    val safeDuration = durationMillis.coerceAtLeast(0L)
    if (safeDuration in 1 until MILLIS_PER_MINUTE) {
        return UsageDurationParts(UsageDurationKind.UNDER_MINUTE)
    }
    val totalMinutes = safeDuration / MILLIS_PER_MINUTE
    if (totalMinutes < MINUTES_PER_HOUR) {
        return UsageDurationParts(UsageDurationKind.MINUTES, minutes = totalMinutes)
    }
    return UsageDurationParts(
        UsageDurationKind.HOURS_MINUTES,
        hours = totalMinutes / MINUTES_PER_HOUR,
        minutes = totalMinutes % MINUTES_PER_HOUR,
    )
}

private fun formatUsageDuration(activity: Activity, durationMillis: Long): String {
    val parts = usageDurationParts(durationMillis)
    return when (parts.kind) {
        UsageDurationKind.UNDER_MINUTE -> activity.getString(R.string.home_stats_usage_under_minute)
        UsageDurationKind.MINUTES -> activity.getString(
            R.string.home_stats_usage_minutes,
            parts.minutes,
        )
        UsageDurationKind.HOURS_MINUTES -> activity.getString(
            R.string.home_stats_usage_hours_minutes,
            parts.hours,
            parts.minutes,
        )
    }
}

internal enum class DetailedUsageDurationKind {
    UNDER_SECOND,
    SECONDS,
    MINUTES_SECONDS,
    HOURS_MINUTES,
}

internal data class DetailedUsageDurationParts(
    val kind: DetailedUsageDurationKind,
    val hours: Long = 0,
    val minutes: Long = 0,
    val seconds: Long = 0,
)

internal fun detailedUsageDurationParts(durationMillis: Long): DetailedUsageDurationParts {
    val safeDuration = durationMillis.coerceAtLeast(0L)
    if (safeDuration in 1 until MILLIS_PER_SECOND) {
        return DetailedUsageDurationParts(DetailedUsageDurationKind.UNDER_SECOND)
    }
    val totalSeconds = safeDuration / MILLIS_PER_SECOND
    if (totalSeconds < SECONDS_PER_MINUTE) {
        return DetailedUsageDurationParts(DetailedUsageDurationKind.SECONDS, seconds = totalSeconds)
    }
    val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
    if (totalMinutes < MINUTES_PER_HOUR) {
        return DetailedUsageDurationParts(
            DetailedUsageDurationKind.MINUTES_SECONDS,
            minutes = totalMinutes,
            seconds = totalSeconds % SECONDS_PER_MINUTE,
        )
    }
    return DetailedUsageDurationParts(
        DetailedUsageDurationKind.HOURS_MINUTES,
        hours = totalMinutes / MINUTES_PER_HOUR,
        minutes = totalMinutes % MINUTES_PER_HOUR,
    )
}

private fun formatDetailedUsageDuration(activity: Activity, durationMillis: Long): String {
    val parts = detailedUsageDurationParts(durationMillis)
    return when (parts.kind) {
        DetailedUsageDurationKind.UNDER_SECOND -> activity.getString(R.string.home_stats_usage_under_second)
        DetailedUsageDurationKind.SECONDS -> activity.getString(
            R.string.home_stats_usage_seconds,
            parts.seconds,
        )
        DetailedUsageDurationKind.MINUTES_SECONDS -> activity.getString(
            R.string.home_stats_usage_minutes_seconds,
            parts.minutes,
            parts.seconds,
        )
        DetailedUsageDurationKind.HOURS_MINUTES -> activity.getString(
            R.string.home_stats_usage_hours_minutes,
            parts.hours,
            parts.minutes,
        )
    }
}

private fun resolveTextAppearance(activity: Activity, attr: Int): Int {
    val typedValue = TypedValue()
    activity.theme.resolveAttribute(attr, typedValue, true)
    return typedValue.resourceId
}

private fun selectableBackground(activity: Activity): Int {
    val typedValue = TypedValue()
    activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
    return typedValue.resourceId
}

private fun dp(activity: Activity, value: Int): Int =
    (value * activity.resources.displayMetrics.density).toInt()

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L

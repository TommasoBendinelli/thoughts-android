// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A diary entry is a standalone note whose payload also says when it was written:
 * `entry_kind` = "diary", `diary_at` = the local date-time with offset (seconds precision)
 * and `diary_zone_id` = the time zone. Notes without `entry_kind` are To-dos.
 */
internal data class DiaryEntry(
    val id: String,
    val text: String,
    /** The moment the entry was saved, in the offset it was saved in. */
    val at: OffsetDateTime,
    val zoneId: String,
    val revision: Long,
    val deleted: Boolean,
    val payloadJson: String,
) {
    /** The day the entry belongs to: the date where it was written, not where it is read. */
    val day: LocalDate get() = at.toLocalDate()
}

internal object DiaryPayload {
    const val ENTRY_KIND = "entry_kind"
    const val KIND_DIARY = "diary"
    const val DIARY_AT = "diary_at"
    const val DIARY_ZONE_ID = "diary_zone_id"

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx", Locale.ROOT)

    /** "2026-10-24T14:03:27+02:00": seconds precision, always a numeric offset. */
    fun timestamp(at: ZonedDateTime): String = at.truncatedTo(ChronoUnit.SECONDS).format(TIMESTAMP)

    fun newPayload(
        id: String,
        text: String,
        at: ZonedDateTime,
        author: String = CommentAuthor.GENERIC_DEFAULT,
    ): JsonObject = newNotePayload(id, text, at.toInstant().toString(), author = author).apply {
        addProperty(ENTRY_KIND, KIND_DIARY)
        addProperty(DIARY_AT, timestamp(at))
        addProperty(DIARY_ZONE_ID, at.zone.id)
    }

    fun isDiary(payload: JsonObject): Boolean =
        payload.text("subtype") == "Note" && payload.text(ENTRY_KIND) == KIND_DIARY

    private val DIARY_KIND = Regex("\"$ENTRY_KIND\"\\s*:\\s*\"$KIND_DIARY\"")

    /**
     * Whether the payload says `"entry_kind": "diary"`, without parsing it: To-do lists ask this of
     * every note on every render. The same words quoted inside a note's text are escaped, so never match.
     */
    fun declaresDiaryKind(payloadJson: String): Boolean = DIARY_KIND.containsMatchIn(payloadJson)

    fun entry(payload: JsonObject, deleted: Boolean = payload.get("deleted")?.takeIf { it.isJsonPrimitive }?.asBoolean == true): DiaryEntry? {
        if (!isDiary(payload)) return null
        val id = payload.text("comment_id").takeIf(String::isNotBlank) ?: return null
        val zone = payload.text(DIARY_ZONE_ID)
        // A writer that left diary_at out still has created_at; read it where the entry says it was written.
        val at = runCatching { OffsetDateTime.parse(payload.text(DIARY_AT)) }.getOrNull()
            ?: runCatching {
                Instant.parse(payload.text("created_at")).atZone(
                    runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault()),
                ).toOffsetDateTime()
            }.getOrNull()
            ?: return null
        return DiaryEntry(
            id = id,
            text = payload.text("contents"),
            at = at,
            zoneId = zone,
            revision = payload.get("revision")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            deleted = deleted,
            payloadJson = payload.toString(),
        )
    }

    fun entry(payloadJson: String, deleted: Boolean): DiaryEntry? {
        if (!payloadJson.contains(ENTRY_KIND)) return null
        val payload = runCatching { JsonParser.parseString(payloadJson).asJsonObject }.getOrNull() ?: return null
        return entry(payload, deleted)
    }

    private fun JsonObject.text(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
}

/** To-dos are the notes that are not diary entries; existing notes without `entry_kind` stay To-dos. */
internal val OverviewComment.isDiaryEntry: Boolean
    get() = isNote && DiaryPayload.declaresDiaryKind(logicalPayloadJson)

/** Live entries, newest first; for each identity the highest revision wins. */
internal fun currentDiaryEntries(entries: Iterable<DiaryEntry>): List<DiaryEntry> = entries
    .groupBy(DiaryEntry::id)
    .map { (_, revisions) -> revisions.maxBy(DiaryEntry::revision) }
    .filterNot(DiaryEntry::deleted)
    .sortedWith(compareByDescending<DiaryEntry> { it.at.toInstant() }.thenByDescending(DiaryEntry::id))

internal sealed interface DiaryRow {
    data class Day(val date: LocalDate, val label: String) : DiaryRow
    /** [dateLabel] is set where no day header precedes the entry (search hits). */
    data class Entry(val entry: DiaryEntry, val time: String, val dateLabel: String? = null) : DiaryRow
}

internal fun diaryDayLabel(
    date: LocalDate,
    today: LocalDate,
    todayLabel: String,
    yesterdayLabel: String,
    locale: Locale = Locale.getDefault(),
): String = when (date) {
    today -> todayLabel
    today.minusDays(1) -> yesterdayLabel
    else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", locale))
}

internal fun diaryTime(entry: DiaryEntry): String =
    entry.at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))

/** Entries newest first under one header per day. */
internal fun diaryRows(
    entries: List<DiaryEntry>,
    today: LocalDate,
    todayLabel: String,
    yesterdayLabel: String,
    locale: Locale = Locale.getDefault(),
): List<DiaryRow> = buildList {
    var day: LocalDate? = null
    currentDiaryEntries(entries).forEach { entry ->
        if (entry.day != day) {
            day = entry.day
            add(DiaryRow.Day(entry.day, diaryDayLabel(entry.day, today, todayLabel, yesterdayLabel, locale)))
        }
        add(DiaryRow.Entry(entry, diaryTime(entry)))
    }
}

/** Search hits carry their date, since no day header stands above them. */
internal fun diarySearchRows(
    entries: List<DiaryEntry>,
    query: String,
    today: LocalDate,
    todayLabel: String,
    yesterdayLabel: String,
    locale: Locale = Locale.getDefault(),
): List<DiaryRow> = diarySearch(currentDiaryEntries(entries), query).map { entry ->
    DiaryRow.Entry(entry, diaryTime(entry), diaryDayLabel(entry.day, today, todayLabel, yesterdayLabel, locale))
}

/** Every word of [query] must appear, ignoring case and accents. */
internal fun diarySearch(entries: List<DiaryEntry>, query: String): List<DiaryEntry> {
    val words = foldDiaryText(query).split(Regex("\\s+")).filter(String::isNotBlank)
    if (words.isEmpty()) return entries
    return entries.filter { entry ->
        val text = foldDiaryText(entry.text)
        words.all(text::contains)
    }
}

private val COMBINING_MARKS = Regex("\\p{Mn}+")

internal fun foldDiaryText(value: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(value, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

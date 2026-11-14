// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

/**
 * What the ReviewState table holds for one card besides its dirty flag: everything the due and
 * count queries read. Two equal rows are the same stored state.
 */
internal data class ReviewStateRow(
    val pdfStem: String,
    val active: Boolean,
    val eligibleAt: Long,
    val documentId: String,
    val dueAt: Long?,
    val reviewCount: Int,
    val currentMark: String,
    val deleted: Boolean,
    val retained: Boolean,
)

internal fun reviewStateRow(card: ReviewCard?, active: Boolean, eligibleAt: Long, reviewCount: Int, mark: String): ReviewStateRow {
    val schedule = card?.schedule
    val dueAt = (if (schedule?.retained == true) null else schedule?.dueAt ?: card?.initialDueAt)
        ?.let { runCatching { it.toEpochMilli() }.getOrNull() }
    return ReviewStateRow(
        pdfStem = card?.pdfStem.orEmpty(),
        active = active,
        eligibleAt = eligibleAt,
        documentId = card?.documentId.orEmpty(),
        dueAt = dueAt,
        reviewCount = reviewCount,
        currentMark = mark,
        deleted = schedule?.deleted == true,
        retained = schedule?.retained == true,
    )
}

/**
 * The cards whose state row is written. After the sources change, every card's row is computed
 * again (a card's place in its source decides whether it can be reviewed), and every row used to
 * be written again, one statement per card of the whole Library. Only two kinds need writing:
 * - a card whose records changed ([dirty]): writing its row is also what clears its dirty flag;
 * - a card whose row differs from the stored one ([stored], the rows that are not dirty), or
 *   that has none stored.
 */
internal fun <K> reviewStatesToWrite(
    rows: Map<K, ReviewStateRow>,
    dirty: Set<K>,
    stored: Map<K, ReviewStateRow>,
): Set<K> = rows.filterTo(linkedMapOf()) { (id, row) -> id in dirty || stored[id] != row }.keys

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewSkipScope
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

internal fun reviewTagLabel(context: Context, tag: String): String = when (tag) {
    ReviewTags.CORRECT -> context.getString(R.string.review_action_correct)
    ReviewTags.WRONG -> context.getString(R.string.review_action_error)
    ReviewTags.SKIP -> context.getString(R.string.review_skip)
    else -> tag
}

/** A couple of words for buttons and chips; long tags keep their full text everywhere else. */
internal fun reviewTagShortLabel(context: Context, tag: String): String = shortReviewTag(reviewTagLabel(context, tag))

internal fun shortReviewTag(label: String): String {
    if (label.length <= SHORT_TAG_CHARACTERS) return label
    val words = label.split(' ')
    var short = words.first()
    for (word in words.drop(1).take(SHORT_TAG_WORDS - 1)) {
        if (short.length + 1 + word.length > SHORT_TAG_CHARACTERS) break
        short += " $word"
    }
    return short.take(SHORT_TAG_CHARACTERS).trimEnd() + "…"
}

private const val SHORT_TAG_CHARACTERS = 22
private const val SHORT_TAG_WORDS = 3

internal fun reviewTagLabels(context: Context, tags: Collection<String>): String =
    tags.joinToString(", ") { reviewTagLabel(context, it) }

/** The New tag dialog shown last, for device tests that type into it. */
@androidx.annotation.VisibleForTesting
internal var lastNewReviewTagDialog: java.lang.ref.WeakReference<androidx.appcompat.app.AlertDialog>? = null

/** Asks for a new tag name and passes it on normalized. */
internal fun showNewReviewTagDialog(context: Context, onTag: (String) -> Unit) {
    val input = TextInputEditText(context).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        maxLines = 4
        setHint(R.string.review_tags_new_hint)
    }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.review_tags_new)
        .setView(android.widget.FrameLayout(context).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        })
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.review_tags_add) { _, _ ->
            normalizeReviewTag(context, input.text.toString())?.let(onTag)
        }
        .show()
        .also { lastNewReviewTagDialog = java.lang.ref.WeakReference(it) }
}

/** A typed tag; an old name that was merged becomes the tag it was merged into. */
internal fun normalizeReviewTag(context: Context, text: String): String? = ReviewTags.normalize(
    text, ReviewTags.BUILT_IN.associateWith { reviewTagLabel(context, it) },
)?.let(com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges::resolve)

/** Tags created on this phone before their first use; used tags are read from review history. */
internal class ReviewTagPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("review_tags", Context.MODE_PRIVATE)

    fun created(): Set<String> = preferences.getStringSet(CREATED, emptySet()).orEmpty()
        .map(com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges::resolve).toSet()

    fun add(tag: String) {
        if (tag in ReviewTags.BUILT_IN) return
        preferences.edit().putStringSet(CREATED, created() + tag).apply()
    }

    private companion object { const val CREATED = "created" }
}

internal data class ReviewTimingDialogOptions(
    val tags: List<String>,
    val selectedTags: Set<String>,
    val projectAvailable: Boolean,
    val onTagCreated: (String) -> Unit,
    val onApply: (List<String>, ReviewInterval?, ReviewSkipScope, Boolean) -> Unit,
)

internal fun createReviewTimingDialog(
    context: Context,
    interval: ReviewInterval,
    tags: List<String>,
    selectedTags: Set<String>,
    projectAvailable: Boolean,
    onTagCreated: (String) -> Unit = {},
    onApply: (List<String>, ReviewInterval?, ReviewSkipScope, Boolean) -> Unit,
): BottomSheetDialog = createReviewIntervalEditorDialog(
    context, interval,
    timingOptions = ReviewTimingDialogOptions(tags, selectedTags, projectAvailable, onTagCreated, onApply),
    onApply = {},
) as BottomSheetDialog

// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewIntervalUnit
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The card being reviewed and the answer in progress: whether it is revealed, the tags, interval,
 * group notes and section, page and document notes chosen so far, and the before panel's picks. Kept while the reader reloads
 * the same PDF, and saved with the activity, so that switching to another app (where Android may destroy the reader or its process)
 * gives back the same card with the same choices.
 */
internal data class ReviewPresentationState(
    val cardId: String,
    val hidden: Boolean = false,
    val answerRevealed: Boolean = false,
    val answerShown: Boolean = false,
    val maskFlipped: Boolean = false,
    val selectedTags: List<String> = emptyList(),
    val interval: ReviewInterval? = null,
    val draft: ReviewAnswerDraft.Snapshot = ReviewAnswerDraft.Snapshot(null, false, emptyMap()),
) {
    fun encode(): String = JsonObject().apply {
        addProperty("version", VERSION)
        addProperty("card_id", cardId)
        addProperty("hidden", hidden)
        addProperty("answer_revealed", answerRevealed)
        addProperty("answer_shown", answerShown)
        addProperty("mask_flipped", maskFlipped)
        add("selected_tags", JsonArray().apply { selectedTags.forEach(::add) })
        interval?.let { value ->
            add("interval", JsonObject().apply {
                addProperty("amount", value.amount.toPlainString())
                addProperty("unit", value.unit.name)
                value.date?.let { addProperty("date", it.toString()) }
            })
        }
        add("draft", JsonObject().apply {
            draft.expanded?.let { addProperty("expanded", it) }
            addProperty("touched", draft.touched)
            add("notes", JsonObject().apply { draft.notes.forEach(::addProperty) })
            // Review in two steps: the before panel's picks and when the answer was first shown.
            if (draft.beforeShown) addProperty("before_shown", true)
            if (draft.beforeTags.isNotEmpty()) add("before_tags", JsonArray().apply { draft.beforeTags.forEach(::add) })
            draft.answerShownAt?.let { addProperty("answer_shown_at", it) }
            if (draft.beforeReopened) addProperty("before_reopened", true)
            draft.context?.let { context ->
                add("context", JsonObject().apply {
                    add("targets", JsonObject().apply {
                        context.targets.forEach { (scope, target) ->
                            add(scope, JsonObject().apply {
                                addProperty("key", target.key)
                                addProperty("label", target.label)
                            })
                        }
                    })
                    add("current", JsonObject().apply { context.current.forEach(::addProperty) })
                    add("edits", JsonObject().apply { context.edits.forEach(::addProperty) })
                    context.scope?.let { addProperty("scope", it) }
                })
            }
        })
    }.toString()

    companion object {
        private const val VERSION = 1

        /** Null for anything this version did not write; the card then opens as it would anew. */
        fun decode(encoded: String?): ReviewPresentationState? {
            if (encoded.isNullOrBlank()) return null
            return runCatching {
                val root = JsonParser.parseString(encoded).asJsonObject
                if (root.get("version")?.asInt != VERSION) return null
                val cardId = root.get("card_id")?.asString?.takeIf(String::isNotBlank) ?: return null
                val interval = root.getAsJsonObject("interval")?.let { value ->
                    ReviewInterval(
                        BigDecimal(value.get("amount").asString),
                        ReviewIntervalUnit.valueOf(value.get("unit").asString),
                        value.get("date")?.asString?.let(LocalDate::parse),
                    )
                }
                val draft = root.getAsJsonObject("draft")
                ReviewPresentationState(
                    cardId = cardId,
                    hidden = root.get("hidden")?.asBoolean == true,
                    answerRevealed = root.get("answer_revealed")?.asBoolean == true,
                    answerShown = root.get("answer_shown")?.asBoolean == true,
                    maskFlipped = root.get("mask_flipped")?.asBoolean == true,
                    selectedTags = root.getAsJsonArray("selected_tags")?.map { it.asString }.orEmpty(),
                    interval = interval,
                    draft = ReviewAnswerDraft.Snapshot(
                        expanded = draft?.get("expanded")?.asString,
                        touched = draft?.get("touched")?.asBoolean == true,
                        notes = draft?.getAsJsonObject("notes")?.entrySet()
                            ?.associate { (key, value) -> key to value.asString }.orEmpty(),
                        beforeShown = draft?.get("before_shown")?.asBoolean == true,
                        beforeTags = draft?.getAsJsonArray("before_tags")?.map { it.asString }.orEmpty(),
                        answerShownAt = draft?.get("answer_shown_at")?.asString,
                        beforeReopened = draft?.get("before_reopened")?.asBoolean == true,
                        context = draft?.getAsJsonObject("context")?.let { context ->
                            fun strings(name: String) = context.getAsJsonObject(name)?.entrySet()
                                ?.associate { (key, value) -> key to value.asString }.orEmpty()
                            ReviewAnswerDraft.ContextSnapshot(
                                targets = context.getAsJsonObject("targets")?.entrySet()?.associate { (scope, value) ->
                                    val target = value.asJsonObject
                                    scope to ReviewAnswerDraft.ContextTarget(target.get("key").asString, target.get("label").asString)
                                }.orEmpty(),
                                current = strings("current"),
                                edits = strings("edits"),
                                scope = context.get("scope")?.asString,
                            )
                        },
                    ),
                )
            }.getOrNull()
        }
    }
}

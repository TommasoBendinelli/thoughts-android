// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant

/**
 * A review in two steps (shared spec "review-phases", 2026-11-03), as an answer records it. Before
 * the answer is shown the review asks the groups with a before phase ([ReviewTagGroups.Group.askedBefore],
 * the before panel) and one before note; once it is shown (Reveal or Set knowledge) those picks are
 * locked and the tag sheet asks the other groups, a before-and-after group (Recall) again, starting at
 * its predicted step ([ReviewAnswerPhases]). Besides its usual fields the answer then records:
 * - `review_tags`: the before-only groups' tags and the after step's tags ([ReviewAnswerPhases.recordedTags]);
 * - `review_group_notes`: the before note under [ReviewGroupNotes.BEFORE_NOTE_KEY];
 * - [FIELD] ([toJson]): each step's tags, when the answer was first shown, and the agent's recall
 *   prediction if one arrived before Save.
 * Answers without [FIELD] (older ones, other clients) read and show as before.
 */
data class ReviewPhases(
    /** The tags picked in the before panel, the predicted Recall step included. */
    val beforeTags: List<String>,
    /** The tags picked once the answer was shown, the actual Recall step included. */
    val afterTags: List<String>,
    /** When the answer was first shown, as recorded (ISO-8601, UTC); null when it never was. */
    val answerShownAt: String? = null,
    val agentPrediction: ReviewAgentPrediction? = null,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("schema_version", SCHEMA_VERSION)
        add("before", JsonObject().apply {
            add("tags", JsonArray().apply { beforeTags.forEach(::add) })
            answerShownAt?.let { addProperty("answer_shown_at", it) }
        })
        add("after", JsonObject().apply { add("tags", JsonArray().apply { afterTags.forEach(::add) }) })
        agentPrediction?.let { add("agent_prediction", it.toJson()) }
    }

    /**
     * The history's lines for this answer: "Before: <tags> — <before note>", "After: <tags> — <note>"
     * and, with a prediction, "Agent predicted: <tag>". [label] names a tag, [none] stands for a step
     * with no tags; [notes] are the answer's group notes.
     */
    fun historyLines(
        notes: Map<String, String>,
        label: (String) -> String,
        none: String,
        before: String = "Before",
        after: String = "After",
        agent: String = "Agent predicted",
    ): List<String> {
        fun line(name: String, tags: List<String>, note: String?) =
            "$name: " + tags.joinToString(" · ", transform = label).ifEmpty { none } + note?.let { " — $it" }.orEmpty()
        return listOfNotNull(
            line(before, beforeTags, notes[ReviewGroupNotes.BEFORE_NOTE_KEY]),
            line(after, afterTags, notes[ReviewGroupNotes.ANSWER_NOTE_KEY]),
            agentPrediction?.let { "$agent: ${label(it.tag)}" },
        )
    }

    companion object {
        const val FIELD = "review_phases"
        const val SCHEMA_VERSION = 1

        /** The event's field, or null for an answer without one; malformed parts are left out. */
        fun parse(event: JsonObject): ReviewPhases? = runCatching {
            val phases = event.get(FIELD) as? JsonObject ?: return null
            val before = phases.get("before") as? JsonObject
            val after = phases.get("after") as? JsonObject
            ReviewPhases(
                beforeTags = tagsOf(before?.get("tags")),
                afterTags = tagsOf(after?.get("tags")),
                answerShownAt = stringOf(before?.get("answer_shown_at")),
                agentPrediction = (phases.get("agent_prediction") as? JsonObject)?.let(ReviewAgentPrediction::parse),
            )
        }.getOrNull()

        private fun tagsOf(value: JsonElement?): List<String> = (value as? JsonArray)?.mapNotNull { element ->
            stringOf(element)?.let(ReviewTags::normalize)
        }?.distinct().orEmpty()

        internal fun stringOf(value: JsonElement?): String? =
            value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
}

/**
 * The agent's recall prediction for one card ([ReviewRecallPrediction]): a step of the recall group's
 * scale, with its reason. Shown once the answer is shown, never before; recorded with the answer
 * when it arrived before Save. [arrivedBeforeAnswer]: made_at <= answer_shown_at ([arrivedBy]).
 */
data class ReviewAgentPrediction(
    val tag: String,
    val groupId: String,
    val reason: String,
    val model: String,
    val predictionId: String,
    val requestedAt: String,
    val madeAt: String,
    val arrivedBeforeAnswer: Boolean = false,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("tag", tag)
        addProperty("group_id", groupId)
        addProperty("reason", reason)
        addProperty("model", model)
        addProperty("prediction_id", predictionId)
        addProperty("requested_at", requestedAt)
        addProperty("made_at", madeAt)
        addProperty("arrived_before_answer", arrivedBeforeAnswer)
    }

    /**
     * As recorded with an answer first shown at [answerShownAt]: whether it was made before then
     * (also when the answer was never shown). A made_at that cannot be read counts as after.
     */
    fun arrivedBy(answerShownAt: Instant?): ReviewAgentPrediction {
        val made = runCatching { Instant.parse(madeAt) }.getOrNull()
        return copy(arrivedBeforeAnswer = answerShownAt == null || (made != null && !made.isAfter(answerShownAt)))
    }

    companion object {
        /** A recorded prediction; null without a tag. */
        fun parse(json: JsonObject): ReviewAgentPrediction? {
            fun text(name: String) = ReviewPhases.stringOf(json.get(name)).orEmpty()
            val tag = text("tag").trim().takeIf(String::isNotEmpty) ?: return null
            return ReviewAgentPrediction(
                tag = tag,
                groupId = text("group_id"),
                reason = text("reason"),
                model = text("model"),
                predictionId = text("prediction_id"),
                requestedAt = text("requested_at"),
                madeAt = text("made_at"),
                arrivedBeforeAnswer = runCatching { json.get("arrived_before_answer").asBoolean }.getOrDefault(false),
            )
        }
    }
}

/**
 * The rules of an answer in two steps, as the review session applies them; nothing here is synced.
 * Groups are the current layout's; [mapping] maps merged tag names to the names they became.
 */
object ReviewAnswerPhases {
    private fun canonical(tag: String, mapping: Map<String, String>): String =
        ReviewTagGroups.canonicalTag(tag, mapping) ?: tag

    private fun tagsOf(groups: List<ReviewTagGroups.Group>, phase: String, mapping: Map<String, String>): Set<String> =
        ReviewTagGroups.resolved(groups, mapping).filter { it.phase == phase }.flatMapTo(hashSetOf()) { it.tags }

    /** The groups the before panel asks, in record order; none means the review has no before step. */
    fun beforeGroups(groups: List<ReviewTagGroups.Group>): List<ReviewTagGroups.Group> = groups.filter { it.askedBefore }

    /**
     * The after step's starting tags: the slider steps of the card's current answer, as today
     * ([ReviewTagScales.onlySteps]), but not those of before-only groups: they are asked afresh before
     * the answer and nothing carries over into the before panel.
     */
    fun afterStart(previous: List<String>, groups: List<ReviewTagGroups.Group>, mapping: Map<String, String> = emptyMap()): List<String> {
        val beforeOnly = tagsOf(groups, ReviewTagGroups.PHASE_BEFORE, mapping)
        return ReviewTagScales.onlySteps(previous, groups, mapping).filter { canonical(it, mapping) !in beforeOnly }
    }

    /**
     * [after] once the answer is shown: each before-and-after slider (Recall) moves to the step
     * predicted in [before]; one without a prediction keeps its step.
     */
    fun startAtPrediction(
        after: List<String>,
        before: List<String>,
        groups: List<ReviewTagGroups.Group>,
        mapping: Map<String, String> = emptyMap(),
    ): List<String> = ReviewTagGroups.resolved(groups, mapping)
        .filter { it.phase == ReviewTagGroups.PHASE_BEFORE_AND_AFTER && it.scale != null }
        .fold(after) { tags, group ->
            val steps = checkNotNull(group.scale)
            val predicted = ReviewTagScales.selectedStep(steps, before, mapping) ?: return@fold tags
            ReviewTagScales.select(tags, steps, predicted, mapping)
        }

    /**
     * The answer's `review_tags`: the tags in effect. [before]'s tags but those of before-and-after
     * groups (a prediction is not a tag of the answer), then [after], each once.
     */
    fun recordedTags(
        before: List<String>,
        after: List<String>,
        groups: List<ReviewTagGroups.Group>,
        mapping: Map<String, String> = emptyMap(),
    ): List<String> {
        val predicted = tagsOf(groups, ReviewTagGroups.PHASE_BEFORE_AND_AFTER, mapping)
        return (before.filter { canonical(it, mapping) !in predicted } + after).distinct()
    }

    /**
     * The answer's [ReviewPhases]: the before panel's tags in group order, the after step's tags, when
     * the answer was first shown, and [prediction] if it arrived before Save.
     */
    fun phases(
        before: List<String>,
        after: List<String>,
        answerShownAt: Instant?,
        prediction: ReviewAgentPrediction?,
        groups: List<ReviewTagGroups.Group>,
        mapping: Map<String, String> = emptyMap(),
    ): ReviewPhases {
        // Compared as recorded (to the millisecond), so a reader of the event finds the same order.
        val shown = answerShownAt?.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        return ReviewPhases(
            beforeTags = ReviewTagScales.summaryTags(before, beforeGroups(groups), mapping),
            afterTags = after.distinct(),
            answerShownAt = shown?.let(ReviewTagGroups::timestamp),
            agentPrediction = prediction?.arrivedBy(shown),
        )
    }
}

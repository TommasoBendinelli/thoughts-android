// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

/**
 * Slider groups ([ReviewTagGroups.Group.scale]): an answer holds at most one step of each scale.
 * Picking a step replaces the scale's other steps; picking the chosen step again clears the scale.
 * The group's free tags (not steps) stay independent. Old tag names merged into a step count as
 * that step ([mapping]: old name to final name). Answers store plain tags; nothing here is synced.
 */
object ReviewTagScales {
    private fun canonical(tag: String, mapping: Map<String, String>): String =
        ReviewTagGroups.canonicalTag(tag, mapping) ?: tag

    /** The steps of [scale] that [chosen] holds, merged names mapped, in scale order. */
    fun chosenSteps(scale: List<String>, chosen: Collection<String>, mapping: Map<String, String> = emptyMap()): List<String> {
        val held = chosen.mapTo(hashSetOf()) { canonical(it, mapping) }
        return scale.filter { it in held }
    }

    /** Where the slider sits: the first chosen step in scale order, or null when the scale is unset. */
    fun selectedStep(scale: List<String>, chosen: Collection<String>, mapping: Map<String, String> = emptyMap()): String? =
        chosenSteps(scale, chosen, mapping).firstOrNull()

    /**
     * [chosen] with [step] of [scale] picked: every tag that is (or was merged into) a step of the
     * scale leaves, then [step] is added; a null [step] only clears the scale. Other tags keep their
     * place; [step] goes last.
     */
    fun select(chosen: List<String>, scale: List<String>, step: String?, mapping: Map<String, String> = emptyMap()): List<String> {
        val steps = scale.toSet()
        val kept = chosen.filter { canonical(it, mapping) !in steps }
        return if (step == null) kept else kept + step
    }

    /** A tap on [step]: picks it, or clears the scale when it is already the slider's step. */
    fun toggle(chosen: List<String>, scale: List<String>, step: String, mapping: Map<String, String> = emptyMap()): List<String> =
        select(chosen, scale, step.takeUnless { selectedStep(scale, chosen, mapping) == it }, mapping)

    /**
     * The tags of [chosen] that are (or were merged into) a slider step. Free tags are no longer
     * offered, so a new answer keeps only these.
     */
    fun onlySteps(chosen: List<String>, groups: List<ReviewTagGroups.Group>, mapping: Map<String, String> = emptyMap()): List<String> {
        val steps = ReviewTagGroups.resolved(groups, mapping).flatMap { it.scale.orEmpty() }.toSet()
        return chosen.filter { canonical(it, mapping) in steps }
    }

    /**
     * The tags the review bar's tag cell names, in order: each slider group's step (groups in record
     * order), then the other chosen tags as chosen. Merged names show as the tag they became.
     */
    fun summaryTags(chosen: List<String>, groups: List<ReviewTagGroups.Group>, mapping: Map<String, String> = emptyMap()): List<String> {
        val scales = ReviewTagGroups.resolved(groups, mapping).mapNotNull { it.scale }
        val steps = scales.flatMap { chosenSteps(it, chosen, mapping) }
        val stepSet = steps.toSet()
        val rest = chosen.filter { canonical(it, mapping) !in stepSet }
        return (steps + rest).distinct()
    }
}

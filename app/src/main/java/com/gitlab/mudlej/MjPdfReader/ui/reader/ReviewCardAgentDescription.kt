// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

/** One passage of a source-linked card: 1-based inclusive lines inside its mask markers, and their text. */
internal data class ReviewCardSourcePassage(
    val sourceFile: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
)

/**
 * The passages between card [cardId]'s mask markers in [sources] (Library-relative path to
 * contents, in the order to report them), markers excluded. Within a file, passages come in
 * source order. Blank lines just inside the markers (the marker lines' own remainders) are not
 * part of a passage; trailing whitespace is trimmed from every line.
 */
internal fun reviewCardSourcePassages(cardId: String, sources: List<Pair<String, String>>): List<ReviewCardSourcePassage> {
    if (cardId.isBlank()) return emptyList()
    val id = Regex.escape(cardId)
    return sources.flatMap { (path, contents) ->
        val markdown = path.endsWith(".md", ignoreCase = true) || path.endsWith(".markdown", ignoreCase = true)
        val start = if (markdown) Regex("<!--\\s*thoughts:v1:start\\s+review:$id:answer:([0-9]+)\\s*-->")
            else Regex("\\\\ReviewCardMaskStart\\s*\\{$id\\}\\s*\\{([0-9]+)\\}%?")
        start.findAll(contents).mapNotNull { opening ->
            val segment = Regex.escape(opening.groupValues[1])
            val end = (if (markdown) Regex("<!--\\s*thoughts:v1:end\\s+review:$id:answer:$segment\\s*-->")
                else Regex("\\\\ReviewCardMaskEnd\\s*\\{$id\\}\\s*\\{$segment\\}"))
                .find(contents, opening.range.last + 1) ?: return@mapNotNull null
            val from = opening.range.last + 1
            val firstLine = contents.substring(0, from).count { it == '\n' } + 1
            val lines = contents.substring(from, end.range.first).split('\n').map(String::trimEnd)
            var first = 0
            var last = lines.lastIndex
            while (first <= last && lines[first].isBlank()) first++
            while (last >= first && lines[last].isBlank()) last--
            if (first > last) null
            else ReviewCardSourcePassage(path, firstLine + first, firstLine + last,
                lines.subList(first, last + 1).joinToString("\n"))
        }.toList()
    }
}

/** "6", or "18–19" for answers spanning pages; empty when no page is known. */
internal fun reviewCardAnswerPagesLabel(pages: Collection<Int>): String {
    val known = pages.filter { it > 0 }
    if (known.isEmpty()) return ""
    val first = known.min()
    val last = known.max()
    return if (first == last) "$first" else "$first–$last"
}

/** "main.tex, lines 3–5; 9–12", naming each file once for consecutive passages in it. */
internal fun reviewCardAnswerSourceLabel(passages: List<ReviewCardSourcePassage>): String {
    val groups = mutableListOf<Pair<String, MutableList<String>>>()
    for (passage in passages) {
        val range = "${passage.startLine}–${passage.endLine}"
        if (groups.lastOrNull()?.first == passage.sourceFile) groups.last().second += range
        else groups += passage.sourceFile to mutableListOf(range)
    }
    return groups.joinToString("; ") { (path, ranges) -> "$path, lines " + ranges.joinToString("; ") }
}

/**
 * The review card's placeholders for [AgentPromptTemplates], added to its document's
 * ([documentFields], from [agentDocumentFields] plus pdf_pages): card_id, question, note,
 * answer_pages, answer_source and answer_text. With source [passages], the answer is their source
 * text; otherwise [pdfAnswerText], the PDF text in the card's answer regions. The desktop's
 * reviewCardPromptFields gives the same values.
 */
internal fun reviewCardPromptFields(
    documentFields: Map<String, String>,
    cardId: String,
    question: String,
    note: String,
    answerPages: Collection<Int>,
    passages: List<ReviewCardSourcePassage>,
    pdfAnswerText: String?,
    /** The card's "Answer feedback:" section ([reviewCardFeedbackSection]), empty without feedback. */
    answerFeedback: String = "",
): Map<String, String> {
    fun oneLine(value: String) = value.replace(Regex("\\s+"), " ").trim()
    val answer = if (passages.isNotEmpty()) passages.joinToString("\n\n") { it.text }
    else pdfAnswerText.orEmpty().lineSequence().map { oneLine(it) }.filter(String::isNotEmpty).joinToString("\n")
    return documentFields + mapOf(
        "card_id" to oneLine(cardId),
        "question" to oneLine(question),
        "note" to oneLine(note),
        "answer_pages" to reviewCardAnswerPagesLabel(answerPages),
        "answer_source" to if (passages.isEmpty()) "" else reviewCardAnswerSourceLabel(passages),
        "answer_text" to answer,
        "answer_feedback" to answerFeedback.trimEnd(),
    )
}

/**
 * One feedback record for the agent text: [orphaned] when the last placement did not find its
 * passage; [sharedFrom] the question of the card it was made on when that is another card of the
 * document (null for the card's own feedback).
 */
internal data class ReviewCardFeedbackLine(
    val positive: Boolean,
    val quote: String,
    val comment: String,
    val createdAt: String,
    val orphaned: Boolean,
    val sharedFrom: String? = null,
)

/**
 * The "Answer feedback:" section of Copy for agent (protocol/review-card-feedback.md §6.5), the same
 * on the desktop: one line per record in created_at order; empty when the card has none. Feedback
 * shared from another card of the document ends with ` (from another card: "<its question>")`.
 */
internal fun reviewCardFeedbackSection(lines: List<ReviewCardFeedbackLine>): String {
    if (lines.isEmpty()) return ""
    val whitespace = Regex("\\s+")
    return buildString {
        append("Answer feedback:\n")
        lines.sortedBy { it.createdAt }.forEach { line ->
            val quote = line.quote.replace(whitespace, " ").trim().let { if (it.length > 300) it.take(300) + "…" else it }
            val comment = line.comment.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
            append("- ").append(if (line.positive) "Liked" else "Disliked").append(": \"").append(quote).append('"')
            if (comment.isNotEmpty()) append(" — ").append(comment)
            line.sharedFrom?.let { question ->
                val text = question.replace(whitespace, " ").trim().let { if (it.length > 300) it.take(300) + "…" else it }
                append(if (text.isEmpty()) " (from another card)" else " (from another card: \"$text\")")
            }
            if (line.orphaned) append(" (not found in the current answer)")
            append('\n')
        }
    }
}

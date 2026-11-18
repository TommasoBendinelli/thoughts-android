// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder
import com.gitlab.mudlej.MjPdfReader.data.sync.defaultSourceFolder

/**
 * Where the reader is in a document: 1-based page and page count (0 when unknown), the selected
 * text and, when known, the source lines the selection was built from.
 */
internal data class AgentReaderPlace(
    val page: Int = 0,
    val pages: Int = 0,
    val selectedText: String? = null,
    val sourceLocation: AgentSourceLocation? = null,
)

/** Source lines [firstLine]..[lastLine] (1-based) of [path], relative to the Library folder like the Source line. */
internal data class AgentSourceLocation(val path: String, val firstLine: Int, val lastLine: Int)

/**
 * The source lines of a selection from where its first and last words map to. Both ends must map,
 * to the same file; otherwise the selection has no single source passage worth naming.
 */
internal fun agentSourceLocation(start: Pair<String, Int>?, end: Pair<String, Int>?): AgentSourceLocation? {
    if (start == null || end == null || start.first != end.first || start.first.isBlank()) return null
    if (start.second < 1 || end.second < 1) return null
    return AgentSourceLocation(start.first, minOf(start.second, end.second), maxOf(start.second, end.second))
}

/**
 * The active tab reports its live place; another tab only the page it was left on, if this
 * process remembers it. A selection belongs to the visible reader only.
 */
internal fun agentReaderPlaceForTab(
    isCurrent: Boolean,
    live: () -> AgentReaderPlace,
    remembered: CurrentDocumentSnapshot?,
): AgentReaderPlace = if (isCurrent) live()
    else AgentReaderPlace(page = remembered?.page ?: 0, pages = remembered?.pages ?: 0)

/** The Library project and document a reader PDF shows; either can be null. */
internal fun readerProjectDocument(
    projects: JsonArray,
    target: ReaderTitleTarget,
    path: String,
    canonicalDirectory: File,
): Pair<JsonObject?, JsonObject?> {
    fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    val file = File(path)
    val canonical = file.absoluteFile.parentFile?.canonicalFile == canonicalDirectory.canonicalFile
    val entries = projects.map { it.asJsonObject }.flatMap { project ->
        (project.getAsJsonArray("documents") ?: JsonArray()).map { project to it.asJsonObject }
    }
    if (!target.documentId.isNullOrBlank()) {
        entries.firstOrNull { (_, document) -> document.text("document_id") == target.documentId }?.let { return it }
    }
    if (!target.projectId.isNullOrBlank()) {
        projects.map { it.asJsonObject }.firstOrNull { it.text("id") == target.projectId }?.let { return it to null }
    }
    // Like the desktop: the first document whose Library PDF this is.
    entries.firstOrNull { (_, document) ->
        val localPath = document.text("local_path")
        (canonical && file.name in setOf(document.text("filename"), document.text("attachment_filename"))) ||
            (localPath.isNotBlank() && File(localPath).canonicalFile == file.canonicalFile)
    }?.let { return it }
    // A project's combined PDF belongs to the project, not to one of its documents.
    if (canonical) projects.map { it.asJsonObject }.firstOrNull { it.text("target") == file.name }?.let { return it to null }
    return null to null
}

/**
 * The desktop's project settings path (ProjectConfiguration::path), relative to the Library folder:
 * the manifest in the Project's default folder as its catalogue entry names it (Rule 2 of the
 * default-folder rename), else in the folder named [segment].
 */
internal fun agentProjectConfigurationPath(project: JsonObject, segment: String = DefaultFolder.LEGACY): String =
    defaultSourceFolder(project, segment) + "/thoughts-project.json"

internal fun agentProjectConfigurationPath(projectId: String, segment: String = DefaultFolder.LEGACY): String =
    DefaultFolder.path(projectId, segment) + "/thoughts-project.json"

/** The PDF as the Mac sees it: relative to the Library folder, or only its name outside the Library. */
private fun agentLibraryPdf(file: File, canonicalDirectory: File, document: JsonObject?): String {
    val canonical = file.absoluteFile.parentFile?.canonicalFile == canonicalDirectory.canonicalFile
    val filename = document?.get("filename")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    return when {
        canonical -> "current_pdfs/${file.name}"
        filename.isNotBlank() -> "current_pdfs/$filename"
        // Not in the Library: only the file name means anything off the phone.
        else -> file.name
    }
}

/** "LaTeX" or "Markdown" for a source path, else empty. */
internal fun agentSourceFormat(source: String): String =
    when (source.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        "tex" -> "LaTeX"
        "md", "markdown" -> "Markdown"
        else -> ""
    }

/**
 * The document's placeholders for the review card prompts ([AgentPromptTemplates]): document_name,
 * project, source, source_name, source_format, pdf, project_file and document_id. The same values
 * the lines of [agentDocumentDescription] show, with Library-relative paths.
 */
internal fun agentDocumentFields(
    projects: JsonArray,
    target: ReaderTitleTarget,
    path: String,
    canonicalDirectory: File,
    documentName: String,
): Map<String, String> {
    fun JsonObject?.text(key: String) = this?.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    val (project, document) = readerProjectDocument(projects, target, path, canonicalDirectory)
    val file = File(path)
    val source = document.text("source_file")
    val projectId = project.text("id")
    return buildMap {
        put("document_name", documentName.ifEmpty { file.name })
        put("project", project.text("name").trim())
        if (source.isNotEmpty()) {
            put("source", source)
            put("source_name", source.substringAfterLast('/'))
            // Another kind of source still names its format, so its Source line stays.
            put("source_format", agentSourceFormat(source).ifEmpty { source.substringAfterLast('/').substringAfterLast('.', "") })
        }
        put("pdf", agentLibraryPdf(file, canonicalDirectory, document))
        // The desktop names the settings file when it exists; the catalogue carries its contents then.
        if (projectId.isNotEmpty() && project != null && project.get("project_configuration")?.isJsonObject == true) {
            put("project_file", agentProjectConfigurationPath(project))
        }
        put("document_id", document.text("document_id"))
    }
}

/**
 * "Copy for agent": what a coding agent needs to find the document a reader tab shows, as plain
 * text to paste into Codex or Claude. The same lines as the desktop's agentDocumentDescription,
 * except that paths are relative to the Library folder: the phone cannot know where it is on the Mac.
 */
internal fun agentDocumentDescription(
    projects: JsonArray,
    target: ReaderTitleTarget,
    path: String,
    canonicalDirectory: File,
    documentName: String,
    place: AgentReaderPlace,
): String {
    fun JsonObject?.text(key: String) = this?.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    val (project, document) = readerProjectDocument(projects, target, path, canonicalDirectory)
    val file = File(path)
    val filename = file.name
    val libraryPdf = agentLibraryPdf(file, canonicalDirectory, document)

    val lines = mutableListOf("Thoughts document: \"${documentName.ifEmpty { filename }}\"")
    val projectName = project.text("name").trim()
    if (projectName.isNotEmpty()) lines += "Project: $projectName"
    val source = document.text("source_file")
    if (source.isNotEmpty()) {
        val format = agentSourceFormat(source)
        lines += "Source: $source" + if (format.isEmpty()) "" else "  ($format)"
        // Proposals let the user accept or reject each change in Thoughts.
        lines += "To change the text, create proposals with the Thoughts create_proposals tool (several edits at once) or " +
            "`thoughtsctl create-proposals`; don't edit the source directly unless asked, and never edit the PDF."
    }
    // The page on screen is left out; the Mac drops it too.
    lines += "Built PDF: $libraryPdf"
    val projectId = project.text("id")
    // The desktop lists the settings file when it exists; the catalogue carries its contents then.
    if (projectId.isNotEmpty() && project != null && project.get("project_configuration")?.isJsonObject == true) {
        lines += "Project file: " + agentProjectConfigurationPath(project)
    }
    val documentId = document.text("document_id")
    if (documentId.isNotEmpty()) lines += "Document ID: $documentId"
    val selection = place.selectedText.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (selection.isNotEmpty()) {
        lines += "Selected text: \"$selection\""
        place.sourceLocation?.let { location ->
            lines += "Source location: ${location.path}, " + if (location.firstLine == location.lastLine) {
                "line ${location.firstLine}"
            } else "lines ${location.firstLine}–${location.lastLine}"
        }
    }
    return lines.joinToString("\n") + "\n"
}

/**
 * The tab's "Copy for agent" section on the reader's feedback, as the desktop adds it
 * (agentVersionFeedback): "Text feedback on this PDF version:" and one line per record of the
 * document given on the PDF with SHA-256 [pdfSha256] (marked on it, or last saved while it was on
 * screen), oldest first: `- Kind (page N): "quote" — comment`, with " (resolved)" on a closed
 * question; a drawing or a text box has no quote of its own (`- Text box (page N) — text`). Feedback
 * given on another version is left out, even where the reader finds its passage, and so is every
 * comment (kind other), which is the reader's own. Empty when there is none.
 */
internal fun agentTextFeedbackSection(
    records: List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Feedback>,
    pdfSha256: String,
): String {
    if (pdfSha256.isBlank()) return ""
    fun givenOnVersion(record: com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Feedback) =
        record.pdfSha256.equals(pdfSha256, ignoreCase = true) ||
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewVersionStamp.of(record.payload)?.pdfSha256
                ?.equals(pdfSha256, ignoreCase = true) == true
    // A comment follows the text on every version and is the reader's own: it never goes to agents.
    val given = records.filter { !it.deleted && it.kind != com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Kind.OTHER && givenOnVersion(it) }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    if (given.isEmpty()) return ""
    val whitespace = Regex("\\s+")
    val lines = mutableListOf("Text feedback on this PDF version:")
    for (record in given) {
        val kind = when (record.kind) {
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Kind.QUESTION -> "Question"
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Kind.OTHER -> "Comment"
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Kind.DRAWING -> "Drawing"
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Kind.TEXT -> "Text box"
            null -> if (record.polarity == com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Polarity.NEGATIVE) "Disliked" else "Liked"
        }
        val page = record.regions.firstOrNull()?.page?.takeIf { it > 0 }?.let { " (page $it)" }.orEmpty()
        var quote = record.exact.replace(whitespace, " ").trim()
        if (quote.codePointCount(0, quote.length) > MAX_AGENT_QUOTE) {
            quote = quote.substring(0, quote.offsetByCodePoints(0, MAX_AGENT_QUOTE)) + "…"
        }
        var line = if (record.isPageMark) "- $kind$page" else "- $kind$page: \"$quote\""
        val comment = record.comment.replace(whitespace, " ").trim()
        if (comment.isNotEmpty()) line += " — $comment"
        if (record.kind != null && record.resolvedAt != null) line += " (resolved)"
        lines += line
    }
    // What each kind asks of the agent: a question wants an answer, not an
    // edit; likes and dislikes are what to keep and what to change. As on the desktop.
    if (given.any { it.kind == com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Kind.QUESTION && it.resolvedAt == null }) {
        lines += "Answer each Question in your reply. A question asks you something; it is not a request to change the text, so " +
            "make no proposal for it unless I ask for one."
    }
    if (given.any { it.kind == null }) {
        lines += "Liked and Disliked passages say what to keep and what to change; a change goes in a proposal."
    }
    return lines.joinToString("\n") + "\n"
}

/** Quotes longer than this many characters are cut, as the desktop cuts them. */
private const val MAX_AGENT_QUOTE = 300

/**
 * The tab's "Copy for agent" section on the PDF's review cards, as the desktop adds it before the
 * text feedback (agentVersionFeedback): "Cards reviewed on this PDF version:" and, by page, each
 * card that is not deleted and was answered on the PDF with SHA-256 [pdfSha256] or given answer
 * feedback on it: its answers on that PDF (when, the tags by group with Other last, the notes) and
 * that feedback. Empty when there is none.
 */
internal fun agentReviewedCardsSection(
    cards: List<Pair<com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard, List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewHistoryEntry>>>,
    cardFeedback: List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Feedback>,
    groups: List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Group>,
    mergeTags: (List<String>) -> List<String>,
    pdfSha256: String,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String {
    if (pdfSha256.isBlank()) return ""
    fun onVersion(sha: String?) = sha?.equals(pdfSha256, ignoreCase = true) == true
    val reviewed = cards.filter { (card, _) -> card.schedule?.deleted != true }.mapNotNull { (card, history) ->
        val answers = agentAnswers(history).filter { onVersion(it.pdfSha256) }
        val feedback = cardFeedback.filter { record ->
            record.cardId == card.id && !record.deleted && record.kind != com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Kind.OTHER && (onVersion(record.pdfSha256) ||
                onVersion(com.gitlab.mudlej.MjPdfReader.data.review.ReviewVersionStamp.of(record.payload)?.pdfSha256))
        }
        if (answers.isEmpty() && feedback.isEmpty()) null else Triple(card, answers, feedback)
    }.sortedBy { it.first.page }
    if (reviewed.isEmpty()) return ""
    val whitespace = Regex("\\s+")
    val time = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
    fun label(tag: String) = when (tag) {
        com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags.CORRECT -> "Correct"
        com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags.WRONG -> "Wrong"
        else -> tag
    }
    val lines = mutableListOf("Cards reviewed on this PDF version:")
    for ((card, answers, feedback) in reviewed) {
        val pages = card.regions.map { it.page }.distinct().sorted().joinToString(", ").ifEmpty { card.page.toString() }
        val page = if (pages.isEmpty() || pages == "0") "" else " (page $pages)"
        lines += "- Card ${card.id}$page: \"${card.question.replace(whitespace, " ").trim()}\""
        for (answer in answers) {
            val recorded = if (answer.reviewTagsRecorded) answer.reviewTags else listOfNotNull(answer.reviewAction)
            val tags = mergeTags(recorded)
            val listed = mutableSetOf<String>()
            val parts = mutableListOf<String>()
            for (group in groups) {
                val chosen = (group.scale.orEmpty() + group.tags).filter { it in tags && listed.add(it) }.map(::label)
                if (chosen.isNotEmpty()) parts += "${group.name}: ${chosen.joinToString(", ")}"
            }
            val other = tags.filter { it !in listed }.map(::label)
            if (other.isNotEmpty()) parts += "${com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.OTHER_NAME}: ${other.joinToString(", ")}"
            val at = answer.reviewedAt?.let(time::format) ?: "(no date)"
            lines += "  - Reviewed $at: " + if (parts.isEmpty()) "no tags" else parts.joinToString("; ")
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes.lines(answer.groupNotes, groups).forEach { note ->
                lines += "    - " + note.replace(whitespace, " ").trim()
            }
        }
        val section = reviewCardFeedbackSection(feedback.map { record ->
            ReviewCardFeedbackLine(record.polarity == com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Polarity.POSITIVE,
                record.exact, record.comment, record.createdAt, orphaned = false)
        })
        section.split('\n').filter(String::isNotEmpty).forEach { lines += "  $it" }
    }
    return lines.joinToString("\n") + "\n"
}

/**
 * A card's answers still in effect, oldest first, as the desktop counts them: not retracted, not a
 * skip, an interval change or a deletion (nothing after a deletion), and a custom interval only
 * when it set a later due date.
 */
private fun agentAnswers(
    history: List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewHistoryEntry>,
): List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewHistoryEntry> {
    val answers = mutableListOf<com.gitlab.mudlej.MjPdfReader.data.review.ReviewHistoryEntry>()
    val ordered = history.filter { it.reviewedAt != null && !it.retracted }.sortedWith(compareBy({ it.reviewedAt }, { it.eventId }))
    for (entry in ordered) {
        if (entry.queueAction != null || entry.intervalAction != null) continue
        val rating = entry.rating ?: continue
        if (rating == "delete") break
        if (rating == "custom_interval" && entry.scheduledDueAt?.isAfter(entry.reviewedAt) != true) continue
        if (entry.reviewAction.equals(com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags.SKIP, ignoreCase = true)) continue
        if (entry.reviewTags.isNotEmpty() &&
            entry.reviewTags.all { it.trim().equals(com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags.SKIP, ignoreCase = true) }) continue
        answers += entry
    }
    return answers
}

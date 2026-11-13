// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

/**
 * The review card ⋮ menu's "Copy for agent" text and the study-mode prompt: templates the user can
 * edit in Settings > Agent notes and prompts, with {placeholders} for the card and its document.
 * The desktop has the same defaults, placeholders and rules (part/agentprompts.h).
 */
internal object AgentPromptTemplates {
    enum class Kind { AGENT, STUDY_MODE }

    const val DEFAULT_AGENT_TEMPLATE =
        "Thoughts document: \"{document_name}\"\n" +
            "Project: {project}\n" +
            "Source: {source}  ({source_format})\n" +
            "To change {source_name}, create proposals with the Thoughts create_proposals tool (several edits at once) or " +
            "`thoughtsctl create-proposals`; don't edit the source directly unless asked, and never edit the PDF.\n" +
            "Built PDF: {pdf}  ({pdf_pages} pages)\n" +
            "Project file: {project_file}\n" +
            "Document ID: {document_id}\n" +
            "Review card: {card_id}\n" +
            "Question: \"{question}\"\n" +
            "Note: \"{note}\"\n" +
            "Answer pages: {answer_pages}\n" +
            "Answer source: {answer_source}\n" +
            "{answer_feedback}\n"

    /**
     * Copied when the user turns study mode on: what the agent does until the conversation ends.
     * Editable in Settings > Agent notes and prompts, like the card template.
     */
    const val DEFAULT_STUDY_MODE_TEMPLATE =
        "Agent study mode is on in Thoughts on my phone.\n" +
            "- Physical studying: I study on paper and photograph or film the page with my smart glasses. New captures arrive on " +
            "this computer within seconds: photos in {mentra_folder}/photos as IMG_YYYYMMDD_HHMMSS_*.jpg, videos in " +
            "{mentra_folder}/videos as VID_YYYYMMDD_HHMMSS_*.mp4. Watch both folders for the rest of this conversation (a background " +
            "loop that checks every few seconds; re-arm it when it expires) and react to each new photo or video as soon as it " +
            "appears, without waiting for me. Ignore files that are already there and unfinished *.partial files.\n" +
            "- Read every new photo at full resolution. For a video, take frames with ffmpeg (about one per second, plus the last " +
            "frame) and read the sharpest ones. Also transcribe its audio with `~/.local/bin/transcribe <video>` (AssemblyAI; it " +
            "prints the text, or \"(no audio)\") and treat what I say as part of the context. " +
            "Say what is right and what is wrong or missing, point by point (per formula or " +
            "step). If something is unreadable, say so rather than guess. Anything underlined is where to focus.\n" +
            "- If my phone is in the picture showing an open review card or document in Thoughts, that is what we are studying: " +
            "read its question or title, find it in my library, grade against it and attach your notes to it. A different card on " +
            "the phone means we moved on.\n" +
            "- After every capture you grade, send a Thoughts note with send_note, attached to the card or document we are on (it " +
            "opens on my phone by itself; I mark it up and tap Send), not only a chat answer. The note redraws my page as I laid it " +
            "out (my diagrams, boxes and words) in black ink and marks it: green = right, red = fix (struck through, correction " +
            "next to it), orange = missing, grey = context. Add a one-line legend and short point-by-point notes under the drawing.\n" +
            "- One page, one series of notes: when I photograph the same page again, send the updated redraw of that page in the " +
            "same layout and style, so its notes read as versions of each other. A new page gets its own note.\n" +
            "- To show me anything else (a derivation, a picture), send a note the same way.\n" +
            "- Also watch my note replies for the rest of this conversation: run `~/.local/bin/thoughts-watch-replies` as a " +
            "background monitor (one line per reply; re-arm it when it expires) and answer each reply as soon as it arrives, " +
            "reading it with get_note (get_note_reply in the local Thoughts tools): the picture with my marks, and my comment. " +
            "Answer with a new note when a picture helps.\n" +
            "- The same monitor prints a PAGE line when I send you a page from my phone: the PDF page on my screen, marked " +
            "up or not, with my comment. Open it with get_note and treat it like a capture: grade it and answer with a note " +
            "attached to the same document (and card).\n"

    /**
     * Where the Mentra glasses' captures arrive (photos/ and videos/ inside), on the computer where the agent runs,
     * until one is set in Settings (Gradle property `thoughtsStudyCaptureFolder`, default `~/glasses`).
     */
    val DEFAULT_MENTRA_FOLDER: String = com.gitlab.mudlej.MjPdfReader.BuildConfig.STUDY_CAPTURE_FOLDER.trim().ifEmpty { "~/glasses" }

    fun defaultTemplate(kind: Kind) = when (kind) {
        Kind.AGENT -> DEFAULT_AGENT_TEMPLATE
        Kind.STUDY_MODE -> DEFAULT_STUDY_MODE_TEMPLATE
    }

    /** The card template's placeholders and what they stand for, in the order Settings lists them. */
    private val cardPlaceholders: List<Pair<String, String>> = listOf(
        "document_name" to "the document's name, as its reader tab shows it",
        "project" to "the project's name",
        "source" to "the document's source file, relative to the Library folder",
        "source_name" to "the source file's name, e.g. grpo.tex",
        "source_format" to "LaTeX or Markdown",
        "pdf" to "the built PDF, relative to the Library folder",
        "pdf_pages" to "the built PDF's page count",
        "project_file" to "the project's thoughts-project.json",
        "document_id" to "the document's ID",
        "card_id" to "the review card's ID",
        "question" to "the card's question",
        "note" to "the card's note",
        "answer_pages" to "the answer's pages, e.g. 6 or 6–7",
        "answer_source" to "the answer's source file and lines (between the card's markers)",
        "answer_text" to "the answer itself: those source lines, else the PDF text (not in the defaults)",
        "answer_feedback" to "the passages of the answer you liked or disliked, with your comments (\"Answer feedback:\" lines)",
    )

    private val studyModePlaceholders: List<Pair<String, String>> = listOf(
        "mentra_folder" to "where Mentra glasses captures arrive, with photos/ and videos/ inside (setting below)",
    )

    /** The placeholders [kind]'s template can use. */
    fun placeholders(kind: Kind) = when (kind) {
        Kind.AGENT -> cardPlaceholders
        Kind.STUDY_MODE -> studyModePlaceholders
    }

    private val names = (cardPlaceholders + studyModePlaceholders).map { it.first }.toSet()
    private val pattern = Regex("\\{([a-z_]+)\\}")

    /**
     * [template] with each {placeholder} replaced by its value. A line is left out when one of its
     * placeholders has no value (e.g. "Note:" for a card without a note); a missing value is empty.
     * Braces around anything else, e.g. {x} or LaTeX, are copied as written. The result ends with
     * exactly one newline; values may span several lines.
     */
    fun render(template: String, values: Map<String, String>): String {
        val lines = template.replace("\r\n", "\n").split('\n').mapNotNull { line ->
            var empty = false
            val rendered = pattern.replace(line) { match ->
                val name = match.groupValues[1]
                if (name !in names) return@replace match.value
                val value = values[name].orEmpty().trimEnd()
                if (value.isBlank()) empty = true
                value
            }
            rendered.takeUnless { empty }
        }
        val text = lines.joinToString("\n").trimEnd()
        return if (text.isEmpty()) "" else text + "\n"
    }

    fun usesPlaceholder(template: String, name: String) = template.contains("{$name}")

    /** The card's "Copy for agent" text: [fields] are the card's and document's placeholders. */
    fun renderForCard(fields: Map<String, String>, agentTemplate: String): String {
        val text = render(agentTemplate, fields)
        // A template saved before {answer_feedback} existed still ends with the card's feedback.
        val feedback = fields["answer_feedback"].orEmpty()
        return if (feedback.isBlank() || usesPlaceholder(agentTemplate, "answer_feedback")) text
        else text.trimEnd() + "\n" + feedback.trimEnd() + "\n"
    }

    /** The study-mode prompt: [template] with {mentra_folder} filled in. */
    fun studyModePrompt(template: String, mentraFolder: String) = render(template, mapOf("mentra_folder" to mentraFolder))
}

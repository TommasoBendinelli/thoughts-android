// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import java.text.Normalizer
import kotlin.math.abs

/*
 * A review card for a whole section (as on the desktop: reviewSourceSection and reviewPdfSection in
 * shell/reviewdocumenttools.cpp). The person selects a section or subsection heading and the card
 * hides its body, from just below the heading to just before the next heading of the same or a
 * higher level, across pages; the heading stays visible as the cue. Android-free, for tests.
 */

/**
 * The section of a TeX or Markdown source whose heading is on one of the given lines. [firstLine]
 * to [lastLine] is its body (1-based, inclusive); [nextTitles] are the texts the PDF shows where the
 * next section starts (its heading, or References for a bibliography), empty at the document's end.
 * [isHeading] is set once the lines are known to hold a section heading, even when it is refused.
 */
data class SourceSection(
    val error: String? = null,
    val isHeading: Boolean = false,
    val title: String = "",
    val kind: String = "section",
    val level: Int = 0,
    val headingLine: Int = 0,
    val firstLine: Int = 0,
    val lastLine: Int = 0,
    val nextHeadingLine: Int = 0,
    val nextTitles: List<String> = emptyList(),
)

/** A line of text on a PDF page, in points from the page's top left. */
data class SectionLine(
    val column: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
    /** The median font size of its characters. */
    val size: Float,
) {
    val centreY get() = (top + bottom) / 2
}

/** A page's lines in reading order (column, then top to bottom), its running header, footer and page number left out. */
class SectionPage(val width: Float, val height: Float, val columns: Int, val lines: List<SectionLine>) {
    fun columnExtent(column: Int): Pair<Float, Float> {
        val mine = lines.filter { it.column == column }
        if (mine.isEmpty()) return 0f to width
        return mine.minOf { it.left } to mine.maxOf { it.right }
    }
    val bodySize: Float get() = lines.map { it.size }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
}

/** A PDF's pages, read when first needed (1-based). */
class SectionLayout(val pageCount: Int, private val load: (Int) -> SectionPage?) {
    private val pages = HashMap<Int, SectionPage?>()
    fun page(number: Int): SectionPage? = if (number !in 1..pageCount) null else pages.getOrPut(number) { load(number) }
}

/** An outline (bookmark) entry: its title, depth (1 = top level) and 1-based page. */
data class SectionOutlineEntry(val title: String, val depth: Int, val page: Int)

/** An area of a page, as fractions of its size from the top left. */
data class SectionArea(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A section of a PDF without source: its heading, and the areas its body covers. */
data class PdfSection(
    val error: String? = null,
    val isHeading: Boolean = false,
    val fromOutline: Boolean = false,
    val title: String = "",
    val number: String = "",
    val level: Int = 0,
    val areas: List<SectionArea> = emptyList(),
    val text: String = "",
)

object ReviewSections {
    private val texHeading = Regex("""^\s*\\(part|chapter|section|subsection|subsubsection|paragraph|subparagraph)\*?\s*(\[[^\]]*\]\s*)?\{""")
    private val levels = mapOf("part" to -1, "chapter" to 0, "section" to 1, "subsection" to 2, "subsubsection" to 3, "paragraph" to 4, "subparagraph" to 5)
    private val sectionsEnd = Regex("""^\s*\\(end\s*\{document\}|appendix(?![A-Za-z@])|bibliography\s*\{|bibliographystyle\s*\{|printbibliography(?![A-Za-z@])|begin\s*\{thebibliography\})""")
    private val bibliography = Regex("""^\s*\\(bibliography\s*\{|bibliographystyle\s*\{|printbibliography(?![A-Za-z@])|begin\s*\{thebibliography\})""")
    // Labels, anchors and contents entries print nothing of the section's text.
    private val companion = Regex("""^\s*((\\(label|SourceLabel|index|hypertarget|phantomsection|addcontentsline|pdfbookmark|markboth|markright|nopagebreak)\*?\s*(\[[^\]]*\]\s*)*(\{[^{}]*\}\s*)*)\s*)*$""")
    internal val texMarker = Regex("""^\\ReviewCardMask(Start|End)\{([0-9A-Fa-f-]{36})\}\{([0-9]+)\}%$""")
    internal val markdownMarker = Regex("""^ {0,3}<!-- thoughts:v1:(start|end) review:([0-9A-Fa-f-]{36}):answer:([0-9]+) -->\r?$""")
    private val fenceStart = Regex("""^ {0,3}(`{3,}|~{3,})""")
    private val atx = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*$""")

    /** A TeX line as TeX reads it: without its comment. */
    private fun texActive(line: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '%') break
            out.append(c)
            if (c == '\\' && i + 1 < line.length) out.append(line[++i])
            i++
        }
        return out.toString()
    }

    private fun plainTexTitle(title: String): String = title
        .replace(Regex("""\\[A-Za-z@]+\*?"""), " ")
        .replace(Regex("""\\([^A-Za-z@])"""), "$1")
        .replace(Regex("[{}$]"), "")
        .replace('~', ' ')
        .replace(Regex("\\s+"), " ").trim()

    private fun plainMarkdownTitle(title: String): String = title
        .replace(Regex("""\s*\{[^}]*\}\s*$"""), "")
        .replace(Regex("""!?\[([^\]]*)\]\([^)]*\)"""), "$1")
        .replace(Regex("[*_`]"), "")
        .replace(Regex("\\s+"), " ").trim()

    /** The letters and digits of [text], decomposed and lower case, for comparing PDF and outline text. */
    fun folded(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD).filter(Char::isLetterOrDigit).lowercase()

    private class Heading(val line: Int, val endLine: Int, val level: Int, val name: String, val title: String, val rest: String)

    /**
     * The section whose heading is on one of [headingLines] (a title may run over several lines).
     * TeX headings are \part to \subsubsection, starred or not; the body starts after the labels
     * and anchors below the heading and ends before the next heading of the same or a higher
     * level, the appendix, the bibliography or the end of the document. Markdown headings are # to
     * ###### outside code blocks. Blank and comment lines at either end, and the markers of another
     * card whose passage lies outside, stay out of the body.
     */
    fun sourceSection(path: String, source: String, headingLines: List<Int>): SourceSection {
        val tex = path.endsWith(".tex", ignoreCase = true)
        val markdown = path.endsWith(".md", ignoreCase = true) || path.endsWith(".markdown", ignoreCase = true)
        if (!tex && !markdown) return SourceSection(error = "Choose a LaTeX or Markdown source file.")
        val lines = source.split('\n').let { if (source.endsWith('\n')) it.dropLast(1) else it }
        val count = lines.size
        val wanted = headingLines.filter { it in 1..count }
        if (wanted.isEmpty()) return SourceSection(error = "The heading's source line was not found.")
        val headings = mutableListOf<Heading>()
        val ends = mutableListOf<Pair<Int, Boolean>>() // (line, a bibliography starts there)
        if (tex) {
            val active = lines.map(::texActive)
            val documentStart = active.indexOfFirst { Regex("""\\begin\s*\{document\}""").containsMatchIn(it) }.let { if (it < 0) 0 else it + 1 }
            for (i in documentStart until count) {
                if (sectionsEnd.containsMatchIn(active[i])) { ends += (i + 1) to bibliography.containsMatchIn(active[i]); continue }
                val match = texHeading.find(active[i]) ?: continue
                // The title's braces, over at most six lines.
                var depth = 1
                var line = i
                var at = match.range.last + 1
                val title = StringBuilder()
                var endLine = 0
                var rest = ""
                while (depth > 0 && line < count && line < i + 6) {
                    val text = active[line]
                    while (at < text.length) {
                        val c = text[at]
                        if (c == '\\' && at + 1 < text.length) { title.append(c).append(text[at + 1]); at += 2; continue }
                        if (c == '{') depth++
                        if (c == '}' && --depth == 0) { endLine = line + 1; rest = text.substring(at + 1); break }
                        title.append(c)
                        at++
                    }
                    if (depth > 0) { title.append(' '); line++; at = 0 }
                }
                if (depth > 0) continue
                val name = match.groupValues[1]
                headings += Heading(i + 1, endLine, levels.getValue(name), name, plainTexTitle(title.toString()), rest)
            }
        } else {
            var fence = ""
            for (i in 0 until count) {
                val text = lines[i]
                val trimmed = text.trim()
                if (fence.isNotEmpty()) {
                    val length = trimmed.takeWhile { it == fence[0] }.length
                    if (length >= fence.length && trimmed.substring(length).isEmpty()) fence = ""
                    continue
                }
                val fenceMatch = fenceStart.find(text)
                if (fenceMatch != null) { fence = fenceMatch.groupValues[1]; continue }
                val match = atx.find(text) ?: continue
                val title = match.groupValues[2].replace(Regex("""(^|[ \t]+)#+$"""), "")
                headings += Heading(i + 1, i + 1, match.groupValues[1].length, "", plainMarkdownTitle(title), "")
            }
        }
        val chosen = headings.firstOrNull { heading -> wanted.any { it in heading.line..heading.endLine } }
            ?: return SourceSection(error = "Line ${wanted.first()} of $path is not a section heading.")
        val kind = if (tex) chosen.name else when { chosen.level <= 1 -> "section"; chosen.level == 2 -> "subsection"; else -> "subsubsection" }
        var section = SourceSection(title = chosen.title, kind = kind, level = chosen.level, headingLine = chosen.line)
        if (tex && chosen.level > 3)
            return section.copy(error = "“${chosen.title}” is a paragraph heading, which runs into its text. Select the passage instead.")
        section = section.copy(isHeading = true)
        if (tex && !companion.matches(chosen.rest))
            return section.copy(error = "The text of “${chosen.title}” starts on its heading's line (line ${chosen.endLine} of $path). " +
                "Put the heading on a line of its own to make a card of its section.")
        var first = chosen.endLine + 1
        if (tex) while (first <= count && companion.matches(texActive(lines[first - 1])) && !texMarker.matches(lines[first - 1])) first++
        val nextHeading = headings.firstOrNull { it.line > chosen.endLine && it.level <= chosen.level }
        val end = ends.firstOrNull { it.first > chosen.endLine && (nextHeading == null || it.first < nextHeading.line) }
        val next = end?.first ?: nextHeading?.line ?: 0
        // What the PDF shows where the next section starts.
        val nextTitles = when {
            end == null -> listOfNotNull(nextHeading?.title)
            end.second -> listOf("References", "Bibliography")
            else -> listOfNotNull(headings.firstOrNull { it.line > end.first }?.title, "Appendix").takeIf {
                !Regex("""\\end\s*\{document\}""").containsMatchIn(texActive(lines[end.first - 1]))
            }.orEmpty()
        }
        var last = if (next > 0) next - 1 else count
        val range = trimBody(lines, tex, first, last)
        first = range.first; last = range.last
        if (first > last) return section.copy(error = "The section “${chosen.title}” has no text below its heading.")
        return section.copy(firstLine = first, lastLine = last, nextHeadingLine = next, nextTitles = nextTitles.filter { folded(it).isNotEmpty() })
    }

    /** The body without blank (and TeX comment) lines at its ends, nor the markers of a card whose passage reaches past an end. */
    private fun trimBody(lines: List<String>, tex: Boolean, from: Int, to: Int): IntRange {
        val marker = if (tex) texMarker else markdownMarker
        fun blank(line: Int) = lines[line - 1].isBlank() || (tex && lines[line - 1].trimStart().startsWith('%'))
        fun markerOf(line: Int) = marker.find(lines[line - 1])
        fun partner(line: Int, match: MatchResult, direction: Int): Int {
            val start = match.groupValues[1].equals("start", ignoreCase = true)
            var other = line + direction
            while (other in 1..lines.size) {
                val candidate = markerOf(other)
                if (candidate != null && candidate.groupValues[2].equals(match.groupValues[2], ignoreCase = true) &&
                    candidate.groupValues[3] == match.groupValues[3] && candidate.groupValues[1].equals("start", ignoreCase = true) != start) return other
                other += direction
            }
            return 0
        }
        var first = from
        var last = to
        var changed = true
        while (changed && first <= last) {
            changed = false
            while (first <= last && blank(first)) { first++; changed = true }
            while (last >= first && blank(last)) { last--; changed = true }
            if (first > last) break
            val opening = markerOf(first)
            val closing = markerOf(last)
            if (opening != null && opening.groupValues[1].equals("end", ignoreCase = true)) { first++; changed = true; continue }
            if (closing != null && closing.groupValues[1].equals("start", ignoreCase = true)) { last--; changed = true; continue }
            if (opening != null) { val end = partner(first, opening, 1); if (end == 0 || end > last) { first++; changed = true; continue } }
            if (closing != null) { val start = partner(last, closing, -1); if (start == 0 || start < first) { last--; changed = true; continue } }
        }
        return first..last
    }

    /** Other cards whose passages lie in lines [first]..[last] of [source]. */
    fun wrappedCards(path: String, source: String, first: Int, last: Int): Int {
        val marker = if (path.endsWith(".tex", ignoreCase = true)) texMarker else markdownMarker
        val lines = source.split('\n')
        return (first..last).mapNotNull { lines.getOrNull(it - 1)?.let(marker::find)?.groupValues?.get(2)?.lowercase() }.toSet().size
    }

    // ---- PDF headings ------------------------------------------------------------------------

    /** A heading's number: 2, 2.1, A.1, A., II. (a letter or a Roman numeral only with a dot after it). */
    internal data class HeadingNumber(val parts: List<Int>, val letter: Boolean = false, val roman: Boolean = false, val rest: String = "") {
        val valid get() = parts.isNotEmpty()
        val text get() = parts.mapIndexed { i, part -> if (i == 0 && letter) ('A' + part - 1).toString() else part.toString() }.joinToString(".")
    }
    private val numbered = Regex("""^\s*(?:(\d+(?:\.\d+)*)\.?|([A-Z])((?:\.\d+)+\.?|\.)|([IVXLC]+)\.)\s+(\S.*)$""")
    private fun roman(text: String): Int {
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100)
        var total = 0
        for (i in text.indices) {
            val value = values[text[i]] ?: return 0
            total += if (i + 1 < text.length && (values[text[i + 1]] ?: 0) > value) -value else value
        }
        return total
    }
    internal fun headingNumber(text: String): HeadingNumber {
        val match = numbered.find(text) ?: return HeadingNumber(emptyList())
        val rest = match.groupValues[5]
        return when {
            match.groupValues[1].isNotEmpty() -> HeadingNumber(match.groupValues[1].split('.').map { it.toInt() }, rest = rest)
            match.groupValues[2].isNotEmpty() -> HeadingNumber(listOf(match.groupValues[2][0] - 'A' + 1) +
                match.groupValues[3].split('.').filter(String::isNotEmpty).map { it.toInt() }, letter = true, rest = rest)
            else -> roman(match.groupValues[4]).let { if (it > 0) HeadingNumber(listOf(it), roman = true, rest = rest) else HeadingNumber(emptyList()) }
        }
    }
    /** Whether [candidate] numbers the next heading of the same or a higher level after [heading] (2.1 → 2.2 or 3; appendix A after sections). */
    internal fun nextNumber(heading: HeadingNumber, candidate: HeadingNumber): Boolean {
        if (!candidate.valid || candidate.parts.size > heading.parts.size || candidate.roman != heading.roman) return false
        if (candidate.letter && !heading.letter) return candidate.parts == listOf(1)
        if (candidate.letter != heading.letter) return false
        val level = candidate.parts.size
        return (0 until level - 1).all { candidate.parts[it] == heading.parts[it] } && candidate.parts[level - 1] == heading.parts[level - 1] + 1
    }
    private val closing = setOf("references", "bibliography", "acknowledgments", "acknowledgements", "acknowledgment", "acknowledgement", "appendix", "appendices")
    private fun closingHeading(text: String): Boolean {
        val number = headingNumber(text)
        return folded(if (number.valid) number.rest else text) in closing
    }
    private fun words(text: String) = text.trim().split(Regex("\\s+")).count(String::isNotEmpty)

    /** A place in reading order: a page, a column on it, and a height in points. */
    private data class Place(val page: Int, val column: Int, val y: Float) : Comparable<Place> {
        override fun compareTo(other: Place) = compareValuesBy(this, other, Place::page, Place::column, Place::y)
    }

    /** The lines of [heading]'s page whose text lies in it (a word's centre inside, give or take a point). */
    private fun headingLines(layout: SectionLayout, heading: SectionArea): List<Int> {
        val page = layout.page(heading.page) ?: return emptyList()
        val left = heading.left * page.width - 1; val right = heading.right * page.width + 1
        val top = heading.top * page.height - 1; val bottom = heading.bottom * page.height + 1
        return page.lines.indices.filter { i ->
            val line = page.lines[i]
            line.centreY in top..bottom && line.right >= left && line.left <= right
        }
    }

    /** The areas between two places in reading order: per page and column, as wide as the column's text. */
    private fun areasBetween(layout: SectionLayout, start: Place, end: Place): Pair<List<SectionArea>, String> {
        val areas = mutableListOf<SectionArea>()
        val text = StringBuilder()
        for (number in start.page..minOf(end.page, layout.pageCount)) {
            val page = layout.page(number) ?: continue
            for (column in 0 until page.columns) {
                if (Place(number, column, page.height + 1) < start || end < Place(number, column, -1f)) continue
                val first = number == start.page && column == start.column
                val last = number == end.page && column == end.column
                val inside = page.lines.filter { line ->
                    line.column == column && !(first && line.centreY <= start.y) && !(last && line.centreY >= end.y)
                }
                if (inside.isEmpty()) continue
                inside.forEach { text.append(it.text).append(' ') }
                // From just below the heading to just above the next one; elsewhere around the text.
                val top = (if (first) start.y + 0.5f else inside.minOf { it.top } - 1).coerceAtLeast(0f)
                val bottom = (if (last) end.y - 0.5f else inside.maxOf { it.bottom } + 1).coerceAtMost(page.height)
                val (left, right) = page.columnExtent(column)
                if (bottom - top < 2) continue
                areas += SectionArea(number, (left - 2).coerceAtLeast(0f) / page.width, top / page.height,
                    (right + 2).coerceAtMost(page.width) / page.width, bottom / page.height)
            }
        }
        return areas to text.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * For a note built from source: the areas from just below [heading] to the next line in reading
     * order that shows one of [nextTitles] (the next section's heading), or to the end of [lastPage].
     */
    fun areasToNextHeading(layout: SectionLayout, heading: SectionArea, nextTitles: List<String>, lastPage: Int): Pair<List<SectionArea>, String> {
        val page = layout.page(heading.page) ?: return emptyList<SectionArea>() to ""
        val chosen = headingLines(layout, heading)
        if (chosen.isEmpty()) return emptyList<SectionArea>() to ""
        val headingLine = page.lines[chosen.last()]
        val start = Place(heading.page, headingLine.column, headingLine.bottom)
        val wanted = nextTitles.filter { folded(it).length >= 2 }
        val final = minOf(lastPage, layout.pageCount)
        var end = Place(final, (layout.page(final)?.columns ?: 1) - 1, (layout.page(final)?.height ?: 0f) + 1)
        search@ for (number in heading.page..final) {
            val lines = layout.page(number)?.lines ?: continue
            for (line in lines) {
                if (!(start < Place(number, line.column, line.top))) continue
                if (words(line.text) > 20) continue
                if (wanted.any { showsTitle(line.text, it) }) { end = Place(number, line.column, line.top); break@search }
            }
        }
        return areasBetween(layout, start, end)
    }

    /**
     * Whether a PDF line shows the heading [title]: the same letters, perhaps after the heading's
     * number ("2.2 Model" for Model), or the start of a title that runs on to the next line. A line
     * of text that merely mentions the title ("... before the results.") does not.
     */
    internal fun showsTitle(line: String, title: String): Boolean {
        val wanted = folded(title)
        if (wanted.length < 2) return false
        val number = headingNumber(line)
        return listOfNotNull(folded(line), if (number.valid) folded(number.rest) else null).any { shown ->
            shown == wanted || (shown.length >= 3 && wanted.startsWith(shown))
        }
    }

    /**
     * The section of a PDF without source whose heading [heading] shows: its outline entry on that
     * page, then the next entry of the same or a higher level; or, without one, the heading's
     * number (2.1 ends at 2.2, 3, References, ...) or, unnumbered, its type size (the next line
     * set at least as large). With [identifyOnly] only the heading.
     */
    fun pdfSection(layout: SectionLayout, outline: List<SectionOutlineEntry>, heading: SectionArea, identifyOnly: Boolean = false): PdfSection {
        val page = layout.page(heading.page) ?: return PdfSection(error = "The heading's page is unavailable.")
        val chosen = headingLines(layout, heading)
        if (chosen.isEmpty()) return PdfSection(error = "Select the heading's text.")
        val lineText = chosen.joinToString(" ") { page.lines[it].text }
        val lineLetters = folded(lineText)
        var headingLast = chosen.last()
        // The entry whose title the selected line shows (a line that mentions it is text, not its heading).
        val found = outline.indices.firstOrNull { i -> outline[i].page == heading.page && showsTitle(lineText, outline[i].title) }
        var end = Place(layout.pageCount + 1, 0, 0f)
        var section: PdfSection
        if (found != null) {
            val entry = outline[found]
            val number = headingNumber(entry.title)
            val shown = headingNumber(lineText)
            section = PdfSection(isHeading = true, fromOutline = true, level = entry.depth,
                title = if (number.valid) number.rest.trim() else entry.title.trim(),
                number = if (number.valid) number.text else if (shown.valid) shown.text else "")
            // A title over several lines: the lines below that continue it.
            var letters = lineLetters
            val title = folded(entry.title)
            while (headingLast + 1 < page.lines.size && letters.length < title.length && page.lines[headingLast + 1].column == page.lines[headingLast].column) {
                val more = letters + folded(page.lines[headingLast + 1].text)
                if (!title.contains(more) && !more.contains(title)) break
                letters = more
                headingLast++
            }
            if (identifyOnly) return section
            val start = Place(heading.page, page.lines[headingLast].column, page.lines[headingLast].bottom)
            outline.drop(found + 1).firstOrNull { it.depth <= entry.depth && it.page >= 1 }?.let { next ->
                end = layout.page(next.page)?.lines?.firstOrNull { line ->
                    showsTitle(line.text, next.title) && start < Place(next.page, line.column, line.top)
                }?.let { Place(next.page, it.column, it.top) } ?: Place(next.page, 0, 0f)
            }
            if (end.page > layout.pageCount) end = lastPlace(layout)
            val (areas, text) = areasBetween(layout, start, end)
            return if (areas.isEmpty()) section.copy(error = "The section “${section.title}” has no text below its heading.") else section.copy(areas = areas, text = text)
        }
        // Without an outline entry: a heading line, numbered or set larger than the text.
        val line = page.lines[chosen.first()]
        val number = headingNumber(lineText)
        val size = line.size
        val body = page.bodySize
        val trimmed = lineText.trim()
        val shortLine = chosen.size <= 2 && words(trimmed) <= 15 && trimmed.length <= 120
        val sentence = trimmed.endsWith(',') || trimmed.endsWith(';') || (trimmed.endsWith('.') && words(trimmed) > 8)
        if (!shortLine || sentence || size < 0.95f * body || (!number.valid && size < 1.08f * body))
            return PdfSection(error = "This is not a section heading: the PDF has no outline entry for it, and it is neither numbered nor set larger than the text.")
        section = PdfSection(isHeading = true, number = if (number.valid) number.text else "",
            title = if (number.valid) number.rest.trim() else trimmed, level = if (number.valid) number.parts.size else 1)
        if (identifyOnly) return section
        val start = Place(heading.page, page.lines[headingLast].column, page.lines[headingLast].bottom)
        search@ for (p in heading.page..layout.pageCount) {
            val candidatePage = layout.page(p) ?: continue
            for (candidate in candidatePage.lines) {
                if (!(start < Place(p, candidate.column, candidate.top))) continue
                val text = candidate.text.trim()
                if (words(text) > 20 || candidate.size < 0.9f * size) continue
                // A numbered line of text ("3 of them ...") is no heading: a heading's title starts with
                // a capital, and its line is short or set larger than the text.
                val (columnLeft, columnRight) = candidatePage.columnExtent(candidate.column)
                val headingLike = candidate.right - candidate.left <= 0.8f * (columnRight - columnLeft) || candidate.size >= 1.05f * candidatePage.bodySize
                val candidateNumber = headingNumber(text)
                val next = if (number.valid) headingLike && candidateNumber.rest.firstOrNull()?.isUpperCase() == true && nextNumber(number, candidateNumber)
                    else candidate.size >= 0.95f * size && !text.endsWith('.')
                if (next || (headingLike && closingHeading(text))) { end = Place(p, candidate.column, candidate.top); break@search }
            }
        }
        if (end.page > layout.pageCount) end = lastPlace(layout)
        val (areas, text) = areasBetween(layout, start, end)
        return if (areas.isEmpty()) section.copy(error = "The section “${section.title}” has no text below its heading.") else section.copy(areas = areas, text = text)
    }

    private fun lastPlace(layout: SectionLayout): Place {
        val last = layout.page(layout.pageCount)
        return Place(layout.pageCount, (last?.columns ?: 1) - 1, (last?.height ?: 0f) + 1)
    }

    // ---- Pages from their characters ---------------------------------------------------------

    /** One character of a page: its box in points from the top left, its code point and font size. */
    data class PageChar(val left: Float, val top: Float, val right: Float, val bottom: Float, val codePoint: Int, val size: Float)

    /**
     * A page's lines from its characters in content order: words split at spaces and gaps, lines
     * from words whose vertical centres share a band, and two columns when most lines leave a wide
     * gap at the page's middle. [furniture] (running headers, page numbers) is left out by the caller.
     */
    fun pageFromChars(width: Float, height: Float, chars: List<PageChar>): SectionPage {
        class Word(var left: Float, var top: Float, var right: Float, var bottom: Float, val text: StringBuilder, val sizes: MutableList<Float>)
        val words = mutableListOf<Word>()
        var current: Word? = null
        for (c in chars) {
            val previous = current
            if (Character.isWhitespace(c.codePoint)) { current = null; continue }
            // A character without a box (part of a ligature) belongs to the word it is in.
            if (c.right <= c.left || c.bottom <= c.top) { previous?.text?.appendCodePoint(c.codePoint); continue }
            val centre = (c.top + c.bottom) / 2
            val joins = previous != null && centre in previous.top..previous.bottom && c.left >= previous.right - 1 &&
                c.left - previous.right <= 0.25f * maxOf(c.size, 1f)
            if (joins) {
                previous!!.right = maxOf(previous.right, c.right); previous.top = minOf(previous.top, c.top); previous.bottom = maxOf(previous.bottom, c.bottom)
                previous.text.appendCodePoint(c.codePoint); previous.sizes += c.size
            } else {
                current = Word(c.left, c.top, c.right, c.bottom, StringBuilder().appendCodePoint(c.codePoint), mutableListOf(c.size))
                words += current
            }
        }
        // Bands of words whose centres share a line, top to bottom.
        val bands = mutableListOf<MutableList<Word>>()
        var bandTop = 0f; var bandBottom = -1f
        for (word in words.sortedBy { (it.top + it.bottom) / 2 }) {
            val centre = (word.top + word.bottom) / 2
            if (bands.isNotEmpty() && centre in bandTop..bandBottom) {
                bands.last() += word; bandTop = minOf(bandTop, word.top); bandBottom = maxOf(bandBottom, word.bottom)
            } else { bands += mutableListOf(word); bandTop = word.top; bandBottom = word.bottom }
        }
        val middle = width / 2
        fun crosses(band: List<Word>): Boolean {
            if (band.any { it.left < middle - 4 && it.right > middle + 4 }) return true
            val leftEdge = band.filter { (it.left + it.right) / 2 < middle }.maxOfOrNull { it.right } ?: return false
            val rightEdge = band.filter { (it.left + it.right) / 2 >= middle }.minOfOrNull { it.left } ?: return false
            return rightEdge - leftEdge < 8
        }
        val spanning = bands.count(::crosses)
        val left = bands.count { band -> !crosses(band) && band.any { it.right <= middle + 4 } }
        val right = bands.count { band -> !crosses(band) && band.any { it.left >= middle - 4 } }
        val twoColumns = bands.size >= 10 && spanning * 5 <= bands.size && left * 4 >= bands.size && right * 4 >= bands.size
        val lines = mutableListOf<SectionLine>()
        for (column in 0 until if (twoColumns) 2 else 1) {
            val mine = bands.flatMap { band ->
                val across = crosses(band)
                band.filter { word -> (if (!twoColumns || across || (word.left + word.right) / 2 < middle) 0 else 1) == column }
            }.sortedBy { (it.top + it.bottom) / 2 }
            val grouped = mutableListOf<MutableList<Word>>()
            var top = 0f; var bottom = -1f
            for (word in mine) {
                val centre = (word.top + word.bottom) / 2
                if (grouped.isNotEmpty() && centre in top..bottom) {
                    grouped.last() += word; top = minOf(top, word.top); bottom = maxOf(bottom, word.bottom)
                } else { grouped += mutableListOf(word); top = word.top; bottom = word.bottom }
            }
            for (group in grouped) {
                val sorted = group.sortedBy { it.left }
                val sizes = sorted.flatMap { it.sizes }.sorted()
                lines += SectionLine(column, sorted.minOf { it.left }, sorted.minOf { it.top }, sorted.maxOf { it.right }, sorted.maxOf { it.bottom },
                    sorted.joinToString(" ") { it.text }, sizes[sizes.size / 2])
            }
        }
        return SectionPage(width, height, if (twoColumns) 2 else 1, lines)
    }

    /**
     * [page] without its running header, footer and page number: its first or last lines (in its
     * top or bottom tenth) that a neighbouring page repeats at the same height with the same
     * letters, or lines there without letters (page numbers).
     */
    fun withoutFurniture(page: SectionPage, neighbours: List<SectionPage>): SectionPage {
        fun repeated(line: SectionLine) = neighbours.any { other ->
            other.lines.any { abs(it.centreY - line.centreY) <= 3 && folded(it.text).filter(Char::isLetter) == folded(line.text).filter(Char::isLetter) }
        }
        val edge = page.height / 10
        val kept = page.lines.filterNot { line ->
            (line.centreY < edge || line.centreY > page.height - edge) &&
                (folded(line.text).none(Char::isLetter) && line.text.isNotBlank() || repeated(line))
        }
        return SectionPage(page.width, page.height, page.columns, kept)
    }

    /** "subsection 2.1 “Method”" or "subsection “Method”". */
    fun name(kind: String, number: String, title: String): String =
        if (number.isEmpty()) "${kind.ifEmpty { "section" }} “$title”" else "${kind.ifEmpty { "section" }} $number “$title”"

    fun kindOfLevel(level: Int) = when { level <= 1 -> "section"; level == 2 -> "subsection"; else -> "subsubsection" }
}

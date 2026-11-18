// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import org.junit.Assert.*
import org.junit.Test

class ReviewSectionsTest {
    private val card = "20000000-0000-4000-8000-000000000001"
    private val inner = "30000000-0000-4000-8000-000000000002"
    private val outer = "40000000-0000-4000-8000-000000000003"

    private val tex = listOf(
        "\\documentclass{article}",                                  // 1
        "\\begin{document}",                                         // 2
        "\\section{Training}",                                       // 3
        "Intro text.",                                               // 4
        "\\subsection*{Global batch, mini-batch and micro-batch}",   // 5
        "\\hypertarget{gb}{}",                                       // 6
        "",                                                          // 7
        "\\paragraph{Pretraining} The global batch is split.",       // 8
        "\\ReviewCardMaskStart{$inner}{0}%",                         // 9
        "A micro-batch goes through one pass.",                      // 10
        "\\ReviewCardMaskEnd{$inner}{0}%",                           // 11
        "\\begin{figure}",                                           // 12
        "\\caption{A global batch.}",                                // 13
        "\\end{figure}",                                             // 14
        "\\subsubsection*{Gradient accumulation}",                   // 15
        "Gradients add up.",                                         // 16
        "% a comment",                                               // 17
        "",                                                          // 18
        "\\subsection*{Collective operations}",                      // 19
        "All-reduce.",                                               // 20
        "\\section{Parallelism}",                                    // 21
        "Data parallelism.",                                         // 22
        "\\bibliography{refs}",                                      // 23
        "\\end{document}",                                           // 24
    ).joinToString("\n", postfix = "\n")

    @Test fun texSubsectionRunsToTheNextHeadingOfItsLevel() {
        val section = ReviewSections.sourceSection("docs-test/tex/source/main.tex", tex, listOf(5))
        assertNull(section.error)
        assertTrue(section.isHeading)
        assertEquals("Global batch, mini-batch and micro-batch", section.title)
        assertEquals("subsection", section.kind)
        // Below the heading's anchor; the paragraph heading, another card, a figure and a
        // subsubsection inside; blank and comment lines at the end left out.
        assertEquals(8, section.firstLine)
        assertEquals(16, section.lastLine)
        assertEquals(19, section.nextHeadingLine)
        assertEquals(listOf("Collective operations"), section.nextTitles)
        assertEquals(1, ReviewSections.wrappedCards("x.tex", tex, section.firstLine, section.lastLine))
    }

    @Test fun texSectionsEndAtAHigherHeadingOrTheBibliography() {
        val path = "docs-test/tex/source/main.tex"
        // A subsection ends where a section starts.
        val collective = ReviewSections.sourceSection(path, tex, listOf(19))
        assertEquals(20 to 20, collective.firstLine to collective.lastLine)
        assertEquals(listOf("Parallelism"), collective.nextTitles)
        // A section holds its subsections.
        val training = ReviewSections.sourceSection(path, tex, listOf(3))
        assertEquals(4 to 20, training.firstLine to training.lastLine)
        // The last section ends at the bibliography, which the PDF shows as References.
        val last = ReviewSections.sourceSection(path, tex, listOf(21))
        assertEquals(22 to 22, last.firstLine to last.lastLine)
        assertEquals(listOf("References", "Bibliography"), last.nextTitles)
        val ending = "\\begin{document}\n\\section{Only}\nText.\n\\end{document}\n"
        val only = ReviewSections.sourceSection(path, ending, listOf(2))
        assertEquals(3 to 3, only.firstLine to only.lastLine)
        assertTrue(only.nextTitles.isEmpty())
    }

    @Test fun texRefusesWhatIsNoSectionHeading() {
        val path = "docs-test/tex/source/main.tex"
        val text = ReviewSections.sourceSection(path, tex, listOf(10))
        assertFalse(text.isHeading)
        assertNotNull(text.error)
        // A paragraph heading runs into its text: an ordinary card instead.
        val paragraph = ReviewSections.sourceSection(path, tex, listOf(8))
        assertFalse(paragraph.isHeading)
        // A heading sharing its line with text is a heading, but its section cannot be marked.
        val shared = ReviewSections.sourceSection(path, "\\begin{document}\n\\section{Intro} Text here.\nMore.\n\\end{document}\n", listOf(2))
        assertTrue(shared.isHeading)
        assertTrue(shared.error!!.contains("starts on its heading's line"))
        // A title over two lines is found from either line.
        val long = "\\begin{document}\n\\section{A long\ntitle}\\label{x}\nBody.\n\\end{document}\n"
        val found = ReviewSections.sourceSection(path, long, listOf(3))
        assertEquals("A long title", found.title)
        assertEquals(4 to 4, found.firstLine to found.lastLine)
    }

    @Test fun cardMarkersAtTheEdgesStayOutUnlessTheirPassageIsInside() {
        val path = "docs-test/tex/source/main.tex"
        val source = listOf(
            "\\begin{document}",                        // 1
            "\\ReviewCardMaskStart{$outer}{0}%",        // 2  a card over the heading ...
            "\\section{One}",                           // 3
            "First.",                                   // 4
            "\\ReviewCardMaskEnd{$outer}{0}%",          // 5  ... ending inside the section
            "Second.",                                  // 6
            "\\ReviewCardMaskStart{$inner}{0}%",        // 7  a card over the next heading
            "\\section{Two}",                           // 8
            "Third.",                                   // 9
            "\\ReviewCardMaskEnd{$inner}{0}%",          // 10
            "\\end{document}",                          // 11
        ).joinToString("\n", postfix = "\n")
        val one = ReviewSections.sourceSection(path, source, listOf(3))
        assertEquals(4 to 6, one.firstLine to one.lastLine)
        // The card over the heading ends inside the body: marking it would cut that card's passage.
        assertTrue(runCatching { ReviewSourceMarkup.placeSection(path, source, card, one.firstLine, one.lastLine) }
            .exceptionOrNull()!!.message!!.contains("partly overlaps"))
        // The card over the next heading stays outside the section.
        val two = ReviewSections.sourceSection(path, source, listOf(8))
        assertEquals(9 to 9, two.firstLine to two.lastLine)
    }

    @Test fun texSectionMarkersHoldOtherCardsWhole() {
        val path = "docs-test/tex/source/main.tex"
        val section = ReviewSections.sourceSection(path, tex, listOf(5))
        val edit = ReviewSourceMarkup.placeSection(path, tex, card, section.firstLine, section.lastLine)
        val lines = edit.after.split('\n')
        assertEquals("\\ReviewCardMaskStart{$card}{0}%", lines[7])
        assertEquals("\\paragraph{Pretraining} The global batch is split.", lines[8])
        assertEquals("Gradients add up.", lines[16])
        assertEquals("\\ReviewCardMaskEnd{$card}{0}%", lines[17])
        val segment = edit.anchor!!.getAsJsonArray("segments").single().asJsonObject
        assertEquals("0", segment.get("segment_id").asString)
        assertEquals(tex, ReviewSourceMarkup.remove(path, edit.after, card, segment, false).after)
        // The inner card's passage is untouched and still readable.
        assertEquals(1, ReviewSourceMarkup.anchor(path, edit.after, inner).getAsJsonArray("segments").size())
        // A passage that would cut another card's passage is refused.
        val crossing = tex.replace("\\ReviewCardMaskEnd{$inner}{0}%\n", "").replace("Intro text.\n", "Intro text.\n\\ReviewCardMaskEnd{$inner}{0}%\n")
        assertTrue(runCatching { ReviewSourceMarkup.placeSection(path, crossing, card, 9, 17) }.isFailure)
    }

    private val markdown = listOf(
        "# Notes",                      // 1
        "",                             // 2
        "## Global batch {#gb}",        // 3
        "The global batch is split.",   // 4
        "",                             // 5
        "```",                          // 6
        "# not a heading",              // 7
        "```",                          // 8
        "",                             // 9
        "### Accumulation",             // 10
        "Gradients add up.",            // 11
        "",                             // 12
        "## Collective operations",     // 13
        "All-reduce.",                  // 14
        "",                             // 15
    ).joinToString("\n")

    @Test fun markdownSectionSkipsCodeBlocksAndEndsAtItsLevel() {
        val path = "docs-test/notes.md"
        val section = ReviewSections.sourceSection(path, markdown, listOf(3))
        assertNull(section.error)
        assertEquals("Global batch", section.title)
        assertEquals("subsection", section.kind)
        assertEquals(4 to 11, section.firstLine to section.lastLine)
        assertEquals(listOf("Collective operations"), section.nextTitles)
        // The top heading holds everything; the last section runs to the end of the file.
        assertEquals(3 to 14, ReviewSections.sourceSection(path, markdown, listOf(1)).let { it.firstLine to it.lastLine })
        assertEquals(14 to 14, ReviewSections.sourceSection(path, markdown, listOf(13)).let { it.firstLine to it.lastLine })
        assertFalse(ReviewSections.sourceSection(path, markdown, listOf(7)).isHeading)
    }

    @Test fun markdownSectionMarkersFollowTheHeading() {
        val path = "docs-test/notes.md"
        val wrapped = markdown.replace("Gradients add up.\n",
            "<!-- thoughts:v1:start review:$inner:answer:0 -->\nGradients add up.\n<!-- thoughts:v1:end review:$inner:answer:0 -->\n")
        val section = ReviewSections.sourceSection(path, wrapped, listOf(3))
        assertEquals(4 to 13, section.firstLine to section.lastLine)
        val edit = ReviewSourceMarkup.placeSection(path, wrapped, card, section.firstLine, section.lastLine)
        val lines = edit.after.split('\n')
        assertEquals("## Global batch {#gb}", lines[2])
        assertEquals("<!-- thoughts:v1:start review:$card:answer:0 -->", lines[3])
        assertEquals("The global batch is split.", lines[4])
        assertEquals("<!-- thoughts:v1:end review:$inner:answer:0 -->", lines[13])
        assertEquals("<!-- thoughts:v1:end review:$card:answer:0 -->", lines[14])
        assertEquals("## Collective operations", lines[16])
        val segment = edit.anchor!!.getAsJsonArray("segments").single().asJsonObject
        assertEquals(wrapped, ReviewSourceMarkup.remove(path, edit.after, card, segment, false).after)
        assertEquals(1, ReviewSections.wrappedCards(path, wrapped, section.firstLine, section.lastLine))
    }

    // ---- PDF sections ----------------------------------------------------------------------

    private fun line(top: Float, text: String, size: Float = 10f, left: Float = 72f, right: Float = 540f, column: Int = 0) =
        SectionLine(column, left, top, right, top + size, text, size)

    private fun layout(vararg pages: List<SectionLine>, columns: Int = 1) =
        SectionLayout(pages.size) { SectionPage(612f, 792f, columns, pages[it - 1]) }

    private fun area(page: Int, top: Float, size: Float = 10f) = SectionArea(page, 72f / 612, top / 792, 300f / 612, (top + size) / 792)

    private val page1 = listOf(
        line(72f, "1 Introduction", 14f, right = 200f),
        line(100f, "Some introduction text that runs over the line."),
        line(130f, "2 Method", 14f, right = 160f),
        line(160f, "The method text runs here across the column width."),
        line(175f, "3 of the runs failed, which this full line explains."),
        line(700f, "More method text at the bottom of the page."),
    )
    private val page2 = listOf(
        line(72f, "Method continues on the next page here, before the results."),
        line(100f, "2.1 Data", 12f, right = 140f),
        line(120f, "Data text."),
        line(150f, "3 Results", 14f, right = 160f),
        line(180f, "Results text."),
    )

    @Test fun outlineEntryEndsAtTheNextEntryOfItsLevel() {
        val outline = listOf(SectionOutlineEntry("1 Introduction", 1, 1), SectionOutlineEntry("2 Method", 1, 1),
            SectionOutlineEntry("2.1 Data", 2, 2), SectionOutlineEntry("3 Results", 1, 2))
        val method = ReviewSections.pdfSection(layout(page1, page2), outline, area(1, 130f, 14f))
        assertNull(method.error)
        assertTrue(method.fromOutline)
        assertEquals("Method", method.title)
        assertEquals("2", method.number)
        assertEquals(listOf(1, 2), method.areas.map { it.page })
        // From just below the heading on page 1 to just above "3 Results" on page 2, over its subsection.
        assertEquals(144.5f / 792, method.areas[0].top, 1e-4f)
        assertEquals(149.5f / 792, method.areas[1].bottom, 1e-4f)
        assertTrue(method.text.contains("Data text."))
        assertFalse(method.text.contains("Results"))
        // A line of text that mentions an entry's title is not its heading.
        assertFalse(ReviewSections.pdfSection(layout(page1, page2), outline, area(1, 160f)).isHeading)
        val data = ReviewSections.pdfSection(layout(page1, page2), outline, area(2, 100f, 12f))
        assertEquals(listOf(2), data.areas.map { it.page })
        assertEquals("Data text.", data.text)
    }

    @Test fun numberedHeadingWithoutOutlineEndsAtTheNextNumber() {
        val method = ReviewSections.pdfSection(layout(page1, page2), emptyList(), area(1, 130f, 14f))
        assertNull(method.error)
        assertFalse(method.fromOutline)
        // "3 of the runs failed ..." is a full line of text, not the next heading.
        assertEquals(listOf(1, 2), method.areas.map { it.page })
        assertTrue(method.text.contains("3 of the runs failed"))
        assertFalse(method.text.contains("Results text"))
        assertEquals("subsection", ReviewSections.kindOfLevel(ReviewSections.pdfSection(layout(page1, page2), emptyList(), area(2, 100f, 12f)).level))
    }

    @Test fun unnumberedLargerHeadingEndsAtTheNextOneAsLarge() {
        val page = listOf(
            line(72f, "Background", 14f, right = 180f),
            line(100f, "Background text here, a full line of it."),
            line(115f, "More background text, another full line."),
            line(140f, "Approach", 14f, right = 160f),
            line(170f, "Approach text."),
        )
        val section = ReviewSections.pdfSection(layout(page), emptyList(), area(1, 72f, 14f))
        assertNull(section.error)
        assertEquals("Background", section.title)
        assertEquals(1, section.areas.size)
        assertEquals(139.5f / 792, section.areas[0].bottom, 1e-4f)
        // Body text is no heading.
        assertFalse(ReviewSections.pdfSection(layout(page), emptyList(), area(1, 100f)).isHeading)
    }

    @Test fun twoColumnSectionContinuesInTheRightColumn() {
        val page = listOf(
            line(72f, "Left column text before.", left = 50f, right = 290f, column = 0),
            line(100f, "4 Experiments", 14f, left = 50f, right = 170f, column = 0),
            line(130f, "Experiments text in the left column.", left = 50f, right = 290f, column = 0),
            line(700f, "Last left line.", left = 50f, right = 290f, column = 0),
            line(72f, "Experiments continue at the right.", left = 320f, right = 560f, column = 1),
            line(100f, "5 Conclusion", 14f, left = 320f, right = 450f, column = 1),
            line(130f, "Conclusion text.", left = 320f, right = 560f, column = 1),
        )
        val section = ReviewSections.pdfSection(layout(page, columns = 2), emptyList(), SectionArea(1, 50f / 612, 100f / 792, 170f / 612, 114f / 792))
        assertNull(section.error)
        assertEquals(2, section.areas.size)
        assertTrue(section.areas[0].right < 0.5f)
        assertTrue(section.areas[1].left > 0.5f)
        assertEquals(99.5f / 792, section.areas[1].bottom, 1e-4f)
        assertFalse(section.text.contains("Conclusion"))
    }

    @Test fun noteSectionRunsToTheHeadingOfTheNextSection() {
        val note = listOf(
            line(72f, "Global batch, mini-batch and micro-batch", 12f, right = 400f),
            line(100f, "Pretraining The global batch is split."),
            line(700f, "A micro-batch goes through one pass."),
        )
        val next = listOf(
            line(72f, "Figure 1: A global batch."),
            line(100f, "Collective operations", 12f, right = 260f),
            line(130f, "All-reduce."),
        )
        val (areas, text) = ReviewSections.areasToNextHeading(layout(note, next), area(1, 72f, 12f), listOf("Collective operations"), 2)
        assertEquals(listOf(1, 2), areas.map { it.page })
        assertEquals(99.5f / 792, areas[1].bottom, 1e-4f)
        assertTrue(text.endsWith("Figure 1: A global batch."))
        // Without a next heading, to the end of the last page.
        assertEquals(listOf(1, 2), ReviewSections.areasToNextHeading(layout(note, next), area(1, 72f, 12f), emptyList(), 2).first.map { it.page })
    }

    @Test fun pagesFromCharactersFindLinesColumnsAndFurniture() {
        fun chars(text: String, left: Float, top: Float, size: Float = 10f): List<ReviewSections.PageChar> =
            text.mapIndexed { i, c -> ReviewSections.PageChar(left + i * 5f, top, left + i * 5f + 4.5f, top + size, c.code, size) }
        val single = ReviewSections.pageFromChars(612f, 792f, chars("Heading", 72f, 72f, 14f) + chars("Body text here", 72f, 100f))
        assertEquals(1, single.columns)
        assertEquals(listOf("Heading", "Body text here"), single.lines.map { it.text })
        assertEquals(14f, single.lines[0].size)
        val columns = (0 until 12).flatMap { row -> chars("Left text $row", 50f, 72f + row * 20f) + chars("Right text $row", 330f, 72f + row * 20f) }
        val double = ReviewSections.pageFromChars(612f, 792f, columns)
        assertEquals(2, double.columns)
        assertEquals("Left text 0", double.lines.first().text)
        assertEquals("Right text 0", double.lines.first { it.column == 1 }.text)
        // The parts of a ligature without boxes of their own stay in their word.
        val ligature = chars("fle", 72f, 200f).let { listOf(it[0], ReviewSections.PageChar(77f, 200f, 77f, 200f, 'i'.code, 10f)) + it.drop(1) }
        assertEquals("file", ReviewSections.pageFromChars(612f, 792f, ligature).lines.single().text)
        val numbered = SectionPage(612f, 792f, 1, listOf(line(72f, "Text"), line(760f, "7")))
        assertEquals(listOf("Text"), ReviewSections.withoutFurniture(numbered, emptyList()).lines.map { it.text })
    }

    @Test fun headingNumbersAndTheirSuccessors() {
        val two = ReviewSections.headingNumber("2.1 Data")
        assertEquals(listOf(2, 1), two.parts)
        assertTrue(ReviewSections.nextNumber(two, ReviewSections.headingNumber("2.2 Model")))
        assertTrue(ReviewSections.nextNumber(two, ReviewSections.headingNumber("3 Results")))
        assertFalse(ReviewSections.nextNumber(two, ReviewSections.headingNumber("2.1.1 Sources")))
        assertTrue(ReviewSections.nextNumber(ReviewSections.headingNumber("4 Conclusion"), ReviewSections.headingNumber("A. Proofs")))
        assertTrue(ReviewSections.nextNumber(ReviewSections.headingNumber("II. Related Work"), ReviewSections.headingNumber("III. Method")))
        assertFalse(ReviewSections.headingNumber("A Simple Method").valid)
        assertEquals("subsection 2.1 “Data”", ReviewSections.name("subsection", "2.1", "Data"))
        // A heading line shows its title (perhaps after its number); a line that mentions it does not.
        assertTrue(ReviewSections.showsTitle("3 Results", "Results"))
        assertTrue(ReviewSections.showsTitle("3 Results", "3 Results"))
        assertTrue(ReviewSections.showsTitle("Global batch, mini-batch", "Global batch, mini-batch and micro-batch"))
        assertFalse(ReviewSections.showsTitle("It ends before the results.", "Results"))
        assertFalse(ReviewSections.showsTitle("Results text.", "Results"))
    }
}

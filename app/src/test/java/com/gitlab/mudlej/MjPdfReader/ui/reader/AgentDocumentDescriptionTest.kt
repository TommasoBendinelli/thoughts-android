// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The desktop's MainShellTest::testAgentDocumentDescription, with Library-relative paths.
class AgentDocumentDescriptionTest {
    private val directory = File("/library/current_pdfs")
    private val projectId = "new:applications"
    private val configuration = agentProjectConfigurationPath(projectId)
    private val owner = configuration.removeSuffix("/thoughts-project.json")

    private fun project(vararg documents: JsonObject, configured: Boolean = true) = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("id", projectId); addProperty("name", "Applications"); addProperty("target", "project_applications.pdf")
            if (configured) add("project_configuration", JsonObject())
            add("documents", JsonArray().apply { documents.forEach(::add) })
        })
    }

    private val letter = JsonObject().apply {
        addProperty("filename", "document_abc.pdf")
        addProperty("document_id", "7995b5d7-ab6e-4d53-9c3c-0e8c1c3f2a10")
        addProperty("source_file", "$owner/AlexExampleLetter_2026.tex")
    }

    private fun describe(projects: JsonArray, filename: String, name: String, place: AgentReaderPlace = AgentReaderPlace(),
                         target: ReaderTitleTarget = ReaderTitleTarget(), folder: File = directory) =
        agentDocumentDescription(projects, target, File(folder, filename).path, directory, name, place)

    @Test fun projectSettingsPathMatchesTheDesktop() {
        // ProjectConfiguration::path: SHA-256 of the project id.
        assertEquals(".thoughts/projects/94762026e793161c772d8451923b3920462e60920d3e57a8f5bfacd54f23e1eb/documents/thoughts-project.json",
            agentProjectConfigurationPath("new:7699ca82-fea6-4b48-bf0b-953a5d55f0d0"))
    }

    @Test fun projectSettingsPathFollowsTheManifest() {
        // The default folder the catalogue names, in either spelling (default-folder rename, Rule 2).
        val renamed = ".thoughts/projects/94762026e793161c772d8451923b3920462e60920d3e57a8f5bfacd54f23e1eb/default"
        val project = JsonObject().apply {
            addProperty("id", "new:7699ca82-fea6-4b48-bf0b-953a5d55f0d0")
            add("project_configuration", com.google.gson.JsonParser.parseString(
                """{"default_folder_id":"f","source_folders":[{"folder_id":"f","path":"$renamed"}]}"""))
        }
        assertEquals("$renamed/thoughts-project.json", agentProjectConfigurationPath(project))
        assertEquals("$renamed/thoughts-project.json",
            agentProjectConfigurationPath("new:7699ca82-fea6-4b48-bf0b-953a5d55f0d0", "default"))
        val lines = describe(JsonArray().apply { add(project.deepCopy().apply {
            addProperty("name", "Applications"); add("documents", JsonArray().apply { add(letter) })
        }) }, "document_abc.pdf", "Letter")
        assertTrue(lines, lines.contains("Project file: $renamed/thoughts-project.json\n"))
    }

    @Test fun describesSourceProjectPageAndCollapsedSelection() {
        assertEquals(
            "Thoughts document: \"Alex Example Letter 2026\"\n" +
                "Project: Applications\n" +
                "Source: $owner/AlexExampleLetter_2026.tex  (LaTeX)\n" +
                "To change the text, create proposals with the Thoughts create_proposals tool (several edits at once) or " +
                "`thoughtsctl create-proposals`; don't edit the source directly unless asked, and never edit the PDF.\n" +
                "Built PDF: current_pdfs/document_abc.pdf\n" +
                "Project file: $configuration\n" +
                "Document ID: 7995b5d7-ab6e-4d53-9c3c-0e8c1c3f2a10\n" +
                "Selected text: \"Lead and mentor teams of engineers\"\n",
            describe(project(letter), "document_abc.pdf", "Alex Example Letter 2026",
                AgentReaderPlace(1, 1, "  Lead and mentor\n teams of  engineers ")),
        )
    }

    @Test fun documentFieldsForReviewCardPrompts() {
        assertEquals(
            mapOf(
                "document_name" to "Alex Example Letter 2026",
                "project" to "Applications",
                "source" to "$owner/AlexExampleLetter_2026.tex",
                "source_name" to "AlexExampleLetter_2026.tex",
                "source_format" to "LaTeX",
                "pdf" to "current_pdfs/document_abc.pdf",
                "project_file" to configuration,
                "document_id" to "7995b5d7-ab6e-4d53-9c3c-0e8c1c3f2a10",
            ),
            agentDocumentFields(project(letter), ReaderTitleTarget(), File(directory, "document_abc.pdf").path, directory,
                "Alex Example Letter 2026"),
        )
        // Outside the Library and any project: only the file name.
        val outside = agentDocumentFields(project(letter), ReaderTitleTarget(), "/sdcard/Download/other.pdf", directory, "")
        assertEquals(mapOf("document_name" to "other.pdf", "project" to "", "pdf" to "other.pdf", "document_id" to ""), outside)
        assertEquals("Thoughts document: \"other.pdf\"\nBuilt PDF: other.pdf  (3 pages)\n",
            AgentPromptTemplates.render("Thoughts document: \"{document_name}\"\nProject: {project}\nBuilt PDF: {pdf}  ({pdf_pages} pages)\n",
                outside + ("pdf_pages" to "3")))
    }

    @Test fun selectionNamesItsSourceLinesWhenKnown() {
        val header = "Thoughts document: \"Alex Example Letter 2026\"\n" +
            "Project: Applications\n" +
            "Source: $owner/AlexExampleLetter_2026.tex  (LaTeX)\n" +
            "To change the text, create proposals with the Thoughts create_proposals tool (several edits at once) or " +
            "`thoughtsctl create-proposals`; don't edit the source directly unless asked, and never edit the PDF.\n" +
            "Built PDF: current_pdfs/document_abc.pdf\n" +
            "Project file: $configuration\n" +
            "Document ID: 7995b5d7-ab6e-4d53-9c3c-0e8c1c3f2a10\n" +
            "Selected text: \"Lead and mentor teams of engineers\"\n"
        val selection = " Lead and mentor\n teams of\tengineers "
        val location = AgentSourceLocation("$owner/AlexExampleLetter_2026.tex", 41, 43)
        assertEquals(header + "Source location: $owner/AlexExampleLetter_2026.tex, lines 41–43\n",
            describe(project(letter), "document_abc.pdf", "Alex Example Letter 2026", AgentReaderPlace(2, 3, selection, location)))
        // One source line reads as such.
        assertEquals(header + "Source location: $owner/AlexExampleLetter_2026.tex, line 7\n",
            describe(project(letter), "document_abc.pdf", "Alex Example Letter 2026",
                AgentReaderPlace(2, 3, selection, location.copy(firstLine = 7, lastLine = 7))))
        // Unknown lines leave the line out.
        assertEquals(header, describe(project(letter), "document_abc.pdf", "Alex Example Letter 2026", AgentReaderPlace(2, 3, selection)))
        // Source lines describe a selection; without one there is nothing to locate.
        assertEquals(false, describe(project(letter), "document_abc.pdf", "Alex Example Letter 2026",
            AgentReaderPlace(2, 3, " ", location)).contains("Source location"))
    }

    @Test fun sourceLocationNeedsBothEndsInOneFile() {
        val tex = "docs/paper.tex"
        assertEquals(AgentSourceLocation(tex, 12, 15), agentSourceLocation(tex to 12, tex to 15))
        // Ends are ordered, whatever order the lookup met them in.
        assertEquals(AgentSourceLocation(tex, 12, 15), agentSourceLocation(tex to 15, tex to 12))
        assertEquals(AgentSourceLocation(tex, 9, 9), agentSourceLocation(tex to 9, tex to 9))
        assertEquals(null, agentSourceLocation(null, tex to 15))
        assertEquals(null, agentSourceLocation(tex to 12, null))
        // A selection across an \input boundary has no single passage.
        assertEquals(null, agentSourceLocation(tex to 12, "docs/appendix.tex" to 3))
        assertEquals(null, agentSourceLocation(tex to 0, tex to 3))
        assertEquals(null, agentSourceLocation("" to 1, "" to 2))
    }

    @Test fun leavesOutSelectionAndSourceOfAnImportedPdf() {
        val paper = JsonObject().apply {
            addProperty("filename", "paper.pdf"); addProperty("document_id", "1fd11935-ae0e-4960-a8bd-27c39f1b7ace")
        }
        assertEquals(
            "Thoughts document: \"Paper\"\nProject: Applications\nBuilt PDF: current_pdfs/paper.pdf\n" +
                "Project file: $configuration\nDocument ID: 1fd11935-ae0e-4960-a8bd-27c39f1b7ace\n",
            describe(project(paper), "paper.pdf", "Paper", AgentReaderPlace(3, 12, "")),
        )
    }

    @Test fun pdfOutsideEveryProjectHasOnlyItsName() {
        assertEquals("Thoughts document: \"loose.pdf\"\nBuilt PDF: current_pdfs/loose.pdf\n",
            describe(JsonArray(), "loose.pdf", ""))
        // The page on screen (and a blank selection) adds nothing.
        assertEquals("Thoughts document: \"Loose\"\nBuilt PDF: current_pdfs/loose.pdf\n",
            describe(JsonArray(), "loose.pdf", "Loose", AgentReaderPlace(4, 0, " \n ")))
        // Off the Library folder only the file name means anything on the Mac.
        assertEquals("Thoughts document: \"Download\"\nBuilt PDF: download.pdf\n",
            describe(JsonArray(), "download.pdf", "Download", AgentReaderPlace(2, 5), folder = File("/sdcard/Download")))
    }

    @Test fun markdownSourceAndAttachmentFilenameAndNoSettingsFile() {
        val notes = JsonObject().apply {
            addProperty("filename", "notes_library.pdf"); addProperty("attachment_filename", "notes.pdf")
            addProperty("source_file", "docs-applications/markdown/source/Nvidia .MARKDOWN")
        }
        assertEquals(
            "Thoughts document: \"Notes\"\nProject: Applications\n" +
                "Source: docs-applications/markdown/source/Nvidia .MARKDOWN  (Markdown)\n" +
                "To change the text, create proposals with the Thoughts create_proposals tool (several edits at once) or " +
                "`thoughtsctl create-proposals`; don't edit the source directly unless asked, and never edit the PDF.\n" +
                "Built PDF: current_pdfs/notes.pdf\n",
            describe(project(notes, configured = false), "notes.pdf", "Notes"),
        )
        // A source in another format has no format note.
        val html = JsonObject().apply { addProperty("filename", "page.pdf"); addProperty("source_file", "imports/page.html") }
        assertEquals(true, describe(project(html), "page.pdf", "Page").contains("Source: imports/page.html\n"))
    }

    @Test fun selectedDocumentWinsOverSharedFilenameAndCombinedPdfBelongsToProject() {
        val first = JsonObject().apply { addProperty("filename", "shared.pdf"); addProperty("document_id", "first") }
        val second = JsonObject().apply { addProperty("filename", "shared.pdf"); addProperty("document_id", "second") }
        assertEquals(true, describe(project(first, second), "shared.pdf", "Second",
            target = ReaderTitleTarget(documentId = "second")).contains("Document ID: second\n"))
        assertEquals(true, describe(project(first, second), "shared.pdf", "First").contains("Document ID: first\n"))
        assertEquals(
            "Thoughts document: \"Applications\"\nProject: Applications\nBuilt PDF: current_pdfs/project_applications.pdf\n" +
                "Project file: $configuration\n",
            describe(project(letter), "project_applications.pdf", "Applications"),
        )
        // A Library document opened from its phone copy names its Library PDF.
        val staged = JsonObject().apply {
            addProperty("filename", "staged_library.pdf"); addProperty("document_id", "staged")
            addProperty("local_path", "/data/files/staging/staged.pdf")
        }
        assertEquals(true, describe(project(staged), "staged.pdf", "Staged", folder = File("/data/files/staging"))
            .contains("Built PDF: current_pdfs/staged_library.pdf\n"))
    }

    @Test fun backgroundTabReportsOnlyItsRememberedPage() {
        val live = AgentReaderPlace(7, 9, "selected")
        assertEquals(live, agentReaderPlaceForTab(true, { live }, null))
        val remembered = CurrentDocumentSnapshot("file:///a.pdf", "A", page = 4, pages = 10)
        assertEquals(AgentReaderPlace(4, 10, null), agentReaderPlaceForTab(false, { live }, remembered))
        assertEquals(AgentReaderPlace(), agentReaderPlaceForTab(false, { live }, null))
    }

    @Test
    fun textFeedbackOnTheVersionOnScreenFollowsTheDescription() {
        val current = "a".repeat(64)
        val older = "b".repeat(64)
        fun record(
            id: Int, polarity: String, quote: String, comment: String, markedOn: String, page: Int,
            kind: String? = null, editedOn: String? = null, resolved: Boolean = false, deleted: Boolean = false,
            strokes: Boolean = false,
        ) = com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback.parse(JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("feedback_id", "00000000-0000-4000-8000-00000000000$id")
            addProperty("polarity", polarity)
            kind?.let { addProperty("kind", it) }
            addProperty("comment", comment)
            addProperty("revision", 1)
            addProperty("created_at", "2026-10-29T10:00:0${id}Z")
            addProperty("updated_at", "2026-10-29T10:00:0${id}Z")
            addProperty("deleted", deleted)
            if (resolved) addProperty("resolved_at", "2026-10-30T10:00:00Z")
            if (strokes) add("drawing", com.google.gson.JsonParser.parseString("{\"strokes\":[{\"points\":[[0.2,0.3],[0.25,0.5]]}]}").asJsonObject)
            editedOn?.let { add("review_version", JsonObject().apply { addProperty("pdf_sha256", it); addProperty("source_built", true) }) }
            add("anchor", JsonObject().apply {
                addProperty("version", 1)
                add("quote", JsonObject().apply { addProperty("exact", quote) })
                add("pdf", JsonObject().apply {
                    addProperty("document_id", "c1e62ecc-2dfb-4259-97cc-e319b1186848")
                    addProperty("pdf_sha256", markedOn)
                    add("regions", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("page", page)
                            add("rect", JsonArray().apply { listOf(0.1, 0.1, 0.5, 0.2).forEach { add(it) } })
                        })
                    })
                })
            })
        })!!
        val records = listOf(
            record(2, "negative", "beta is set\n to 0.1", "Should be 0.05", current, 5),
            record(1, "positive", "nice derivation", "", current, 3),
            // Made on an older version: left out.
            record(3, "positive", "older version", "", older, 3),
            // A question marked on an older version, last saved on this one: listed, and closed.
            record(4, "neutral", "the learning rate", "Why\n0.1?", older, 6, kind = "question", editedOn = current, resolved = true),
            // A comment is the reader's own: never for agents.
            record(5, "neutral", "a long proof", "Expand this step.", current, 7, kind = "other"),
            // Deleted: left out.
            record(6, "positive", "deleted one", "", current, 2, deleted = true),
        )
        assertEquals(
            "Text feedback on this PDF version:\n" +
                "- Liked (page 3): \"nice derivation\"\n" +
                "- Disliked (page 5): \"beta is set to 0.1\" — Should be 0.05\n" +
                "- Question (page 6): \"the learning rate\" — Why 0.1? (resolved)\n",
            agentTextFeedbackSection(records, current),
        )
        // Nothing given on the version on screen, or no version known: nothing is added.
        assertEquals("", agentTextFeedbackSection(records.filter { it.exact == "older version" }, current))
        assertEquals("", agentTextFeedbackSection(records, ""))
        // A drawing has no quote of its own: its comment says what it marks.
        val drawing = record(8, "neutral", "Drawing", "Arrow\nhere", current, 3, kind = "drawing", strokes = true)
        assertEquals("Text feedback on this PDF version:\n- Drawing (page 3) — Arrow here\n",
            agentTextFeedbackSection(listOf(drawing), current))
        // A long quote is cut at 300 characters, as on the desktop.
        val long = record(7, "positive", "x".repeat(320), "", current, 1)
        assertEquals("Text feedback on this PDF version:\n- Liked (page 1): \"${"x".repeat(300)}…\"\n",
            agentTextFeedbackSection(listOf(long), current))
    }

    @Test
    fun cardsReviewedOnTheVersionOnScreenAreListedAsOnTheDesktop() {
        // The desktop's reviewstoretest agentVersionFeedbackKeepsOnlyTheVersionOnScreen, on the phone.
        val current = "a".repeat(64)
        val older = "b".repeat(64)
        val zone = java.time.ZoneOffset.UTC
        fun card(id: String, question: String, page: Int, deleted: Boolean = false) =
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard(id, "approved", "grpo", current, page, current, question,
                android.graphics.RectF(), if (deleted) com.gitlab.mudlej.MjPdfReader.data.review.ReviewSchedule(
                    java.time.Instant.EPOCH, 0, deleted = true) else null, "")
        fun answer(id: String, at: String, tags: List<String>, sha: String, note: String? = null) =
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewHistoryEntry(
                eventId = id, rating = "good", reviewedAt = java.time.Instant.parse(at), originId = "mac", revision = 1,
                updatedAt = null, retractedEventId = null, retracted = false, effective = true, pendingSync = false,
                sourcePath = null, reviewTags = tags, reviewTagsRecorded = true,
                groupNotes = note?.let { mapOf(com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes.ANSWER_NOTE_KEY to it) }.orEmpty(),
                pdfSha256 = sha)
        fun feedback(id: Int, cardId: String, polarity: String, quote: String, comment: String, markedOn: String, deleted: Boolean = false) =
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.parse(JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("feedback_id", "00000000-0000-4000-8000-00000000001$id")
                addProperty("card_id", cardId)
                addProperty("polarity", polarity)
                addProperty("comment", comment)
                addProperty("revision", 1)
                addProperty("created_at", "2026-10-29T10:00:0${id}Z")
                addProperty("updated_at", "2026-10-29T10:00:0${id}Z")
                addProperty("deleted", deleted)
                add("anchor", JsonObject().apply {
                    addProperty("version", 1)
                    add("quote", JsonObject().apply { addProperty("exact", quote) })
                    add("pdf", JsonObject().apply {
                        addProperty("document_id", "c1e62ecc-2dfb-4259-97cc-e319b1186848")
                        addProperty("pdf_sha256", markedOn)
                        add("regions", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("page", 4)
                                add("rect", JsonArray().apply { listOf(0.1, 0.1, 0.5, 0.2).forEach { add(it) } })
                            })
                        })
                    })
                })
            })!!
        val first = card("card-2", "Why the KL term?", 4) to listOf(
            answer("e1", "2026-10-20T09:00:00Z", listOf("wrong"), older),
            answer("e2", "2026-10-29T09:00:00Z", listOf("partly"), current, "Mixed up the\nsign"))
        val second = card("card-1", "Explain  GRPO", 2) to listOf(answer("e3", "2026-10-29T08:00:00Z", listOf("correct"), current))
        // Answered only on an older version, or deleted: left out.
        val stale = card("card-3", "Old question", 1) to listOf(answer("e4", "2026-10-20T08:00:00Z", listOf("partly"), older))
        val deleted = card("card-4", "Deleted", 1, deleted = true) to listOf(answer("e5", "2026-10-29T08:00:00Z", listOf("partly"), current))
        val answerFeedback = listOf(
            feedback(1, "card-2", "positive", "keeps the policy close", "Clear", current),
            feedback(2, "card-2", "negative", "old wording", "", older),
            feedback(3, "card-2", "negative", "deleted one", "", current, deleted = true),
        )
        val groups = listOf(com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Group("recall", "Recall", emptyList(),
            scale = listOf("correct", "partly", "wrong")))
        assertEquals(
            "Cards reviewed on this PDF version:\n" +
                "- Card card-1 (page 2): \"Explain GRPO\"\n" +
                "  - Reviewed 2026-10-29 08:00: Recall: Correct\n" +
                "- Card card-2 (page 4): \"Why the KL term?\"\n" +
                "  - Reviewed 2026-10-29 09:00: Recall: partly\n" +
                "    - Note: Mixed up the sign\n" +
                "  Answer feedback:\n" +
                "  - Liked: \"keeps the policy close\" — Clear\n",
            agentReviewedCardsSection(listOf(first, second, stale, deleted), answerFeedback, groups, { it }, current, zone),
        )
        // Nothing given on the version on screen, or no version known: nothing is added.
        assertEquals("", agentReviewedCardsSection(listOf(stale), answerFeedback, groups, { it }, current, zone))
        assertEquals("", agentReviewedCardsSection(listOf(first), answerFeedback, groups, { it }, "", zone))
        // A card with only answer feedback on this version is listed too; a skip is not an answer.
        val skipped = card("card-2", "Why the KL term?", 4) to listOf(
            answer("e6", "2026-10-29T09:00:00Z", listOf("skip"), current))
        assertEquals(
            "Cards reviewed on this PDF version:\n" +
                "- Card card-2 (page 4): \"Why the KL term?\"\n" +
                "  Answer feedback:\n" +
                "  - Liked: \"keeps the policy close\" — Clear\n",
            agentReviewedCardsSection(listOf(skipped), answerFeedback, groups, { it }, current, zone),
        )
    }
}

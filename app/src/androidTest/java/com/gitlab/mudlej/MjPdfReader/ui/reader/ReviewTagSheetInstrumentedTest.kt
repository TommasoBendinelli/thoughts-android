// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.google.android.material.chip.Chip
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The rating sheet: sliders that open and close on their own (remembered), each with a note, then the answer's note. */
@RunWith(AndroidJUnit4::class)
class ReviewTagSheetInstrumentedTest {
    private lateinit var directory: File
    private val previousFile = ReviewTagGroups.file

    private fun forgetFolding() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences(ReviewTagGroupFolding.PREFERENCES, android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun useScratchGroups() {
        directory = Files.createTempDirectory("tag-sheet").toFile()
        ReviewTagGroups.file = { File(directory, "tag-groups.json") }
        forgetFolding()
    }

    @After fun restoreGroups() {
        ReviewTagGroups.file = previousFile
        directory.deleteRecursively()
        forgetFolding()
    }

    private fun visible(view: View, id: Int) = view.findViewById<View>(id).visibility == View.VISIBLE

    private fun chips(view: View): List<Chip> = when (view) {
        is Chip -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { chips(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** Tags are gone: no chip groups, no Other, no New group; only sliders and notes. */
    private fun assertNoTags(sheet: android.app.Dialog) {
        assertFalse(visible(sheet.window!!.decorView, R.id.reviewTags))
        assertFalse(visible(sheet.window!!.decorView, R.id.reviewNewTagGroup))
        assertEquals(emptyList<Chip>(), chips(sheet.findViewById(R.id.reviewTagScales)).filter { it.isShown })
    }

    @Test fun slidersShowOnlyTheirStepsAndNoTags() {
        val understanding = listOf("I don't get it", "Partially", "I get it")
        val recall = listOf("Forgot", "Hesitated", "Recalled")
        val interest = listOf("Boring", "Fine", "Interesting", "Fascinating")
        val longFree = "Slowly understanding the proof"
        ReviewTagGroups.installServer(com.google.gson.JsonParser.parseString("""
            {"layout_id":"groups","schema_version":1,
             "groups":[{"group_id":"u","name":"Understanding","tags":["I don't get it","Partially","I get it","$longFree"],
                        "scale":["I don't get it","Partially","I get it"]},
                       {"group_id":"s","name":"Study","tags":["proof","notation"]},
                       {"group_id":"r","name":"Recall","tags":["Forgot","Hesitated","Recalled"],"scale":["Forgot","Hesitated","Recalled"]},
                       {"group_id":"i","name":"Interest","tags":["Boring","Fine","Interesting","Fascinating"],
                        "scale":["Boring","Fine","Interesting","Fascinating"]}],
             "updated_at":"2026-10-15T12:00:00.000Z","origin_id":"desktop"}
        """).asJsonObject)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var chosen = listOf("Partially", "Recalled")
            var sheet: android.app.Dialog? = null
            scenario.onActivity { activity ->
                sheet = createReviewTagSheet(activity, understanding + recall + interest + listOf(longFree, "proof", "notation"),
                    chosen, "test", ReviewAnswerDraft("card"), mapOf(longFree to 2, "proof" to 5, "Partially" to 3),
                    listOf("Partially"), onTagCreated = {}) { chosen = it }.also { it.show() }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val shown = checkNotNull(sheet)
                val blocks = shown.findViewById<ViewGroup>(R.id.reviewTagScales).let { list -> (0 until list.childCount).map(list::getChildAt) }
                // Only the sliders, in record order: the chip group Study and Other are not shown.
                assertEquals(listOf("Understanding", "Recall", "Interest"),
                    blocks.map { it.findViewById<TextView>(R.id.reviewTagScaleName).text.toString() })
                assertNoTags(shown)
                val track = blocks[0].findViewById<ReviewScaleTrack>(R.id.reviewTagScaleTrack)
                val labels = blocks[0].findViewById<ViewGroup>(R.id.reviewTagScaleLabels)
                fun label(index: Int) = labels.getChildAt(index) as TextView
                // Steps show their counts like chips (0 too); the chosen one is bold and holds the thumb.
                fun texts() = (0 until labels.childCount).map { label(it).text.toString() }
                assertEquals(listOf("I don't get it (0)", "Partially (3)", "I get it (0)"), texts())
                assertEquals(1, track.selected)
                assertTrue(label(1).typeface?.isBold == true)
                assertNull(blocks[2].findViewById<ReviewScaleTrack>(R.id.reviewTagScaleTrack).selected)
                // Sliders fold: Understanding and Recall start open, Interest closed. Recall closed
                // names its step on the name's line, and keeps it.
                assertEquals(listOf(true, true, false), blocks.map { visible(it, R.id.reviewTagScaleBody) })
                assertFalse(visible(blocks[1], R.id.reviewTagScaleSummary))
                blocks[1].findViewById<View>(R.id.reviewTagScaleHeading).performClick()
                assertFalse(visible(blocks[1], R.id.reviewTagScaleBody))
                assertEquals("· Recalled", blocks[1].findViewById<TextView>(R.id.reviewTagScaleSummary).text.toString())
                assertTrue(visible(blocks[1], R.id.reviewTagScaleSummary))
                assertTrue("Recalled" in chosen)
                // Nothing chosen in Interest: its closed header names nothing.
                assertFalse(visible(blocks[2], R.id.reviewTagScaleSummary))
                // The note button of a closed slider opens the group with its note.
                blocks[2].findViewById<View>(R.id.reviewTagScaleNoteButton).performClick()
                assertTrue(visible(blocks[2], R.id.reviewTagScaleBody))
                assertTrue(visible(blocks[2], R.id.reviewTagScaleNoteLayout))
                blocks[2].findViewById<View>(R.id.reviewTagScaleNoteButton).performClick()
                blocks[2].findViewById<View>(R.id.reviewTagScaleHeading).performClick()
                blocks[1].findViewById<View>(R.id.reviewTagScaleHeading).performClick()
                assertEquals(listOf(true, true, false), blocks.map { visible(it, R.id.reviewTagScaleBody) })
                // Picking another step replaces it; the other slider stays.
                label(2).performClick()
                assertEquals(2, track.selected)
                assertEquals(setOf("I get it", "Recalled"), chosen.toSet())
                // The counts follow: this card moves from Partially to I get it.
                assertEquals(listOf("I don't get it (0)", "Partially (2)", "I get it (1)"), texts())
                // Tapping the chosen step clears the slider.
                label(2).performClick()
                assertNull(track.selected)
                assertEquals(setOf("Recalled"), chosen.toSet())
                assertEquals(listOf("I don't get it (0)", "Partially (2)", "I get it (0)"), texts())
                label(0).performClick()
                assertEquals(listOf("I don't get it (1)", "Partially (2)", "I get it (0)"), texts())
                // The group's free tag is not offered under its slider, and New tag is gone.
                assertFalse(visible(blocks[0], R.id.reviewTagScaleTagsCaption))
                assertFalse(visible(blocks[0], R.id.reviewTagScaleChips))
                assertEquals(setOf("I don't get it", "Recalled"), chosen.toSet())
                // The note opens behind its button.
                val note = blocks[0].findViewById<View>(R.id.reviewTagScaleNoteLayout)
                assertEquals(View.GONE, note.visibility)
                blocks[0].findViewById<View>(R.id.reviewTagScaleNoteButton).performClick()
                assertEquals(View.VISIBLE, note.visibility)
                blocks[0].findViewById<View>(R.id.reviewTagScaleNoteButton).performClick()
                assertEquals(View.GONE, note.visibility)
                // The three sliders fit above the fold of a phone screen.
                val screen = shown.window!!.decorView.height
                val lastBlock = IntArray(2).also { blocks[2].getLocationOnScreen(it) }[1] + blocks[2].height
                assertTrue("sliders end at $lastBlock of $screen", lastBlock < screen)
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(800)
            // A picture of the sheet for the report (best effort).
            scenario.onActivity {
                // The sheet's own window drawn to a file (the activity may sit under onboarding).
                runCatching {
                    val decor = checkNotNull(sheet).window!!.decorView
                    val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
                    decor.draw(android.graphics.Canvas(bitmap))
                    val target = File(instrumentation.targetContext.getExternalFilesDir(null), "tag-sheet-sliders.png")
                    target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    // Kept outside the app, which the test run removes afterwards.
                    instrumentation.uiAutomation.executeShellCommand("cp ${target.absolutePath} /data/local/tmp/tag-sheet-sliders.png")
                        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
                }.onFailure { android.util.Log.w("ReviewTagSheetTest", "no picture", it) }
                checkNotNull(sheet).dismiss()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun described(view: View, description: String): View? = when {
        view.contentDescription?.toString() == description -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { described(view.getChildAt(it), description) }
        else -> null
    }

    private fun texts(view: View): List<String> = when (view) {
        is android.widget.Button -> emptyList()
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun editSliderEditsOnlyTheSteps() {
        val steps = listOf("Skimmed", "Read", "Studied")
        ReviewTagGroups.installServer(com.google.gson.JsonParser.parseString("""
            {"layout_id":"groups","schema_version":1,
             "groups":[{"group_id":"e","name":"Effort","tags":["Skimmed","Read","Studied","Asked AI"],
                        "scale":["Skimmed","Read","Studied"]},
                       {"group_id":"s","name":"Source","tags":["Me","AI"]}],
             "updated_at":"2026-10-16T12:00:00.000Z","origin_id":"desktop"}
        """).asJsonObject)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val created = mutableListOf<String>()
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var sheet: android.app.Dialog? = null
            scenario.onActivity { activity ->
                sheet = createReviewTagSheet(activity, steps + listOf("Asked AI", "Me", "AI"), listOf("Read"), "test",
                    ReviewAnswerDraft("card"), mapOf("Asked AI" to 2, "Read" to 1), listOf("Read"), onTagCreated = { created += it }) {}
                    .also { it.show() }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val shown = checkNotNull(sheet)
                val block = shown.findViewById<ViewGroup>(R.id.reviewTagScales).getChildAt(0)
                // The group's other tag is not shown on the sheet.
                assertNoTags(shown)
                block.findViewById<View>(R.id.reviewTagScaleEditButton).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val editor = checkNotNull(lastReviewScaleEditor?.get())
                val root = editor.window!!.decorView
                // Only the steps are listed, numbered; the group's tag is not.
                val listed = texts(root)
                assertTrue(listed.containsAll(listOf("1", "Skimmed", "2", "Read", "3", "Studied")))
                assertFalse("Asked AI" in listed)
                // Add step: a typed name is a new step at the end.
                root.findViewWithText("Add step").performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val add = checkNotNull(lastReviewAddStepDialog?.get())
                // The group's tags that are not steps can be used as they are.
                assertEquals(listOf("Asked AI"), chips(add.window!!.decorView).map { it.tag })
                // The keyboard's Done key adds it, as Add does.
                add.window!!.decorView.findViewWithClass<EditText>()!!.apply {
                    setText("Deep dive")
                    onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
                }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val editor = checkNotNull(lastReviewScaleEditor?.get())
                val root = editor.window!!.decorView
                // Skimmed leaves the slider; Read moves right, after Studied.
                described(root, "Remove Skimmed")!!.performClick()
                described(root, "Move Read right")!!.performClick()
                editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val effort = ReviewTagGroups.groups().first { it.id == "e" }
                assertEquals(listOf("Studied", "Read", "Deep dive"), effort.scale)
                // The removed step stays a tag of the group; no answer changes.
                assertTrue("Skimmed" in effort.tags)
                assertEquals(listOf("Deep dive"), created)
                val block = checkNotNull(sheet).findViewById<ViewGroup>(R.id.reviewTagScales).getChildAt(0)
                val labels = block.findViewById<ViewGroup>(R.id.reviewTagScaleLabels)
                assertEquals(listOf("Studied (0)", "Read (1)", "Deep dive (0)"),
                    (0 until labels.childCount).map { (labels.getChildAt(it) as TextView).text.toString() })
                assertEquals(1, block.findViewById<com.gitlab.mudlej.MjPdfReader.ui.reader.ReviewScaleTrack>(R.id.reviewTagScaleTrack).selected)
                checkNotNull(sheet).dismiss()
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun aSliderNeedsTwoStepsAndAChipGroupStartsWithNone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var saved: List<String>? = null
            scenario.onActivity { activity ->
                showReviewScaleEditor(activity, "Make “Source” a slider", emptyList(), listOf("Me", "AI"), { it }, { it.trim() }) { saved = it }
            }
            instrumentation.waitForIdleSync()
            fun pick(tag: String) {
                scenario.onActivity { checkNotNull(lastReviewScaleEditor?.get()).window!!.decorView.findViewWithText("Add step").performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { chips(checkNotNull(lastReviewAddStepDialog?.get()).window!!.decorView).first { it.tag == tag }.performClick() }
                instrumentation.waitForIdleSync()
            }
            val save = { checkNotNull(lastReviewScaleEditor?.get()).getButton(android.app.AlertDialog.BUTTON_POSITIVE) }
            scenario.onActivity { assertFalse(save().isEnabled) }
            pick("AI")
            scenario.onActivity { assertFalse(save().isEnabled) }
            pick("Me")
            scenario.onActivity {
                assertTrue(save().isEnabled)
                save().performClick()
            }
            instrumentation.waitForIdleSync()
            assertEquals(listOf("AI", "Me"), saved)
        }
    }

    @Test fun theAnswerNoteIsAtTheEndAndRecordedUnderNote() {
        ReviewTagGroups.edit(ReviewTagGroups.Edit.AddGroup("Recall", "Recalled", id = "g1"), "test")
        ReviewTagGroups.edit(ReviewTagGroups.Edit.SetScale("g1", listOf("Forgot", "Recalled")), "test")
        ReviewTagGroups.edit(ReviewTagGroups.Edit.AddGroup("Interest", "cool", id = "g2"), "test")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            val draft = ReviewAnswerDraft("card")
            var sheet: android.app.Dialog? = null
            fun open(activity: HomeActivity, answer: ReviewAnswerDraft) = createReviewTagSheet(activity,
                listOf("Forgot", "Recalled", "cool", ReviewTags.WRONG), emptyList(), "test", answer,
                mapOf("cool" to 2, ReviewTags.WRONG to 1), emptyList(), onTagCreated = {}) {}.also { it.show() }
            scenario.onActivity { activity -> sheet = open(activity, draft) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val shown = checkNotNull(sheet)
                val field = shown.findViewById<EditText>(R.id.reviewAnswerNote)
                val layout = shown.findViewById<View>(R.id.reviewAnswerNoteLayout)
                val caption = shown.findViewById<TextView>(R.id.reviewAnswerNoteCaption)
                // After the sliders (the hidden tag rows and New group in between): the caption, then
                // the field; only "About this…" comes after it (hidden here: the answer's card has no targets).
                val content = layout.parent as ViewGroup
                val order = (0 until content.childCount).map { content.getChildAt(it).id }
                assertEquals(listOf(R.id.reviewTagScales, R.id.reviewTags, R.id.reviewNewTagGroup,
                    R.id.reviewAnswerNoteCaption, R.id.reviewAnswerNoteLayout, R.id.reviewContextBlock), order.drop(1))
                assertEquals(View.GONE, shown.findViewById<View>(R.id.reviewContextBlock).visibility)
                assertNoTags(shown)
                // Always open, never collapsed, several lines, at most 500 characters.
                assertEquals("Note", caption.text.toString())
                assertEquals(View.VISIBLE, caption.visibility)
                assertEquals(View.VISIBLE, layout.visibility)
                assertTrue(field.isShown)
                assertEquals("Anything else about this answer…", field.hint.toString())
                assertTrue(field.minLines >= 3)
                field.setText("x".repeat(600))
                assertEquals(500, field.text.length)
                // Recorded with the answer under the reserved key "note", like a group note.
                field.setText("  ask about the second proof  ")
                assertEquals(mapOf("note" to "ask about the second proof"), draft.recordedNotes())
                shown.dismiss()
                // Opened again for the same answer, the note is still there; a new card starts empty.
                val again = open(activity, draft)
                assertEquals("  ask about the second proof  ", again.findViewById<EditText>(R.id.reviewAnswerNote)!!.text.toString())
                again.dismiss()
                val next = open(activity, ReviewAnswerDraft("next card"))
                assertEquals("", next.findViewById<EditText>(R.id.reviewAnswerNote)!!.text.toString())
                next.dismiss()
                // Wherever notes are listed, the answer note comes last as "Note".
                assertEquals(listOf("Interest: cool one", "Other: misc", "Note: ask about the second proof"),
                    com.gitlab.mudlej.MjPdfReader.ui.review.reviewGroupNoteLines(activity,
                        linkedMapOf("note" to "ask about the second proof", "other" to "misc", "g2" to "cool one")))
            }
            instrumentation.waitForIdleSync()
        }
    }

    /**
     * "About this…": the selector keeps each scope's edit, Next records only the changed scopes,
     * another card of the same section and page shows them, and Section is disabled without a heading.
     */
    @Test fun aboutThisNotesAreKeptPerScopeAndSharedWithTheDocumentsOtherCards() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(directory, "reviews").apply { mkdirs() }
        val incoming = File(directory, "incoming").apply { mkdirs() }
        val outgoing = File(directory, "outgoing-events").apply { mkdirs() }
        val document = "65fe82b8-0fbf-4a37-8844-fbece45c7e22"
        fun writeCard(): String = java.util.UUID.randomUUID().toString().also { id ->
            File(root, "cards").apply { mkdirs() }.resolve("$id.json").writeText(
                """{"schema_version":3,"card_id":"$id","status":"approved","revision":1,"primary":{"pdf_stem":"paper",""" +
                    """"document_id":"$document","pdf_sha256":"${"a".repeat(64)}","page":3,"rect":[0.1,0.5,0.8,0.6],""" +
                    """"page_visual_sha256":"${"b".repeat(64)}","question":"What is hidden?"}}""")
        }
        val first = writeCard()
        val second = writeCard()
        val store = com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore(null, root, incoming, outgoing)
        val targets = mapOf(
            ReviewContextNotes.Scope.SECTION to ReviewContextNotes.Target("2 Method › 2.1 Data", "2.1 Data"),
            ReviewContextNotes.Scope.PAGE to ReviewContextNotes.Target("3", "Page 3"),
            ReviewContextNotes.Scope.DOCUMENT to ReviewContextNotes.Target("", "Paper"),
        )
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            fun open(activity: HomeActivity, draft: ReviewAnswerDraft) = createReviewTagSheet(activity, emptyList(), emptyList(), "test",
                draft, emptyMap(), emptyList(), onTagCreated = {}) {}.also { it.show() }
            fun text(sheet: android.app.Dialog, id: Int) = sheet.findViewById<TextView>(id)!!.text.toString()
            fun choose(sheet: android.app.Dialog, id: Int) = sheet.findViewById<View>(id)!!.performClick()
            scenario.onActivity { activity ->
                // Without targets (the answer note's tests) the block stays hidden.
                val plain = open(activity, ReviewAnswerDraft("plain"))
                assertFalse(visible(plain.findViewById(android.R.id.content)!!, R.id.reviewContextBlock))
                plain.dismiss()

                val draft = ReviewAnswerDraft(first).apply { settleContext(targets, emptyMap()) }
                var sheet = open(activity, draft)
                val content = sheet.findViewById<View>(android.R.id.content)!!
                assertTrue(visible(content, R.id.reviewContextBlock))
                // Section first, named by its heading.
                assertEquals("2.1 Data", text(sheet, R.id.reviewContextLabel))
                sheet.findViewById<EditText>(R.id.reviewContextNote)!!.setText("the split is by subject")
                // Page: its own, empty box; switching back keeps the section's text.
                choose(sheet, R.id.reviewContextPage)
                assertEquals("Page 3", text(sheet, R.id.reviewContextLabel))
                assertEquals("", text(sheet, R.id.reviewContextNote))
                sheet.findViewById<EditText>(R.id.reviewContextNote)!!.setText("figure 2 is mislabelled")
                choose(sheet, R.id.reviewContextDocument)
                assertEquals("Paper", text(sheet, R.id.reviewContextLabel))
                assertEquals("", text(sheet, R.id.reviewContextNote))
                choose(sheet, R.id.reviewContextSection)
                assertEquals("the split is by subject", text(sheet, R.id.reviewContextNote))
                sheet.dismiss()
                // Opened again for the same answer: the edits and the shown scope are kept.
                sheet = open(activity, draft)
                assertEquals("the split is by subject", text(sheet, R.id.reviewContextNote))
                sheet.dismiss()
                // Next records the two changed scopes, not the untouched document.
                val recorded = draft.recordedContextNotes()
                assertEquals(setOf(ReviewContextNotes.Scope.SECTION, ReviewContextNotes.Scope.PAGE), recorded.keys)
                store.record(first, com.gitlab.mudlej.MjPdfReader.data.review.ReviewRating.CUSTOM_INTERVAL,
                    java.time.Instant.now(), java.time.Instant.now().plusSeconds(600), reviewTags = emptyList(),
                    contextNotes = recorded).getOrThrow()

                // The second card of the same section and page shows them.
                val current = store.contextNotes(store.card(second)!!)
                val next = ReviewAnswerDraft(second).apply {
                    settleContext(targets, targets.mapValues { (scope, target) -> current[scope.wire to target.key].orEmpty() })
                }
                sheet = open(activity, next)
                assertEquals("the split is by subject", text(sheet, R.id.reviewContextNote))
                choose(sheet, R.id.reviewContextPage)
                assertEquals("figure 2 is mislabelled", text(sheet, R.id.reviewContextNote))
                sheet.dismiss()
                assertEquals(emptyMap<ReviewContextNotes.Scope, ReviewContextNotes.Note>(), next.recordedContextNotes())

                // A card above every heading: Section is disabled and Page is shown.
                val noSection = ReviewAnswerDraft("no-section").apply {
                    settleContext(targets - ReviewContextNotes.Scope.SECTION, emptyMap())
                }
                sheet = open(activity, noSection)
                assertFalse(sheet.findViewById<View>(R.id.reviewContextSection)!!.isEnabled)
                assertTrue(sheet.findViewById<View>(R.id.reviewContextSection)!!.contentDescription.toString()
                    .endsWith("No section headings in this PDF"))
                assertEquals("Page 3", text(sheet, R.id.reviewContextLabel))
                sheet.dismiss()
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun adjacentGroupsOfASectionFoldUnderOneHeaderThatSummarisesThem() {
        ReviewTagGroups.installServer(com.google.gson.JsonParser.parseString("""
            {"layout_id":"groups","schema_version":1,
             "groups":[{"group_id":"r","name":"Recall","tags":["Forgot","Recalled"],"scale":["Forgot","Recalled"]},
                       {"group_id":"wl1","name":"Structure","tags":["Missing steps","Fine","Asked AI"],
                        "scale":["Missing steps","Fine"],"section":"Work left"},
                       {"group_id":"wl2","name":"Style","tags":["Messy","Clean"],"scale":["Messy","Clean"],"section":"Work left"},
                       {"group_id":"wd1","name":"Structure done","tags":["Reordered","Kept"],"scale":["Reordered","Kept"],
                        "section":"Work done"},
                       {"group_id":"src","name":"Source","tags":["Me","AI"],"section":"Work left"},
                       {"group_id":"st","name":"Study","tags":["proof"]}],
             "updated_at":"2026-10-22T12:00:00.000Z","origin_id":"desktop"}
        """).asJsonObject)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val all = listOf("Forgot", "Recalled", "Missing steps", "Fine", "Asked AI", "Messy", "Clean", "Reordered", "Kept", "Me", "AI", "proof")
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var chosen = listOf("Missing steps", "Clean")
            var sheet: android.app.Dialog? = null
            fun open(activity: HomeActivity) = createReviewTagSheet(activity, all, chosen, "test", ReviewAnswerDraft("card"),
                mapOf("Me" to 2, "Asked AI" to 1), emptyList(), onTagCreated = {}) { chosen = it }.also { it.show() }
            fun children(group: ViewGroup) = (0 until group.childCount).map(group::getChildAt)
            fun scaleParts() = children(checkNotNull(sheet).findViewById(R.id.reviewTagScales))
            fun sectionBody(view: View) = children(view.findViewById(R.id.reviewTagSectionBody))
            fun scaleNames(views: List<View>) = views.map { it.findViewById<TextView>(R.id.reviewTagScaleName).text.toString() }
            fun sectionName(view: View) = view.findViewById<TextView>(R.id.reviewTagSectionName).text.toString()
            scenario.onActivity { activity -> sheet = open(activity) }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val shown = checkNotNull(sheet)
                // Recall on its own, then one header per run: Structure and Style under Work left,
                // Structure done under Work done; the groups keep record order and their names.
                val parts = scaleParts()
                assertEquals(3, parts.size)
                assertEquals(listOf("Recall"), scaleNames(parts.take(1)))
                assertEquals("Work left", sectionName(parts[1]))
                assertEquals(listOf("Structure", "Style"), scaleNames(sectionBody(parts[1])))
                assertEquals("Work done", sectionName(parts[2]))
                assertEquals(listOf("Structure done"), scaleNames(sectionBody(parts[2])))
                // Source (Work left too) and Study are chip groups: not shown.
                assertNoTags(shown)
                // The header is bold, the groups indented under it; sections start open, without a summary.
                assertTrue(parts[1].findViewById<TextView>(R.id.reviewTagSectionName).typeface?.isBold == true)
                assertTrue(parts[1].findViewById<View>(R.id.reviewTagSectionBody).paddingStart > 0)
                assertTrue(visible(parts[1], R.id.reviewTagSectionBody))
                assertFalse(visible(parts[1], R.id.reviewTagSectionSummary))
                // The groups keep their own folding: Structure and Style start closed.
                assertEquals(listOf(false, false), sectionBody(parts[1]).map { visible(it, R.id.reviewTagScaleBody) })
                sectionBody(parts[1])[0].findViewById<View>(R.id.reviewTagScaleHeading).performClick()
                assertEquals(listOf(true, false), sectionBody(parts[1]).map { visible(it, R.id.reviewTagScaleBody) })
                // Closed, the header names each group's step; groups with nothing chosen are left out.
                parts[1].findViewById<View>(R.id.reviewTagSectionHeading).performClick()
                assertFalse(visible(parts[1], R.id.reviewTagSectionBody))
                assertTrue(visible(parts[1], R.id.reviewTagSectionSummary))
                assertEquals("· Structure: Missing steps · Style: Clean",
                    parts[1].findViewById<TextView>(R.id.reviewTagSectionSummary).text.toString())
                assertEquals(android.text.TextUtils.TruncateAt.END, parts[1].findViewById<TextView>(R.id.reviewTagSectionSummary).ellipsize)
                assertTrue(parts[1].findViewById<View>(R.id.reviewTagSectionHeading).contentDescription.toString().endsWith("Collapsed"))
                // Nothing chosen in Work done: no summary even when closed.
                parts[2].findViewById<View>(R.id.reviewTagSectionHeading).performClick()
                assertFalse(visible(parts[2], R.id.reviewTagSectionBody))
                assertFalse(visible(parts[2], R.id.reviewTagSectionSummary))
                // Long-press shows the whole text.
                assertTrue(parts[1].findViewById<View>(R.id.reviewTagSectionHeading).performLongClick())
                // Picking a step in a closed section's group updates its summary; reopen to tap.
                parts[2].findViewById<View>(R.id.reviewTagSectionHeading).performClick()
                sectionBody(parts[2])[0].findViewById<View>(R.id.reviewTagScaleHeading).performClick()
                val labels = sectionBody(parts[2])[0].findViewById<ViewGroup>(R.id.reviewTagScaleLabels)
                labels.getChildAt(1).performClick()
                assertTrue("Kept" in chosen)
                parts[2].findViewById<View>(R.id.reviewTagSectionHeading).performClick()
                assertEquals("· Structure done: Kept", parts[2].findViewById<TextView>(R.id.reviewTagSectionSummary).text.toString())
                shown.dismiss()
            }
            instrumentation.waitForIdleSync()
            // Opened again: the sections are as they were left, and the groups inside too.
            scenario.onActivity { activity -> sheet = open(activity) }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val parts = scaleParts()
                assertFalse(visible(parts[1], R.id.reviewTagSectionBody))
                assertFalse(visible(parts[2], R.id.reviewTagSectionBody))
                parts[1].findViewById<View>(R.id.reviewTagSectionHeading).performClick()
                assertEquals(listOf(true, false), sectionBody(parts[1]).map { visible(it, R.id.reviewTagScaleBody) })
                // Section… on Recall: no section yet, the ones in use to pick.
                parts[0].findViewById<View>(R.id.reviewTagScaleMenu).performClick()
            }
            instrumentation.waitForIdleSync()
            fun pickSection() = scenario.onActivity {
                val menu = checkNotNull(lastReviewTagGroupMenu?.get()).menu
                val item = (0 until menu.size()).map(menu::getItem).first { it.title.toString() == "Section…" }
                menu.performIdentifierAction(item.itemId, 0)
            }
            pickSection()
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val dialog = checkNotNull(lastReviewSectionDialog?.get())
                val root = dialog.window!!.decorView
                assertEquals("", root.findViewWithClass<EditText>()!!.text.toString())
                assertEquals(listOf("Work left", "Work done"), chips(root).map { it.tag })
                assertFalse(dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.isShown == true)
                chips(root).first { it.tag == "Work left" }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                // Recall now heads the Work left run: one header with three groups.
                assertEquals("Work left", ReviewTagGroups.groups().first { it.id == "r" }.section)
                val parts = scaleParts()
                assertEquals(2, parts.size)
                assertEquals(listOf("Recall", "Structure", "Style"), scaleNames(sectionBody(parts[0])))
                assertEquals(listOf("Structure done"), scaleNames(sectionBody(parts[1])))
                // Section… on Style: its section filled in, the other one to pick, No section offered.
                sectionBody(parts[0])[2].findViewById<View>(R.id.reviewTagScaleMenu).performClick()
            }
            instrumentation.waitForIdleSync()
            pickSection()
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val dialog = checkNotNull(lastReviewSectionDialog?.get())
                val root = dialog.window!!.decorView
                val input = root.findViewWithClass<EditText>()!!
                assertEquals("Work left", input.text.toString())
                assertEquals(listOf("Work done"), chips(root).map { it.tag })
                assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).isShown)
                // Too long: the dialog stays with the reason.
                input.setText("s".repeat(61))
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertEquals("Work left", ReviewTagGroups.groups().first { it.id == "wl2" }.section)
                input.setText("  Polish  ")
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals("Polish", ReviewTagGroups.groups().first { it.id == "wl2" }.section)
                val parts = scaleParts()
                assertEquals(listOf("Work left", "Polish", "Work done"), parts.map(::sectionName))
                assertEquals(listOf("Recall", "Structure"), scaleNames(sectionBody(parts[0])))
                assertEquals(listOf("Style"), scaleNames(sectionBody(parts[1])))
                sectionBody(parts[1])[0].findViewById<View>(R.id.reviewTagScaleMenu).performClick()
            }
            instrumentation.waitForIdleSync()
            pickSection()
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                // No section: Style stands on its own between the two headers.
                checkNotNull(lastReviewSectionDialog?.get()).getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertNull(ReviewTagGroups.groups().first { it.id == "wl2" }.section)
                val parts = scaleParts()
                assertEquals(3, parts.size)
                assertEquals("Work left", sectionName(parts[0]))
                assertEquals(listOf("Style"), scaleNames(parts.subList(1, 2)))
                assertEquals("Work done", sectionName(parts[2]))
                // The record keeps every other section and waits for a server that keeps them.
                assertTrue(ReviewTagGroups.pending())
                assertEquals(listOf("Work left", "Work left", null, "Work done", "Work left", null),
                    ReviewTagGroups.groups().map { it.section })
                checkNotNull(sheet).dismiss()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun View.findViewWithText(text: String): View =
        checkNotNull(findFirst { it is TextView && it.text.toString() == text }) { "no view with text $text" }

    private inline fun <reified T : View> View.findViewWithClass(): T? = findFirst { it is T } as T?

    private fun View.findFirst(match: (View) -> Boolean): View? = when {
        match(this) -> this
        this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findFirst(match) }
        else -> null
    }
}

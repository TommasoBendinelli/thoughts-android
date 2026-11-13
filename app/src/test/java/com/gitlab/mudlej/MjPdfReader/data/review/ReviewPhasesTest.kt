// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Group
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

/** An answer in two steps: its tags, notes and `review_phases`, as the phone and the desktop write them. */
class ReviewPhasesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val recallSteps = listOf("Forgot", "Partly recalled", "Recalled")
    private val groups = listOf(
        Group("f", "Feeling", listOf("Meh", "Good"), scale = listOf("Meh", "Good"), phase = "before"),
        Group("r", "Recall", recallSteps, scale = recallSteps, phase = "before_and_after"),
        Group("u", "Understanding", listOf("Lost", "I get it"), scale = listOf("Lost", "I get it")),
        Group("e", "Study effort", listOf("Skimmed", "Read", "Studied"), scale = listOf("Skimmed", "Read", "Studied")),
    )
    private val prediction = ReviewAgentPrediction(
        tag = "Partly recalled", groupId = "r", reason = "Two answers, the last one with gaps.", model = "claude-test",
        predictionId = "6f1e9a52-2f0b-4d8e-b8a9-0c3f5c1b2e77", requestedAt = "2026-11-03T12:00:00.500Z",
        madeAt = "2026-11-03T12:00:04.000Z",
    )

    @Test fun theFieldIsWrittenAsTheSharedSpecSays() {
        val phases = ReviewAnswerPhases.phases(
            before = listOf("Partly recalled", "Meh"),
            after = listOf("Recalled", "I get it", "Read"),
            answerShownAt = Instant.parse("2026-11-03T12:00:05.120456Z"),
            prediction = prediction,
            groups = groups,
        )
        assertEquals(JsonParser.parseString("""
            {"schema_version":1,
             "before":{"tags":["Meh","Partly recalled"],"answer_shown_at":"2026-11-03T12:00:05.120Z"},
             "after":{"tags":["Recalled","I get it","Read"]},
             "agent_prediction":{"tag":"Partly recalled","group_id":"r","reason":"Two answers, the last one with gaps.",
               "model":"claude-test","prediction_id":"6f1e9a52-2f0b-4d8e-b8a9-0c3f5c1b2e77",
               "requested_at":"2026-11-03T12:00:00.500Z","made_at":"2026-11-03T12:00:04.000Z","arrived_before_answer":true}}
        """), phases.toJson())
        assertEquals(phases, ReviewPhases.parse(JsonObject().apply { add(ReviewPhases.FIELD, phases.toJson()) }))
        // No prediction before Save, and an answer never shown: both left out.
        val plain = ReviewPhases(listOf("Meh"), listOf("I get it"))
        assertEquals(JsonParser.parseString("""{"schema_version":1,"before":{"tags":["Meh"]},"after":{"tags":["I get it"]}}"""),
            plain.toJson())
        assertEquals("review_phases", ReviewPhases.FIELD)
        assertEquals("before_note", ReviewGroupNotes.BEFORE_NOTE_KEY)
    }

    @Test fun aPredictionArrivedBeforeTheAnswerWhenItWasMadeNoLaterThanTheAnswerWasShown() {
        val shown = Instant.parse("2026-11-03T12:00:05.120Z")
        fun arrived(madeAt: String, at: Instant? = shown) = prediction.copy(madeAt = madeAt).arrivedBy(at).arrivedBeforeAnswer
        assertTrue(arrived("2026-11-03T12:00:04Z"))
        assertTrue(arrived("2026-11-03T12:00:05.120Z"))
        assertFalse(arrived("2026-11-03T12:00:05.121Z"))
        assertFalse(arrived("not a time"))
        assertTrue(arrived("2026-11-03T12:00:09Z", at = null))
        // Compared as recorded, to the millisecond.
        val phases = ReviewAnswerPhases.phases(emptyList(), emptyList(), Instant.parse("2026-11-03T12:00:05.120900Z"),
            prediction.copy(madeAt = "2026-11-03T12:00:05.120Z"), groups)
        assertTrue(checkNotNull(phases.agentPrediction).arrivedBeforeAnswer)
    }

    @Test fun malformedPartsAreLeftOutAndOlderAnswersHaveNone() {
        assertNull(ReviewPhases.parse(JsonParser.parseString("""{"review_tags":["correct"]}""").asJsonObject))
        assertNull(ReviewPhases.parse(JsonParser.parseString("""{"review_phases":["before"]}""").asJsonObject))
        val odd = ReviewPhases.parse(JsonParser.parseString("""
            {"review_phases":{"schema_version":2,"before":{"tags":["  Meh ",3,{"x":1},"Meh"],"answer_shown_at":5},
              "after":"Recalled","agent_prediction":{"reason":"no tag"},"later":true}}
        """).asJsonObject)
        assertEquals(ReviewPhases(listOf("Meh"), emptyList(), null, null), odd)
        val partial = ReviewAgentPrediction.parse(JsonParser.parseString("""{"tag":"Recalled","arrived_before_answer":"yes"}""").asJsonObject)
        assertEquals("Recalled", partial?.tag)
        assertEquals("", partial?.reason)
        assertFalse(checkNotNull(partial).arrivedBeforeAnswer)
    }

    @Test fun theAfterStepStartsAsTodayWithoutBeforeOnlyStepsThenAtThePrediction() {
        // Free tags and Feeling (asked afresh before the answer) do not carry over.
        val start = ReviewAnswerPhases.afterStart(listOf("Good", "Recalled", "I get it", "proof"), groups)
        assertEquals(listOf("Recalled", "I get it"), start)
        // Recall starts at the predicted step once the answer is shown; the other sliders keep theirs.
        assertEquals(listOf("I get it", "Partly recalled"),
            ReviewAnswerPhases.startAtPrediction(start, listOf("Meh", "Partly recalled"), groups))
        // Without a prediction, as today.
        assertEquals(start, ReviewAnswerPhases.startAtPrediction(start, listOf("Meh"), groups))
        // Merged names count as the step they became.
        assertEquals(listOf("I get it", "Forgot"),
            ReviewAnswerPhases.startAtPrediction(start, listOf("Blank"), groups, mapOf("Blank" to "Forgot")))
        // A layout without phases: exactly today's slider steps.
        val plain = groups.map { it.copy(phase = null) }
        assertEquals(listOf("Good", "Recalled", "I get it"), ReviewAnswerPhases.afterStart(listOf("Good", "Recalled", "I get it", "proof"), plain))
        assertEquals(listOf("Recalled"), ReviewAnswerPhases.startAtPrediction(listOf("Recalled"), listOf("Forgot"), plain))
    }

    @Test fun theTagsInEffectAreTheBeforeOnlyTagsAndTheAfterStep() {
        val before = listOf("Meh", "Partly recalled")
        val after = listOf("Recalled", "I get it", "Read")
        val tags = ReviewAnswerPhases.recordedTags(before, after, groups)
        assertEquals(listOf("Meh", "Recalled", "I get it", "Read"), tags)
        // The prediction is a tag of the answer only when it is also the actual step.
        assertEquals(listOf("Meh", "Partly recalled"), ReviewAnswerPhases.recordedTags(before, listOf("Partly recalled"), groups))
        // Every tag in effect is a tag of one of the steps.
        val phases = ReviewAnswerPhases.phases(before, after, Instant.parse("2026-11-03T12:00:05Z"), null, groups)
        assertTrue(tags.all { it in phases.beforeTags || it in phases.afterTags })
    }

    @Test fun historyLinesNameBothStepsTheirNotesAndThePrediction() {
        val phases = ReviewPhases(listOf("Meh", "Partly recalled"), listOf("Recalled", "I get it", "Read"),
            "2026-11-03T12:00:05.120Z", prediction.copy(arrivedBeforeAnswer = true))
        val notes = mapOf("before_note" to "felt shaky", "note" to "fine after all", "u" to "the second step")
        assertEquals(listOf("Before: Meh · Partly recalled — felt shaky", "After: Recalled · I get it · Read — fine after all",
            "Agent predicted: Partly recalled"), phases.historyLines(notes, { it }, "Not rated"))
        assertEquals(listOf("Vorher: Nicht bewertet", "Nachher: I GET IT"),
            ReviewPhases(emptyList(), listOf("I get it")).historyLines(emptyMap(), { it.uppercase() }, "Nicht bewertet",
                before = "Vorher", after = "Nachher", agent = "Agent"))
    }

    @Test fun theBeforeNoteIsAReservedGroupNoteListedFirst() {
        val notes = linkedMapOf("note" to "after", "u" to "vague", "before_note" to "shaky")
        assertEquals(listOf("Before note: shaky", "Understanding: vague", "Note: after"), ReviewGroupNotes.lines(notes, groups))
        assertEquals(listOf("Vorher: shaky"), ReviewGroupNotes.lines(mapOf("before_note" to "shaky"), groups, beforeNoteName = "Vorher"))
        // Even a group record carrying that id does not name it.
        assertEquals(listOf("Before note: x"), ReviewGroupNotes.lines(mapOf("before_note" to "x"), groups + Group("before_note", "Clash", emptyList())))
        assertEquals(JsonParser.parseString("""{"before_note":"shaky","note":"after"}"""),
            ReviewGroupNotes.toJson(mapOf("before_note" to " shaky ", "note" to "after")))
    }

    @Test fun anAnswerRecordsWhetherItCameFromReviewNowOrTheDueCards() {
        val root = temporary.newFolder("entry-root")
        val incoming = temporary.newFolder("entry-incoming")
        val events = temporary.newFolder("entry-events")
        val cards = temporary.newFolder("entry-cards")
        val first = writeCard(root, "paper")
        val second = writeCard(root, "paper")
        val now = Instant.parse("2026-11-12T20:00:00Z")
        val store = ReviewStore(null, root, incoming, events, cards)
        fun payload(file: java.io.File) = JsonParser.parseString(file.readText()).asJsonObject
        fun eventOf(id: String) = JsonParser.parseString((events.walkTopDown() + cards.walkTopDown() + root.walkTopDown())
            .first { it.isFile && it.nameWithoutExtension == id }.readText()).asJsonObject
        val reviewNow = store.recordScoped(first, ReviewSkipScope.CARD, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600),
            null, reviewTags = listOf("Recalled"), reviewEntry = ReviewEntry.of(singleCardSession = true)).getOrThrow()
        assertEquals("review_now", eventOf(reviewNow.getValue(first)).get("review_entry").asString)
        val due = store.record(second, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600), reviewTags = listOf("Recalled"),
            reviewEntry = ReviewEntry.of(singleCardSession = false)).getOrThrow()
        assertEquals("due_queue", payload(due).get("review_entry").asString)
        // Without one the field is left out, and a deletion never carries it.
        val plain = store.record(second, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(1), now.plusSeconds(900)).getOrThrow()
        assertFalse(payload(plain).has("review_entry"))
        assertTrue(store.record(second, ReviewRating.DELETE, now.plusSeconds(2), reviewEntry = ReviewEntry.REVIEW_NOW).isFailure)
        assertEquals("review_entry", ReviewEntry.FIELD)
    }

    @Test fun anAnswerInTwoStepsIsRecordedWithItsStepsAndReadBack() {
        val root = temporary.newFolder("phases-root")
        val incoming = temporary.newFolder("phases-incoming")
        val events = temporary.newFolder("phases-events")
        val cards = temporary.newFolder("phases-cards")
        val first = writeCard(root, "paper")
        val second = writeCard(root, "paper")
        val now = Instant.parse("2026-11-03T12:00:10Z")
        val store = ReviewStore(null, root, incoming, events, cards)
        val phases = ReviewAnswerPhases.phases(listOf("Meh", "Partly recalled"), listOf("Recalled", "I get it", "Read"),
            Instant.parse("2026-11-03T12:00:05.120Z"), prediction, groups)
        val tags = ReviewAnswerPhases.recordedTags(listOf("Meh", "Partly recalled"), listOf("Recalled", "I get it", "Read"), groups)
        val recorded = store.recordScoped(first, ReviewSkipScope.CARD, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600),
            null, reviewTags = tags, groupNotes = mapOf("before_note" to "felt shaky", "note" to "fine"), reviewPhases = phases).getOrThrow()
        fun payload(id: String) = JsonParser.parseString((events.walkTopDown() + cards.walkTopDown() + root.walkTopDown())
            .first { it.isFile && it.nameWithoutExtension == id }.readText()).asJsonObject
        val event = payload(recorded.getValue(first))
        assertEquals(listOf("Meh", "Recalled", "I get it", "Read"), event.getAsJsonArray("review_tags").map { it.asString })
        assertEquals(JsonParser.parseString("""{"before_note":"felt shaky","note":"fine"}"""), event.get("review_group_notes"))
        assertEquals(phases.toJson(), event.get("review_phases"))
        // Any other answer has no field.
        val plain = store.record(second, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600), reviewTags = listOf("I get it")).getOrThrow()
        assertFalse(JsonParser.parseString(plain.readText()).asJsonObject.has("review_phases"))
        val restored = ReviewStore(null, root, incoming, events, cards).browserCards(now).associateBy { it.card.id }
        val answer = restored.getValue(first).history.single { it.rating == "custom_interval" }
        assertEquals(phases, answer.phases)
        assertEquals(listOf("Meh", "Recalled", "I get it", "Read"), restored.getValue(first).currentTags())
        assertNull(restored.getValue(second).history.single { it.rating == "custom_interval" }.phases)
    }

    @Test fun anAnswerAsTheDesktopWritesItReadsTheSame() {
        val root = temporary.newFolder("desktop-root")
        val card = writeCard(root, "paper")
        val eventId = "0b0e4c58-8f6e-4a43-9d0c-2b9b1d0b6c11"
        // Field order and number formats as the desktop writes them; extra fields it may add are ignored.
        File(root, "events/desktop").apply { mkdirs() }.resolve("$eventId.json").writeText("""
            {"schema_version":3,"event_id":"$eventId","card_id":"$card","rating":"custom_interval",
             "review_tags":["Meh","Recalled","I get it","Read"],
             "review_group_notes":{"before_note":"felt shaky","note":"fine after all","u":"the second step"},
             "review_phases":{"schema_version":1,
               "before":{"tags":["Meh","Partly recalled"],"answer_shown_at":"2026-11-03T12:00:05.120Z"},
               "after":{"tags":["Recalled","I get it","Read"]},
               "agent_prediction":{"tag":"Partly recalled","group_id":"r","reason":"Two answers, the last one with gaps.",
                 "model":"claude-test","prediction_id":"6f1e9a52-2f0b-4d8e-b8a9-0c3f5c1b2e77",
                 "requested_at":"2026-11-03T12:00:00.500Z","made_at":"2026-11-03T12:00:04.000Z",
                 "arrived_before_answer":true}},
             "scheduled_due_at":"2026-11-05T12:00:20Z","scheduler_version":4,
             "reviewed_at":"2026-11-03T12:00:20.000Z","updated_at":"2026-11-03T12:00:20.000Z","revision":1,
             "origin_id":"desktop-mac"}
        """.trimIndent())
        val now = Instant.parse("2026-11-03T12:30:00Z")
        val browser = ReviewStore(null, root, temporary.newFolder("desktop-in"), temporary.newFolder("desktop-events"),
            temporary.newFolder("desktop-out")).browserCards(now).single { it.card.id == card }
        val answer = browser.history.single { it.eventId == eventId }
        val phases = checkNotNull(answer.phases)
        assertEquals(listOf("Meh", "Partly recalled"), phases.beforeTags)
        assertEquals(listOf("Recalled", "I get it", "Read"), phases.afterTags)
        assertEquals("2026-11-03T12:00:05.120Z", phases.answerShownAt)
        assertEquals(prediction.copy(arrivedBeforeAnswer = true), phases.agentPrediction)
        // The tags in effect are review_tags, as for any answer; the prediction is not one of them.
        assertEquals(listOf("Meh", "Recalled", "I get it", "Read"), browser.currentTags())
        assertEquals(mapOf("before_note" to "felt shaky", "note" to "fine after all", "u" to "the second step"), answer.groupNotes)
        assertEquals(listOf("Before: Meh · Partly recalled — felt shaky", "After: Recalled · I get it · Read — fine after all",
            "Agent predicted: Partly recalled"), phases.historyLines(answer.groupNotes, { it }, "Not rated"))
        // Read back exactly: the phone writes the same field for the same answer.
        assertEquals(JsonParser.parseString(File(root, "events/desktop/$eventId.json").readText()).asJsonObject.get("review_phases"),
            phases.toJson())
    }

    private fun writeCard(root: File, pdfStem: String, cardId: String = UUID.randomUUID().toString()): String {
        val cards = File(root, "cards").apply { mkdirs() }
        File(cards, "$cardId.json").writeText(
            """{"schema_version":3,"card_id":"$cardId","status":"approved","revision":1,"primary":{"pdf_stem":"$pdfStem","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"What is hidden?"}}""",
        )
        return cardId
    }
}

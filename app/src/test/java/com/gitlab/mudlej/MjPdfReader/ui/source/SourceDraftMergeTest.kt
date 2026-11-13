// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SourceDraftMergeTest {
    @Test fun independentAndAdjacentChangesMerge() {
        assertEquals("A\nB\nc\n", SourceDraftMerge.merge("a\nb\nc\n", "A\nb\nc\n", "a\nB\nc\n"))
        assertEquals("start\na\nb\nend", SourceDraftMerge.merge("a\nb\n", "start\na\nb\n", "a\nb\nend"))
        assertEquals("B\nc", SourceDraftMerge.merge("a\nb\nc", "b\nc", "a\nB\nc"))
    }
    @Test fun separateEditsOnOneLineMerge() {
        assertEquals("The green cat sleeps.", SourceDraftMerge.merge("The blue cat sits.", "The green cat sits.", "The blue cat sleeps."))
        assertEquals("a\nx\nB\n", SourceDraftMerge.merge("a\nb\n", "a\nx\nb\n", "a\nB\n"))
    }
    @Test fun identicalChangesAndUnchangedSidesDoNotConflict() {
        assertEquals("new", SourceDraftMerge.merge("old", "new", "new"))
        assertEquals("new", SourceDraftMerge.merge("old", "old", "new"))
        assertEquals("new", SourceDraftMerge.merge("old", "new", "old"))
        assertEquals("A\nb\nC", SourceDraftMerge.merge("a\nb\nc", "A\nb\nc", "A\nb\nC"))
    }
    @Test fun overlappingChangesKeepAllThreeVersionsAndIndependentEdits() {
        val merged = SourceDraftMerge.merge("a\nb\nc\nd\n", "a\nlocal\nc\nd\n", "a\nremote\nc\nD\n")
        assertEquals("a\n<<<<<<< Your draft\nlocal\n||||||| Previous source\nb\n=======\nremote\n>>>>>>> Synced source\nc\nD\n", merged)
    }
    @Test fun competingInsertionsAndDeleteVersusEditConflict() {
        assertTrue(SourceDraftMerge.merge("a\n", "x\na\n", "y\na\n").contains("<<<<<<<"))
        assertTrue(SourceDraftMerge.merge("a\nb\n", "a\n", "a\nB\n").contains("<<<<<<<"))

    }
    @Test fun emptyFilesAndMissingFinalNewlineArePreserved() {
        assertEquals("", SourceDraftMerge.merge("a", "", "a"))
        assertEquals("hello", SourceDraftMerge.merge("", "hello", ""))
        assertTrue(SourceDraftMerge.merge("", "hello", "world").contains("hello\n||||||| Previous source\n=======\nworld\n"))
        assertEquals("A\nB", SourceDraftMerge.merge("a\nb", "A\nb", "a\nB"))
    }
    @Test fun reconciliationPreservesHistoryAndArchivesUndoWithoutRevertingSyncedText() {
        val state = JsonParser.parseString("""{"filename":"f.tex","baseSha256":"old","savedSource":"a\nb\n","source":"A\nb\n","events":[{"event_id":"immutable"}],"undo":[{"before":"a\nb\n","after":"A\nb\n"}],"redo":[],"savingSource":"stale"}""").asJsonObject
        val original = state.deepCopy()
        assertTrue(SourceDraftMerge.reconcile(state,"new","a\nB\n"))
        assertEquals("A\nB\n",state.get("source").asString)
        assertEquals(original.get("events"),state.get("events"))
        val recovery=state.getAsJsonArray("mergeRecoveries")[0].asJsonObject
        assertFalse(recovery.has("undo"))
        assertFalse(recovery.has("events"))
        assertEquals(original.get("source"),recovery.get("source"))
        // Undo returns to the synced text; the step stores the changed span only.
        val step=ReviewTextEdit.fromJson(state.getAsJsonArray("undo")[0].asJsonObject)!!
        assertEquals("a\nB\n",step.undo(state.get("source").asString))
        assertFalse(state.getAsJsonArray("undo")[0].asJsonObject.has("before"))
        assertFalse(state.has("savingSource"))
        assertFalse(SourceDraftMerge.reconcile(state,"new","a\nB\n"))
        assertEquals(1,state.getAsJsonArray("mergeRecoveries").size())
    }
    @Test fun recoveryIsBoundedAndOnlyForActualDraftMerges() {
        val state = JsonParser.parseString("""{"baseSha256":"old","savedSource":"a\nb\n","source":"a\nb\n","events":[],"undo":[],"redo":[],"mergeRecoveries":[{"source":"obsolete"}]}""").asJsonObject
        SourceDraftMerge.reconcile(state, "new", "a\nB\n")
        assertEquals(0, state.getAsJsonArray("mergeRecoveries").size())
        state.addProperty("source", "LOCAL\nB\n")
        SourceDraftMerge.reconcile(state, "next", "a\nREMOTE\n")
        assertEquals(1, state.getAsJsonArray("mergeRecoveries").size())
        SourceDraftMerge.reconcile(state, "next2", "a\nREMOTE2\n")
        assertEquals(1, state.getAsJsonArray("mergeRecoveries").size())
        val before = state.deepCopy()
        SourceDraftMerge.cleanRecoveries(state, "unrelated")
        assertEquals(before, state)
        state.addProperty("source", "resolved")
        SourceDraftMerge.cleanRecoveries(state, "resolved")
        assertEquals(1, state.getAsJsonArray("mergeRecoveries").size())
        state.addProperty("savedSource", "resolved")
        SourceDraftMerge.cleanRecoveries(state, "resolved")
        assertEquals(0, state.getAsJsonArray("mergeRecoveries").size())
    }

    @Test fun broadRewritesHaveBoundedMemoryAndPreserveBothVersions() {
        val base=(0..2100).joinToString("\n") { "base $it" }
        val local=(0..2100).joinToString("\n") { "local $it" }
        val remote=(0..2100).joinToString("\n") { "remote $it" }
        val result=SourceDraftMerge.merge(base,local,remote)
        assertTrue(result.contains(local)); assertTrue(result.contains(remote)); assertTrue(result.contains(base))
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TemporaryRecoveryInstrumentedTest {
    @Test fun migrationAndAcknowledgementPreserveDraftAndRejectStaleRecoveryResurrection() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "temporary-recovery-${UUID.randomUUID()}")
        root.mkdirs()
        try {
            val state = JsonParser.parseString("""{"filename":"test.tex","source":"local","savedSource":"base","baseSha256":"old","events":[],"undo":[{"before":"base","after":"local"}],"redo":[],"mergeRecoveries":[{"source":"old archive","events":[]}]}""").asJsonObject
            val draft = state.get("source").deepCopy()
            val undo = state.get("undo").deepCopy()
            val file = File(root, ProposalSourceActivity.digest("test.tex") + ".json")
            file.writeText(state.toString())
            ProposalReviewJournal.cleanLegacyJournals(root)
            val journal = ProposalReviewJournal(root, "test.tex")
            val migrated = journal.read()!!
            assertEquals(draft, migrated.get("source"))
            // Format 2 stores the legacy step as the changed span; it still undoes to "base".
            assertEquals(1, undo.asJsonArray.size())
            assertEquals("base", ReviewTextEdit.fromJson(migrated.getAsJsonArray("undo")[0].asJsonObject)!!.undo("local"))
            assertEquals(0, migrated.getAsJsonArray("mergeRecoveries").size())
            SourceDraftMerge.reconcile(migrated, "remote", "remote")
            migrated.addProperty("source", "resolved")
            journal.write(migrated)
            journal.sourceSynced("resolved") // Not saved yet: keep recovery.
            assertEquals(1, journal.read()!!.getAsJsonArray("mergeRecoveries").size())
            migrated.addProperty("savedSource", "resolved")
            journal.sourceSynced("unrelated")
            journal.write(migrated)
            assertEquals(1, journal.read()!!.getAsJsonArray("mergeRecoveries").size())
            journal.sourceSynced("resolved")
            assertEquals(0, journal.read()!!.getAsJsonArray("mergeRecoveries").size())
            journal.write(migrated) // An open editor must not resurrect the cleared snapshot.
            assertEquals(0, journal.read()!!.getAsJsonArray("mergeRecoveries").size())
            assertEquals("resolved", journal.read()!!.get("source").asString)
        } finally { root.deleteRecursively() }
    }
}

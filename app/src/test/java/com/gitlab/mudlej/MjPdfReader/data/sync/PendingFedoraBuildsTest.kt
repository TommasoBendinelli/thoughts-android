// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sync leaves Fedora's running builds to RemoteWorkWatch instead of waiting for them (13 November 2026). */
class PendingFedoraBuildsTest {
    private fun progress(id: String, status: String, target: String = "all", documents: List<String> = emptyList()) =
        SyncBuildProgress(id, target, status, "", 0, 0, "", "", documents = documents)

    @Test
    fun aWholeLibrarySyncReplacesWhatItSawAndAOneDocumentSyncMerges() {
        val previous = listOf(PendingFedoraBuild("old", listOf("a.pdf")), PendingFedoraBuild("b-build", listOf("b.pdf")))
        val observed = listOf(
            progress("new", "running", documents = listOf("c.pdf", "d.pdf")),
            progress("b-build", "succeeded", target = "b.pdf"),
        )
        // Fedora was asked about every document: the old build is no longer pending.
        assertEquals(
            listOf(PendingFedoraBuild("new", listOf("c.pdf", "d.pdf"))),
            pendingFedoraBuildsAfter(previous, observed, wholeLibrary = true),
        )
        // One document: builds it did not see stay pending, finished ones go.
        assertEquals(
            listOf(PendingFedoraBuild("old", listOf("a.pdf")), PendingFedoraBuild("new", listOf("c.pdf", "d.pdf"))),
            pendingFedoraBuildsAfter(previous, observed, wholeLibrary = false),
        )
        // A targeted build names its PDF; queued counts like running.
        assertEquals(
            listOf(PendingFedoraBuild("t", listOf("paper.pdf"))),
            pendingFedoraBuildsAfter(emptyList(), listOf(progress("t", "queued", target = "paper.pdf")), wholeLibrary = true),
        )
        // Nothing running: nothing pending, so the Library says nothing about building.
        assertEquals(emptyList<PendingFedoraBuild>(),
            pendingFedoraBuildsAfter(previous, listOf(progress("x", "failed")), wholeLibrary = true))
    }

    @Test
    fun theLibraryCountsEachPdfOnce() {
        assertEquals(0, pendingFedoraPdfCount(emptyList()))
        assertEquals(3, pendingFedoraPdfCount(listOf(
            PendingFedoraBuild("1", listOf("a.pdf", "b.pdf")),
            PendingFedoraBuild("2", listOf("b.pdf", "c.pdf")),
        )))
        // A build that names no PDF still makes at least one.
        assertEquals(2, pendingFedoraPdfCount(listOf(PendingFedoraBuild("1", listOf("a.pdf")), PendingFedoraBuild("2"))))
        assertEquals("1 PDF still building on the server", pdfsStillBuildingMessage(1))
        assertEquals("2 PDFs still building on the server", pdfsStillBuildingMessage(2))
    }

    @Test
    fun anEndedSyncSaysHowManyPdfsWereStillBuilding() {
        val record = SyncOperationRecord("op", null, SyncOperationKind.SYNC, SyncOperationStore.STATUS_SYNCED, 1, 2, emptyList(),
            buildProgress = listOf(progress("1", "running", documents = listOf("a.pdf", "b.pdf"))))
        assertEquals(2, pdfsStillBuildingAfter(record))
        assertEquals(0, pdfsStillBuildingAfter(record.copy(status = SyncOperationStore.STATUS_ERROR)))
        assertEquals(0, pdfsStillBuildingAfter(record.copy(buildProgress = listOf(progress("1", "succeeded")))))
        assertEquals(0, pdfsStillBuildingAfter(null))
    }

    @Test
    fun theStoreKeepsThePendingBuildsAndForgetsFinishedOnes() {
        val preferences = MemoryPreferences()
        val store = PendingFedoraBuildStore(preferences)
        store.recordObserved(listOf(progress("1", "running", documents = listOf("a.pdf")), progress("2", "queued", target = "b.pdf")),
            wholeLibrary = true)
        // Read back as a new process would.
        assertEquals(listOf(PendingFedoraBuild("1", listOf("a.pdf")), PendingFedoraBuild("2", listOf("b.pdf"))),
            PendingFedoraBuildStore(preferences).builds())
        assertEquals(2, store.pdfCount())
        store.remove(listOf("1"))
        assertEquals(listOf(PendingFedoraBuild("2", listOf("b.pdf"))), store.builds())
        store.recordObserved(listOf(progress("2", "succeeded")), wholeLibrary = false)
        assertEquals(emptyList<PendingFedoraBuild>(), store.builds())
        assertTrue(preferences.values.isEmpty())
        // Garbage left by anything else reads as nothing pending.
        preferences.values["builds"] = "{not json"
        assertEquals(emptyList<PendingFedoraBuild>(), store.builds())
    }

    private class MemoryPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableListOf<() -> Unit>()
            override fun putString(key: String, value: String?) = apply { changes += { values[key] = value } }
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun remove(key: String) = apply { changes += { values.remove(key) } }
            override fun clear() = apply { changes += { values.clear() } }
            override fun commit(): Boolean { changes.forEach { it() }; return true }
            override fun apply() { commit() }
        }
    }
}

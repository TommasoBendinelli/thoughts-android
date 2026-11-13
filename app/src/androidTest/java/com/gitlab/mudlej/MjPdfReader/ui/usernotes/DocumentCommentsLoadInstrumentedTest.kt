// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCatalogue
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.SourceProjectionMemoryCache
import com.gitlab.mudlej.MjPdfReader.data.source.logicalReadScope
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in read-only audit against an installed PDF; never changes comments or runs Sync. */
class DocumentCommentsLoadInstrumentedTest {
    @Test fun scopedLoadMatchesWholeLibraryForInstalledDocument() = runBlocking {
        val filename = InstrumentationRegistry.getArguments().getString("commentsPdf")
        assumeTrue("Requires an explicitly selected installed PDF", filename != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ThoughtsLocalStore.currentPdfs(), requireNotNull(filename))
        assumeTrue(file.isFile)
        val store = OfflineSourceBundleStore(context)
        SourceProjectionMemoryCache.clear()
        val started = SystemClock.elapsedRealtime()
        val scoped = store.overviewSourceSnapshot(filename)
        val sourceMillis = SystemClock.elapsedRealtime() - started
        val payloads = FedoraRepository(context).effectiveLogicalCommentPayloads(scoped.logicalReadScope(filename))
        val catalogue = CommentCatalogue().commentsFor(filename)
        val hash = computeSha256(file)
        assertNotNull(hash)
        val actual = documentComments(filename, catalogue, scoped.withLogicalPayloads(payloads), hash)
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i("CommentsLoadAudit", "$filename: ${actual.size} comments, source=${sourceMillis}ms total=${elapsed}ms")
        val warmStarted = SystemClock.elapsedRealtime()
        val warm = store.overviewSourceSnapshot(filename).withLogicalPayloads(
            FedoraRepository(context).effectiveLogicalCommentPayloads(scoped.logicalReadScope(filename)),
        )
        val warmRows = documentComments(filename, CommentCatalogue().commentsFor(filename), warm, computeSha256(file))
        val warmMillis = SystemClock.elapsedRealtime() - warmStarted
        Log.i("CommentsLoadAudit", "warm $filename: ${warmRows.size} comments, total=${warmMillis}ms")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("comments_load_audit", "$filename: ${actual.size} comments; cold=${elapsed}ms; source=${sourceMillis}ms; warm=${warmMillis}ms")
        })
        assertEquals(actual, warmRows)
        SourceProjectionMemoryCache.clear()
        val diskStarted = SystemClock.elapsedRealtime()
        val disk = store.overviewSourceSnapshot(filename).withLogicalPayloads(payloads)
        val diskRows = documentComments(filename, catalogue, disk, hash)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("comments_disk_audit", "$filename: ${diskRows.size} comments; cleared-memory=${SystemClock.elapsedRealtime() - diskStarted}ms")
        })
        assertEquals(actual, diskRows)
        val all = store.overviewSourceSnapshot().withLogicalPayloads(FedoraRepository(context).effectiveLogicalCommentPayloads())
        assertEquals(documentComments(filename, catalogue, all, hash), actual)
    }
}

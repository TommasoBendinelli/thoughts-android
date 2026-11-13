// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.annotation.*
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.tbend.thoughts.sdk.SyncClient
import java.io.File
import java.time.Instant

class NoteAttachmentSyncTest {
    @Test fun fileUploadsBeforeReferenceAndDownloadsWithIdenticalBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = requireNotNull(ThoughtsServerSecretStore(context).config())
        assertEquals(BuildConfig.TEST_SERVER_URL, config.baseUrl)
        val client = SyncClient(config.baseUrl, config.token)
        val id = "test-${TestRunIdentity.id}-attachment-sync"
        val source = File(context.cacheDir, "$id.txt").apply { writeText("[TEST ${TestRunIdentity.id}] Synced attachment") }
        val bytes = source.readBytes()
        val store = NoteAttachmentStore(context)
        val item = store.import(Uri.fromFile(source))
        val repository = FedoraRepository(context)
        repository.enqueueLogicalComment(newNotePayload(id, "", Instant.now().toString(), listOf(item)))
        repository.syncDeltas()
        assertTrue(AppDatabase.getInstance(context).thoughtsSyncDao().undelivered().isEmpty())
        val remote = client.projections().projections.single { it.entityId == id }.payload
        assertEquals(listOf(item), NoteAttachment.read(remote))
        assertTrue(client.hasObject(item.sha256))
        assertTrue(store.file(item).delete())
        assertArrayEquals(bytes, repository.downloadNoteAttachment(item).readBytes())
        // Removal changes the note reference, preserving the unrelated text and object.
        val changed = requireNotNull(logicalCommentMutationPayload(remote, id, false, "Kept note"))
        repository.enqueueLogicalComment(NoteAttachment.write(changed, emptyList()))
        repository.syncDeltas()
        val after = client.projections().projections.single { it.entityId == id }.payload
        assertTrue(NoteAttachment.read(after).isEmpty())
        assertEquals("Kept note", after.get("contents").asString)
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.render.sourceLinkedLogicalFile
import com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutation
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.library.LibraryImportController
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Put in Library", Library rename and archive change the phone's Library at once, offline, and
 * leave publishing to the next Sync the user starts: none of them records or schedules a Sync.
 */
class LocalFirstLibraryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val canonical = PhoneRenderQueue.defaultCanonicalPdfDirectory()

    private fun pdf(file: File, pages: Int) {
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                page.canvas.drawText("Local-first Library test ${TestRunIdentity.id} page $index", 20f, 40f, android.graphics.Paint())
                document.finishPage(page)
            }
            file.parentFile?.mkdirs()
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun sha(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** A Library generation with one published unlinked PDF, as a completed Sync leaves it. */
    private fun publishedLibrary(filename: String): File {
        val published = File(canonical, filename)
        pdf(published, pages = 1)
        File(canonical, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME).writeText(
            """{"schema_version":1,"library_revision":"${"1".repeat(64)}","source_index_sha256":"${"2".repeat(64)}",""" +
                """"pdfs":[{"filename":"$filename","document_id":"4b1e2f3a-9c8d-4e7f-a6b5-000000000001","kind":"unlinked",""" +
                """"pdf_sha256":"${sha(published)}","display_name":"Published","archived":false}]}""",
        )
        return published
    }

    private fun <T> waitFor(description: String, timeoutMillis: Long = 15_000, value: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            value()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun assertNoSyncStarted() {
        val operations = SyncOperationStore(context)
        assertNull("No Sync was recorded", operations.latestGlobalOperation())
        assertTrue("No Sync is pending", operations.pendingOperations().isEmpty())
    }

    @Test
    fun putInLibraryAddsThePdfAtOnceWithoutSync() {
        val run = TestRunIdentity.id
        publishedLibrary("test-$run-published.pdf")
        val external = File(context.cacheDir, "external/test-$run-import.pdf")
        pdf(external, pages = 2)
        val digest = sha(external)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(external), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                LibraryImportController(activity, root).putInLibrary(Uri.fromFile(external), "test-$run-import.pdf")
            }
            val filename = "test-$run-import.pdf"
            val add = waitFor("the import to be queued") {
                LibraryMutationStore(context).operations().firstOrNull { it.filename == filename }
            }
            assertEquals(LibraryMutation.Type.ADD, add.type)
            assertEquals(LibraryMutation.State.PENDING, add.state)
            assertEquals(digest, add.sha256)

            val snapshot = SourceLinkedPdfIndex().apply { invalidate() }.snapshot()
            val local = LibraryMutationStore(context).pdfFile(add)
            assertEquals(digest, sha(local))
            assertEquals(LibraryPdfKind.UNLINKED, snapshot.libraryKindsByFilename[filename])
            assertEquals(local, snapshot.pendingLibraryPdfFiles[filename])
            assertEquals(LibraryPdfKind.UNLINKED, snapshot.libraryKindFor(local, canonical))
            assertTrue(libraryDisplayEntries(snapshot, canonical).any { it.path == local.path })
            // Comments and cards on the local copy use its permanent document identifier.
            assertEquals(add.documentId, DocumentIdentityStore().identifier(filename))
            assertEquals(add.documentId, DocumentIdentityStore().forFile(local)?.get("document_id")?.asString)
            assertEquals(local, sourceLinkedLogicalFile(context, Uri.fromFile(local)))
            assertNoSyncStarted()

            // Putting the same name in the Library again asks for another name instead of replacing it.
            val again = File(context.cacheDir, "external/test-$run-again.pdf").also { pdf(it, pages = 3) }
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                LibraryImportController(activity, root).putInLibrary(Uri.fromFile(again), filename)
            }
            SystemClock.sleep(1_500)
            assertEquals(1, LibraryMutationStore(context).operations().count { it.filename == filename })
        }
        assertNoSyncStarted()
    }

    @Test
    fun renameAndArchiveShowAtOnceWithoutSync() {
        val run = TestRunIdentity.id
        val filename = "test-$run-published.pdf"
        val published = publishedLibrary(filename)
        val digest = sha(published)
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            val item = HomeItem.fromScan(ScannedPdfEntry(published.absolutePath, published.length(), published.lastModified(), digest))
            fun dialog(activity: HomeActivity): RecordOptionsDialog =
                HomeActivity::class.java.getDeclaredField("recordOptionsDialog").apply { isAccessible = true }
                    .get(activity) as RecordOptionsDialog
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.findViewById<androidx.viewpager2.widget.ViewPager2>(com.gitlab.mudlej.MjPdfReader.R.id.homePager)
                    .setCurrentItem(HomeTab.LIBRARY.ordinal, false)
                RecordOptionsDialog::class.java
                    .getDeclaredMethod("performLibraryDisplayRename", HomeItem::class.java, String::class.java)
                    .apply { isAccessible = true }
                    .invoke(dialog(activity), item, "Renamed on the phone")
            }
            waitFor("the rename to be queued") {
                LibraryMutationStore(context).pending().firstOrNull { it.type == LibraryMutation.Type.RENAME }
            }
            scenario.onActivity { activity ->
                RecordOptionsDialog::class.java
                    .getDeclaredMethod("performLibraryArchiveChange", HomeItem::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(dialog(activity), item, true)
            }
            waitFor("the archive change to be queued") {
                LibraryMutationStore(context).pending().firstOrNull { it.type == LibraryMutation.Type.ARCHIVE }
            }
            val snapshot = SourceLinkedPdfIndex().apply { invalidate() }.snapshot()
            assertEquals("Renamed on the phone", snapshot.libraryDisplayNamesByFilename[filename])
            assertTrue(filename in snapshot.archivedLibraryFilenames)

            // The Library tab lists it in Archived with its new name.
            val archived = waitFor("the Library tab to show the archived item") {
                scenario.let { s ->
                    var found: HomeItem? = null
                    s.onActivity { activity ->
                        val tab = HomeActivity::class.java.getDeclaredField("libraryTab").apply { isAccessible = true }.get(activity)
                        @Suppress("UNCHECKED_CAST")
                        val items = LibraryTabController::class.java.getDeclaredField("archivedItems")
                            .apply { isAccessible = true }.get(tab) as List<HomeItem>
                        found = items.firstOrNull { it.fileName == filename }
                    }
                    found
                }
            }
            assertEquals("Renamed on the phone", archived.libraryDisplayName)
        }
        val operations = LibraryMutationStore(context).operations()
        assertEquals(listOf(LibraryMutation.Type.RENAME, LibraryMutation.Type.ARCHIVE), operations.map { it.type })
        assertTrue(operations.all { it.sha256 == digest })
        assertNoSyncStarted()
        // The Library index itself still holds Fedora's state; the change is only queued.
        assertFalse(File(canonical, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME).readText().contains("Renamed on the phone"))
    }
}

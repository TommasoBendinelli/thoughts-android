// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class HtmlEditionSyncTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Remote(var capabilities: Set<String> = setOf(HtmlEditionSync.CAPABILITY)) : HtmlEditionRemote {
        val objects = mutableMapOf<String, ByteArray>()
        val editions = mutableMapOf<String, JsonObject>()
        val asked = mutableListOf<String>()
        val downloads = mutableListOf<String>()
        override fun capabilities() = capabilities
        override fun edition(pdfSha256: String): JsonObject? { asked += pdfSha256; return editions[pdfSha256] }
        override fun download(sha256: String, destination: File) {
            downloads += sha256
            destination.writeBytes(objects[sha256] ?: throw IOException("404"))
        }
    }

    private fun publish(remote: Remote, pdf: String): String {
        val html = "<p>$pdf</p>".toByteArray()
        remote.objects[sha(html)] = html
        val manifest = """{"kind":"thoughts_html_edition","schema":1,"entry":"index.html","files":{"index.html":"${sha(html)}"}}""".toByteArray()
        remote.objects[sha(manifest)] = manifest
        remote.editions[pdf] = JsonObject().apply {
            addProperty("pdf_sha256", pdf); addProperty("html_status", "ready"); addProperty("html_edition_sha256", sha(manifest))
        }
        return sha(manifest)
    }

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    @Test fun withoutTheCapabilityNothingIsAskedAndTheReaderSaysWhy() {
        val remote = Remote(capabilities = setOf("agent_conversations_v1"))
        val store = HtmlEditionStore(temp.newFolder())
        val summary = HtmlEditionSync(store, remote).run(listOf(HtmlEditionSync.Document("x.pdf", a, true)))
        assertFalse(summary.supported)
        assertEquals(emptyList<String>(), remote.asked)
        assertEquals(HtmlAvailability.Unavailable("the server does not build HTML editions yet"), store.availability(a))
    }

    @Test fun eachSyncRemembersWhetherTheServerTakesAttachmentCards() {
        val store = HtmlEditionStore(temp.newFolder())
        assertFalse("not before a Sync asked", store.attachmentCardSupport())
        HtmlEditionSync(store, Remote(setOf(HtmlEditionSync.CAPABILITY, HtmlAttachmentCards.CAPABILITY))).run(emptyList())
        assertTrue(store.attachmentCardSupport())
        // Also when the server builds no editions; and a server that stops advertising it is followed.
        HtmlEditionSync(store, Remote(setOf("agent_conversations_v1"))).run(emptyList())
        assertFalse(store.attachmentCardSupport())
        HtmlEditionSync(store, Remote(setOf(HtmlAttachmentCards.CAPABILITY))).run(emptyList())
        assertTrue(store.attachmentCardSupport())
    }

    @Test fun readyEditionsAreDownloadedPendingOnesAskedAgainFailedOnesNot() {
        val remote = Remote()
        publish(remote, a)
        remote.editions[b] = JsonObject().apply { addProperty("html_status", "pending") }
        remote.editions[c] = JsonObject().apply { addProperty("html_status", "failed"); addProperty("html_error", "tex4ht: font crash") }
        val store = HtmlEditionStore(temp.newFolder())
        var now = 1_000L
        val sync = HtmlEditionSync(store, remote) { now }
        val documents = listOf(
            HtmlEditionSync.Document("a.pdf", a, true),
            HtmlEditionSync.Document("b.pdf", b, true),
            HtmlEditionSync.Document("c.pdf", c, true),
            HtmlEditionSync.Document("imported.pdf", "d".repeat(64), false),
        )
        val first = sync.run(documents)
        assertEquals(3, first.checked)
        assertEquals(1, first.installed)
        assertEquals(1, first.pending)
        assertEquals(1, first.failed)
        assertTrue(store.availability(a) is HtmlAvailability.Ready)
        assertEquals(HtmlAvailability.Building, store.availability(b))
        assertEquals(HtmlAvailability.Unavailable("tex4ht: font crash"), store.availability(c))
        // Next Sync: only the pending one is asked; it is ready now.
        remote.asked.clear()
        publish(remote, b)
        now += 60_000
        val second = sync.run(documents)
        assertEquals(listOf(b), remote.asked)
        assertEquals(1, second.installed)
        assertTrue(store.availability(b) is HtmlAvailability.Ready)
    }

    @Test fun unknownVersionsAreAskedAgainOnlyAfterAWhileAndRetentionFollowsTheLibrary() {
        val remote = Remote()
        val store = HtmlEditionStore(temp.newFolder())
        var now = 1_000L
        val sync = HtmlEditionSync(store, remote) { now }
        sync.run(listOf(HtmlEditionSync.Document("a.pdf", a, true)))
        assertEquals(listOf(a), remote.asked)
        sync.run(listOf(HtmlEditionSync.Document("a.pdf", a, true)))
        assertEquals(listOf(a), remote.asked)
        now += HtmlEditionSync.NONE_RECHECK_MS + 1
        publish(remote, a)
        sync.run(listOf(HtmlEditionSync.Document("a.pdf", a, true)))
        assertTrue(store.availability(a) is HtmlAvailability.Ready)
        // The document got a new PDF version: the old edition goes, the new one is asked for.
        publish(remote, b)
        sync.run(listOf(HtmlEditionSync.Document("a.pdf", b, true)))
        assertTrue(store.availability(a) is HtmlAvailability.Unavailable)
        assertTrue(store.availability(b) is HtmlAvailability.Ready)
        assertEquals(2, File(store.root, "objects").listFiles()!!.size)
    }

    @Test fun aDownloadFailureIsReportedAndRetried() {
        val remote = Remote()
        val edition = publish(remote, a)
        val html = remote.objects.keys.first { it != edition }
        val saved = remote.objects.remove(html)!!
        val store = HtmlEditionStore(temp.newFolder())
        val sync = HtmlEditionSync(store, remote)
        val failed = sync.run(listOf(HtmlEditionSync.Document("a.pdf", a, true)))
        assertEquals(1, failed.errors.size)
        assertEquals(HtmlAvailability.NotDownloaded, store.availability(a))
        remote.objects[html] = saved
        assertEquals(1, sync.run(listOf(HtmlEditionSync.Document("a.pdf", a, true))).installed)
    }

    @Test fun listedFinalStatesNeedNoRequestPendingAndUnknownOnesAreAsked() {
        val remote = Remote()
        val edition = publish(remote, a)
        remote.editions[b] = JsonObject().apply { addProperty("html_status", "pending") }
        val store = HtmlEditionStore(temp.newFolder())
        val sync = HtmlEditionSync(store, remote)
        val n = "e".repeat(64)
        val documents = listOf(
            HtmlEditionSync.Document("a.pdf", a, true, HtmlEditionState(HtmlEditionState.Status.READY, edition)),
            HtmlEditionSync.Document("b.pdf", b, true, HtmlEditionState(HtmlEditionState.Status.PENDING)),
            HtmlEditionSync.Document("c.pdf", c, true, HtmlEditionState(HtmlEditionState.Status.FAILED, error = "tex4ht: x")),
            HtmlEditionSync.Document("n.pdf", n, true, HtmlEditionState.NONE),
        )
        val first = sync.run(documents)
        // Ready and failed are final for a PDF version; the listing can be older than "pending" or "none".
        assertEquals(listOf(b, n), remote.asked)
        assertEquals(1, first.installed)
        assertEquals(1, first.pending)
        assertEquals(1, first.pollable)
        assertEquals(1, first.failed)
        assertTrue(store.availability(a) is HtmlAvailability.Ready)
        assertEquals(HtmlAvailability.Building, store.availability(b))
        assertEquals(HtmlAvailability.Unavailable("tex4ht: x"), store.availability(c))
        assertEquals(HtmlAvailability.Unavailable("the server has no HTML edition of this version"), store.availability(n))
        // Same listing again: only the pending version is asked; nothing is downloaded again.
        remote.asked.clear()
        val downloadsBefore = remote.downloads.size
        val second = sync.run(documents)
        assertEquals(listOf(b), remote.asked)
        assertEquals(0, second.installed)
        assertEquals(downloadsBefore, remote.downloads.size)
        // The pending one became ready in the listing: its objects only, no request.
        remote.asked.clear()
        val bEdition = publish(remote, b)
        val third = sync.run(documents.map {
            if (it.pdfSha256 == b) it.copy(listed = HtmlEditionState(HtmlEditionState.Status.READY, bEdition)) else it
        })
        assertEquals(1, third.installed)
        assertEquals(0, third.pollable)
        assertEquals(emptyList<String>(), remote.asked)
        assertTrue(store.availability(b) is HtmlAvailability.Ready)
    }

    @Test fun aStaleListingNeitherHidesAFinishedEditionNorUndoesOne() {
        // The listing was stored while b was being built; the server finished it since (no new
        // Library generation for that).
        val remote = Remote()
        val bEdition = publish(remote, b)
        val store = HtmlEditionStore(temp.newFolder())
        val sync = HtmlEditionSync(store, remote)
        val stale = listOf(HtmlEditionSync.Document("b.pdf", b, true, HtmlEditionState(HtmlEditionState.Status.PENDING)))
        val first = sync.run(stale)
        assertEquals(listOf(b), remote.asked)
        assertEquals(1, first.installed)
        assertEquals(0, first.pollable)
        assertEquals(bEdition, (store.availability(b) as HtmlAvailability.Ready).edition.sha256)
        // The next Sync still has the same listing: the ready edition stays, no request.
        remote.asked.clear()
        sync.run(stale)
        assertEquals(emptyList<String>(), remote.asked)
        assertTrue(store.availability(b) is HtmlAvailability.Ready)
        // A failed one known here is not turned back into "building" either.
        remote.editions[c] = JsonObject().apply { addProperty("html_status", "failed"); addProperty("html_error", "tex4ht: z") }
        sync.run(listOf(HtmlEditionSync.Document("c.pdf", c, true)))
        remote.asked.clear()
        sync.run(listOf(HtmlEditionSync.Document("c.pdf", c, true, HtmlEditionState(HtmlEditionState.Status.PENDING))))
        assertEquals(emptyList<String>(), remote.asked)
        assertEquals(HtmlAvailability.Unavailable("tex4ht: z"), store.availability(c))
    }

    @Test fun aListedEditionThatCannotBeDownloadedAsksTheServerOnce() {
        // Listed ready, but rebuilt since: the old edition's objects are gone, a new one is ready.
        val remote = Remote()
        val old = publish(remote, a)
        remote.objects.remove(old)
        val rebuilt = "<p>rebuilt</p>".toByteArray()
        remote.objects[sha(rebuilt)] = rebuilt
        val manifest = """{"kind":"thoughts_html_edition","schema":1,"entry":"index.html","files":{"index.html":"${sha(rebuilt)}"}}""".toByteArray()
        remote.objects[sha(manifest)] = manifest
        remote.editions[a] = JsonObject().apply {
            addProperty("html_status", "ready"); addProperty("html_edition_sha256", sha(manifest))
        }
        val store = HtmlEditionStore(temp.newFolder())
        val summary = HtmlEditionSync(store, remote)
            .run(listOf(HtmlEditionSync.Document("a.pdf", a, true, HtmlEditionState(HtmlEditionState.Status.READY, old))))
        assertEquals(listOf(a), remote.asked)
        assertEquals(1, summary.installed)
        assertEquals(emptyList<String>(), summary.errors)
        assertEquals(sha(manifest), (store.availability(a) as HtmlAvailability.Ready).edition.sha256)
    }

    @Test fun followUpRunsAskOnlyForEditionsThatWouldBeDownloaded() {
        val remote = Remote()
        remote.editions[a] = JsonObject().apply { addProperty("html_status", "pending") }
        remote.editions[b] = JsonObject().apply { addProperty("html_status", "running") }
        val store = HtmlEditionStore(temp.newFolder())
        val sync = HtmlEditionSync(store, remote)
        val documents = listOf(
            HtmlEditionSync.Document("a.pdf", a, true, HtmlEditionState(HtmlEditionState.Status.PENDING)),
            HtmlEditionSync.Document("b.pdf", b, true, HtmlEditionState(HtmlEditionState.Status.PENDING)),
        )
        // After a Sync on mobile data: both are asked once; only a (read as HTML) is worth polling.
        val afterSync = meteredRun(sync, documents, readAsHtml = setOf("a.pdf"))
        assertEquals(listOf(a, b), remote.asked)
        assertEquals(2, afterSync.pending)
        assertEquals(1, afterSync.pollable)
        // A follow-up run on mobile data asks only for a.
        remote.asked.clear()
        val aEdition = publish(remote, a)
        val followUp = sync.run(documents, download = { it.filename == "a.pdf" }, followUp = true)
        assertEquals(listOf(a), remote.asked)
        assertEquals(1, followUp.installed)
        assertEquals(0, followUp.pollable)
        assertEquals(aEdition, (store.availability(a) as HtmlAvailability.Ready).edition.sha256)
        assertEquals(HtmlAvailability.Building, store.availability(b))
        // Unreachable server while polling: the pending one stays worth asking again.
        val offline = object : HtmlEditionRemote by remote {
            override fun edition(pdfSha256: String): JsonObject? = throw IOException("Unable to resolve host")
        }
        val retry = HtmlEditionSync(store, offline).run(documents, followUp = true)
        assertEquals(1, retry.errors.size)
        assertEquals(1, retry.pollable)
    }

    @Test fun followUpsBackOffAndStop() {
        assertEquals(null, HtmlEditionSyncWorker.followUpDelaySeconds(0))
        assertEquals(20L, HtmlEditionSyncWorker.followUpDelaySeconds(1))
        assertEquals(40L, HtmlEditionSyncWorker.followUpDelaySeconds(2))
        assertEquals(600L, HtmlEditionSyncWorker.followUpDelaySeconds(HtmlEditionSyncWorker.MAX_FOLLOW_UPS))
        assertEquals(null, HtmlEditionSyncWorker.followUpDelaySeconds(HtmlEditionSyncWorker.MAX_FOLLOW_UPS + 1))
        val total = (1..HtmlEditionSyncWorker.MAX_FOLLOW_UPS).sumOf { HtmlEditionSyncWorker.followUpDelaySeconds(it)!! }
        assertTrue(total in 600L..3600L)
    }

    @Test fun listedStatesComeFromTheStoredGenerationOnlyWhenItCarriesTheFields() {
        val file = temp.newFile("generation.json")
        file.writeText("""{"schema_version":1,"publications":[{"filename":"a.pdf","sha256":"$a","html_status":"pending"}]}""")
        assertEquals(null, HtmlEditionSyncWorker.listedEditionStates(file))
        file.writeText("""{"schema_version":1,"html_edition_fields":1,"publications":[
            {"filename":"a.pdf","sha256":"$a","html_status":"ready","html_edition_sha256":"$b"},
            {"filename":"c.pdf","sha256":"$c"}]}""")
        val states = HtmlEditionSyncWorker.listedEditionStates(file)!!
        assertEquals(HtmlEditionState(HtmlEditionState.Status.READY, b), states["a.pdf" to a])
        assertEquals(HtmlEditionState.NONE, states["c.pdf" to c])
    }

    // ---- Metered networks (2 GB/month plan): which editions are downloaded after a Sync --------

    @Test fun downloadAfterSyncOnlyOnUnmeteredOrForDocumentsReadAsHtml() {
        assertFalse(HtmlEditionDownloadPolicy.downloadAfterSync(unmetered = false, openedInHtml = false))
        assertTrue(HtmlEditionDownloadPolicy.downloadAfterSync(unmetered = false, openedInHtml = true))
        assertTrue(HtmlEditionDownloadPolicy.downloadAfterSync(unmetered = true, openedInHtml = false))
        assertTrue(HtmlEditionDownloadPolicy.downloadAfterSync(unmetered = true, openedInHtml = true))
    }

    @Test fun documentsReadAsHtmlComeFromTheReadersRememberedMode() {
        val keys = HtmlEditionDownloadPolicy.htmlDocumentKeys(mapOf(
            HtmlEditionDownloadPolicy.modeKey("doc-1") to "html",
            HtmlEditionDownloadPolicy.modeKey("notes.pdf") to "html",
            HtmlEditionDownloadPolicy.modeKey("doc-2") to "pdf",
            "text_size" to 120,
        ))
        assertEquals(setOf("doc-1", "notes.pdf"), keys)
        assertTrue(HtmlEditionDownloadPolicy.openedInHtml("renamed.pdf", "doc-1", keys))
        assertTrue(HtmlEditionDownloadPolicy.openedInHtml("notes.pdf", null, keys))
        assertFalse(HtmlEditionDownloadPolicy.openedInHtml("other.pdf", "doc-2", keys))
        assertFalse(HtmlEditionDownloadPolicy.openedInHtml("other.pdf", null, keys))
    }

    private fun meteredRun(sync: HtmlEditionSync, documents: List<HtmlEditionSync.Document>, readAsHtml: Set<String>) =
        sync.run(documents, download = { document ->
            HtmlEditionDownloadPolicy.downloadAfterSync(unmetered = false, openedInHtml = document.filename in readAsHtml)
        })

    @Test fun meteredSyncDownloadsOnlyEditionsOfDocumentsReadAsHtml() {
        val remote = Remote()
        val aEdition = publish(remote, a)
        val bEdition = publish(remote, b)
        val store = HtmlEditionStore(temp.newFolder())
        val sync = HtmlEditionSync(store, remote)
        val documents = listOf(
            HtmlEditionSync.Document("a.pdf", a, true, HtmlEditionState(HtmlEditionState.Status.READY, aEdition)),
            HtmlEditionSync.Document("b.pdf", b, true, HtmlEditionState(HtmlEditionState.Status.READY, bEdition)),
        )
        val summary = meteredRun(sync, documents, readAsHtml = setOf("a.pdf"))
        assertEquals(1, summary.installed)
        assertEquals(1, summary.deferred)
        assertTrue(store.availability(a) is HtmlAvailability.Ready)
        // Not downloaded: the state stays "ready on the server", and nothing of b was fetched.
        assertEquals(HtmlAvailability.NotDownloaded, store.availability(b))
        assertFalse(remote.downloads.contains(bEdition))
        // Still metered at the next Sync: no download, no request.
        remote.downloads.clear()
        val again = meteredRun(sync, documents, readAsHtml = setOf("a.pdf"))
        assertEquals(1, again.deferred)
        assertEquals(emptyList<String>(), remote.downloads)
        // On Wi-Fi it is downloaded.
        val unmetered = sync.run(documents)
        assertEquals(1, unmetered.installed)
        assertEquals(0, unmetered.deferred)
        assertTrue(store.availability(b) is HtmlAvailability.Ready)
    }

    @Test fun meteredSyncWithoutListedStatesAsksOnceAndKeepsBuildingAndFailedStatesRight() {
        val remote = Remote()
        publish(remote, a)
        remote.editions[b] = JsonObject().apply { addProperty("html_status", "pending") }
        remote.editions[c] = JsonObject().apply { addProperty("html_status", "failed"); addProperty("html_error", "tex4ht: x") }
        val store = HtmlEditionStore(temp.newFolder())
        val sync = HtmlEditionSync(store, remote)
        val documents = listOf(
            HtmlEditionSync.Document("a.pdf", a, true),
            HtmlEditionSync.Document("b.pdf", b, true),
            HtmlEditionSync.Document("c.pdf", c, true),
        )
        val first = meteredRun(sync, documents, readAsHtml = emptySet())
        assertEquals(0, first.installed)
        assertEquals(1, first.deferred)
        assertEquals(1, first.pending)
        assertEquals(1, first.failed)
        assertEquals(HtmlAvailability.NotDownloaded, store.availability(a))
        assertEquals(HtmlAvailability.Building, store.availability(b))
        assertEquals(HtmlAvailability.Unavailable("tex4ht: x"), store.availability(c))
        assertEquals(emptyList<String>(), remote.downloads)
        // The ready one is known: the next metered Sync asks only for the pending one.
        remote.asked.clear()
        meteredRun(sync, documents, readAsHtml = emptySet())
        assertEquals(listOf(b), remote.asked)
        // Once a is read as HTML, a metered Sync downloads it.
        val opened = meteredRun(sync, documents, readAsHtml = setOf("a.pdf"))
        assertEquals(1, opened.installed)
        assertTrue(store.availability(a) is HtmlAvailability.Ready)
    }

    @Test fun switchingToHtmlDownloadsTheEditionRightThen() {
        val remote = Remote()
        val edition = publish(remote, a)
        val store = HtmlEditionStore(temp.newFolder())
        val sync = HtmlEditionSync(store, remote)
        meteredRun(sync, listOf(HtmlEditionSync.Document("a.pdf", a, true, HtmlEditionState(HtmlEditionState.Status.READY, edition))),
            readAsHtml = emptySet())
        assertEquals(HtmlAvailability.NotDownloaded, store.availability(a))
        val shown = sync.downloadNow("a.pdf", a)
        assertTrue(shown is HtmlAvailability.Ready)
        assertEquals(edition, (shown as HtmlAvailability.Ready).edition.sha256)
        assertEquals(listOf(a), remote.asked)
        assertTrue(store.availability(a) is HtmlAvailability.Ready)
    }

    @Test fun switchingToHtmlReportsWhyTheDownloadFailed() {
        val remote = Remote()
        val edition = publish(remote, a)
        val store = HtmlEditionStore(temp.newFolder())
        store.recordState(a, "a.pdf", HtmlEditionState(HtmlEditionState.Status.READY, edition))
        // An object is missing on the server: the reason is reported, nothing partial is kept.
        val html = remote.objects.keys.first { it != edition }
        remote.objects.remove(html)
        assertEquals(HtmlAvailability.Unavailable("404"), sync(store, remote).downloadNow("a.pdf", a))
        assertEquals(HtmlAvailability.NotDownloaded, store.availability(a))
        // The server cannot be reached and the edition is known to be ready: still tried.
        val offline = object : HtmlEditionRemote by remote {
            override fun edition(pdfSha256: String): JsonObject? = throw IOException("Unable to resolve host")
        }
        assertEquals(HtmlAvailability.Unavailable("404"), sync(store, offline).downloadNow("a.pdf", a))
        // Rebuilt in the meantime: the server's current state is what the reader is told.
        remote.editions[a] = JsonObject().apply { addProperty("html_status", "pending") }
        assertEquals(HtmlAvailability.Building, sync(store, remote).downloadNow("a.pdf", a))
        remote.editions[a] = JsonObject().apply { addProperty("html_status", "failed"); addProperty("html_error", "tex4ht: y") }
        assertEquals(HtmlAvailability.Unavailable("tex4ht: y"), sync(store, remote).downloadNow("a.pdf", a))
        // Offline with no ready edition known: the connection error is the reason.
        assertEquals(HtmlAvailability.Unavailable("Unable to resolve host"), sync(store, offline).downloadNow("a.pdf", a))
    }

    private fun sync(store: HtmlEditionStore, remote: HtmlEditionRemote) = HtmlEditionSync(store, remote)
}

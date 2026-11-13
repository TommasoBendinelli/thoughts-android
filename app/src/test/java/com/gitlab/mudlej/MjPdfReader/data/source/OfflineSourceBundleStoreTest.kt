// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfflineSourceBundleStoreTest {
    private lateinit var root: File
    private lateinit var staging: File
    private lateinit var working: File
    private lateinit var privateDirectory: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("offline-source-bundle").toFile()
        staging = File(root, "mac-to-phone/docs-source-bundles")
        working = File(root, "Documents/MJ PDF Sources")
        privateDirectory = File(root, "private")
    }

    @After
    fun tearDown() {
        SourceProjectionMemoryCache.clear()
        root.deleteRecursively()
    }

    private fun removedRow(path: String, line: Int, revision: Long = 5) = JsonObject().apply {
        addProperty("schema_version", 1); addProperty("proposal_id", "agent-1"); addProperty("status", "removed_outside")
        addProperty("kind", "removed_outside"); addProperty("source_file", path); addProperty("deleted", false)
        addProperty("revision", revision); addProperty("wrapper_sha256", "252bfd0570e87e771344faa0a2033f6d46454250eaf6f11d2ea7e8644f76dd8e")
        add("snapshot", JsonObject().apply {
            addProperty("proposal_id", "agent-1"); addProperty("source_file", path); addProperty("comment", "Clarify.\n")
            addProperty("before", "Old line.\n"); addProperty("after", "New line.\n")
            addProperty("created_at", "2026-10-10T12:00:00.000Z"); addProperty("conversation_id", "0b6c5b7e-1d2a-4c3b-9f8e-112233445566")
        })
        add("removal", JsonObject().apply {
            addProperty("source_line", line); addProperty("looks_like", "accept"); addProperty("removed_at", "2026-10-11T09:00:00.000Z")
        })
    }

    @Test
    fun removedProposalsAreListedUntilRestoredInThePhonesCopy() {
        val path = "docs-paper/tex/source/main.tex"
        publishBundle(path, "Intro.\nNew line.\nOutro.\n", "/workspace/$path", gzip(syncTex("/workspace/$path")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val row = removedRow(path, 2)
        val listed = store.withCurrentProposals(OfflineOverviewSourceSnapshot(), listOf(row), null).removedProposals.single()
        assertEquals("agent-1", listed.id)
        assertEquals(2, listed.line)
        assertEquals("accept", listed.looksLike)
        assertEquals("New line.\n", listed.after)

        var saved: String? = null
        val restorer = RemovedProposalRestorer(store) { snapshot, _, _, contents ->
            saved = contents; assertTrue(store.writeWorkingContents(snapshot.sourceFile, contents))
        }
        val outcome = restorer.restore(listed)
        assertTrue(outcome.restored)
        assertEquals(2, outcome.line)
        assertEquals("Intro.\n\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]" +
            "{agent-1}{Clarify.\n}{Old line.\n}{New line.\n}Outro.\n", saved)
        // The wrapper is back in the phone's copy: the row leaves Issues before the next Sync.
        assertTrue(store.withCurrentProposals(OfflineOverviewSourceSnapshot(), listOf(row), null).removedProposals.isEmpty())
        // Restoring again changes nothing.
        assertTrue(restorer.restore(listed).alreadyPresent)
    }

    @Test
    fun aRefusedRestoreWritesNothing() {
        val path = "docs-paper/tex/source/main.tex"
        publishBundle(path, "Intro.\nSomething else.\nOutro.\n", "/workspace/$path", gzip(syncTex("/workspace/$path")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val restorer = RemovedProposalRestorer(store) { _, _, _, _ -> error("A refused Restore must not save") }
        val changed = restorer.restore(RemovedOutsideProposal.from(removedRow(path, 2))!!)
        assertEquals("text_changed", changed.refusal)
        assertEquals(2, changed.line)
        assertEquals("source_missing", restorer.restore(RemovedOutsideProposal.from(removedRow("docs-paper/tex/source/gone.tex", 2))!!).refusal)
    }

    @Test
    fun currentPendingRowsKeepTheArtifactsProposalObject() {
        val proposal = OfflineAgentProposal("e".repeat(64), "f".repeat(64), "agent-1", "edit", "Why", "docs-paper/tex/source/main.tex",
            "1".repeat(64), 3, "New", "Old", "New", null, false, listOf("sample.pdf"), emptyList())
        fun pending(id: String) = JsonObject().apply {
            addProperty("proposal_id", id); addProperty("status", "pending"); addProperty("source_file", proposal.sourceFile)
            addProperty("source_sha256", "2".repeat(64))
            add("snapshot", JsonObject().apply { addProperty("comment", "Why"); addProperty("before", "Old"); addProperty("after", "Newer") })
        }
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        val result = store.withCurrentProposals(OfflineOverviewSourceSnapshot(bundleId = "9".repeat(64), proposals = listOf(proposal)),
            listOf(pending("agent-1"), pending("agent-2")), null).proposals.associateBy { it.id }
        // Not the source SHA-256: Fedora resolves a proposal by the object its artifact declared.
        assertEquals("f".repeat(64), result.getValue("agent-1").objectSha256)
        assertEquals("e".repeat(64), result.getValue("agent-1").bundleId)
        assertEquals("2".repeat(64), result.getValue("agent-1").sourceSha256)
        assertEquals("", result.getValue("agent-2").objectSha256)
        // A removed row is not a proposal any more.
        val removed = store.withCurrentProposals(OfflineOverviewSourceSnapshot(proposals = listOf(proposal)),
            listOf(removedRow(proposal.sourceFile, 3)), null)
        assertTrue(removed.proposals.isEmpty())
        assertEquals(listOf("sample.pdf"), removed.removedProposals.single().pdfFilenames)
    }

    @Test
    fun dismissRetiresTheRowAtItsRevision() {
        val row = removedRow("docs-paper/tex/source/main.tex", 2).apply { addProperty(RemovedProposalDismissal.PROJECTION_REVISION, 7) }
        val dismissal = RemovedProposalDismissal.of(row, "2026-10-12T10:00:00.000Z")
        assertEquals("agent-1", dismissal.proposalId)
        assertEquals(7L, dismissal.baseRevision)
        val expected = removedRow("docs-paper/tex/source/main.tex", 2).apply {
            addProperty("status", "dismissed"); addProperty("kind", "dismissed"); addProperty("deleted", true)
            addProperty("revision", 8); addProperty("updated_at", "2026-10-12T10:00:00.000Z")
        }
        assertEquals(expected, dismissal.payload)
        val pending = row.deepCopy().apply { addProperty("status", "pending") }
        assertTrue(runCatching { RemovedProposalDismissal.of(pending, "now") }.isFailure)
    }

    @Test
    fun commentsLinkOfflineAndSourceEditsAndDeletionProjectLocally() {
        val path = "docs-paper/tex/source/main.tex"
        val text = "The selected words are here.\n"
        val pdfRoot = File(root, "pdfs").also { it.mkdirs() }
        val pdf = File(pdfRoot, "sample.pdf").also { it.writeText("fixture PDF identity") }
        val hash = sha256(pdf.readBytes())
        publishBundle(path, text, "/workspace/$path", gzip(syncTex("/workspace/$path")), pdfSha256 = hash)
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        var saves = 0
        val local = LocalSourceComments(store, { snapshot, _, _, contents ->
            assertTrue(store.writeWorkingContents(snapshot.sourceFile, contents)); saves++
        }, pdfRoot, { com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability.SOURCE_LINKED }, { _, _ -> 600f })
        val raw = JsonObject().apply {
            addProperty("comment_id", "offline-comment"); addProperty("contents", "A question.")
            addProperty("subtype", "Highlight"); addProperty("selected_text", "selected words")
            add("scope", JsonObject().apply { addProperty("state", "pdf_only") })
            add("origin", JsonObject().apply {
                addProperty("collection", "sample"); addProperty("pdf_sha256", hash)
                add("raw_pdf_geometry", JsonObject().apply {
                    addProperty("page", 1); add("rect", JsonArray().apply { add(100); add(490); add(120); add(510) })
                })
            })
        }
        val linked = local.apply(raw, mutate = true)
        assertEquals("source_linked", linked.getAsJsonObject("scope").get("state").asString)
        assertEquals(1, saves)
        assertTrue(File(working, path).readText().contains("\\CommentHighlight{offline-comment}"))
        assertEquals(linked, local.apply(linked, mutate = false))
        val changed = File(working, path).readText().replace("A question.", "Edited in source.").replace("{selected words}", "{updated passage}")
        store.writeWorkingContents(path, changed)
        assertEquals("Edited in source.", local.apply(linked).get("contents").asString)
        assertEquals("updated passage", local.apply(linked).get("selected_text").asString)
        val edited = linked.deepCopy().apply { addProperty("contents", "Edited in UI.") }
        local.apply(edited, linked, mutate = true)
        assertTrue(File(working, path).readText().contains("Edited in UI."))
        // An unreadable file preserves the link; a confirmed removal keeps the comment as "Anchor removed".
        val file = File(working, path); val marked = file.readText(); file.delete(); file.mkdir()
        val unavailable = local.apply(linked)
        assertEquals(linked, unavailable)
        file.delete()
        val fileGone = local.apply(linked)
        assertTrue(fileGone.get("deleted")?.asBoolean != true)
        assertTrue(CommentAnchorState.isAnchorRemoved(fileGone.getAsJsonObject("scope")))
        file.writeText(marked.replace("\\CommentHighlight", "% \\CommentHighlight"))
        // A commented-out identity is not a confirmed removal.
        assertEquals("source_linked", local.apply(linked).getAsJsonObject("scope").get("state").asString)
        file.writeText(text)
        val removed = local.apply(linked)
        assertTrue(removed.get("deleted")?.asBoolean != true)
        val linkedScope = linked.getAsJsonObject("scope")
        assertEquals(JsonObject().apply {
            addProperty("state", "needs_attention"); addProperty("reason", "anchor_removed")
            addProperty("anchor_id", "offline-comment"); addProperty("source_file", path)
            add("source_line", linkedScope.get("source_line"))
            addProperty("anchor_kind", "CommentHighlight"); addProperty("anchor_mirror", "A question.")
        }, removed.getAsJsonObject("scope"))
        // Reads, edits, a delete and its Undo keep the state and never touch the source or relink.
        assertEquals(removed, local.apply(removed))
        val savesBefore = saves
        val editedRemoved = local.apply(removed.deepCopy().apply { addProperty("contents", "Edited while removed.") }, removed, mutate = true)
        assertEquals(removed.getAsJsonObject("scope"), editedRemoved.getAsJsonObject("scope"))
        assertEquals("Edited while removed.", editedRemoved.get("contents").asString)
        val tombstone = local.apply(editedRemoved.deepCopy().apply { addProperty("deleted", true) }, editedRemoved, mutate = true)
        assertTrue(tombstone.get("deleted").asBoolean)
        assertEquals(removed.getAsJsonObject("scope"), tombstone.getAsJsonObject("scope"))
        val undone = local.apply(editedRemoved.deepCopy(), tombstone, mutate = true)
        assertTrue(undone.get("deleted")?.asBoolean != true)
        assertTrue(CommentAnchorState.isAnchorRemoved(undone.getAsJsonObject("scope")))
        assertEquals(savesBefore, saves)
        assertEquals(text, file.readText())
        // Restore re-wraps the passage at the last known line with the current text.
        val restore = local.restoreAnchor(editedRemoved)
        assertTrue(restore.success)
        assertEquals(LocalSourceComments.RestoreResult.Placement.EXACT, restore.placement)
        assertFalse(restore.pointAnchorFallback)
        assertEquals(savesBefore + 1, saves)
        assertTrue(file.readText().contains("\\CommentHighlight{offline-comment}{Edited while removed.}{selected words}"))
        // The marker's reappearance relinks the comment and drops the anchor_* keys.
        val relinked = local.apply(editedRemoved)
        assertEquals(JsonObject().apply {
            addProperty("state", "source_linked"); addProperty("anchor_id", "offline-comment")
            addProperty("source_file", path); addProperty("source_line", 1); addProperty("reason", "unique_source_marker")
        }, relinked.getAsJsonObject("scope"))
        val again = local.restoreAnchor(editedRemoved)
        assertEquals(LocalSourceComments.RestoreResult.Placement.ALREADY_PRESENT, again.placement)
        assertEquals(savesBefore + 1, saves)
    }

    private fun anchorRemovedComment(id: String, sourceFile: String, line: Int, subtype: String = "Text",
                                     selected: String = "", collection: String = "sample") = JsonObject().apply {
        addProperty("schema_version", 1); addProperty("comment_id", id); addProperty("thread_root_id", id)
        addProperty("revision", 3); addProperty("deleted", false); addProperty("contents", "Restored text.")
        addProperty("subtype", subtype); addProperty("selected_text", selected)
        add("scope", JsonObject().apply {
            addProperty("state", "needs_attention"); addProperty("reason", "anchor_removed")
            addProperty("anchor_id", id); addProperty("source_file", sourceFile); addProperty("source_line", line)
            addProperty("anchor_mirror", "Last marker text.")
        })
        add("origin", JsonObject().apply { addProperty("collection", collection); addProperty("pdf_sha256", "a".repeat(64)) })
    }

    @Test
    fun restoreAnchorUsesNearestSafeLineEndOfFileAndPrimarySource() {
        val path = "docs-paper/tex/source/main.tex"
        val text = "\\documentclass{article}\n\\begin{document}\nFirst sentence.\n\\section{Two}\n\\end{document}\n"
        publishBundle(path, text, "/workspace/$path", gzip(syncTex("/workspace/$path")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        var saves = 0
        val local = LocalSourceComments(store, { snapshot, filename, hash, contents ->
            assertEquals("sample.pdf", filename); assertEquals("a".repeat(64), hash)
            // A restore is an ordinary offline edit: it carries the bundle's merge base for the 3-way merge.
            assertNotNull(snapshot.bundleId); assertEquals(text, snapshot.baseContents)
            assertTrue(store.writeWorkingContents(snapshot.sourceFile, contents)); saves++
        }, File(root, "pdfs"), { com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability.SOURCE_LINKED }, { _, _ -> 600f })
        assertEquals(LocalSourceComments.RestoreResult.Failure.SOURCES_UNAVAILABLE, local.restoreAnchor(anchorRemovedComment("c-0", path, 3)).failure)
        assertNotNull(store.importLatest())
        val file = File(working, path)
        // Not an "Anchor removed" comment: nothing to restore.
        assertEquals(LocalSourceComments.RestoreResult.Failure.NOT_ANCHOR_REMOVED, local.restoreAnchor(anchorRemovedComment("c-0", path, 3).apply {
            getAsJsonObject("scope").addProperty("reason", "source_marker_unavailable")
        }).failure)

        // \section and \end{document} cannot hold an anchor: the nearest safe line does, as a point anchor.
        val nearest = local.restoreAnchor(anchorRemovedComment("c-1", path, 4, "Highlight", "absent words"))
        assertTrue(nearest.success)
        assertEquals(LocalSourceComments.RestoreResult.Placement.NEAREST_LINE, nearest.placement)
        assertEquals(3, nearest.line)
        assertTrue(nearest.pointAnchorFallback)
        assertTrue(file.readText().contains("\\CommentAnchor{c-1}{Restored text.}%\nFirst sentence."))

        // A line past the end clamps to the file and searches back.
        val clamped = local.restoreAnchor(anchorRemovedComment("c-2", path, 99))
        assertEquals(LocalSourceComments.RestoreResult.Placement.NEAREST_LINE, clamped.placement)
        assertEquals(4, clamped.line)
        assertTrue(clamped.requestedLine > clamped.lineCount)

        // The comment's file is gone: the PDF's primary source receives the anchor.
        val fallback = local.restoreAnchor(anchorRemovedComment("c-3", "docs-paper/tex/source/gone.tex", 3))
        assertTrue(fallback.success)
        assertTrue(fallback.fallbackFile)
        assertEquals(path, fallback.sourceFile)
        assertEquals("docs-paper/tex/source/gone.tex", fallback.requestedFile)

        // Empty contents fall back to the last marker's text.
        store.writeWorkingContents(path, "\\documentclass{article}\n\\begin{document}\n\\section{A}\n\\end{document}\n")
        val end = local.restoreAnchor(anchorRemovedComment("c-4", path, 2).apply { addProperty("contents", "") })
        assertEquals(LocalSourceComments.RestoreResult.Placement.END_OF_FILE, end.placement)
        assertEquals(4, end.line)
        assertTrue(file.readText().endsWith("\\CommentAnchor{c-4}{Last marker text.}%\n\\end{document}\n"))

        // A raw identity that does not parse is left to the user, and nothing is written.
        store.writeWorkingContents(path, file.readText() + "% \\CommentAnchor{c-5}{Old}\n")
        val before = saves
        val malformed = local.restoreAnchor(anchorRemovedComment("c-5", path, 3))
        assertEquals(LocalSourceComments.RestoreResult.Failure.MALFORMED, malformed.failure)
        assertEquals(6, malformed.line)
        assertEquals(before, saves)
        // A comment from another project has no source to restore into.
        assertEquals(LocalSourceComments.RestoreResult.Failure.SOURCE_FILE_MISSING,
            local.restoreAnchor(anchorRemovedComment("c-6", "docs-other/tex/source/main.tex", 3, collection = "other")).failure)
        assertEquals(before, saves)
    }

    @Test
    fun anchorRemovedRootKeepsItsRepliesAndUnknownStatesAreNotDropped() {
        fun payload(id: String, root: String, scope: JsonObject, revision: Long = 2) = JsonObject().apply {
            addProperty("schema_version", 1); addProperty("comment_id", id); addProperty("thread_root_id", root)
            if (id != root) addProperty("parent_id", root)
            addProperty("revision", revision); addProperty("deleted", false); addProperty("contents", id)
            add("scope", scope)
            add("origin", JsonObject().apply { addProperty("collection", "sample"); addProperty("page_index", 0) })
        }
        val removed = JsonObject().apply {
            addProperty("state", "needs_attention"); addProperty("reason", "anchor_removed")
            addProperty("anchor_id", "root"); addProperty("source_file", "docs-paper/main.tex"); addProperty("source_line", 4)
        }
        val snapshot = OfflineOverviewSourceSnapshot().withLogicalPayloads(listOf(
            payload("root", "root", removed),
            payload("reply", "root", JsonObject().apply { addProperty("state", "pdf_only") }),
            payload("local", "local", JsonObject().apply { addProperty("state", "source_unavailable") }),
            payload("future", "future", JsonObject().apply { addProperty("state", "some_future_state") }),
        ))
        val byId = snapshot.logicalComments.associateBy { it.id }
        assertEquals(setOf("root", "reply", "local", "future"), byId.keys)
        assertTrue(byId.getValue("root").anchorRemoved)
        assertFalse(byId.getValue("reply").anchorRemoved)
        assertFalse(byId.getValue("reply").deleted)
        assertEquals("docs-paper/main.tex", byId.getValue("reply").sourceFile)
        assertEquals(4, byId.getValue("root").sourceLine)
        assertEquals("needs_attention", byId.getValue("local").scopeState)
        assertEquals("needs_attention", byId.getValue("future").scopeState)
        assertTrue(byId.values.all { com.google.gson.JsonParser.parseString(it.payloadJson).asJsonObject.has("scope") })
    }

    private fun projectManifest(name: String, text: String, path: String = "docs-$name/tex/source/main.tex"): String {
        val digest = publishBundle(path, text, "/workspace/$path", gzip(syncTex("/workspace/$path")))
        val raw = JsonParser.parseString(File(staging, "objects/$digest").readText()).asJsonObject
        raw.getAsJsonArray("pdfs")[0].asJsonObject.addProperty("filename", "$name.pdf")
        val bytes = raw.toString().toByteArray()
        return sha256(bytes).also { File(staging, "objects/$it").writeBytes(bytes) }
    }

    private fun installProjects(vararg projects: Pair<String, String>, readerIndex: File? = null, stale: Set<String> = emptySet()): OfflineSourceBundle? {
        val raw = JsonObject().apply {
            addProperty("schema_version", 3)
            add("project_manifests", JsonArray().apply {
                projects.forEach { (name, digest) -> add(JsonObject().apply {
                    addProperty("filename", "$name.pdf")
                    addProperty("artifact_sha256", digest)
                    addProperty("manifest_object", digest)
                    addProperty("pdf_stale", name in stale)
                }) }
            })
        }.toString().toByteArray()
        val digest = sha256(raw)
        val store = OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = readerIndex)
        check(store.stageDownloadedObject(digest, raw))
        val index = JsonObject().apply {
            addProperty("schema_version", 3)
            addProperty("bundle_id", digest)
            addProperty("manifest_object", digest)
        }.toString().toByteArray()
        assertEquals(emptySet<String>(), store.missingObjectIds(index, raw))
        return store.installDownloaded(index, emptyMap())
    }

    @Test
    fun pdfGenerationPointerKeepsOldSourcesUntilCommit() {
        val a = projectManifest("alpha", "alpha original")
        assertNotNull(installProjects("alpha" to a))
        val reader = File(root, "pdf-generation/.thoughts-project-sources.json")
        reader.parentFile.mkdirs()
        val store = OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = reader)
        store.pinReaderGeneration()
        val oldId = store.activeInstalledId()
        val next = projectManifest("alpha", "alpha changed")
        assertNotNull(installProjects("alpha" to next, readerIndex = reader))
        assertEquals(oldId, store.activeInstalledId())
        assertEquals(a, store.activeDocumentArtifacts()["alpha.pdf"])
        assertNotNull(store.librarySourceMetadata())
        // The PDF generation directory swap carries this pointer with it.
        reader.writeBytes(File(privateDirectory, "active-index.json").readBytes())
        assertEquals(next, store.activeDocumentArtifacts()["alpha.pdf"])
        assertEquals("alpha changed", store.sourceSnapshot("docs-alpha/tex/source/main.tex", 1)!!.contents)
    }

    @Test
    fun projectUpdateReusesUnchangedIndexesAndPreservesDraftAfterRestart() {
        val a = projectManifest("alpha", "alpha original")
        val b = projectManifest("beta", "beta original")
        assertNotNull(installProjects("alpha" to a, "beta" to b, stale = setOf("beta")))
        val betaIndex = File(privateDirectory, "projects/$b/source-open-index-v2.json")
        val betaTime = betaIndex.lastModified()
        val betaWorking = File(working, "docs-beta/tex/source/main.tex")
        betaWorking.writeText("beta unsynced edit")
        val next = projectManifest("alpha", "alpha changed")
        assertNotNull(installProjects("alpha" to next, "beta" to b, stale = setOf("beta")))
        val restarted = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(betaTime, betaIndex.lastModified())
        assertEquals(true, restarted.activeDocumentStaleness()["beta.pdf"])
        assertEquals(b, restarted.installedDocumentManifest("beta.pdf", b, true))
        assertNull(restarted.installedDocumentManifest("beta.pdf", b, false))
        assertEquals("beta unsynced edit", betaWorking.readText())
        assertEquals(setOf("alpha.pdf", "beta.pdf"), restarted.librarySourceMetadata()!!.pdfs.keys)
        assertEquals(setOf("alpha.pdf", "beta.pdf"), restarted.overviewSourceSnapshot().pdfSha256ByFilename.keys)
        assertEquals("alpha changed", restarted.sourceSnapshot("docs-alpha/tex/source/main.tex", 1)!!.contents)
        assertTrue(File(privateDirectory, "objects/$b").isFile)
        assertTrue(restarted.pruneActiveBundle())
        assertTrue(File(privateDirectory, "objects/$b").isFile)
    }

    @Test
    fun rebuiltDocumentReplacesItsSourceInSiblingDocumentOfSameFolder() {
        // Two PDFs built from one docs folder; the unchanged one reuses its installed
        // projection, which must not carry the rebuilt sibling's previous source.
        val mainPath = "docs-shared/tex/source/main.tex"
        val notesPath = "docs-shared/notes.md"
        val main = projectManifest("shared_main", "main original", mainPath)
        val notes = projectManifest("shared_notes", "notes original", notesPath)
        assertNotNull(installProjects("shared_main" to main, "shared_notes" to notes))
        val next = projectManifest("shared_notes", "notes changed", notesPath)
        assertNotNull(installProjects("shared_main" to main, "shared_notes" to next))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals("notes changed", store.sourceSnapshot(notesPath, 1)!!.contents)
        assertEquals("main original", store.sourceSnapshot(mainPath, 1)!!.contents)
        assertEquals("notes changed", File(working, notesPath).readText())
        // The reverse order and a second rebuild also activate.
        val last = projectManifest("shared_notes", "notes changed again", notesPath)
        assertNotNull(installProjects("shared_notes" to last, "shared_main" to main))
        assertEquals("notes changed again", OfflineSourceBundleStore(staging, working, privateDirectory)
            .sourceSnapshot(notesPath, 1)!!.contents)
    }

    @Test
    fun afterTheLocalRenameALegacyBundleIsReadInTheDefaultSpelling() {
        val legacy = ".thoughts/projects/${"a".repeat(64)}/documents/notes.md"
        val renamed = ".thoughts/projects/${"a".repeat(64)}/default/notes.md"
        val digest = projectManifest("alpha", "alpha notes\n", legacy)
        DefaultFolder.localStateMigrated = true
        try {
            assertNotNull(installProjects("alpha" to digest))
            val store = OfflineSourceBundleStore(staging, working, privateDirectory)
            assertEquals("alpha notes\n", File(working, renamed).readText())
            assertFalse(File(working, legacy).exists())
            assertEquals("alpha notes\n", store.sourceSnapshot(renamed, 1)!!.contents)
            val bundle = store.librarySourceMetadata()!!
            assertEquals(setOf(renamed), bundle.sources.keys)
            val input = bundle.pdfs.getValue("alpha.pdf").segments.single().inputs.single()
            assertEquals(renamed, input.sourceFile)
            // SyncTeX keeps the build path its object records.
            assertEquals("/workspace/$legacy", input.synctexPath)
        } finally {
            DefaultFolder.localStateMigrated = false
        }
    }

    @Test
    fun defaultFolderNotesOfTwoProjectsStayIsolated() {
        val ownerA = ".thoughts/projects/${"a".repeat(64)}/documents"
        val ownerB = ".thoughts/projects/${"b".repeat(64)}/documents"
        val cardId = "6f1d3c55-0a43-4b7b-9a51-3d1f2b8f9e10"
        // The same comment and card identities in both Projects' notes.md.
        fun notes(note: String, answer: String) =
            "<!-- thoughts:v1:meta {\"key\":\"comment:note:s1\",\"contents\":\"$note\"} -->\n" +
                "<!-- thoughts:v1:start comment:note:s1 -->\nNoted.\n<!-- thoughts:v1:end comment:note:s1 -->\n" +
                "<!-- thoughts:v1:start review:$cardId:answer:0 -->\n$answer\n<!-- thoughts:v1:end review:$cardId:answer:0 -->\n"
        val a = projectManifest("alpha", notes("Alpha note.", "Alpha answer."), "$ownerA/notes.md")
        val b = projectManifest("beta", notes("Beta note.", "Beta answer."), "$ownerB/notes.md")
        assertNotNull(installProjects("alpha" to a, "beta" to b))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(notes("Alpha note.", "Alpha answer."), File(working, "$ownerA/notes.md").readText())
        val local = LocalSourceComments(store, { _, _, _, _ -> error("No source edit expected") }, File(root, "pdfs"),
            { com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability.SOURCE_LINKED }, { _, _ -> 600f })

        val index = local.annotationIndex(listOf("$ownerA/notes.md", "$ownerB/notes.md"))
        assertEquals(setOf(ownerA, ownerB), index.getAsJsonObject("projects").keySet())
        assertEquals(ownerA, index.getAsJsonObject("files").getAsJsonObject("$ownerA/notes.md").get("project").asString)
        assertEquals(ownerB, index.getAsJsonObject("files").getAsJsonObject("$ownerB/notes.md").get("project").asString)
        for (owner in listOf(ownerA, ownerB)) {
            val location = SourceAnnotationIndex.locate(index, "comment", "note", owner)
            assertEquals("linked", location.get("state").asString)
            assertEquals("$owner/notes.md", location.get("source_file").asString)
        }
        assertEquals("unavailable", SourceAnnotationIndex.locate(index, "comment", "note", ".thoughts").get("state").asString)

        // Review lookups and evidence see only the card's own Project.
        store.annotationIndex.install(index)
        val card = store.annotationIndex.locate("review_card", cardId, ownerB)
        assertEquals("linked", card.get("state").asString)
        assertEquals("$ownerB/notes.md", card.get("source_file").asString)
        val evidence = store.annotationIndex.reviewEvidence(cardId, ownerA)
        assertEquals(1, evidence.size)
        assertEquals(sha256("Alpha answer.\n".toByteArray()), evidence.single().get("content_sha256").asString)
        assertEquals(listOf("$ownerA/notes.md"), store.annotationIndex.locations("review_card", cardId, ownerA)?.map { it.get("source_file").asString })

        // A linked comment resolves within its own Project, not ambiguously across both.
        val linked = JsonObject().apply {
            addProperty("comment_id", "note"); addProperty("contents", "Stale.")
            add("scope", JsonObject().apply {
                addProperty("state", "source_linked"); addProperty("anchor_id", "note"); addProperty("source_file", "$ownerA/notes.md")
            })
            add("origin", JsonObject().apply { addProperty("collection", "alpha") })
        }
        val resolved = local.apply(linked)
        assertEquals("source_linked", resolved.getAsJsonObject("scope").get("state").asString)
        assertEquals("$ownerA/notes.md", resolved.getAsJsonObject("scope").get("source_file").asString)
        assertEquals("Alpha note.", resolved.get("contents").asString)
    }

    @Test
    fun documentCommentsDoNotReadUnrelatedProjectOrFullManifest() {
        val a = projectManifest("alpha", "alpha original")
        val b = projectManifest("beta", "beta original")
        assertNotNull(installProjects("alpha" to a, "beta" to b))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        val expected = store.overviewSourceSnapshot("alpha.pdf")
        SourceProjectionMemoryCache.clear()
        // Installed projections suffice even when the source manifests are unavailable.
        for (digest in listOf(a, b)) {
            File(privateDirectory, "objects/$digest").delete()
            File(privateDirectory, "object-cache/$digest").delete()
            File(staging, "objects/$digest").delete()
        }
        // A broken unrelated project must not delay or prevent this document's list.
        File(privateDirectory, "projects/$b/overview-projection-v1.json").writeText("{broken")
        val restarted = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(expected, restarted.overviewSourceSnapshot("ALPHA.pdf"))
        assertEquals(setOf("alpha.pdf"), expected.pdfSha256ByFilename.keys)
        assertTrue(restarted.overviewSourceSnapshot("missing.pdf").pdfSha256ByFilename.isEmpty())
    }

    @Test
    fun sourceOpeningDoesNotReadAnotherProjectsIndexesOrRewriteItsState() {
        val a = projectManifest("alpha", "alpha original")
        val b = projectManifest("beta", "beta original")
        assertNotNull(installProjects("alpha" to a, "beta" to b))
        val betaState = File(privateDirectory, "working/${sha256("docs-beta/tex/source/main.tex".toByteArray())}.json")
        val before = betaState.readBytes()
        File(privateDirectory, "projects/$b/source-open-index-v2.json").writeText("{broken")
        for (dir in listOf(File(privateDirectory, "objects"), File(privateDirectory, "object-cache"), File(staging, "objects"))) {
            File(dir, b).delete()
        }
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals("alpha original", store.sourceSnapshot("docs-alpha/tex/source/main.tex", 1)?.contents)
        assertTrue(before.contentEquals(betaState.readBytes()))
        assertNull(store.sourceSnapshot("docs-beta/tex/source/main.tex", 1))
        val next = projectManifest("alpha", "alpha updated independently")
        assertNotNull(installProjects("alpha" to next, "beta" to b))
        assertEquals("alpha updated independently", OfflineSourceBundleStore(staging, working, privateDirectory)
            .sourceSnapshot("docs-alpha/tex/source/main.tex", 1)?.contents)
        assertTrue(before.contentEquals(betaState.readBytes()))
        assertEquals("{broken", File(privateDirectory, "projects/$b/source-open-index-v2.json").readText())
    }

    @Test
    fun cardMetadataLoadsOnlyRequestedProjectsAndRefreshesAfterActivation() {
        val a = projectManifest("alpha", "alpha original")
        val b = projectManifest("beta", "beta original")
        assertNotNull(installProjects("alpha" to a, "beta" to b))
        val betaIndex = File(privateDirectory, "projects/$b/source-open-index-v2.json")
        betaIndex.writeText("{broken")
        for (dir in listOf(File(privateDirectory, "objects"), File(privateDirectory, "object-cache"), File(staging, "objects"))) {
            File(dir, b).delete()
        }
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        val first = store.librarySourceMetadataFor(setOf("ALPHA.pdf"))!!
        assertEquals(setOf("alpha.pdf"), first.pdfs.keys)
        assertTrue(first === store.librarySourceMetadataFor(setOf("alpha.pdf")))
        assertNull(store.librarySourceMetadataFor(emptySet()))
        assertTrue(store.librarySourceMetadataFor(setOf("absent.pdf"))!!.pdfs.isEmpty())
        assertEquals("{broken", betaIndex.readText())
        val next = projectManifest("alpha", "alpha updated")
        assertNotNull(installProjects("alpha" to next, "beta" to b))
        val refreshed = store.librarySourceMetadataFor(setOf("alpha.pdf"))!!
        assertTrue(first.bundleId != refreshed.bundleId)
        assertEquals(sha256("alpha updated".toByteArray()), refreshed.sources.values.single().sha256)
    }

    @Test
    fun invalidProjectIndexCannotAdvanceActiveGeneration() {
        val a = projectManifest("alpha", "alpha original")
        assertNotNull(installProjects("alpha" to a))
        val previous = File(privateDirectory, "active-index.json").readText()
        val next = projectManifest("alpha", "alpha changed")
        // The claimed filename is inconsistent with its verified manifest.
        assertNull(installProjects("wrong" to next))
        assertEquals(previous, File(privateDirectory, "active-index.json").readText())
        assertEquals("alpha original", File(working, "docs-alpha/tex/source/main.tex").readText())
    }

    @Test
    fun importsVerifiedBundleAndResolvesNeverOpenedSelectionOffline() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "first line\nresolved line\n"
        val synctexPath = "/workspace/docs-sample/tex/source/main.tex"
        val synctex = gzip(
            """
            SyncTeX Version:1
            Input:1:$synctexPath
            Output:pdf
            Magnification:1000
            Unit:1
            X Offset:0
            Y Offset:0
            Content:
            {1
            [1,75:4736286,52741856:29685704,48005570,267389
            x1,42:7893811,6249267
            }
            Postamble:
            """.trimIndent() + "\n"
        )
        val bundleId = publishBundle(sourcePath, source, synctexPath, synctex)
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)

        val snapshot = store.resolve(
            SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "")
        )

        assertNotNull(snapshot)
        assertEquals(42, snapshot?.sourceLine)
        assertEquals(sourcePath, snapshot?.sourceFile)
        assertEquals(bundleId, snapshot?.bundleId)
        assertEquals(source, snapshot?.baseContents)
        assertEquals(source, File(working, sourcePath).readText())

        val textRefined = store.resolve(
            SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "resolved line")
        )
        assertEquals(42, textRefined?.sourceLine)

        val renderedTextFallback = store.resolve(
            SourceSelection(
                "sample.pdf",
                "a".repeat(64),
                1,
                120f,
                95f,
                "We provide additional filtering details in Appendix B.",
            )
        )
        assertEquals(42, renderedTextFallback?.sourceLine)

        // "Copy for agent" names the same line of the source the PDF was built from.
        val point = SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "resolved line")
        assertEquals(sourcePath to 42, store.sourceLine(point))
        assertNull(store.sourceLine(point.copy(pdfSha256 = "b".repeat(64))))
        assertNull(store.sourceLine(point.copy(page = 2)))
    }

    @Test
    fun normalRuntimeRejectsLegacyBundleSchemas() {
        for (schemaVersion in listOf(1, 2)) {
            staging.deleteRecursively()
            working.deleteRecursively()
            privateDirectory.deleteRecursively()
            val bundleId = publishBundle(
                "docs-sample/tex/source/main.tex",
                "legacy source\n",
                "/workspace/main.tex",
                gzip(syncTex("/workspace/main.tex")),
                schemaVersion = schemaVersion,
            )
            val indexRaw = File(staging, "index.json").readBytes()
            val manifestRaw = File(staging, "objects/$bundleId").readBytes()
            val objects = File(staging, "objects").listFiles().orEmpty().associate { file ->
                file.name to file.readBytes()
            }
            val store = OfflineSourceBundleStore(staging, working, privateDirectory)

            assertNull(store.missingObjectIds(indexRaw, manifestRaw))
            assertNull(store.installDownloaded(indexRaw, objects))
            assertNull(store.importLatest())
        }
    }

    @Test
    fun importsOneVerifiedSharedStyleAsAnEditableSingleton() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val sharedStyle = "\\ProvidesPackage{manual_style}\n"
        val bundleId = publishBundle(
            sourcePath,
            "document source\n",
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
            schemaVersion = 3,
            sharedStyle = sharedStyle,
            sharedStylePdfFilenames = listOf("sample.pdf", "second.pdf"),
        )
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)

        assertNotNull(store.importLatest())
        val snapshot = store.sourceSnapshot("docs-paper/tex/support/manual_style.sty", 1)

        assertNotNull(snapshot)
        assertEquals(bundleId, snapshot?.bundleId)
        assertEquals("docs-paper/tex/support/manual_style.sty", snapshot?.sourceFile)
        assertEquals("sample.pdf", snapshot?.bundlePdfFilename)
        assertEquals(listOf("sample.pdf", "second.pdf"), snapshot?.bundlePdfFilenames)
        assertEquals("a".repeat(64), snapshot?.bundlePdfSha256)
        assertEquals(sharedStyle, snapshot?.contents)
        assertEquals(sharedStyle, snapshot?.baseContents)
        assertEquals(sharedStyle, File(working, "docs-paper/tex/support/manual_style.sty").readText())

        File(working, "docs-paper/tex/support/manual_style.sty").writeText("$sharedStyle% phone edit\n")
        val edit = store.externalEdits().single { it.snapshot.sourceFile == "docs-paper/tex/support/manual_style.sty" }
        assertEquals("$sharedStyle% phone edit\n", edit.contents)
        assertEquals(listOf("sample.pdf", "second.pdf"), edit.snapshot.bundlePdfFilenames)
        val queue = SourceEditQueue(
            requestDirectory = File(root, "requests"),
            receiptDirectory = File(root, "receipts"),
            cacheDirectory = File(root, "cache"),
            requestScan = {},
            directorySync = {},
            offlineStore = store,
        )
        assertEquals(1, queue.captureExternalEdits())
        val mutation = queue.pendingSourceMutations().single()
        assertEquals(listOf("sample.pdf", "second.pdf"), mutation.scopePdfFilenames())
        assertTrue(mutation.matchesPdfTarget("second.pdf"))
        assertTrue(queue.hasPendingWorkspaceStyleMutation())
        assertEquals(setOf("sample.pdf", "second.pdf"), queue.pendingSavePdfFilenames())
        assertTrue(queue.discardLocalChanges(requireNotNull(snapshot)))
        assertTrue(!queue.requiresGlobalWorkspaceStyleRefresh())
    }

    @Test
    fun rejectsSharedStyleWhoseObjectIdentityDiffersFromItsHash() {
        publishBundle(
            "docs-sample/tex/source/main.tex",
            "document source\n",
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
            schemaVersion = 3,
            sharedStyle = "\\ProvidesPackage{manual_style}\n",
            mutateSharedStyleResource = { it.addProperty("object", "b".repeat(64)) },
        )

        assertNull(OfflineSourceBundleStore(staging, working, privateDirectory).importLatest())
    }

    @Test
    fun rejectsDuplicateSharedStyleIdentity() {
        publishBundle(
            "docs-sample/tex/source/main.tex",
            "document source\n",
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
            schemaVersion = 3,
            sharedStyle = "\\ProvidesPackage{manual_style}\n",
            duplicateSharedStyleResource = true,
        )

        assertNull(OfflineSourceBundleStore(staging, working, privateDirectory).importLatest())
    }

    @Test
    fun rejectsSharedStyleWhoseDeclaredSizeDiffersFromVerifiedObject() {
        publishBundle(
            "docs-sample/tex/source/main.tex",
            "document source\n",
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
            schemaVersion = 3,
            sharedStyle = "\\ProvidesPackage{manual_style}\n",
            mutateSharedStyleResource = { resource ->
                resource.addProperty("bytes", resource.get("bytes").asLong + 1)
            },
        )

        assertNull(OfflineSourceBundleStore(staging, working, privateDirectory).importLatest())
    }

    @Test
    fun markdownSourceResolvesWithoutEvidenceObjectAndSurvivesIndexRestart() {
        checkMarkdownSource("docs-sample/markdown/source/main.md")
    }

    @Test
    fun rootMarkdownSourceResolvesWithoutEvidenceObjectAndSurvivesIndexRestart() {
        checkMarkdownSource("docs-sample/reviews.md")
    }

    @Test
    fun nestedMarkdownSourceResolvesWithoutEvidenceObjectAndSurvivesIndexRestart() {
        checkMarkdownSource("docs-sample/reviews/round-one.markdown")
    }

    private fun checkMarkdownSource(path: String) {
        val text = "# Heading\n\nA mapped Markdown passage.\n"
        val lookup = File(root, "markdown.zip")
        ZipOutputStream(lookup.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("format.json"))
            zip.write("{\"format\":\"thoughts-markdown-source-map-v1\",\"schema_version\":1}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("pages/1.tsv"))
            zip.write("h\t$path\t3\t90\t100\t40\t10\t0\t11\t35\tthoughts-md-fragment-1\n".toByteArray())
            zip.closeEntry()
        }
        val id = publishBundle(path, text, path, lookup.readBytes())
        val objects = File(staging, "objects")
        val manifest = JsonParser.parseString(File(objects, id).readText()).asJsonObject
        val evidence = JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("kind", "markdown_renderer_evidence")
            addProperty("pdf_sha256", "b".repeat(64))
            addProperty("source_sha256", sha256(text.toByteArray()))
        }.toString().toByteArray()
        // The server validates the evidence; the reader neither downloads nor needs it.
        val evidenceSha = sha256(evidence)
        manifest.addProperty("review_placement_model_version", 4)
        manifest.getAsJsonArray("pdfs")[0].asJsonObject.getAsJsonArray("segments")[0].asJsonObject.apply {
            addProperty("source_format", "markdown")
            addProperty("fingerprint", "b".repeat(64))
            addProperty("lookup_format", "thoughts-markdown-source-map-v1")
            addProperty("lookup_object", sha256(lookup.readBytes()))
            addProperty("lookup_sha256", sha256(lookup.readBytes()))
            addProperty("renderer_evidence_object", evidenceSha)
            addProperty("renderer_evidence_sha256", evidenceSha)
        }
        val bytes = manifest.toString().toByteArray()
        val artifact = sha256(bytes)
        File(objects, artifact).writeBytes(bytes)
        val index = "{\"schema_version\":3,\"bundle_id\":\"$artifact\",\"manifest_object\":\"$artifact\"}"
        File(staging, "index.json").writeText(index)
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(emptySet<String>(), store.missingObjectIds(index.toByteArray(), bytes))
        assertNotNull(store.importLatest())
        assertFalse(File(privateDirectory, "objects/$evidenceSha").exists())
        val selection = SourceSelection("sample.pdf", "a".repeat(64), 1, 100f, 98f, "")
        assertEquals(path, store.resolve(selection)?.sourceFile)
        assertEquals(text, OfflineSourceBundleStore(staging, working, privateDirectory).resolve(selection)?.contents)
    }

    @Test
    fun pageIndexedReverseSyncTexReadsOnlyRequestedPage() {
        val index = File(root, "reverse-synctex.zip")
        ZipOutputStream(index.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("pages/2.tsv"))
            output.write(
                "x\t/workspace/docs-sample/tex/source/main.tex\t42\t120\t95\t0\t0\t0\n"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            output.closeEntry()
        }
        val inputs = listOf(
            OfflineBundleInput(
                "/workspace/docs-sample/tex/source/main.tex",
                "docs-sample/tex/source/main.tex",
                "a".repeat(64),
            ),
        )

        assertNull(OfflineSyncTexResolver.resolve(index, 1, 120f, 95f, inputs))
        assertEquals(
            "docs-sample/tex/source/main.tex" to 42,
            OfflineSyncTexResolver.resolve(index, 2, 120f, 95f, inputs),
        )
    }

    @Test
    fun verifiedManifestFormatRecoversDamagedLegacyIndexHeader() {
        val index = File(root, "damaged-header.zip")
        ZipOutputStream(index.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("format.json"))
            output.write(byteArrayOf(0, 0, 0, 1, 117, 103, 104, 116, 115))
            output.closeEntry()
            output.putNextEntry(ZipEntry("pages/2.tsv"))
            output.write("x\tmain.tex\t245\t120\t95\t0\t0\t0\n".toByteArray())
            output.closeEntry()
        }
        val inputs = listOf(OfflineBundleInput("main.tex", "main.tex", "a".repeat(64)))
        assertNull(OfflineSyncTexResolver.resolve(index, 2, 120f, 95f, inputs))
        assertEquals("main.tex" to 245, OfflineSyncTexResolver.resolve(index, 2, 120f, 95f, inputs,
            "thoughts-reverse-synctex-v1"))
        assertNull(OfflineSyncTexResolver.resolve(index, 2, 120f, 95f, inputs, "thoughts-markdown-source-map-v1"))
    }

    @Test
    fun reverseSyncTexPrefersGlyphPointOverContainingInputBox() {
        val index = File(root, "reverse-synctex.zip")
        ZipOutputStream(index.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("pages/1.tsv"))
            output.write(
                buildString {
                    append("(\t/workspace/docs-sample/tex/source/main.tex\t140\t318\t324\t240\t6\t2\n")
                    append("x\t/workspace/docs-sample/tex/fragments/section.tex\t133\t440\t324\t0\t0\t0\n")
                }.toByteArray(StandardCharsets.UTF_8),
            )
            output.closeEntry()
        }
        val inputs = listOf(
            OfflineBundleInput(
                "/workspace/docs-sample/tex/source/main.tex",
                "docs-sample/tex/source/main.tex",
                "a".repeat(64),
            ),
            OfflineBundleInput(
                "/workspace/docs-sample/tex/fragments/section.tex",
                "docs-sample/tex/fragments/section.tex",
                "b".repeat(64),
            ),
        )

        assertEquals(
            "docs-sample/tex/fragments/section.tex" to 133,
            OfflineSyncTexResolver.resolve(index, 1, 440f, 322f, inputs),
        )
    }

    @Test
    fun remoteOnlyEditUpdatesWorkingSourceAndReceipt() {
        val path = "docs-sample/tex/source/main.tex"
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        publishBundle(path, "base\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        assertNotNull(store.importLatest())
        publishBundle(path, "desktop edit\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        assertNotNull(store.importLatest())
        assertEquals("desktop edit\n", File(working, path).readText())
        val receipt = File(privateDirectory, "materialization/${sha256(path.toByteArray())}.json").readText()
        assertTrue(receipt.contains("remote_only_change"))
        assertTrue(store.materializationProblems().isEmpty())
        assertTrue(receipt.contains("installed_sha256"))
    }

    @Test
    fun concurrentSourceEditsRemainVisibleAsFilesNeedingMerge() {
        val path = "docs-sample/tex/source/main.tex"
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        publishBundle(path, "base\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        assertNotNull(store.importLatest())
        val workingFile = File(working, path)
        workingFile.writeText("phone edit\n")
        publishBundle(path, "verified edit\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        assertNotNull(store.importLatest())

        assertEquals("phone edit\n", workingFile.readText())
        assertEquals(listOf(path), store.sourceFilesNeedingMerge())
        workingFile.writeText("verified edit\n")
        assertTrue(store.sourceFilesNeedingMerge().isEmpty())
    }

    @Test
    fun pendingReviewMarkerRemovalIsRetriedOnceThePhoneSourceIsSafe() {
        val path = "docs-paper/tex/source/main.tex"
        val cardId = "6f1d3c55-0a43-4b7b-9a51-3d1f2b8f9e10"
        val body = "Intro.\nThe answer.\nOutro.\n"
        val marked = ReviewSourceMarkup.create(path, body, "The answer.", cardId).after
        publishBundle(path, marked, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val requests = File(root, "requests")
        val queue = SourceEditQueue(requests, File(root, "receipts"), File(root, "cache"), {}, {}, store)
        fun rescan() {
            // What the background source scanner installs after reading the phone copy.
            val scanned = SourceAnnotationMarkup.scan(File(working, path).readText(), false)
                .apply { addProperty("project", "docs-paper") }
            store.annotationIndex.install(JsonObject().apply {
                addProperty("schema_version", 1)
                add("files", JsonObject().apply { add(path, scanned) })
                add("projects", JsonObject().apply { addProperty("docs-paper", true) })
            })
        }
        rescan()

        val reviews = File(root, "reviews")
        val cards = File(reviews, "cards").also(File::mkdirs)
        File(cards, "$cardId.json").writeText(JsonObject().apply {
            addProperty("schema_version", 3); addProperty("card_id", cardId); addProperty("status", "approved")
            addProperty("revision", 1)
            add("primary", JsonParser.parseString(
                """{"pdf_stem":"sample","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],""" +
                    """"page_visual_sha256":"${"b".repeat(64)}","question":"What is hidden?"}""",
            ).asJsonObject.apply { add("source_anchor", ReviewSourceMarkup.anchor(path, marked, cardId)) })
        }.toString())
        var removals = 0
        fun reviewStore() = com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore(
            null, reviews, File(root, "reviews-in"), File(root, "reviews-out/events"), File(root, "reviews-out/cards"),
        ).useMarkerSourcesForTesting(com.gitlab.mudlej.MjPdfReader.data.review.ReviewMarkerSources(store.annotationIndex) { id, anchor ->
            LocalReviewSources.retryMarkerRemoval(store, id, anchor) { snapshot, contents ->
                removals++
                queue.saveForNextSync(snapshot, snapshot.bundlePdfFilename!!, snapshot.bundlePdfSha256!!, contents)
            }
        })
        // Without a Context the deletion itself cannot edit the source: it records a pending removal.
        val event = reviewStore().deleteCard(cardId, Instant.parse("2026-10-02T12:00:00Z")).getOrThrow()
        val recorded = JsonParser.parseString(event.readText()).asJsonObject
        assertEquals("pending", recorded.get("marker_removal").asString)
        assertTrue(reviewStore().card(cardId)!!.markerRemovalPending)

        // Unsafe: a stray second start marker makes the phone copy's markup invalid.
        val workingFile = File(working, path)
        val broken = marked.replace("Outro.", "\\ReviewCardMaskStart{$cardId}{0}Outro.")
        workingFile.writeText(broken)
        rescan()
        assertEquals(0, reviewStore().retryPendingMarkerRemovals())
        assertEquals(broken, workingFile.readText())
        assertTrue(queue.pendingSourceMutations().isEmpty())
        assertTrue(reviewStore().card(cardId)!!.markerRemovalPending)

        // Safe again: the retry removes exactly the markers and queues an ordinary source save.
        workingFile.writeText(marked)
        rescan()
        assertEquals(1, reviewStore().retryPendingMarkerRemovals())
        assertEquals(1, removals)
        assertEquals(body, workingFile.readText())
        val mutation = queue.pendingSourceMutations().single()
        assertEquals(path, mutation.sourceFile)
        val request = JsonParser.parseString(File(requests, "${mutation.requestId}.json").readText()).asJsonObject
        assertEquals(marked, request.get("base_contents").asString)
        assertEquals(body, request.get("contents").asString)
        rescan()
        assertTrue(!reviewStore().card(cardId)!!.markerRemovalPending)
        // Nothing left to do: a second retry is a no-op.
        assertEquals(0, reviewStore().retryPendingMarkerRemovals())
        assertEquals(1, removals)
        assertEquals(body, workingFile.readText())
        assertEquals(recorded, JsonParser.parseString(event.readText()).asJsonObject)
        // A source outside the phone's offline bundle is never edited.
        val elsewhere = ReviewSourceMarkup.anchor("docs-other/main.tex",
            ReviewSourceMarkup.create("docs-other/main.tex", body, "The answer.", cardId).after, cardId)
        assertTrue(!LocalReviewSources.retryMarkerRemoval(store, cardId, elsewhere) { _, _ -> removals++ })
        assertEquals(1, removals)
    }

    private fun mergeFixture(queuePhoneEdit: Boolean = false): Pair<OfflineSourceBundleStore, SourceEditQueue> {
        val path = "docs-sample/tex/source/main.tex"
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        val queue = SourceEditQueue(File(root, "requests"), File(root, "receipts"), File(root, "cache"), {}, {}, store)
        publishBundle(path, "base\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        assertNotNull(store.importLatest())
        if (queuePhoneEdit) {
            val snapshot = requireNotNull(store.sourceSnapshot(path, 1))
            queue.saveForNextSync(snapshot, "sample.pdf", "a".repeat(64), "phone edit\n")
        } else {
            File(working, path).writeText("phone edit\n")
        }
        publishBundle(path, "verified edit\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        assertNotNull(store.importLatest())
        assertEquals(listOf(path), store.sourceFilesNeedingMerge())
        return store to queue
    }

    private fun mergeRecoveries(path: String, version: String) =
        File(privateDirectory, "merge-recoveries").listFiles().orEmpty()
            .filter { it.name.startsWith("${sha256(path.toByteArray())}.$version.") }.map { it.readText() }

    @Test
    fun mergeFileResolvedWithTheSyncedVersionKeepsThePhoneVersionRecoverable() {
        val path = "docs-sample/tex/source/main.tex"
        val (store, queue) = mergeFixture()
        val versions = requireNotNull(store.sourceMergeVersions(path))
        assertEquals("phone edit\n", versions.phoneContents)
        assertEquals("verified edit\n", versions.syncedContents)
        assertNull(store.sourceMergeVersions("docs-sample/tex/source/other.tex"))

        // A phone version other than the one the user chose is never replaced.
        assertTrue(!queue.useSyncedVersionForMerge(path, "c".repeat(64)))
        assertEquals("phone edit\n", File(working, path).readText())

        assertTrue(queue.useSyncedVersionForMerge(path, versions.phoneSha256))
        assertEquals("verified edit\n", File(working, path).readText())
        assertTrue(store.sourceFilesNeedingMerge().isEmpty())
        assertEquals(listOf("phone edit\n"), mergeRecoveries(path, "phone"))
        // The synced version is now the phone's clean base: nothing to send, nothing to merge.
        assertEquals("verified edit\n", store.sourceSnapshot(path, 1)?.baseContents)
        assertTrue(store.externalEdits().isEmpty())
        assertTrue(queue.pendingSourceMutations().isEmpty())
        assertTrue(!queue.useSyncedVersionForMerge(path, versions.phoneSha256))
    }

    @Test
    fun mergeFileResolvedWithThePhoneVersionQueuesItOnTheSyncedVersion() {
        val path = "docs-sample/tex/source/main.tex"
        val (store, queue) = mergeFixture()
        val versions = requireNotNull(store.sourceMergeVersions(path))

        val requestId = queue.keepPhoneVersionForMerge(path, versions.phoneSha256)

        assertEquals("phone edit\n", File(working, path).readText())
        assertTrue(store.sourceFilesNeedingMerge().isEmpty())
        assertEquals(listOf("verified edit\n"), mergeRecoveries(path, "synced"))
        val request = JsonParser.parseString(File(root, "requests/$requestId.json").readText()).asJsonObject
        assertEquals("verified edit\n", request.get("base_contents").asString)
        assertEquals("phone edit\n", request.get("contents").asString)
        assertEquals(SourceMutationState.QUEUED, queue.pendingSourceMutations().single().state)
        // Queued once: the next Sync's scan does not queue the same edit again.
        assertTrue(store.externalEdits().isEmpty())
        assertNull(store.sourceMergeVersions(path))
    }

    @Test
    fun mergeFileWithAQueuedPhoneEditWithdrawsItOnlyBeforeDelivery() {
        val path = "docs-sample/tex/source/main.tex"
        val (store, queue) = mergeFixture(queuePhoneEdit = true)
        val versions = requireNotNull(store.sourceMergeVersions(path))
        val queued = queue.pendingSourceMutations().single()
        assertEquals(SourceMutationState.QUEUED, queued.state)

        queue.markDeliveryStarted(queued.requestId)
        assertTrue(runCatching { queue.useSyncedVersionForMerge(path, versions.phoneSha256) }.isFailure)
        assertTrue(runCatching { queue.keepPhoneVersionForMerge(path, versions.phoneSha256) }.isFailure)
        assertEquals("phone edit\n", File(working, path).readText())
        assertTrue(File(root, "requests/${queued.requestId}.json").isFile)

        // Still only queued (delivery never started): the queued phone edit is withdrawn.
        assertTrue(File(root, "cache/deliveries/${queued.requestId}.json").delete())
        assertEquals(SourceMutationState.QUEUED, queue.pendingSourceMutations().single().state)
        assertTrue(queue.useSyncedVersionForMerge(path, versions.phoneSha256))
        assertTrue(!File(root, "requests/${queued.requestId}.json").exists())
        assertTrue(queue.pendingSourceMutations().isEmpty())
        assertEquals("verified edit\n", File(working, path).readText())
        assertEquals(listOf("phone edit\n"), mergeRecoveries(path, "phone"))
        assertTrue(store.sourceFilesNeedingMerge().isEmpty())
        assertNull(store.queueState(path))
    }

    @Test
    fun nonOverlappingSourceEditsMergeLocallyAndRemainPendingForSync() {
        val path = "docs-sample/tex/source/main.tex"
        val base = "first\nsecond\nthird\n"
        val local = "first phone\nsecond\nthird\n"
        val remote = "first\nsecond\nthird desktop\n"
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        publishBundle(path, base, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        assertNotNull(store.importLatest())
        val stateFile = File(privateDirectory, "working/${sha256(path.toByteArray())}.json")
        val oldState = stateFile.readBytes()
        File(working, path).writeText(local)
        publishBundle(path, remote, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))

        assertNotNull(store.importLatest())

        val merged = "first phone\nsecond\nthird desktop\n"
        assertEquals(merged, File(working, path).readText())
        assertEquals(remote, store.sourceSnapshot(path, 1)?.baseContents)
        assertEquals(merged, store.sourceSnapshot(path, 1)?.contents)
        assertTrue(store.sourceFilesNeedingMerge().isEmpty())
        assertEquals(local, File(privateDirectory, "merge-recoveries/${sha256(path.toByteArray())}.local").readText())
        assertEquals(merged, store.externalEdits().single().contents)

        // Existing installations can still hold a merge receipt from before this behavior.
        File(working, path).writeText(local)
        stateFile.writeBytes(oldState)
        val receiptFile = File(privateDirectory, "materialization/${sha256(path.toByteArray())}.json")
        receiptFile.writeText("""{"action":"merge","remote_sha256":"${sha256(remote.toByteArray())}"}""")
        assertEquals(1, store.autoMergePendingSources())
        assertEquals(merged, File(working, path).readText())
        assertTrue(store.sourceFilesNeedingMerge().isEmpty())
    }

    @Test
    fun missingLineagePreservesFileWithoutInventingBase() {
        val path = "docs-sample/tex/source/main.tex"
        val file = File(working, path)
        file.parentFile!!.mkdirs()
        file.writeText("untracked phone draft\n")
        publishBundle(path, "remote base\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        assertEquals("untracked phone draft\n", file.readText())
        val receipt = File(privateDirectory, "materialization/${sha256(path.toByteArray())}.json").readText()
        assertTrue(receipt.contains("missing_merge_base"))
        assertEquals(listOf("$path: missing merge base"), store.materializationProblems())
        assertTrue(!receipt.contains("installed_sha256"))
        assertTrue(!File(privateDirectory, "working/${sha256(path.toByteArray())}.json").exists())
    }

    @Test
    fun externalEditorChangeIsPreservedAndReturnedAsMergeDraft() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "base line\n"
        publishBundle(sourcePath, source, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val workingSource = File(working, sourcePath)
        workingSource.writeText("edited by another app\n")

        val edits = store.externalEdits()

        assertEquals(1, edits.size)
        assertEquals(1, store.externalEdits().size)
        assertEquals(source, edits.single().snapshot.baseContents)
        assertEquals("edited by another app\n", edits.single().contents)
        assertTrue(edits.single().snapshot.bundlePdfSha256?.matches(Regex("a{64}")) == true)
    }

    @Test
    fun discardRestoresTheVerifiedBundleBaseAndClearsQueueState() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "base line\n"
        publishBundle(sourcePath, source, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        assertTrue(store.writeWorkingContents(sourcePath, "phone edit\n"))
        store.recordQueued(
            sourcePath,
            "3daaf7df-2e77-47cf-8e49-51ab5a761191",
            2,
            sha256("phone edit\n".toByteArray(StandardCharsets.UTF_8)),
        )

        assertTrue(store.discardWorkingChanges(sourcePath))

        assertEquals(source, File(working, sourcePath).readText())
        assertNull(store.queueState(sourcePath))
        assertTrue(store.externalEdits().isEmpty())
    }

    @Test
    fun restartRepairRecordsOnlyTheDraftStillMaterializedInWorkingTeX() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        publishBundle(
            sourcePath,
            "base line\n",
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
        )
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val firstRequestId = "3daaf7df-2e77-47cf-8e49-51ab5a761191"
        val draft = "crash-preserved draft\n"
        assertTrue(store.writeWorkingContents(sourcePath, draft))

        assertTrue(
            store.repairQueuedIfWorkingMatches(
                sourcePath,
                firstRequestId,
                1,
                sha256(draft.toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        assertEquals(OfflineQueueState(firstRequestId, 1), store.queueState(sourcePath))

        val newerExternalEdit = "newer external edit\n"
        File(working, sourcePath).writeText(newerExternalEdit)
        assertTrue(
            !store.repairQueuedIfWorkingMatches(
                sourcePath,
                "10d7da38-75d0-436d-b86f-f98809545c8f",
                2,
                sha256(draft.toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        assertEquals(newerExternalEdit, File(working, sourcePath).readText())
        assertEquals(OfflineQueueState(firstRequestId, 1), store.queueState(sourcePath))
    }

    @Test
    fun successfulReceiptAdvancesTheOfflineBaseForTheNextEdit() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "base line\n"
        publishBundle(sourcePath, source, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val requestId = "3daaf7df-2e77-47cf-8e49-51ab5a761191"
        val canonical = "accepted phone edit\n"
        assertTrue(store.writeWorkingContents(sourcePath, canonical))
        store.recordQueued(sourcePath, requestId, 1, sha256(canonical.toByteArray()))

        store.applySuccessfulReceipt(
            SourceSnapshot(
                resolutionId = requestId,
                sourceFile = sourcePath,
                sourceLine = 1,
                sourceSha256 = sha256(canonical.toByteArray()),
                contents = canonical,
            ),
            deliveredRevision = 1,
        )

        val resolved = store.resolve(
            SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, ""),
        )
        assertEquals(canonical, resolved?.contents)
        assertEquals(canonical, resolved?.baseContents)
        assertEquals(sha256(canonical.toByteArray()), resolved?.sourceSha256)
        assertNull(store.queueState(sourcePath))
        assertTrue(File(privateDirectory, "objects/${resolved?.sourceSha256}").isFile)

        // Save reopens by source path, unlike PDF navigation above. Both must
        // use the receipt's base while the PDF bundle still has the old source.
        val reopened = OfflineSourceBundleStore(staging, working, privateDirectory)
        val nextEdit = reopened.sourceSnapshot(sourcePath, 1)
        assertEquals(canonical, nextEdit?.contents)
        assertEquals(canonical, nextEdit?.baseContents)
        assertEquals(resolved?.sourceSha256, nextEdit?.sourceSha256)
        assertEquals(source, resolved?.navigationBaseContents)
        val requests = File(root, "requests")
        val queue = SourceEditQueue(
            requestDirectory = requests,
            receiptDirectory = File(root, "receipts"),
            cacheDirectory = File(root, "cache"),
            requestScan = {},
            directorySync = {},
            offlineStore = reopened,
        )
        val handle = requireNotNull(queue.openOfflineSource(sourcePath, 1))
        val savedId = queue.saveForNextSync(requireNotNull(handle.cachedSnapshot), "sample.pdf", "a".repeat(64), "second edit\n")
        val mutation = JsonParser.parseString(File(requests, "$savedId.json").readText()).asJsonObject
        assertEquals(canonical, mutation.get("base_contents").asString)
        assertEquals("second edit\n", mutation.get("contents").asString)
        assertEquals("second edit\n", reopened.sourceSnapshot(sourcePath, 1)?.contents)
        assertEquals(canonical, reopened.sourceSnapshot(sourcePath, 1)?.baseContents)
        // A mismatched/corrupt base remains unavailable rather than being trusted.
        File(privateDirectory, "objects/${nextEdit?.sourceSha256}").writeText("corrupt")
        assertNull(reopened.sourceSnapshot(sourcePath, 1))
    }

    @Test
    fun successfulReceiptRebasesButDoesNotOverwriteANewerDraft() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "base line\n"
        publishBundle(sourcePath, source, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val successorId = "3daaf7df-2e77-47cf-8e49-51ab5a761191"
        val canonical = "accepted first edit\n"
        val newer = "newer local draft\n"
        assertTrue(store.writeWorkingContents(sourcePath, newer))
        store.recordQueued(sourcePath, successorId, 2, sha256(newer.toByteArray()))

        store.applySuccessfulReceipt(
            SourceSnapshot(
                resolutionId = successorId,
                sourceFile = sourcePath,
                sourceLine = 1,
                sourceSha256 = sha256(canonical.toByteArray()),
                contents = canonical,
            ),
            deliveredRevision = 1,
        )

        val resolved = store.resolve(
            SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, ""),
        )
        assertEquals(newer, resolved?.contents)
        assertEquals(canonical, resolved?.baseContents)
        assertEquals(sha256(canonical.toByteArray()), resolved?.sourceSha256)
        assertEquals(OfflineQueueState(successorId, 2), store.queueState(sourcePath))
        val reopened = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(newer, reopened.sourceSnapshot(sourcePath, 1)?.contents)
        assertEquals(canonical, reopened.sourceSnapshot(sourcePath, 1)?.baseContents)
        assertEquals(OfflineQueueState(successorId, 2), reopened.queueState(sourcePath))
    }

    @Test
    fun pruneActiveBundleRemovesCleanStaleSourcesAndKeepsEditedOnes() {
        val retainedPath = "current/main.tex"
        publishBundle(retainedPath, "current\n", "/workspace/current.tex", gzip(syncTex("/workspace/current.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())

        val cleanPath = "old/clean.tex"
        val editedPath = "old/edited.tex"
        val cleanRaw = "clean\n".toByteArray()
        val editedRaw = "base\n".toByteArray()
        val cleanSha = sha256(cleanRaw)
        val editedSha = sha256(editedRaw)
        File(privateDirectory, "objects/$cleanSha").writeBytes(cleanRaw)
        File(privateDirectory, "objects/$editedSha").writeBytes(editedRaw)
        writeStaleState(cleanPath, cleanSha)
        writeStaleState(editedPath, editedSha)
        File(working, cleanPath).apply { parentFile.mkdirs(); writeBytes(cleanRaw) }
        File(working, editedPath).apply { parentFile.mkdirs(); writeText("local edit\n") }

        assertTrue(store.pruneActiveBundle())

        assertTrue(!File(working, cleanPath).exists())
        assertTrue(!stateFiles().any { it.readText().contains(cleanPath) })
        assertTrue(!File(privateDirectory, "objects/$cleanSha").exists())
        assertTrue(File(privateDirectory, "object-cache/$cleanSha").isFile)
        assertEquals("local edit\n", File(working, editedPath).readText())
        assertTrue(stateFiles().any { it.readText().contains(editedPath) })
        assertTrue(File(privateDirectory, "objects/$editedSha").isFile)
    }

    @Test
    fun cleanupReceiptSurvivesRestartAndInvalidatesForChangedFiles() {
        val sourcePath = "current/main.tex"
        publishBundle(sourcePath, "current\n", "/workspace/current.tex", gzip(syncTex("/workspace/current.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val events = mutableListOf<com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnosticEvent>()
        val diagnostics = com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnostics(write = { events.add(it) }, memory = { emptyMap() })
        assertTrue(store.pruneActiveBundle(diagnostics = diagnostics))
        val restarted = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertTrue(restarted.pruneActiveBundle(diagnostics = diagnostics))
        assertEquals("unchanged_cleanup_inventory", events.last { it.stage == "source_bundle_cleanup" }.values["reason"])

        File(working, sourcePath).writeText("unsynced local draft\n")
        assertTrue(restarted.pruneActiveBundle(diagnostics = diagnostics))
        assertEquals("prune_active_bundle", events.last { it.stage == "source_bundle_cleanup" }.values["reason"])
        assertEquals("unsynced local draft\n", File(working, sourcePath).readText())

        val orphan = "unused cached object".toByteArray()
        val orphanFile = File(privateDirectory, "objects/${sha256(orphan)}").apply { writeBytes(orphan) }
        assertTrue(restarted.pruneActiveBundle(diagnostics = diagnostics))
        assertEquals("prune_active_bundle", events.last { it.stage == "source_bundle_cleanup" }.values["reason"])
        assertTrue(!orphanFile.exists())
        assertTrue(restarted.pruneActiveBundle(diagnostics = diagnostics))
        assertEquals("unchanged_cleanup_inventory", events.last { it.stage == "source_bundle_cleanup" }.values["reason"])

        assertNull(restarted.installDownloaded("invalid index".toByteArray(), emptyMap()))
        assertTrue(restarted.pruneActiveBundle(diagnostics = diagnostics))
        assertEquals("prune_active_bundle", events.last { it.stage == "source_bundle_cleanup" }.values["reason"])
    }

    @Test
    fun completeLibraryRequiresAndRetainsBothMappingFormats() {
        val id = publishBundle("current/main.tex", "current\n", "/workspace/current.tex", gzip(syncTex("/workspace/current.tex")))
        val objects = File(staging, "objects")
        val manifest = JsonParser.parseString(File(objects, id).readText()).asJsonObject
        val segment = manifest.getAsJsonArray("pdfs")[0].asJsonObject.getAsJsonArray("segments")[0].asJsonObject
        val lookup = segment.get("lookup_object").asString
        val raw = gzip(syncTex("/workspace/raw-current.tex"))
        val rawId = sha256(raw)
        segment.addProperty("synctex_object", rawId)
        segment.addProperty("synctex_sha256", rawId)
        val bytes = manifest.toString().toByteArray()
        val revisedId = sha256(bytes)
        File(objects, revisedId).writeBytes(bytes)
        val index = JsonObject().apply {
            addProperty("schema_version", 3)
            addProperty("bundle_id", revisedId)
            addProperty("manifest_object", revisedId)
        }.toString().toByteArray()
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertTrue(store.missingObjectIds(index, bytes)!!.contains(rawId))
        assertNull(store.installDownloaded(index, emptyMap()))
        assertTrue(store.stageDownloadedObject(rawId, raw))
        assertNotNull(store.installDownloaded(index, emptyMap()))
        assertTrue(store.pruneActiveBundle())
        assertTrue(File(privateDirectory, "objects/$lookup").isFile)
        assertTrue(File(privateDirectory, "objects/$rawId").isFile)
        assertTrue(store.missingObjectIds(index, bytes)!!.isEmpty())
    }

    @Test
    fun completedInstallSkipsDuplicateCleanupOnlyForItsActiveGeneration() {
        val sourcePath = "current/main.tex"
        val firstId = publishBundle(sourcePath, "current\n", "/workspace/current.tex", gzip(syncTex("/workspace/current.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        val installed = store.installDownloaded(File(staging, "index.json").readBytes(), emptyMap())!!
        assertEquals(firstId, installed.bundleId)
        val events = mutableListOf<com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnosticEvent>()
        val diagnostics = com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnostics(write = { events.add(it) }, memory = { emptyMap() })
        // A fresh store has no parsed-manifest cache. It can reuse only this attempt's cleanup.
        assertTrue(OfflineSourceBundleStore(staging, working, privateDirectory).pruneActiveBundle(firstId, diagnostics))
        assertEquals("already_pruned_during_install", events.last { it.stage == "source_bundle_cleanup" }.values["reason"])

        File(working, sourcePath).writeText("unsynced local draft\n")
        val nextId = publishBundle("new/main.tex", "next\n", "/workspace/new.tex", gzip(syncTex("/workspace/new.tex")))
        assertEquals(nextId, store.importLatest()!!.bundleId)
        val orphan = "unused cached object".toByteArray()
        val orphanFile = File(privateDirectory, "objects/${sha256(orphan)}").apply { writeBytes(orphan) }
        assertTrue(OfflineSourceBundleStore(staging, working, privateDirectory).pruneActiveBundle(firstId, diagnostics))
        assertEquals("prune_active_bundle", events.last { it.stage == "source_bundle_cleanup" }.values["reason"])
        assertTrue(!orphanFile.exists())
        assertEquals("unsynced local draft\n", File(working, sourcePath).readText())
        assertTrue(File(privateDirectory, "objects/${sha256("current\n".toByteArray())}").isFile)
    }

    @Test
    fun failedInstallDoesNotAuthorizeSkippingRecoveryCleanup() {
        publishBundle("current/main.tex", "current\n", "/workspace/current.tex", gzip(syncTex("/workspace/current.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        assertNull(store.installDownloaded("invalid index".toByteArray(), emptyMap()))
        val orphan = "unused cached object".toByteArray()
        val orphanFile = File(privateDirectory, "objects/${sha256(orphan)}").apply { writeBytes(orphan) }
        assertTrue(OfflineSourceBundleStore(staging, working, privateDirectory).pruneActiveBundle())
        assertTrue(!orphanFile.exists())
    }

    @Test
    fun metadataRefreshKeepsBuildInputsAndCurrentEditableSourceSeparate() {
        val path = "docs-sample/tex/source/main.tex"
        val old = "Old source line.\n"
        val current = "New source line.\n"
        val originalId = publishBundle(path, old, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val objects = File(staging, "objects")
        val manifest = JsonParser.parseString(File(objects, originalId).readText()).asJsonObject
        val originalSources = manifest.getAsJsonArray("sources").deepCopy()
        val oldSha = sha256(old.toByteArray())
        val currentSha = sha256(current.toByteArray())
        File(objects, currentSha).writeText(current)
        manifest.getAsJsonArray("sources")[0].asJsonObject.apply {
            addProperty("sha256", currentSha)
            addProperty("object", currentSha)
            addProperty("bytes", current.toByteArray().size)
        }
        manifest.getAsJsonArray("pdfs")[0].asJsonObject.apply {
            add("placement_sources", originalSources)
            add("source_revision_equivalences", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("source_file", path)
                    addProperty("from_sha256", oldSha)
                    addProperty("to_sha256", currentSha)
                    addProperty("method", "proposal_accept_metadata_v1")
                })
            })
        }
        val raw = manifest.toString().toByteArray()
        val id = sha256(raw)
        File(objects, id).writeBytes(raw)
        File(staging, "index.json").writeText(JsonObject().apply {
            addProperty("schema_version", 3)
            addProperty("bundle_id", id)
            addProperty("manifest_object", id)
        }.toString())
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        assertEquals(current, store.sourceSnapshot(path, 1)?.baseContents)
        assertNotNull(store.resolve(SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "New source line.")))
        // Carry the immutable build revision with the old line; the editor translates it.
        val mapped = store.resolve(SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "Old source line."))!!
        assertEquals(old, mapped.navigationBaseContents)
        assertEquals(current, mapped.contents)
        assertEquals(1, mapped.sourceLine)
        // The synced source moved on from the PDF's build: its line numbers no longer name the passage.
        assertNull(store.sourceLine(SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "Old source line.")))
        assertTrue(File(privateDirectory, "objects/$oldSha").isFile)
        assertTrue(store.pruneActiveBundle())
        val reopened = OfflineSourceBundleStore(staging, working, privateDirectory)
            .resolve(SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "Old source line."))!!
        assertEquals(old, reopened.navigationBaseContents)
        assertEquals(current, reopened.contents)
    }

    @Test
    fun importsAgentProposalMetadataAndQuadFragments() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "proposal body\n"
        val proposalId = "agent-remove-11111111-1111-4111-8111-111111111111"
        val sourceSha = sha256(source.toByteArray(StandardCharsets.UTF_8))
        val proposal = JsonObject().apply {
            addProperty("id", proposalId)
            addProperty("comment", "remove this")
            addProperty("source_file", sourcePath)
            addProperty("source_sha256", sourceSha)
            addProperty("line", 7)
            addProperty("body_preview", "proposal body")
            addProperty("suppressed", false)
            add("pdf_filenames", JsonArray().apply { add("sample.pdf") })
            add("placement", JsonObject().apply {
                add("quad_fragments", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("pdf_filename", "sample.pdf")
                        addProperty("page", 1)
                        addProperty("left", 10f)
                        addProperty("top", 20f)
                        addProperty("right", 80f)
                        addProperty("bottom", 32f)
                    })
                })
            })
        }
        publishBundle(
            sourcePath,
            source,
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
            proposal,
        )
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)

        val imported = store.agentProposals("sample.pdf").single()

        assertEquals(proposalId, imported.id)
        assertEquals("remove this", imported.comment)
        assertEquals("proposal body", imported.bodyPreview)
        assertEquals(1, imported.quads.size)
        assertEquals(80f, imported.quads.single().right)
        val cache = File(privateDirectory, "overview-projection-v1.json")
        val bytes = cache.readText()
        val modified = cache.lastModified()
        staging.deleteRecursively()
        assertTrue(File(privateDirectory, "objects/${imported.bundleId}").delete())
        SourceProjectionMemoryCache.clear()
        val cold = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(listOf(imported), cold.agentProposals("sample.pdf"))
        assertTrue(cold.agentProposals("another.pdf").isEmpty())
        assertEquals(bytes, cache.readText())
        assertEquals(modified, cache.lastModified())
    }

    @Test
    fun unchangedSourceStaysOpenAcrossBundleUpdatesAndRepairsOldReuseMetadata() {
        val path = "docs-sample/tex/source/main.tex"
        val source = "Original source.\n"
        val first = publishBundle(path, source, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        assertTrue(store.writeWorkingContents(path, "Unsynced local draft.\n"))
        val next = publishBundle(path, source, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex") + "\n"))
        assertTrue(first != next)
        assertNotNull(store.importLatest())
        val snapshot = store.sourceSnapshot(path, 1)
        assertNotNull(snapshot)
        assertEquals(next, snapshot?.bundleId)
        assertEquals(source, snapshot?.baseContents)
        assertEquals("Unsynced local draft.\n", snapshot?.contents)
        val stateFile = File(privateDirectory, "working/${sha256(path.toByteArray(StandardCharsets.UTF_8))}.json")
        val state = JsonParser.parseString(stateFile.readText()).asJsonObject
        assertEquals(next, state.get("bundle_id").asString)
        // An installation made before the fix can already contain stale reuse metadata.
        state.addProperty("bundle_id", first)
        stateFile.writeText(state.toString())
        assertEquals(next, store.sourceSnapshot(path, 1)?.bundleId)
        assertEquals("Unsynced local draft.\n", store.sourceSnapshot(path, 1)?.contents)
        assertEquals(next, JsonParser.parseString(stateFile.readText()).asJsonObject.get("bundle_id").asString)
        state.addProperty("pdf_sha256", "b".repeat(64))
        stateFile.writeText(state.toString())
        assertNull(store.sourceSnapshot(path, 1))
    }

    @Test
    fun sourceOnlyProposalRemainsAvailableWithoutPdfGeometry() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val id = "remove-externaldocument-20260909"
        val before = "\\externaldocument{paper}"
        val source = "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{$id}{Remove the import.}{$before}{}\n"
        val proposal = JsonObject().apply {
            addProperty("id", id)
            addProperty("kind", "remove")
            addProperty("comment", "Remove the import.")
            addProperty("source_file", sourcePath)
            addProperty("source_sha256", sha256(source.toByteArray(StandardCharsets.UTF_8)))
            addProperty("line", 1)
            addProperty("suppressed", false)
            add("before", JsonObject().apply { addProperty("preview", before) })
            add("after", JsonObject().apply { addProperty("preview", "") })
            add("pdf_filenames", JsonArray().apply { add("sample.pdf") })
            add("placement", JsonObject().apply {
                addProperty("status", "source_only")
                add("quad_fragments", JsonArray())
            })
        }
        publishBundle(sourcePath, source, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")), proposal, 3)
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        val entry = store.agentProposals("sample.pdf").single()
        assertEquals(id, entry.id)
        assertEquals(before, entry.beforePreview)
        assertEquals("", entry.afterPreview)
        assertTrue(entry.quads.isEmpty())
        assertEquals(id, store.overviewSourceSnapshot().proposals.single().id)
        assertEquals(sourcePath, entry.sourceFile)
        assertTrue(!entry.suppressed)
    }

    @Test
    fun importsTypedEditProposalRegionsFromSchemaThree() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "old text and new text\n"
        val sourceSha = sha256(source.toByteArray(StandardCharsets.UTF_8))
        val proposal = JsonObject().apply {
            addProperty("id", "objective-label-20260820")
            addProperty("kind", "edit")
            addProperty("comment", "The objective label was stale.")
            addProperty("source_file", sourcePath)
            addProperty("source_sha256", sourceSha)
            addProperty("line", 1)
            addProperty("body_preview", "old text")
            add("before", JsonObject().apply { addProperty("preview", "old text") })
            add("after", JsonObject().apply { addProperty("preview", "new text") })
            addProperty("suppressed", false)
            add("pdf_filenames", JsonArray().apply { add("sample.pdf") })
            add("placement", JsonObject().apply {
                add("regions", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "before")
                        add("quad_fragments", JsonArray().apply { add(proposalQuad("sample.pdf", 10f, 80f)) })
                    })
                    add(JsonObject().apply {
                        addProperty("role", "after")
                        add("quad_fragments", JsonArray().apply {
                            add(proposalQuad("sample.pdf", 90f, 160f))
                            // The same heading typeset again in the table of contents.
                            add(proposalQuad("sample.pdf", 20f, 60f).apply { addProperty("duplicate", true) })
                        })
                    })
                })
            })
        }
        publishBundle(sourcePath, source, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")), proposal, 3)
        val imported = OfflineSourceBundleStore(staging, working, privateDirectory)
            .agentProposals("sample.pdf")
            .single()

        assertEquals("edit", imported.kind)
        assertEquals("old text", imported.beforePreview)
        assertEquals("new text", imported.afterPreview)
        assertEquals(listOf("before", "after", "after"), imported.quads.map(OfflineProposalQuad::role))
        // Fragments without the key, as in every older bundle, are the source's own occurrence.
        assertEquals(listOf(false, false, true), imported.quads.map(OfflineProposalQuad::duplicate))
    }

    @Test
    fun v2CorrespondenceReplacesLegacyProposalGeometryWithCompleteNormalizedRegions() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "new complete conclusion\n"
        val sourceSha = sha256(source.toByteArray(StandardCharsets.UTF_8))
        val proposalId = "complete-conclusion-20260827"
        val sourceBundleSha = "b".repeat(64)
        val proposal = JsonObject().apply {
            addProperty("id", proposalId)
            addProperty("kind", "edit")
            addProperty("comment", "Align the conclusion with completed findings.")
            addProperty("source_file", sourcePath)
            addProperty("source_sha256", sourceSha)
            addProperty("line", 1)
            add("before", JsonObject().apply { addProperty("preview", "old conclusion") })
            add("after", JsonObject().apply { addProperty("preview", source.trim()) })
            addProperty("suppressed", false)
            add("pdf_filenames", JsonArray().apply { add("sample.pdf") })
            add("placement", JsonObject())
        }
        val proof = JsonObject().apply {
            addProperty("method", "named_span_synctex")
            addProperty("resolver_version", 2)
            addProperty("source_sha256", sourceSha)
            addProperty("source_bundle_sha256", sourceBundleSha)
            addProperty("build_sha256", "d".repeat(64))
            addProperty("pdf_sha256", "a".repeat(64))
            addProperty("extracted_text_sha256", "c".repeat(64))
            addProperty("candidate_count", 1)
            addProperty("uniqueness", "unique")
            addProperty("verified", true)
            addProperty("reason", "complete named span")
        }
        val correspondence = JsonObject().apply {
            addProperty("schema_version", 2)
            addProperty("review_key", "proposal:$proposalId")
            addProperty("kind", "agent_edit")
            addProperty("item_id", proposalId)
            addProperty("state", "exact_text")
            addProperty("reason", "Complete rendered replacement verified.")
            addProperty("source_bundle_sha256", sourceBundleSha)
            add("source_targets", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "after")
                    addProperty("source_file", sourcePath)
                    addProperty("source_sha256", sourceSha)
                    addProperty("byte_start", 0)
                    addProperty("byte_end", source.toByteArray().size)
                    addProperty("line_start", 1)
                    addProperty("line_end", 1)
                    addProperty("column_start", 1)
                    addProperty("column_end", source.length)
                    addProperty("text_sha256", sourceSha)
                    addProperty("render_intent", "text")
                    addProperty("anchor_id", proposalId)
                })
            })
            add("locations", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("pdf_filename", "sample.pdf")
                    addProperty("pdf_sha256", "a".repeat(64))
                    addProperty("source_bundle_sha256", sourceBundleSha)
                    addProperty("build_sha256", "d".repeat(64))
                    addProperty("page_index", 0)
                    addProperty("page", 1)
                    addProperty("role", "after")
                    addProperty("placement_kind", "exact_text")
                    add("regions", JsonArray().apply {
                        add(JsonArray().apply { listOf(0.1f, 0.2f, 0.8f, 0.22f).forEach(::add) })
                        add(JsonArray().apply { listOf(0.1f, 0.23f, 0.7f, 0.25f).forEach(::add) })
                    })
                    add("point", JsonArray())
                    add("proof", proof)
                })
                // A LaTeX-made copy, e.g. a running head repeating the edited text.
                add(JsonObject().apply {
                    addProperty("pdf_filename", "sample.pdf")
                    addProperty("pdf_sha256", "a".repeat(64))
                    addProperty("source_bundle_sha256", sourceBundleSha)
                    addProperty("build_sha256", "d".repeat(64))
                    addProperty("page_index", 0)
                    addProperty("page", 1)
                    addProperty("role", "after")
                    addProperty("placement_kind", "exact_text")
                    addProperty("duplicate", true)
                    add("regions", JsonArray().apply {
                        add(JsonArray().apply { listOf(0.3f, 0.02f, 0.6f, 0.04f).forEach(::add) })
                    })
                    add("point", JsonArray())
                    add("proof", proof)
                })
            })
        }
        publishBundle(
            sourcePath,
            source,
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
            proposal,
            3,
            correspondence = correspondence,
        )

        val imported = OfflineSourceBundleStore(staging, working, privateDirectory)
            .agentProposals("sample.pdf")
            .single()

        assertEquals("exact_text", imported.correspondence?.state)
        assertEquals(3, imported.quads.size)
        assertTrue(imported.quads.all(OfflineProposalQuad::normalized))
        assertEquals(listOf(0.1f, 0.1f, 0.3f), imported.quads.map(OfflineProposalQuad::left))
        assertEquals(listOf(false, false, true), imported.quads.map(OfflineProposalQuad::duplicate))
        assertEquals(listOf(false, true), imported.correspondence!!.locations.map { it.duplicate })
    }

    @Test
    fun importsSourceReviewCommentsAndTheirDocumentCoverage() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "\\CommentAnchor{comment-one}{New \\% text}\n"
        val sourceSha = sha256(source.toByteArray(StandardCharsets.UTF_8))
        val review = JsonObject().apply {
            addProperty("id", "comment-one")
            addProperty("contents", "New % text")
            addProperty("source_file", sourcePath)
            addProperty("source_sha256", sourceSha)
            addProperty("line", 1)
            add("pdf_filenames", JsonArray().apply { add("sample.pdf") })
        }
        val bundleId = publishBundle(
            sourcePath,
            source,
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
            review = review,
        )

        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        val imported = store.reviewComments("sample.pdf").single()
        val overview = store.overviewSourceSnapshot()

        assertEquals("comment-one", imported.id)
        assertEquals("New % text", imported.contents)
        assertEquals(sourcePath, imported.sourceFile)
        assertTrue(store.hasReviewCommentProjection("sample.pdf"))
        assertEquals(bundleId, overview.bundleId)
        assertEquals(listOf("comment-one"), overview.reviewComments.map(OfflineSourceReviewComment::id))
        assertEquals(setOf("sample.pdf"), overview.reviewCommentDocuments)
        staging.deleteRecursively()
        assertTrue(File(privateDirectory, "objects/$bundleId").delete())
        SourceProjectionMemoryCache.clear()
        val cold = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(listOf(imported), cold.reviewComments("sample.pdf"))
        assertTrue(cold.reviewComments("another.pdf").isEmpty())
        assertTrue(cold.hasReviewCommentProjection("sample.pdf"))
    }

    @Test
    fun validatedActiveBundleIsReusedWithoutReadingItsManifestAgain() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "cached source\n"
        val bundleId = publishBundle(
            sourcePath,
            source,
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
        )
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        val first = store.overviewSourceSnapshot()
        assertTrue(File(privateDirectory, "objects/$bundleId").delete())

        val second = store.overviewSourceSnapshot()

        assertEquals(first, second)
    }

    @Test
    fun libraryMetadataUsesCompactIndexWithoutImportingStagedBundle() {
        val path = "docs-sample/tex/source/main.tex"
        val firstId = publishBundle(path, "first line\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val first = OfflineSourceBundleStore(staging, working, privateDirectory).importLatest()!!
        // The Library must work without reading the much larger authoritative manifest.
        assertTrue(File(privateDirectory, "objects/$firstId").delete())
        val nextId = publishBundle(path, "next version\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        assertTrue(firstId != nextId)
        val cold = OfflineSourceBundleStore(staging, working, privateDirectory)

        val metadata = cold.librarySourceMetadata()!!

        assertEquals(firstId, metadata.bundleId)
        assertEquals(first.pdfs, metadata.pdfs)
        assertEquals(first.sources, metadata.sources)
        assertTrue(metadata.proposals.isEmpty())
        assertTrue(metadata.logicalComments.isEmpty())
        assertEquals(firstId, JsonParser.parseString(File(privateDirectory, "active-index.json").readText())
            .asJsonObject.get("bundle_id").asString)
    }

    @Test
    fun sourceOpeningUsesSmallPersistentIndexAndPreservesDraftAndBase() {
        val path = "docs-sample/tex/source/main.tex"
        val base = "first line\nresolved line\n"
        val bundleId = publishBundle(path, base, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        assertTrue(store.writeWorkingContents(path, "unsynced phone draft\n"))
        val selection = SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "resolved line")
        val expected = store.resolve(selection)
        assertEquals("unsynced phone draft\n", expected?.contents)
        assertEquals(base, expected?.baseContents)
        assertEquals(1, expected?.sourceLine)
        staging.deleteRecursively()
        assertTrue(File(privateDirectory, "objects/$bundleId").delete())
        val overview = File(privateDirectory, "overview-projection-v1.json")
        val overviewBytes = overview.readText()
        val overviewModified = overview.lastModified()

        val cold = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(expected, cold.resolve(selection))
        assertEquals(overviewBytes, overview.readText())
        assertEquals(overviewModified, overview.lastModified())
        assertNull(cold.resolve(selection.copy(pdfSha256 = "b".repeat(64))))
        assertNull(cold.resolve(selection.copy(page = 2)))
        assertEquals("unsynced phone draft\n", cold.sourceSnapshot(path, 2)?.contents)
    }

    @Test
    fun missingSourceIndexStreamsVerifiedManifestWithoutRewritingOverview() {
        val path = "docs-sample/tex/source/main.tex"
        val bundleId = publishBundle(path, "resolved line\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")),
            ignoredMetadata = "x".repeat(2_000_000))
        val selection = SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "resolved line")
        val expected = OfflineSourceBundleStore(staging, working, privateDirectory).resolve(selection)
        staging.deleteRecursively()
        assertTrue(File(privateDirectory, "source-open-index-v2.json").delete())
        val overview = File(privateDirectory, "overview-projection-v1.json")
        overview.writeText("do not touch the overview during source opening")
        val cold = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(expected, cold.resolve(selection))
        assertEquals("do not touch the overview during source opening", overview.readText())
        assertTrue(File(privateDirectory, "objects/$bundleId").length() > 2_000_000)
        assertTrue(File(privateDirectory, "source-open-index-v2.json").length() in 1..10_000)
    }

    @Test
    fun corruptedSourceIndexRepairsFromManifestAndInvalidManifestFailsClosed() {
        val path = "docs-sample/tex/source/main.tex"
        val bundleId = publishBundle(path, "resolved line\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val selection = SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "resolved line")
        val expected = OfflineSourceBundleStore(staging, working, privateDirectory).resolve(selection)
        staging.deleteRecursively()
        val cache = File(privateDirectory, "source-open-index-v2.json")
        val changed = JsonParser.parseString(cache.readText()).asJsonObject
        changed.addProperty("source_sha256", "0".repeat(64))
        cache.writeText(changed.toString())
        assertEquals(expected, OfflineSourceBundleStore(staging, working, privateDirectory).resolve(selection))
        cache.writeText("{broken")
        File(privateDirectory, "objects/$bundleId").appendText(" ")
        assertNull(OfflineSourceBundleStore(staging, working, privateDirectory).resolve(selection))
    }

    @Test
    fun sourceOpenCacheInvalidatesWhenActiveBundleChanges() {
        val path = "docs-sample/tex/source/main.tex"
        val selection = SourceSelection("sample.pdf", "a".repeat(64), 1, 120f, 95f, "resolved line")
        publishBundle(path, "resolved line\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val reader = OfflineSourceBundleStore(staging, working, privateDirectory)
        val old = reader.resolve(selection)
        val oldCache = File(privateDirectory, "source-open-index-v2.json").readBytes()
        val next = publishBundle(path, "new first line\nresolved line\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        assertNotNull(OfflineSourceBundleStore(staging, working, privateDirectory).importLatest())
        staging.deleteRecursively()
        // Both the reader's memory cache and an old on-disk projection must be rejected.
        File(privateDirectory, "source-open-index-v2.json").writeBytes(oldCache)
        val updated = reader.resolve(selection)
        assertEquals(next, updated?.bundleId)
        assertEquals(1, updated?.sourceLine)
        assertEquals("new first line\nresolved line\n", updated?.contents)
        assertTrue(old?.bundleId != updated?.bundleId)
    }

    @Test
    fun overviewProjectionSurvivesColdStoreWithoutReparsingManifest() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "cached source\n"
        val bundleId = publishBundle(
            sourcePath,
            source,
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
        )
        val first = OfflineSourceBundleStore(staging, working, privateDirectory)
            .overviewSourceSnapshot()
        staging.deleteRecursively()
        assertTrue(File(privateDirectory, "objects/$bundleId").delete())

        SourceProjectionMemoryCache.clear()
        val afterColdStart = OfflineSourceBundleStore(staging, working, privateDirectory)
            .overviewSourceSnapshot()

        assertEquals(first, afterColdStart)
    }

    @Test
    fun legacyOverviewProjectionUpgradesOnceWithoutReadingManifest() {
        val path = "docs-sample/tex/source/main.tex"
        val bundleId = publishBundle(path, "cached source\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val expected = OfflineSourceBundleStore(staging, working, privateDirectory).overviewSourceSnapshot()
        val cache = File(privateDirectory, "overview-projection-v1.json")
        val legacy = JsonObject().apply {
            // ART reflection writes the bundle ID before the schema version.
            addProperty("bundleId", bundleId)
            addProperty("schemaVersion", 2)
            add("snapshot", com.google.gson.Gson().toJsonTree(expected))
        }
        cache.writeText(legacy.toString())
        staging.deleteRecursively()
        assertTrue(File(privateDirectory, "objects/$bundleId").delete())
        SourceProjectionMemoryCache.clear()
        assertEquals(expected, OfflineSourceBundleStore(staging, working, privateDirectory).overviewSourceSnapshot())
        assertEquals(4, JsonParser.parseString(cache.readText()).asJsonObject.get("schemaVersion").asInt)
        val modified = cache.lastModified()
        SourceProjectionMemoryCache.clear()
        assertEquals(expected, OfflineSourceBundleStore(staging, working, privateDirectory).overviewSourceSnapshot())
        assertEquals(modified, cache.lastModified())
    }

    @Test
    fun malformedOverviewProjectionFallsBackToManifestAndRepairsCache() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "cached source\n"
        val bundleId = publishBundle(
            sourcePath,
            source,
            "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
        )
        val first = OfflineSourceBundleStore(staging, working, privateDirectory)
            .overviewSourceSnapshot()
        File(privateDirectory, "overview-projection-v1.json").writeText("{malformed")
        staging.deleteRecursively()

        val repaired = OfflineSourceBundleStore(staging, working, privateDirectory)
            .overviewSourceSnapshot()
        assertEquals(first, repaired)
        assertTrue(File(privateDirectory, "objects/$bundleId").delete())
        assertEquals(
            first,
            OfflineSourceBundleStore(staging, working, privateDirectory).overviewSourceSnapshot(),
        )
    }

    @Test
    fun downloadedBundleReusesVerifiedPrivateObjectsByDigest() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "shared source\n"
        publishBundle(sourcePath, source, "/workspace/first.tex", gzip(syncTex("/workspace/first.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        staging.deleteRecursively()

        val secondSyncTex = gzip(syncTex("/workspace/second.tex"))
        val secondSyncTexSha = sha256(secondSyncTex)
        val secondBundle = publishBundle(
            sourcePath,
            source,
            "/workspace/second.tex",
            secondSyncTex,
        )
        val indexRaw = File(staging, "index.json").readBytes()
        val manifestRaw = File(staging, "objects/$secondBundle").readBytes()
        File(staging, "objects").listFiles().orEmpty()
            .filter { it.name != secondBundle }
            .forEach(File::delete)

        val missing = store.missingObjectIds(indexRaw, manifestRaw)

        assertEquals(setOf(secondSyncTexSha), missing)
        assertTrue(store.stageDownloadedObject(secondSyncTexSha, secondSyncTex))
        val timingEvents = mutableListOf<String>()
        assertNotNull(store.installDownloaded(indexRaw, emptyMap()) { timingEvents.add(it) })
        assertEquals(source, File(working, sourcePath).readText())
        for (label in listOf(
            "Installing verified source objects", "Checking source resources",
            "Updating editable source files", "Writing active source indexes",
            "Updating source review index", "Removing source staging files",
            "Pruning inactive working source files", "Pruning unused source cache",
        )) {
            assertTrue(timingEvents.contains(label))
            assertTrue(timingEvents.any { it.startsWith("$label: ") && it.endsWith(" s") })
        }
    }

    @Test
    fun componentPackagesAreRequiredOnlyWhenMissingFromLocalStorage() {
        val bundle = publishBundle(
            "docs-sample/tex/source/main.tex", "source\n", "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
        )
        val objects = File(staging, "objects")
        val root = JsonParser.parseString(File(objects, bundle).readText()).asJsonObject
        val pdf = root.getAsJsonArray("pdfs")[0].asJsonObject
        val segment = pdf.getAsJsonArray("segments")[0].asJsonObject
        val componentSha = "c".repeat(64)
        segment.addProperty("source_id", "main.tex")
        segment.addProperty("fingerprint", componentSha)
        val planId = "d".repeat(64)
        val packageId = "e".repeat(64)
        val compositionId = "f".repeat(64)
        val review = JsonObject().apply {
            addProperty("source_id", "main.tex")
            addProperty("start_page", 1)
            addProperty("page_count", 1)
            addProperty("component_pdf_sha256", componentSha)
            addProperty("basic_review_object", planId)
        }
        val document = JsonObject().apply {
            addProperty("source_id", "main.tex")
            addProperty("start_page", 1)
            addProperty("page_count", 1)
            addProperty("component_pdf_sha256", componentSha)
            addProperty("package_sha256", packageId)
        }
        pdf.add("component_review", JsonArray().apply { add(review) })
        pdf.add("composition", JsonObject().apply {
            addProperty("composition_object", compositionId)
            add("documents", JsonArray().apply { add(document) })
        })
        val raw = root.toString().toByteArray(StandardCharsets.UTF_8)
        val digest = sha256(raw)
        File(objects, digest).writeBytes(raw)
        val index = JsonObject().apply {
            addProperty("schema_version", 3)
            addProperty("bundle_id", digest)
            addProperty("manifest_object", digest)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(setOf(compositionId, packageId, planId), store.missingObjectIds(index, raw))
    }

    @Test
    fun basicReviewPlanCanBeComposedFromDocumentPackages() {
        val initial = publishBundle(
            "docs-sample/tex/source/main.tex", "source\n", "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")),
        )
        val objects = File(staging, "objects")
        fun put(value: JsonObject): String {
            val bytes = value.toString().toByteArray(StandardCharsets.UTF_8)
            return sha256(bytes).also { File(objects, it).writeBytes(bytes) }
        }
        val root = JsonParser.parseString(File(objects, initial).readText()).asJsonObject
        val pdf = root.getAsJsonArray("pdfs")[0].asJsonObject
        val segment = pdf.getAsJsonArray("segments")[0].asJsonObject
        val componentSha = "c".repeat(64)
        segment.addProperty("source_id", "main.tex")
        segment.addProperty("fingerprint", componentSha)
        val region = JsonObject().apply {
            addProperty("page", 1)
            add("rect", JsonArray().apply { listOf(0.1, 0.2, 0.3, 0.4).forEach(::add) })
        }
        val planId = put(JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("pdf_sha256", componentSha)
            add("blocks", JsonArray())
            add("cards", JsonArray().apply { add(JsonObject().apply {
                addProperty("key", "stable-card")
                add("regions", JsonArray().apply { add(region) })
            }) })
        })
        val packageId = put(JsonObject().apply {
            addProperty("kind", "thoughts-document-component-package")
            addProperty("pdf_sha256", componentSha)
            addProperty("page_count", 1)
            addProperty("basic_review_object", planId)
        })
        val document = JsonObject().apply {
            addProperty("source_id", "main.tex")
            addProperty("start_page", 1)
            addProperty("page_count", 1)
            addProperty("component_pdf_sha256", componentSha)
            addProperty("package_sha256", packageId)
        }
        val compositionId = put(JsonObject().apply {
            addProperty("kind", "thoughts-project-composition")
            addProperty("pdf_sha256", pdf.get("pdf_sha256").asString)
            add("documents", JsonArray().apply { add(document) })
        })
        pdf.add("component_review", JsonArray().apply { add(JsonObject().apply {
            addProperty("source_id", "main.tex")
            addProperty("start_page", 1)
            addProperty("page_count", 1)
            addProperty("component_pdf_sha256", componentSha)
            addProperty("basic_review_object", planId)
        }) })
        pdf.add("composition", JsonObject().apply {
            addProperty("composition_object", compositionId)
            add("documents", JsonArray().apply { add(document) })
        })
        val childId = put(root)
        val libraryId = put(JsonObject().apply {
            addProperty("schema_version", 3)
            add("project_manifests", JsonArray().apply { add(JsonObject().apply {
                addProperty("filename", "sample.pdf")
                addProperty("artifact_sha256", childId)
                addProperty("manifest_object", childId)
                addProperty("pdf_stale", false)
            }) })
        })
        val libraryRaw = File(objects, libraryId).readBytes()
        val index = JsonObject().apply {
            addProperty("schema_version", 3)
            addProperty("bundle_id", libraryId)
            addProperty("manifest_object", libraryId)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(emptySet<String>(), store.missingObjectIds(index, libraryRaw))
        assertNotNull(store.installDownloaded(index, emptyMap()))
        val plan = store.basicReviewPlan("sample.pdf", pdf.get("pdf_sha256").asString)
        assertEquals("stable-card", plan?.getAsJsonArray("cards")?.get(0)?.asJsonObject?.get("key")?.asString)
        assertEquals(1, plan?.getAsJsonArray("cards")?.get(0)?.asJsonObject
            ?.getAsJsonArray("regions")?.get(0)?.asJsonObject?.get("page")?.asInt)
    }

    @Test
    fun downloadedBundleReusesVerifiedRetiredObjectsByDigest() {
        val retiredPath = "docs-sample/tex/source/retired.tex"
        val retiredSource = "reusable retired source\n"
        val retiredSha = sha256(retiredSource.toByteArray(StandardCharsets.UTF_8))
        publishBundle(
            "docs-sample/tex/source/current.tex",
            "current source\n",
            "/workspace/current.tex",
            gzip(syncTex("/workspace/current.tex")),
        )
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val retiredObject = File(privateDirectory, "objects/$retiredSha")
        retiredObject.writeText(retiredSource)

        assertTrue(store.pruneActiveBundle())
        assertTrue(!retiredObject.exists())
        assertTrue(File(privateDirectory, "object-cache/$retiredSha").isFile)

        staging.deleteRecursively()
        val nextBundle = publishBundle(
            retiredPath,
            retiredSource,
            "/workspace/retired.tex",
            gzip(syncTex("/workspace/retired.tex")),
        )
        val indexRaw = File(staging, "index.json").readBytes()
        val manifestRaw = File(staging, "objects/$nextBundle").readBytes()
        assertTrue(File(staging, "objects/$retiredSha").delete())

        val missing = store.missingObjectIds(indexRaw, manifestRaw).orEmpty()

        assertTrue(retiredSha !in missing)
    }

    @Test
    fun corruptPrivateObjectIsRequestedAgain() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        val source = "shared source\n"
        publishBundle(sourcePath, source, "/workspace/first.tex", gzip(syncTex("/workspace/first.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        staging.deleteRecursively()
        val sourceSha = sha256(source.toByteArray(StandardCharsets.UTF_8))
        File(privateDirectory, "objects/$sourceSha").writeText("corrupt")

        val secondSyncTex = gzip(syncTex("/workspace/second.tex"))
        val secondBundle = publishBundle(
            sourcePath,
            source,
            "/workspace/second.tex",
            secondSyncTex,
        )
        val indexRaw = File(staging, "index.json").readBytes()
        val manifestRaw = File(staging, "objects/$secondBundle").readBytes()
        File(staging, "objects").listFiles().orEmpty()
            .filter { it.name != secondBundle }
            .forEach(File::delete)

        val missing = store.missingObjectIds(indexRaw, manifestRaw).orEmpty()

        assertTrue(sourceSha in missing)
        assertNull(store.installDownloaded(indexRaw, emptyMap()))
    }

    @Test
    fun failedConcurrentWorkingWriteKeepsActiveBundleAndCanBeRetried() {
        val sourcePath = "docs-sample/tex/source/main.tex"
        publishBundle(
            sourcePath, "original source\n", "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")), sharedStyle = "original style\n",
        )
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val activeIndex = File(privateDirectory, "active-index.json")
        val originalIndex = activeIndex.readText()
        staging.deleteRecursively()
        val nextId = publishBundle(
            sourcePath, "updated source\n", "/workspace/main.tex",
            gzip(syncTex("/workspace/main.tex")), sharedStyle = "updated style\n",
        )
        val indexRaw = File(staging, "index.json").readBytes()
        val workingSource = File(working, sourcePath)
        assertTrue(workingSource.delete())
        assertTrue(workingSource.mkdir())
        File(workingSource, "block-replacement").writeText("blocked")

        assertNull(store.installDownloaded(indexRaw, emptyMap()))
        assertEquals(originalIndex, activeIndex.readText())

        assertTrue(workingSource.deleteRecursively())
        assertEquals(nextId, store.installDownloaded(indexRaw, emptyMap())?.bundleId)
        assertEquals("updated source\n", workingSource.readText())
        assertEquals("updated style\n", File(working, "docs-paper/tex/support/manual_style.sty").readText())
    }

    private fun writeStaleState(sourcePath: String, sourceSha: String) {
        val states = File(privateDirectory, "working").also(File::mkdirs)
        File(states, sha256(sourcePath.toByteArray()) + ".json").writeText(JsonObject().apply {
            addProperty("source_file", sourcePath)
            addProperty("bundle_id", "b".repeat(64))
            addProperty("base_sha256", sourceSha)
            addProperty("base_object", sourceSha)
            addProperty("pdf_filename", "sample.pdf")
            addProperty("pdf_sha256", "a".repeat(64))
            addProperty("draft_revision", 0)
            addProperty("last_queued_sha256", sourceSha)
        }.toString())
    }

    private fun stateFiles(): List<File> =
        File(privateDirectory, "working").listFiles().orEmpty().filter(File::isFile)

    @Test
    fun htmlAttachmentSurvivesRestartAndUsesVerifiedDownloadedBytes() {
        val path = "docs-sample/tex/source/tools/plot.html"
        val html = "<!doctype html><button onclick=\"this.textContent=42\">Calculate</button>"
        publishBundle("docs-sample/tex/source/main.tex", "source", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")),
            sharedStyle = html, mutateSharedStyleResource = {
                it.addProperty("kind", "html_attachment")
                it.addProperty("path", path)
            })
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        staging.deleteRecursively()
        File(working, path).apply { parentFile.mkdirs(); writeText("unsynced edit") }
        val restarted = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertEquals(html, restarted.htmlAttachment(path, "sample.pdf")?.readText(Charsets.UTF_8))
        assertEquals(html, restarted.htmlAttachment(path, null)?.readText(Charsets.UTF_8))
        assertNull(restarted.htmlAttachment(path, "other.pdf"))
        File(privateDirectory, "objects/${sha256(html.toByteArray())}").writeText("corrupt")
        assertNull(OfflineSourceBundleStore(staging, working, privateDirectory).htmlAttachment(path, "sample.pdf"))
    }

    private fun missingMarker(id: String, file: String = "docs-paper/tex/source/main.tex") =
        MissingCommentMarker(id, file, 0, "", null, "anchor", listOf("sample.pdf"), MissingCommentMarker.STATE_UNKNOWN)

    private fun storedComment(id: String, deleted: Boolean, revision: Long = 3, extra: JsonObject.() -> Unit = {}) = JsonObject().apply {
        addProperty("schema_version", 1); addProperty("comment_id", id); addProperty("thread_root_id", id)
        addProperty("revision", revision); addProperty("deleted", deleted); addProperty("subtype", "Text")
        addProperty("updated_at", "2026-10-10T10:00:00.000Z"); addProperty("contents", "Old text of $id")
        add("origin", JsonObject().apply { addProperty("collection", "sample"); addProperty("pdf_sha256", "a".repeat(64)); addProperty("page_index", 2) })
        add("scope", JsonObject().apply { addProperty("state", "source_linked"); addProperty("anchor_id", id) })
        extra()
    }

    @Test
    fun commentMissingDetectionReadsTheSourceIndexAndRemoveMarkerVerifiesAndRollsBack() = kotlinx.coroutines.runBlocking {
        val path = "docs-paper/tex/source/main.tex"
        val text = "Intro.\nText before \\CommentAnchor{dead}{Deleted comment}{}%\n\\CommentAnchor{unknown}{Unknown}{}%\n" +
            "\\CommentAnchor{live}{Live}{}%\nWords \\CommentHighlight{hl}{Note}{kept words} end.\nEnd.\n"
        publishBundle(path, text, "/workspace/$path", gzip(syncTex("/workspace/$path")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        val comments = CommentMissingStore(true, listOf(storedComment("live", false), storedComment("dead", true)))
        var saves = 0
        var corruptNextSave = false
        val resolver = MissingCommentResolver(store, { snapshot, filename, hash, contents ->
            assertEquals("sample.pdf", filename); assertEquals("a".repeat(64), hash)
            saves++
            // A write that also loses another marker must be detected and undone.
            val written = if (corruptNextSave) contents.replace("\\CommentAnchor{live}{Live}{}%\n", "") else contents
            corruptNextSave = false
            assertTrue(store.writeWorkingContents(snapshot.sourceFile, written))
        }, { comments }, { null }, { error("Remove marker never queues a comment operation") })
        assertEquals(MissingCommentResolver.Code.SOURCES_UNAVAILABLE, resolver.remove(missingMarker("dead")).code)
        assertNotNull(store.importLatest())
        val file = File(working, path)

        // Detection over the index the source worker builds, fresh and from the saved scan.
        val bundle = store.librarySourceMetadata()!!
        val local = LocalSourceComments(store, { _, _, _, _ -> error("unused") }, File(root, "pdfs"),
            { com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability.SOURCE_LINKED }, { _, _ -> 600f })
        val index = local.annotationIndex(bundle.trackedSourcePaths())
        val detected = CommentMissingState.detect(comments, index, CommentMissingState.targetsByPath(bundle))
        assertEquals(setOf("dead", "unknown", "hl"), detected.map { it.anchorId }.toSet())
        assertEquals(listOf("sample.pdf"), detected.first().targets)
        assertEquals(MissingCommentMarker.STATE_DELETED, detected.single { it.anchorId == "dead" }.state)
        store.annotationIndex.install(index)
        val remembered = LocalSourceComments(store, { _, _, _, _ -> error("unused") }, File(root, "pdfs"),
            { com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability.SOURCE_LINKED }, { _, _ -> 600f })
            .annotationIndex(bundle.trackedSourcePaths())
        assertEquals(detected, CommentMissingState.detect(comments, remembered, CommentMissingState.targetsByPath(bundle)))
        assertTrue(CommentMissingState.detect(comments.copy(loaded = false), index, CommentMissingState.targetsByPath(bundle)).isEmpty())

        // Remove Marker: text before keeps its '%', a lone marker takes its line; refusals write nothing.
        assertEquals(MissingCommentResolver.Code.REMOVED, resolver.remove(missingMarker("dead")).code)
        assertEquals(MissingCommentResolver.Code.REMOVED, resolver.remove(missingMarker("unknown")).code)
        assertEquals(2, saves)
        assertEquals(MissingCommentResolver.Code.EXISTS_AGAIN, resolver.remove(missingMarker("live")).code)
        assertEquals(MissingCommentResolver.Code.NOT_MANAGED, resolver.remove(missingMarker("x", "docs-paper/tex/generated/table.tex")).code)
        assertEquals(MissingCommentResolver.Code.NOT_MANAGED, resolver.remove(missingMarker("x", "docs-paper/tex/source/other.tex")).code)
        assertEquals(MissingCommentResolver.Code.MARKER_GONE, resolver.remove(missingMarker("dead")).code)
        assertEquals(MissingCommentResolver.Code.NO_ID, resolver.remove(missingMarker("")).code)
        assertEquals(2, saves)
        assertEquals("Intro.\nText before %\n\\CommentAnchor{live}{Live}{}%\nWords \\CommentHighlight{hl}{Note}{kept words} end.\nEnd.\n", file.readText())
        store.writeWorkingContents(path, file.readText() + "\\CommentAnchor{twice}{A}{}%\n\\CommentAnchor{twice}{B}{}%\n")
        val duplicated = resolver.remove(missingMarker("twice"))
        assertEquals(MissingCommentResolver.Code.MARKER_DUPLICATED, duplicated.code)
        assertEquals(2, duplicated.count)

        // A write that fails verification is rolled back to the original text.
        val before = file.readText()
        corruptNextSave = true
        assertEquals(MissingCommentResolver.Code.NOT_VERIFIED, resolver.remove(missingMarker("hl")).code)
        assertEquals(4, saves)
        assertEquals(before, file.readText())
        // A highlight keeps its words.
        assertEquals(MissingCommentResolver.Code.REMOVED, resolver.remove(missingMarker("hl")).code)
        assertTrue(file.readText().contains("Words kept words end.\n"))
    }

    @Test
    fun commentMissingRestoreUndeletesOrCreatesWithoutEditingTheSource() = kotlinx.coroutines.runBlocking {
        val path = "docs-paper/tex/source/main.tex"
        val text = "Intro.\nWords \\CommentHighlight{hl}{Highlight note}{kept words} end.\n\\CommentAnchor{unknown2}{Recreate me}{}%\n" +
            "\\CommentAnchor{docgone}{Document deleted}{}%\n\\CommentAnchor{norev}{No revision}{}%\n" +
            "\\CommentAnchor{twice}{A}{}%\n\\CommentAnchor{twice}{B}{}%\nEnd.\n"
        publishBundle(path, text, "/workspace/$path", gzip(syncTex("/workspace/$path")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        val documentId = "8f0c4a8e-5b7d-4c1e-9a3f-2d6b1e7c9a10"
        val identities = mapOf("sample.pdf" to JsonObject().apply { addProperty("document_id", documentId); addProperty("pdf_sha256", "c".repeat(64)) })
        var comments = CommentMissingStore(true, listOf(
            storedComment("hl", true) {
                addProperty("subtype", "Highlight"); addProperty("selected_text", "old words")
                addProperty("deletion_reason", "canonical_source_marker_removed")
            },
            storedComment("docgone", true) { addProperty("deletion_reason", "document_deleted") },
            storedComment("norev", true, revision = 0),
        ))
        val enqueued = mutableListOf<JsonObject>()
        var failEnqueue = false
        fun resolver(identity: (String) -> JsonObject? = identities::get) = MissingCommentResolver(store,
            { _, _, _, _ -> error("Restore never edits the source") }, { comments }, identity,
            { payload -> check(!failEnqueue) { "queue unavailable" }; enqueued += payload },
            now = { "2026-10-12T10:00:00.000Z" }, author = "Tester")
        assertEquals(MissingCommentResolver.Code.SOURCES_UNAVAILABLE, resolver().restore(missingMarker("hl")).code)
        assertNotNull(store.importLatest())

        // Undelete: the same comment, base revision 3, from the marker as it is now.
        assertEquals(MissingCommentResolver.Code.RESTORED, resolver().restore(missingMarker("hl")).code)
        val undeleted = enqueued.single()
        assertEquals(4, undeleted.get("revision").asInt)
        assertFalse(undeleted.get("deleted").asBoolean)
        assertFalse(undeleted.has("deletion_reason"))
        assertEquals("Highlight note", undeleted.get("contents").asString)
        assertEquals("kept words", undeleted.get("selected_text").asString)
        assertEquals("Highlight", undeleted.get("subtype").asString)
        assertEquals(2, undeleted.getAsJsonObject("origin").get("page_index").asInt)
        assertEquals(JsonObject().apply {
            addProperty("state", "source_linked"); addProperty("anchor_id", "hl"); addProperty("source_file", path)
            addProperty("source_line", 2); addProperty("reason", "unique_source_marker")
        }, undeleted.getAsJsonObject("scope"))

        // Create: an unknown id becomes a client-v1 root with the marker's id on the Library document.
        assertEquals(MissingCommentResolver.Code.NOT_IN_LIBRARY, resolver { null }.restore(missingMarker("unknown2")).code)
        assertEquals(MissingCommentResolver.Code.RECREATED, resolver().restore(missingMarker("unknown2")).code)
        val created = enqueued.last()
        assertEquals("unknown2", created.get("comment_id").asString)
        assertEquals(1, created.get("revision").asInt)
        assertEquals("Recreate me", created.get("contents").asString)
        assertEquals("Tester", created.get("author").asString)
        assertEquals("client-v1", created.get("source_authority").asString)
        assertEquals("sample", created.getAsJsonObject("origin").get("collection").asString)
        assertEquals(documentId, created.getAsJsonObject("origin").get("document_id").asString)
        assertEquals("c".repeat(64), created.getAsJsonObject("origin").get("pdf_sha256").asString)
        assertEquals(3, created.getAsJsonObject("scope").get("source_line").asInt)

        // Refusals, each with its reason; nothing is queued.
        val queued = enqueued.size
        assertEquals(MissingCommentResolver.Code.DOCUMENT_DELETED, resolver().restore(missingMarker("docgone")).code)
        assertEquals(MissingCommentResolver.Code.NO_REVISION, resolver().restore(missingMarker("norev")).code)
        assertEquals(MissingCommentResolver.Code.MARKER_GONE, resolver().restore(missingMarker("gone")).code)
        val duplicated = resolver().restore(missingMarker("twice"))
        assertEquals(MissingCommentResolver.Code.MARKER_DUPLICATED, duplicated.code)
        assertEquals(2, duplicated.count)
        assertEquals(MissingCommentResolver.Code.INVALID_ID, resolver().restore(missingMarker("a/b")).code)
        assertEquals(MissingCommentResolver.Code.NOT_MANAGED, resolver().restore(missingMarker("x", "docs-paper/tex/generated/t.tex")).code)
        assertEquals(queued, enqueued.size)
        // Restoring twice is refused once the comment exists (the outbox counts).
        comments = comments.copy(logicalComments = comments.logicalComments + enqueued)
        assertEquals(MissingCommentResolver.Code.ALREADY_EXISTS, resolver().restore(missingMarker("hl")).code)
        assertEquals(MissingCommentResolver.Code.ALREADY_EXISTS, resolver().restore(missingMarker("unknown2")).code)
        failEnqueue = true
        comments = comments.copy(logicalComments = comments.logicalComments.filter { it.get("comment_id").asString != "unknown2" })
        assertEquals(MissingCommentResolver.Code.SAVE_FAILED, resolver().restore(missingMarker("unknown2")).code)
        // The source is never edited by Restore.
        assertEquals(text, File(working, path).readText())
    }

    private fun publishBundle(
        sourcePath: String,
        source: String,
        synctexPath: String,
        synctex: ByteArray,
        proposal: JsonObject? = null,
        schemaVersion: Int = 3,
        review: JsonObject? = null,
        correspondence: JsonObject? = null,
        sharedStyle: String? = null,
        sharedStylePdfFilenames: List<String> = listOf("sample.pdf"),
        mutateSharedStyleResource: ((JsonObject) -> Unit)? = null,
        duplicateSharedStyleResource: Boolean = false,
        ignoredMetadata: String? = null,
        pdfSha256: String = "a".repeat(64),
    ): String {
        val objects = File(staging, "objects").also(File::mkdirs)
        val sourceRaw = source.toByteArray(StandardCharsets.UTF_8)
        val sourceSha = sha256(sourceRaw)
        val synctexSha = sha256(synctex)
        File(objects, sourceSha).writeBytes(sourceRaw)
        File(objects, synctexSha).writeBytes(synctex)
        val input = JsonObject().apply {
            addProperty("synctex_path", synctexPath)
            addProperty("source_file", sourcePath)
            addProperty("source_sha256", sourceSha)
        }
        val segment = JsonObject().apply {
            addProperty("start_page", 1)
            addProperty("page_count", 1)
            addProperty("source_available", true)
            addProperty("primary_source_file", sourcePath)
            // Legacy lookup fixture; native SyncTeX is exercised on Android.
            addProperty("lookup_object", synctexSha)
            addProperty("lookup_sha256", synctexSha)
            add("inputs", JsonArray().apply { add(input) })
        }
        val pdf = JsonObject().apply {
            addProperty("filename", "sample.pdf")
            addProperty("pdf_sha256", pdfSha256)
            addProperty("page_count", 1)
            add("segments", JsonArray().apply { add(segment) })
        }
        val sourceEntry = JsonObject().apply {
            addProperty("path", sourcePath)
            addProperty("sha256", sourceSha)
            addProperty("object", sourceSha)
            addProperty("bytes", sourceRaw.size)
        }
        val resourceEntries = JsonArray()
        sharedStyle?.let { contents ->
            val raw = contents.toByteArray(StandardCharsets.UTF_8)
            val digest = sha256(raw)
            File(objects, digest).writeBytes(raw)
            val resource = JsonObject().apply {
                addProperty("kind", "project_style")
                addProperty("path", "docs-paper/tex/support/manual_style.sty")
                addProperty("sha256", digest)
                addProperty("object", digest)
                addProperty("bytes", raw.size)
                add("pdf_filenames", JsonArray().apply {
                    sharedStylePdfFilenames.forEach(::add)
                })
            }
            mutateSharedStyleResource?.invoke(resource)
            resourceEntries.add(resource)
            if (duplicateSharedStyleResource) resourceEntries.add(resource.deepCopy())
        }
        val manifest = JsonObject().apply {
            addProperty("schema_version", schemaVersion)
            ignoredMetadata?.let { addProperty("unneeded_metadata", it) }
            add("pdfs", JsonArray().apply {
                add(pdf)
                sharedStylePdfFilenames
                    .filter { it != "sample.pdf" }
                    .distinct()
                    .forEach { filename ->
                        add(pdf.deepCopy().apply {
                            addProperty("filename", filename)
                            addProperty("pdf_sha256", "b".repeat(64))
                        })
                    }
            })
            add("sources", JsonArray().apply { add(sourceEntry) })
            if (resourceEntries.size() > 0) add("resources", resourceEntries)
            proposal?.let { add("agent_proposals", JsonArray().apply { add(it) }) }
            review?.let {
                addProperty("review_comments_schema_version", 1)
                add("review_comments", JsonArray().apply { add(it) })
                add("review_comment_documents", JsonArray().apply { add("sample.pdf") })
            }
            correspondence?.let {
                addProperty("review_correspondence_schema_version", 2)
                add("review_correspondences", JsonArray().apply { add(it) })
            }
        }.toString().plus("\n").toByteArray(StandardCharsets.UTF_8)
        val bundleId = sha256(manifest)
        File(objects, bundleId).writeBytes(manifest)
        File(staging, "index.json").writeText(JsonObject().apply {
            addProperty("schema_version", schemaVersion)
            addProperty("bundle_id", bundleId)
            addProperty("manifest_object", bundleId)
        }.toString())
        return bundleId
    }

    private fun proposalQuad(filename: String, left: Float, right: Float) = JsonObject().apply {
        addProperty("pdf_filename", filename)
        addProperty("page", 1)
        addProperty("left", left)
        addProperty("top", 20f)
        addProperty("right", right)
        addProperty("bottom", 32f)
    }

    private fun syncTex(path: String) = """
        SyncTeX Version:1
        Input:1:$path
        Output:pdf
        Magnification:1000
        Unit:1
        X Offset:0
        Y Offset:0
        Content:
        {1
        [1,1:6578176,6578176:6578176,657818,0
        }
    """.trimIndent() + "\n"

    private fun gzip(value: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(value.toByteArray(StandardCharsets.UTF_8)) }
        return output.toByteArray()
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun allocatedBytes(): Long =
        (java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean)
            .getThreadAllocatedBytes(Thread.currentThread().id)

    private fun measuredPrune(store: OfflineSourceBundleStore, events: MutableList<com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnosticEvent>): Pair<Long, Long> {
        val diagnostics = com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnostics(write = { events.add(it) }, memory = { emptyMap() })
        val allocated = allocatedBytes()
        val started = System.nanoTime()
        assertTrue(store.pruneActiveBundle(diagnostics = diagnostics))
        return (System.nanoTime() - started) / 1_000_000 to (allocatedBytes() - allocated)
    }

    /** Opt-in: THOUGHTS_CLEANUP_BENCHMARK=1 ./gradlew testSandboxUnitTest --tests '*retainedObjectCleanupBenchmark*' */
    @Test
    fun retainedObjectCleanupBenchmark() {
        org.junit.Assume.assumeTrue(System.getenv("THOUGHTS_CLEANUP_BENCHMARK") == "1")
        val documents = System.getenv("THOUGHTS_CLEANUP_DOCUMENTS")?.toInt() ?: 250
        publishBundle("current/main.tex", "current\n", "/workspace/current.tex", gzip(syncTex("/workspace/current.tex")))
        val synthetic = SourceCleanupSyntheticStore(root, privateDirectory, documents, planBytes = 240_000)
        val store = OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = synthetic.reader)
        assertNotNull(store.importLatest())
        val events = mutableListOf<com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnosticEvent>()
        val lines = mutableListOf("documents=$documents json_mb=${synthetic.jsonBytes / 1_000_000} objects=${File(privateDirectory, "objects").list()!!.size} cache=${File(privateDirectory, "object-cache").list()!!.size}")
        for (run in 1..3) {
            synthetic.put(File(privateDirectory, "objects"), "orphan $run".toByteArray())
            if (run == 3) restartCleanupProcess()
            val (millis, bytes) = measuredPrune(OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = synthetic.reader), events)
            lines += "run=$run prune_ms=$millis allocated_mb=${bytes / 1_000_000}"
        }
        lines += events.filter { it.state == "finished" && it.stage.startsWith("source_cleanup") }
            .takeLast(6).map { "${it.stage} ${(it.values["duration_ns"] as Long) / 1_000_000} ms" }
        lines += events.filter { it.state == "counters" }.takeLast(3).map { "${it.stage} ${it.values}" }
        File(System.getenv("THOUGHTS_CLEANUP_BENCHMARK_OUT") ?: "/tmp/thoughts-cleanup-benchmark.txt").writeText(lines.joinToString("\n") + "\n")
    }

    /** A new process keeps only what the store persisted. */
    private fun restartCleanupProcess() = ObjectReferenceIndex.forgetLoadedIndexes()

    private fun syntheticCleanupStore(documents: Int, planBytes: Int): Pair<SourceCleanupSyntheticStore, OfflineSourceBundleStore> {
        publishBundle("current/main.tex", "current\n", "/workspace/current.tex", gzip(syncTex("/workspace/current.tex")))
        val synthetic = SourceCleanupSyntheticStore(root, privateDirectory, documents, planBytes)
        val store = OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = synthetic.reader)
        assertNotNull(store.importLatest())
        return synthetic to store
    }

    private fun assertRetainedExactly(synthetic: SourceCleanupSyntheticStore, expected: Set<String>) {
        for (hash in synthetic.graphObjects + synthetic.orphans) {
            assertEquals(hash, hash in expected, File(privateDirectory, "objects/$hash").isFile)
            if (hash !in expected) assertTrue(File(privateDirectory, "object-cache/$hash").isFile)
        }
    }

    private fun retainedWork(events: List<com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnosticEvent>): Map<String, Any> =
        events.last { it.stage == "source_cleanup_retained_objects_work" }.values

    @Test
    fun retainedObjectCleanupKeepsTheLegacyClosureAndReadsEachObjectOnce() {
        val (synthetic, _) = syntheticCleanupStore(documents = 40, planBytes = 100_000)
        val legacyAllocated = allocatedBytes()
        val expected = synthetic.legacyClosure(emptySet())
        val legacyBytes = allocatedBytes() - legacyAllocated
        // Sanity: the fixture reaches every graph object (one hop through plans, two to fonts)
        // and none of the orphans.
        assertEquals(synthetic.graphObjects, synthetic.graphObjects.filterTo(linkedSetOf()) { it in expected })
        assertTrue(synthetic.orphans.none { it in expected })

        val events = mutableListOf<com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnosticEvent>()
        measuredPrune(OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = synthetic.reader), events)
        assertRetainedExactly(synthetic, expected)
        assertEquals(120L, retainedWork(events)["json_parsed"]) // 40 packages, 40 plans, 40 evidence objects
        assertEquals(40L, retainedWork(events)["packages"])

        // Next Sync, new process: only the persisted index is left. Nothing is parsed again.
        synthetic.orphans += synthetic.put(File(privateDirectory, "objects"), "orphan after restart".toByteArray())
        restartCleanupProcess()
        val (_, allocated) = measuredPrune(OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = synthetic.reader), events)
        assertRetainedExactly(synthetic, expected)
        assertEquals(0L, retainedWork(events)["json_parsed"])
        // The earlier closure alone allocated about 13 bytes per JSON byte; the whole cleanup now
        // allocates for the digests it visits, not for the bytes of the objects that name them.
        assertTrue("allocated $allocated, legacy closure $legacyBytes", allocated * 4 < legacyBytes)

        // A lost index only costs time: the result is identical.
        File(privateDirectory, "object-references-v1.bin").delete()
        restartCleanupProcess()
        synthetic.orphans += synthetic.put(File(privateDirectory, "objects"), "orphan after lost index".toByteArray())
        measuredPrune(OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = synthetic.reader), events)
        assertRetainedExactly(synthetic, expected)
        assertEquals(120L, retainedWork(events)["json_parsed"])
    }

    @Test
    fun retainedObjectCleanupNeverCachesFactsFromADamagedObject() {
        val (synthetic, _) = syntheticCleanupStore(documents = 2, planBytes = 1_000)
        val objects = File(privateDirectory, "objects")
        val extra = synthetic.put(objects, "named only by a damaged plan".toByteArray())
        // A plan whose bytes do not match its name still names [extra] this time, as before.
        val plan = synthetic.graphObjects.first { File(objects, it).isFile && File(objects, it).readText().contains("basic_source_passages") }
        val planFile = File(objects, plan)
        val original = planFile.readText()
        planFile.writeText(original.replaceFirst("\"passages\"", "\"extra\":\"$extra\",\"passages\""))
        val events = mutableListOf<com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnosticEvent>()
        measuredPrune(OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = synthetic.reader), events)
        assertTrue(File(objects, extra).isFile)
        restartCleanupProcess()
        assertNull(ObjectReferenceIndex.at(File(privateDirectory, "object-references-v1.bin")).cached(plan))

        // Restored bytes are verified and indexed; [extra] is then an ordinary orphan.
        planFile.writeText(original)
        synthetic.put(objects, "invalidate the cleanup receipt".toByteArray())
        measuredPrune(OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = synthetic.reader), events)
        assertTrue(!File(objects, extra).exists())
        assertNotNull(ObjectReferenceIndex.at(File(privateDirectory, "object-references-v1.bin")).cached(plan))
    }

    @Test
    fun retainedObjectCleanupAbortsWhenARetainedPackageIsDamaged() {
        val (synthetic, _) = syntheticCleanupStore(documents = 3, planBytes = 1_000)
        val previous = File(root, "thoughts/.thoughts-library-previous/objects")
        previous.listFiles()!!.first().appendText(" ")
        val events = mutableListOf<com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnosticEvent>()
        measuredPrune(OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = synthetic.reader), events)
        // Incomplete evidence: nothing is archived, not even orphans.
        assertTrue(synthetic.orphans.all { File(privateDirectory, "objects/$it").isFile })
    }

    @Test
    fun retainedObjectCleanupYieldsBetweenNewObjects() {
        val (synthetic, _) = syntheticCleanupStore(documents = 5, planBytes = 1_000)
        var yields = 0
        assertTrue(OfflineSourceBundleStore(staging, working, privateDirectory, readerIndex = synthetic.reader)
            .pruneActiveBundle(cooperate = { yields++ }))
        // Between stages, and after each of the 10 newly read plans and evidence objects.
        assertTrue("yields=$yields", yields >= 10)
    }

    @Test
    fun cleanupInventorySignatureIsUnchangedSoExistingReceiptsStayValid() {
        val (synthetic, store) = syntheticCleanupStore(documents = 3, planBytes = 1_000)
        File(working, "docs/nested/main.tex").apply { parentFile.mkdirs(); writeText("draft") }
        assertTrue(store.pruneActiveBundle())
        val digest = MessageDigest.getInstance("SHA-256")
        val roots = listOf(File(privateDirectory, "objects"), File(privateDirectory, "object-cache"), File(privateDirectory, "working"), working)
        for ((index, directory) in roots.withIndex()) {
            if (!directory.exists()) continue
            directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).path }.forEach { file ->
                digest.update("$index\u0000${file.relativeTo(directory).path}\u0000${file.length()}\u0000${file.lastModified()}\n".toByteArray(StandardCharsets.UTF_8))
            }
        }
        val receipt = JsonParser.parseString(File(privateDirectory, "cleanup-receipt.json").readText()).asJsonObject
        assertEquals(digest.digest().joinToString("") { "%02x".format(it) }, receipt.get("inventory_sha256").asString)
        assertTrue(synthetic.graphObjects.isNotEmpty())
    }

    // ---- The working copy in the shared folder: a change another app made waits for the user ----

    private val sharedPath = "docs-sample/tex/source/main.tex"

    /** A store whose working copy is where other apps can write, and the queue Sync uses with it. */
    private fun sharedFolderFixture(base: String = "base line\n"): Pair<OfflineSourceBundleStore, SourceEditQueue> {
        publishBundle(sharedPath, base, "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory, workingCopy = {
            object : SourceWorkingCopy {
                override val directory = working
                override val shared = true
            }
        })
        assertNotNull(store.importLatest())
        return store to SourceEditQueue(File(root, "requests"), File(root, "receipts"), File(root, "cache"), {}, {}, store)
    }

    private fun queuedContents(queue: SourceEditQueue): List<String> = queue.pendingSourceMutations().map { mutation ->
        JsonParser.parseString(File(root, "requests/${mutation.requestId}.json").readText()).asJsonObject.get("contents").asString
    }

    @Test
    fun aChangeAnotherAppMadeInTheSharedFolderIsHeldUntilTheUserUploadsIt() {
        val (store, queue) = sharedFolderFixture()
        File(working, sharedPath).writeText("base line\nadded by another app\n")

        // Sync's scan finds it and queues nothing, however often it runs.
        assertEquals(0, queue.captureExternalEdits())
        assertEquals(0, queue.captureExternalEdits())
        assertTrue(queue.pendingSourceMutations().isEmpty())
        assertTrue(!queue.hasPendingSave())
        val held = queue.heldOutsideChanges().single()
        assertEquals(sharedPath, held.sourceFile)
        assertEquals(sha256("base line\nadded by another app\n".toByteArray()), held.contentsSha256)
        assertEquals(1, held.addedLines)
        assertEquals(0, held.removedLines)
        assertTrue(store.isOutsideContent(sharedPath, held.contentsSha256))

        // The user looks at it and chooses Upload: only now it is queued, exactly as shown.
        assertTrue(queue.uploadOutsideChange(held))
        assertEquals(listOf("base line\nadded by another app\n"), queuedContents(queue))
        assertTrue(queue.heldOutsideChanges().isEmpty())
        assertEquals(0, queue.captureExternalEdits())
        assertEquals("base line\nadded by another app\n", File(working, sharedPath).readText())
    }

    @Test
    fun whatThoughtsItselfWroteInTheSharedFolderIsQueuedWithoutAsking() {
        val (store, queue) = sharedFolderFixture()
        // Written through the app but not queued yet (the scan is what queues such a change).
        assertTrue(store.writeWorkingContents(sharedPath, "written in Thoughts\n"))

        assertTrue(queue.heldOutsideChanges().isEmpty())
        assertEquals(1, queue.captureExternalEdits())
        assertEquals(listOf("written in Thoughts\n"), queuedContents(queue))
    }

    @Test
    fun discardingAnOutsideChangePutsBackTheSyncedVersion() {
        val (store, queue) = sharedFolderFixture()
        File(working, sharedPath).writeText("replaced by another app\n")
        val held = queue.heldOutsideChanges().single()

        assertTrue(queue.discardOutsideChange(held))

        assertEquals("base line\n", File(working, sharedPath).readText())
        assertTrue(queue.heldOutsideChanges().isEmpty())
        assertEquals(0, queue.captureExternalEdits())
        assertTrue(queue.pendingSourceMutations().isEmpty())
        // The discarded text is not lost.
        assertEquals(listOf("replaced by another app\n"), mergeRecoveries(sharedPath, "outside"))
        assertTrue(store.externalEdits().isEmpty())
    }

    @Test
    fun discardingAnOutsideChangeKeepsTheEditQueuedInThoughts() {
        val (store, queue) = sharedFolderFixture()
        val snapshot = requireNotNull(store.sourceSnapshot(sharedPath, 1))
        queue.saveForNextSync(snapshot, "sample.pdf", "a".repeat(64), "base line\nedited in Thoughts\n")
        File(working, sharedPath).writeText("base line\nedited in Thoughts\nand then by another app\n")

        assertEquals(0, queue.captureExternalEdits())
        val held = queue.heldOutsideChanges().single()
        assertTrue(queue.discardOutsideChange(held))

        // Back to what Thoughts wrote, not to the older synced version; its queued save is untouched.
        assertEquals("base line\nedited in Thoughts\n", File(working, sharedPath).readText())
        assertEquals(listOf("base line\nedited in Thoughts\n"), queuedContents(queue))
        assertTrue(queue.heldOutsideChanges().isEmpty())
        assertEquals(0, queue.captureExternalEdits())
        assertEquals(listOf("base line\nedited in Thoughts\n"), queuedContents(queue))
    }

    @Test
    fun aDecisionAboutAnOutsideChangeAppliesOnlyToTheTextTheUserSaw() {
        val (_, queue) = sharedFolderFixture()
        File(working, sharedPath).writeText("first outside version\n")
        val seen = queue.heldOutsideChanges().single()
        File(working, sharedPath).writeText("second outside version, never shown\n")

        assertTrue(!queue.uploadOutsideChange(seen))
        assertTrue(!queue.discardOutsideChange(seen))

        assertEquals("second outside version, never shown\n", File(working, sharedPath).readText())
        assertTrue(queue.pendingSourceMutations().isEmpty())
        assertEquals(sha256("second outside version, never shown\n".toByteArray()), queue.heldOutsideChanges().single().contentsSha256)
    }

    @Test
    fun anOutsideChangeMergedWithAServerChangeIsStillHeld() {
        val (_, queue) = sharedFolderFixture("first\nsecond\nthird\n")
        File(working, sharedPath).writeText("first by another app\nsecond\nthird\n")
        publishBundle(sharedPath, "first\nsecond\nthird on the server\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))

        // Sync installs the server's change next to the outside one; that does not make it the user's.
        assertEquals(0, queue.captureExternalEdits())
        assertEquals("first by another app\nsecond\nthird on the server\n", File(working, sharedPath).readText())
        assertTrue(queue.pendingSourceMutations().isEmpty())
        assertEquals(sha256("first by another app\nsecond\nthird on the server\n".toByteArray()), queue.heldOutsideChanges().single().contentsSha256)
    }

    @Test
    fun thoughtsOwnChangeMergedWithAServerChangeIsQueuedAsBefore() {
        val (store, queue) = sharedFolderFixture("first\nsecond\nthird\n")
        assertTrue(store.writeWorkingContents(sharedPath, "first in Thoughts\nsecond\nthird\n"))
        publishBundle(sharedPath, "first\nsecond\nthird on the server\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))

        assertEquals(1, queue.captureExternalEdits())
        assertEquals(listOf("first in Thoughts\nsecond\nthird on the server\n"), queuedContents(queue))
        assertTrue(queue.heldOutsideChanges().isEmpty())
    }

    @Test
    fun privateStorageHoldsNothingAndKnowsWhichTextIsNotItsOwn() {
        // The same direct write, with the working copy where no other app can reach it.
        publishBundle(sharedPath, "base line\n", "/workspace/main.tex", gzip(syncTex("/workspace/main.tex")))
        val store = OfflineSourceBundleStore(staging, working, privateDirectory)
        assertNotNull(store.importLatest())
        val queue = SourceEditQueue(File(root, "requests"), File(root, "receipts"), File(root, "cache"), {}, {}, store)
        File(working, sharedPath).writeText("changed in place\n")

        assertTrue(queue.heldOutsideChanges().isEmpty())
        assertEquals(1, queue.captureExternalEdits())
        // What a move into private storage asks about each file: the synced text and the app's own are not outside.
        assertTrue(!store.isOutsideContent(sharedPath, sha256("base line\n".toByteArray())))
        assertTrue(!store.isOutsideContent(sharedPath, sha256("changed in place\n".toByteArray())))
        assertTrue(store.isOutsideContent(sharedPath, sha256("something else\n".toByteArray())))
        assertTrue(!store.isOutsideContent("docs-sample/tex/source/untracked.tex", sha256("anything\n".toByteArray())))
    }

    @Test
    fun changedLinesCountsWhatEachSideHasAlone() {
        assertEquals(0 to 0, changedLines("a\nb\n", "a\nb\n"))
        assertEquals(1 to 0, changedLines("a\nb\n", "a\nb\nc\n"))
        assertEquals(0 to 1, changedLines("a\nb\n", "a\n"))
        assertEquals(1 to 1, changedLines("a\nb\n", "a\nB\n"))
        assertEquals(1 to 0, changedLines("a\n", "a\na\n"))
    }
}

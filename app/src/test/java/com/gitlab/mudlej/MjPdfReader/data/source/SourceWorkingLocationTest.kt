// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SourceWorkingLocationTest {
    private lateinit var root: File
    private lateinit var privateFolder: File
    private lateinit var sharedFolder: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("source-working-location").toFile()
        privateFolder = File(root, "data/files/source-editor/working")
        sharedFolder = File(root, "storage/Documents/Thoughts Sources")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(folder: File, path: String, text: String) = File(folder, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun tree(folder: File): Map<String, String> = folder.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(folder).invariantSeparatorsPath to it.readText() }

    private val sources = mapOf(
        "docs-paper/tex/source/main.tex" to "\\section{Intro}\n",
        "docs-paper/tex/support/manual_style.sty" to "\\ProvidesPackage{manual_style}\n",
        "default/notes/idea.md" to "# Idea\n",
    )

    // ---- Where an installation keeps its working copy ----

    @Test
    fun aNewInstallationKeepsItsSourcesPrivate() {
        assertEquals(SourceWorkingMode.PRIVATE, SourceWorkingLocation.decide(sandbox = false, saved = null, hasWorkingCopy = false))
    }

    @Test
    fun anInstallationFromBeforeTheSettingKeepsTheSharedFolderItUses() {
        // It has a working copy and never chose: that copy was made in the shared folder.
        assertEquals(SourceWorkingMode.SHARED, SourceWorkingLocation.decide(sandbox = false, saved = null, hasWorkingCopy = true))
    }

    @Test
    fun aSavedChoiceStandsWhateverElseIsThere() {
        for (hasWorkingCopy in listOf(false, true)) {
            assertEquals(SourceWorkingMode.PRIVATE, SourceWorkingLocation.decide(false, SourceWorkingMode.PRIVATE, hasWorkingCopy))
            assertEquals(SourceWorkingMode.SHARED, SourceWorkingLocation.decide(false, SourceWorkingMode.SHARED, hasWorkingCopy))
        }
    }

    @Test
    fun thoughtsTestIsAlwaysPrivate() {
        for (saved in listOf(null, SourceWorkingMode.PRIVATE, SourceWorkingMode.SHARED)) {
            for (hasWorkingCopy in listOf(false, true)) {
                assertEquals(SourceWorkingMode.PRIVATE, SourceWorkingLocation.decide(sandbox = true, saved, hasWorkingCopy))
            }
        }
    }

    @Test
    fun theStoredNamesReadBack() {
        for (mode in SourceWorkingMode.values()) assertEquals(mode, SourceWorkingMode.fromStored(mode.stored))
        assertEquals(null, SourceWorkingMode.fromStored(null))
        assertEquals(null, SourceWorkingMode.fromStored("elsewhere"))
    }

    // ---- Moving the working copy when the setting changes ----

    private fun assertMoves(from: File, to: File) {
        sources.forEach { (path, text) -> write(from, path, text) }
        var committed = 0
        val result = SourceWorkingCopyMover.move(from, to, commit = {
            // The setting changes only once every copy is in place and verified.
            assertEquals(sources, tree(to))
            assertEquals(sources, tree(from))
            committed += 1
        })
        assertEquals(SourceWorkingCopyMover.Result.Moved(sources.size, emptyList()), result)
        assertEquals(1, committed)
        assertEquals(sources, tree(to))
        assertFalse(from.exists())
    }

    @Test
    fun turningTheSettingOnMovesTheSourcesToTheSharedFolder() = assertMoves(privateFolder, sharedFolder)

    @Test
    fun turningTheSettingOffMovesTheSourcesIntoPrivateStorage() = assertMoves(sharedFolder, privateFolder)

    @Test
    fun aMoveAndBackGivesTheSameWorkingCopy() {
        sources.forEach { (path, text) -> write(privateFolder, path, text) }
        val modified = File(privateFolder, "default/notes/idea.md").lastModified()
        assertTrue(SourceWorkingCopyMover.move(privateFolder, sharedFolder, commit = {}) is SourceWorkingCopyMover.Result.Moved)
        assertTrue(SourceWorkingCopyMover.move(sharedFolder, privateFolder, commit = {}) is SourceWorkingCopyMover.Result.Moved)
        assertEquals(sources, tree(privateFolder))
        assertFalse(sharedFolder.exists())
        // Unchanged files keep their time, so the next scan does not have to read them all again.
        assertEquals(modified, File(privateFolder, "default/notes/idea.md").lastModified())
    }

    @Test
    fun whatAlreadyLiesInTheNewPlaceIsSetAsideNotOverwritten() {
        sources.forEach { (path, text) -> write(privateFolder, path, text) }
        write(sharedFolder, "docs-paper/tex/source/main.tex", "left by an earlier installation\n")
        write(sharedFolder, "old/only-here.tex", "only copy\n")

        val result = SourceWorkingCopyMover.move(privateFolder, sharedFolder, commit = {}, timestamp = { "20261112-150000" })

        assertEquals(SourceWorkingCopyMover.Result.Moved(sources.size, emptyList()), result)
        assertEquals(sources, tree(sharedFolder))
        assertEquals(
            mapOf("docs-paper/tex/source/main.tex" to "left by an earlier installation\n", "old/only-here.tex" to "only copy\n"),
            tree(File(sharedFolder.parentFile, "Thoughts Sources.replaced-20261112-150000")),
        )
    }

    @Test
    fun aRefusedFileStopsTheMoveAndLeavesEverythingWhereItWas() {
        sources.forEach { (path, text) -> write(sharedFolder, path, text) }
        write(privateFolder, "earlier/kept.tex", "already there\n")
        var committed = false

        val result = SourceWorkingCopyMover.move(
            sharedFolder, privateFolder,
            // As when a shared file holds another app's change: it must not become private unseen.
            accept = { path, _ -> path != "default/notes/idea.md" },
            commit = { committed = true },
            timestamp = { "20261112-150000" },
        )

        assertTrue(result is SourceWorkingCopyMover.Result.Failed)
        assertFalse(committed)
        assertEquals(sources, tree(sharedFolder))
        assertEquals(mapOf("earlier/kept.tex" to "already there\n"), tree(privateFolder))
        assertFalse(File(privateFolder.parentFile, "working.replaced-20261112-150000").exists())
    }

    @Test
    fun aSettingThatCannotBeSavedLeavesTheOldWorkingCopyInUse() {
        sources.forEach { (path, text) -> write(privateFolder, path, text) }

        val result = SourceWorkingCopyMover.move(privateFolder, sharedFolder, commit = { error("The setting could not be saved") })

        assertEquals(SourceWorkingCopyMover.Result.Failed("The setting could not be saved"), result)
        assertEquals(sources, tree(privateFolder))
        assertFalse(sharedFolder.exists())
    }

    @Test
    fun aFileThatChangesDuringTheMoveIsNeverDeleted() {
        sources.forEach { (path, text) -> write(sharedFolder, path, text) }

        val result = SourceWorkingCopyMover.move(sharedFolder, privateFolder, commit = {
            // Another app writes after the copy was taken: the old folder now holds the only copy of this text.
            File(sharedFolder, "default/notes/idea.md").writeText("# Idea\nwritten during the move\n")
        })

        assertEquals(SourceWorkingCopyMover.Result.Moved(sources.size, listOf("default/notes/idea.md")), result)
        assertEquals(sources, tree(privateFolder))
        assertEquals(mapOf("default/notes/idea.md" to "# Idea\nwritten during the move\n"), tree(sharedFolder))
    }

    @Test
    fun anEmptyOrMissingWorkingCopyMovesAsNothing() {
        var committed = 0
        assertEquals(SourceWorkingCopyMover.Result.Moved(0, emptyList()), SourceWorkingCopyMover.move(privateFolder, sharedFolder, commit = { committed += 1 }))
        assertEquals(1, committed)
        assertTrue(sharedFolder.isDirectory)
    }

    @Test
    fun foldersInsideEachOtherAndLinksAreRefused() {
        sources.forEach { (path, text) -> write(privateFolder, path, text) }
        assertTrue(SourceWorkingCopyMover.move(privateFolder, File(privateFolder, "inside"), commit = {}) is SourceWorkingCopyMover.Result.Failed)
        assertTrue(SourceWorkingCopyMover.move(privateFolder, privateFolder, commit = {}) is SourceWorkingCopyMover.Result.Failed)
        val outside = write(root, "elsewhere/secret.txt", "not a source\n")
        Files.createSymbolicLink(File(privateFolder, "default/notes/link.md").toPath(), outside.toPath())
        assertTrue(SourceWorkingCopyMover.move(privateFolder, sharedFolder, commit = {}) is SourceWorkingCopyMover.Result.Failed)
        assertEquals(sources + ("default/notes/link.md" to "not a source\n"), tree(privateFolder))
        assertFalse(sharedFolder.exists())
    }
}

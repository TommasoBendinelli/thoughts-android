// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LibraryGenerationReuseTest {
    @Test fun interruptedBorrowingRestoresEveryPdfBeforeReactivatingOldGeneration() {
        val parent = Files.createTempDirectory("library-reuse").toFile()
        try {
            val root = File(parent, "current").apply { mkdirs() }
            val stage = File(parent, ".thoughts-library-stage-new").apply { mkdirs() }
            val previous = File(parent, ".thoughts-library-previous")
            val journal = File(parent, ".thoughts-library-activation.json")
            File(root, "a.pdf").writeText("unchanged a")
            File(root, "b.pdf").writeText("unchanged b")
            File(root, "edited.pdf").writeText("old")
            File(stage, "edited.pdf").writeText("new")
            journal.writeText(JsonObject().apply {
                addProperty("staging_path", stage.absolutePath)
                add("reused_pdfs", JsonArray().apply { add("a.pdf"); add("b.pdf") })
            }.toString())
            assertTrue(root.renameTo(previous))
            moveReusedLibraryFiles(previous, stage, listOf("a.pdf"))
            reconcileInterruptedLibraryActivation(root)
            assertEquals("unchanged a", File(root, "a.pdf").readText())
            assertEquals("unchanged b", File(root, "b.pdf").readText())
            assertEquals("old", File(root, "edited.pdf").readText())
            assertFalse(journal.exists())
        } finally { parent.deleteRecursively() }
    }

    @Test fun successfulReusePreservesInodeAndRollbackRestoresOldGeneration() {
        val parent = Files.createTempDirectory("library-reuse").toFile()
        try {
            val root = File(parent, "current").apply { mkdirs() }
            val stage = File(parent, ".thoughts-library-stage-new").apply { mkdirs() }
            val previous = File(parent, ".thoughts-library-previous")
            val journal = File(parent, "journal")
            val original = File(root, "same.pdf").apply { writeText("same") }
            val inode = Files.getAttribute(original.toPath(), "unix:ino")
            File(root, "edited.pdf").writeText("old")
            File(stage, "edited.pdf").writeText("new")
            journal.writeText("{\"reused_pdfs\":[\"same.pdf\"]}")
            assertTrue(root.renameTo(previous))
            moveReusedLibraryFiles(previous, stage, listOf("same.pdf"))
            assertTrue(stage.renameTo(root))
            assertEquals(inode, Files.getAttribute(File(root, "same.pdf").toPath(), "unix:ino"))
            restoreReusedLibraryFiles(root, stage, previous, journal)
            assertEquals(inode, Files.getAttribute(File(previous, "same.pdf").toPath(), "unix:ino"))
            assertEquals("old", File(previous, "edited.pdf").readText())
        } finally { parent.deleteRecursively() }
    }

    @Test fun interruptedActivationRestoresBorrowedReadingObjects() {
        val parent = Files.createTempDirectory("library-reuse").toFile()
        try {
            val root = File(parent, "current").apply { mkdirs() }
            val stage = File(parent, ".thoughts-library-stage-new").apply { mkdirs() }
            val previous = File(parent, ".thoughts-library-previous")
            val journal = File(parent, ".thoughts-library-activation.json")
            val kept = "objects/" + "a".repeat(64)
            val moved = "objects/" + "b".repeat(64)
            File(root, "objects").mkdirs()
            File(root, kept).writeText("kept object")
            File(root, moved).writeText("moved object")
            journal.writeText(JsonObject().apply {
                addProperty("staging_path", stage.absolutePath)
                add("reused_pdfs", JsonArray().apply { add(kept); add(moved) })
            }.toString())
            assertTrue(root.renameTo(previous))
            moveReusedLibraryFiles(previous, stage, listOf(moved))
            assertEquals("moved object", File(stage, moved).readText())
            reconcileInterruptedLibraryActivation(root)
            assertEquals("kept object", File(root, kept).readText())
            assertEquals("moved object", File(root, moved).readText())
            assertFalse(journal.exists())
        } finally { parent.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun borrowedObjectsMustBeContentAddressed() {
        val parent = Files.createTempDirectory("library-reuse").toFile()
        try { moveReusedLibraryFiles(parent, parent, listOf("objects/../escape.pdf")) } finally { parent.deleteRecursively() }
    }
}

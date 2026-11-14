// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class LibraryObjectInventoryTest {
    private fun hash(file: File) = hash(file.readBytes())
    private fun hash(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

    @Test fun findsAllStoresDeduplicatesAndNeverWalksUnrelatedTrees() {
        val root = Files.createTempDirectory("library-inventory").toFile()
        try {
            val expected = mutableSetOf<String>()
            LibraryObjectInventory.OBJECT_STORES.forEachIndexed { index, store ->
                val bytes = "object $index".toByteArray()
                val digest = hash(bytes)
                expected += digest
                File(root, "$store/$digest").apply { parentFile.mkdirs(); writeBytes(bytes) }
                val unrelated = File(root, "$store/nested/${"a".repeat(64)}")
                unrelated.parentFile.mkdirs(); unrelated.writeText("not a Library object")
            }
            val firstStore = LibraryObjectInventory.OBJECT_STORES.first()
            val lastStore = LibraryObjectInventory.OBJECT_STORES.last()
            val original = File(root, firstStore).listFiles()!!.first { it.isFile }
            original.copyTo(File(root, "$lastStore/${original.name}"))
            val unrelated = File(root, "unrelated/deep/${"b".repeat(64)}")
            unrelated.parentFile.mkdirs(); unrelated.writeText("unrelated")
            val checked = mutableListOf<File>()
            val result = LibraryObjectInventory(root) { checked += it; hash(it) }.verifiedHashes()
            assertEquals(expected, result)
            assertEquals(4, checked.size)
            assertFalse(checked.contains(unrelated))
        } finally { root.deleteRecursively() }
    }

    @Test fun removalsCorruptionNewFilesAndUnreadableCopiesAreRechecked() {
        val root = Files.createTempDirectory("library-inventory").toFile()
        try {
            val store = File(root, LibraryObjectInventory.OBJECT_STORES.first()).apply { mkdirs() }
            val raw = "good".toByteArray()
            val digest = hash(raw)
            val file = File(store, digest).apply { writeBytes(raw) }
            val inventory = LibraryObjectInventory(root, ::hash)
            assertEquals(setOf(digest), inventory.verifiedHashes())
            file.writeText("evil")
            assertTrue(inventory.verifiedHashes().isEmpty())
            val copy = File(root, "${LibraryObjectInventory.OBJECT_STORES.last()}/$digest")
            copy.parentFile.mkdirs(); copy.writeBytes(raw)
            assertEquals(setOf(digest), inventory.verifiedHashes())
            val unavailableFirstCopy = LibraryObjectInventory(root) { if (it == file) error("unreadable") else hash(it) }
            assertEquals(setOf(digest), unavailableFirstCopy.verifiedHashes())
            copy.delete()
            assertTrue(inventory.verifiedHashes().isEmpty())
            val added = "new".toByteArray()
            File(store, hash(added)).writeBytes(added)
            assertEquals(setOf(hash(added)), inventory.verifiedHashes())
        } finally { root.deleteRecursively() }
    }

    @Test fun aStoredObjectIsCheckedByOneLookAtItsEntryNotByItsCanonicalPath() {
        val root = Files.createTempDirectory("library-inventory").toFile().canonicalFile
        val outside = Files.createTempFile("outside-inventory", ".bin").toFile()
        try {
            val store = File(root, LibraryObjectInventory.OBJECT_STORES.first()).apply { mkdirs() }
            val plain = File(store, "a".repeat(64)).apply { writeText("object") }
            val directory = File(store, "b".repeat(64)).apply { mkdirs() }
            val link = File(store, "c".repeat(64))
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertTrue(isPlainFileDirectlyIn(store, plain))
            assertFalse(isPlainFileDirectlyIn(store, directory))
            assertFalse(isPlainFileDirectlyIn(store, link))
            // The inventory asks once per listed object and verifies only the plain files.
            val asked = mutableListOf<String>()
            val verified = mutableListOf<String>()
            val inventory = LibraryObjectInventory(root) { verified += it.name; it.name }
            inventory.isPlainFile = { parent, file ->
                assertEquals(store, parent); asked += file.name; isPlainFileDirectlyIn(parent, file)
            }
            val result = inventory.verifiedHashes()
            assertEquals(setOf(plain.name), result)
            assertEquals(listOf(plain.name, directory.name, link.name), asked.sorted())
            assertEquals(listOf(plain.name), verified)
        } finally { root.deleteRecursively(); outside.delete() }
    }

    @Test fun symlinkedFilesAndRedirectedStoresAreNotAdvertised() {
        val root = Files.createTempDirectory("library-inventory").toFile()
        val outside = Files.createTempFile("outside-inventory", ".bin").toFile()
        try {
            outside.writeText("external")
            val digest = hash(outside)
            val store = File(root, LibraryObjectInventory.OBJECT_STORES.first()).apply { mkdirs() }
            Files.createSymbolicLink(File(store, digest).toPath(), outside.toPath())
            val redirected = File(root, LibraryObjectInventory.OBJECT_STORES.last())
            redirected.parentFile.mkdirs()
            Files.createSymbolicLink(redirected.toPath(), store.toPath())
            assertTrue(LibraryObjectInventory(root) { error("must not read a redirected file") }.verifiedHashes().isEmpty())
        } finally { root.deleteRecursively(); outside.delete() }
    }
}

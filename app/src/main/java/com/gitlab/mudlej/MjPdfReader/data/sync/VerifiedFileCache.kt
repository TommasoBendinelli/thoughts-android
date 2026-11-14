// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.os.Build
import android.system.Os
import java.io.File
import java.security.MessageDigest

/**
 * Verification keyed by inode, size and nanosecond mtime and ctime; any write, replacement or rename
 * changes the identity and requires hashing again. Results persist in [journal] across restarts, so a
 * cold start does not re-hash every Library PDF and stored object before the first Sync.
 */
internal class FileVerificationCache(
    private var journal: File? = null,
    private val identity: (File) -> String? = ::fileVerificationIdentity,
) {
    private val verified = object : LinkedHashMap<String, String>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_ENTRIES
    }
    private var journalLoaded = false
    private var journalLines = 0
    private var batchDepth = 0
    private val batchedLines = StringBuilder()

    /** Called once at startup; the journal is read lazily on the first verification. */
    fun persistTo(file: File) = synchronized(verified) {
        if (journal == null) journal = file
    }

    fun sha256(file: File): String {
        val before = identity(file)
        synchronized(verified) {
            loadJournal()
            before?.let(verified::get)?.let { return it }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex().also { remember(file, before, it) }
    }

    /** The digest already verified for this exact file identity, without reading the file. */
    fun knownSha256(file: File): String? {
        val before = identity(file) ?: return null
        return synchronized(verified) { loadJournal(); verified[before] }
    }

    fun read(file: File, expected: String, maximum: Long): ByteArray? {
        if (!file.isFile || file.length() !in 0..maximum) return null
        val before = identity(file)
        val known = synchronized(verified) { loadJournal(); before?.let(verified::get) }
        val raw = file.readBytes()
        if (raw.size.toLong() > maximum) return null
        if (before != null && known == expected && identity(file) == before) return raw
        val actual = MessageDigest.getInstance("SHA-256").digest(raw).hex()
        if (actual != expected) return null
        remember(file, before, actual)
        return raw
    }

    private fun remember(file: File, before: String?, sha256: String) {
        if (before != null && identity(file) == before) synchronized(verified) {
            put(before, sha256)
        }
    }

    /**
     * Runs [block] and writes the verifications it records to the journal once, at its end. Moving
     * the kept files of a Library generation recorded one verification per file, each its own
     * open, append and close of the journal. The journal only spares hashing after a restart, so
     * records lost with the process cost a hash later, never a wrong answer.
     */
    fun <T> batchingJournal(block: () -> T): T {
        synchronized(verified) { batchDepth++ }
        try {
            return block()
        } finally {
            synchronized(verified) {
                if (--batchDepth == 0 && batchedLines.isNotEmpty()) {
                    val lines = batchedLines.toString()
                    val count = lines.count { it == '\n' }
                    batchedLines.setLength(0)
                    appendToJournal(lines, count)
                }
            }
        }
    }

    /** Caller holds the lock. */
    private fun put(identity: String, sha256: String) {
        if (verified.put(identity, sha256) == sha256) return
        if (batchDepth > 0) {
            if (journal != null) batchedLines.append(identity).append('\t').append(sha256).append('\n')
            return
        }
        appendToJournal("$identity\t$sha256\n", 1)
    }

    /** Caller holds the lock. */
    private fun appendToJournal(lines: String, count: Int) {
        val target = journal ?: return
        runCatching {
            target.parentFile?.mkdirs()
            java.io.FileOutputStream(target, true).use { it.write(lines.toByteArray()) }
            journalLines += count
            if (journalLines > MAX_ENTRIES * 2) compactJournal(target)
        }
    }

    /** Caller holds the lock. */
    private fun loadJournal() {
        if (journalLoaded) return
        val source = journal ?: return
        journalLoaded = true
        runCatching {
            if (!source.isFile) return
            source.forEachLine { line ->
                journalLines++
                val tab = line.indexOf('\t')
                if (tab > 0 && line.length == tab + 65) verified[line.substring(0, tab)] = line.substring(tab + 1)
            }
            if (journalLines > MAX_ENTRIES * 2) compactJournal(source)
        }.onFailure { source.delete(); journalLines = 0 }
    }

    /** Caller holds the lock. */
    private fun compactJournal(target: File) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.bufferedWriter().use { output ->
            verified.forEach { (identity, sha256) -> output.write("$identity\t$sha256\n") }
        }
        if (temporary.renameTo(target)) journalLines = verified.size else temporary.delete()
    }

    /** A hard link shares the verified inode; linking changes only its ctime and link count. */
    fun link(source: File, destination: File) {
        val before = identity(source)
        val known = synchronized(verified) { loadJournal(); before?.let(verified::get) }
        android.system.Os.link(source.absolutePath, destination.absolutePath)
        val after = identity(destination)
        if (before != null && after != null && known != null && sameInode(before, after)) synchronized(verified) {
            put(after, known)
        }
    }

    private fun sameInode(before: String, after: String) =
        before.substringBeforeLast(":").substringBeforeLast(":") ==
            after.substringBeforeLast(":").substringBeforeLast(":")

    fun rename(source: File, destination: File): Boolean {
        val before = identity(source)
        val known = synchronized(verified) { loadJournal(); before?.let(verified::get) }
        if (!source.renameTo(destination)) return false
        // rename changes ctime, not bytes; carry forward verification only for a known inode.
        val after = identity(destination)
        if (before != null && after != null && known != null && sameInode(before, after)) synchronized(verified) {
            put(after, known)
        }
        return true
    }
}

private const val MAX_ENTRIES = 32_768

internal val verifiedFileCache = FileVerificationCache()

private fun fileVerificationIdentity(file: File): String? = runCatching {
    // Second-resolution timestamps are insufficient for detecting a same-size edit.
    if (Build.VERSION.SDK_INT < 27) return@runCatching null
    val stat = Os.stat(file.absolutePath)
    "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}:" +
        "${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"
}.getOrNull()

private fun ByteArray.hex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@hex) {
            val value = byte.toInt() and 255
            append(digits[value ushr 4]); append(digits[value and 15])
        }
    }
}

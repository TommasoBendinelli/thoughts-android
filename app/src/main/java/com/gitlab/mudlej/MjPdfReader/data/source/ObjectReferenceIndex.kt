// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.sync.verifiedFileCache
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * The SHA-256 strings each stored JSON object names, keyed by the object's own digest.
 *
 * Objects are content-addressed, so the strings an object names never change. Source
 * cleanup used to read and tree-parse every reachable JSON object (review plans,
 * renderer evidence; tens of MB) after every Sync; it now streams an object once,
 * remembers its references here and only stats it afterwards. Only facts derived from
 * bytes whose digest matched the object name are kept; an unverified file is read
 * again next time, exactly as before.
 */
internal class ObjectReferenceIndex private constructor(private val journal: File) {
    /** [kind] is one of the KIND_ constants; [references] is empty unless [kind] is [KIND_JSON]. */
    class Entry(val kind: Int, val references: Array<String>)

    /** Work done by one cleanup; logged so a slow phone run shows I/O versus parsing. */
    class Counters {
        var hits = 0L
        var parsed = 0L
        var parsedBytes = 0L
        var leafChecks = 0L
        var verifiedBytes = 0L
    }

    private val entries = HashMap<String, Entry>()
    private var loaded = false

    @Synchronized
    fun cached(hash: String): Entry? {
        load()
        return entries[hash]
    }

    /**
     * The SHA-256 strings [file] names when it is a JSON object or array of at most
     * [maximum] bytes whose first byte is `{` or `[`; empty otherwise or when it does not
     * parse. This is the rule cleanup has always applied to objects it reaches.
     */
    fun references(hash: String, file: File, maximum: Long, counters: Counters): Array<String> {
        cached(hash)?.let { entry ->
            counters.hits++
            return if (entry.kind == KIND_JSON) entry.references else NONE
        }
        if (file.length() > maximum) return NONE
        val entry = examine(hash, file, counters) ?: return NONE
        return if (entry.kind == KIND_JSON) entry.references else NONE
    }

    /**
     * Entry for an object the caller has already verified against [hash] (a document
     * package). Reads the file only when no earlier cleanup indexed it.
     */
    fun verifiedEntry(hash: String, file: File, counters: Counters): Entry? {
        cached(hash)?.let { counters.hits++; return it }
        return examine(hash, file, counters)
    }

    private fun examine(hash: String, file: File, counters: Counters): Entry? {
        val first = runCatching { file.inputStream().use { it.read() } }.getOrNull() ?: return null
        if (first != '{'.code && first != '['.code) {
            counters.leafChecks++
            // Classifying a leaf costs one byte; remember it only when the file's digest is
            // already known, so a damaged copy can never hide a JSON object's references.
            val entry = Entry(KIND_NOT_JSON, NONE)
            if (verifiedFileCache.knownSha256(file) == hash) remember(hash, entry)
            return entry
        }
        counters.parsed++
        val digest = MessageDigest.getInstance("SHA-256")
        val references = runCatching {
            DigestInputStream(FileInputStream(file), digest).use { hashed ->
                val references = sha256StringValues(BufferedInputStream(hashed, BUFFER_BYTES))
                // Trailing bytes still count towards the object's digest.
                val buffer = ByteArray(BUFFER_BYTES)
                while (hashed.read(buffer) >= 0) Unit
                references
            }
        }.getOrElse { return null }
        counters.parsedBytes += file.length()
        val entry = if (references == null) Entry(KIND_INVALID, NONE) else Entry(KIND_JSON, references)
        if (digest.digest().hex() == hash) remember(hash, entry)
        return entry
    }

    /** Drops entries for objects that no longer exist anywhere cleanup looks. */
    @Synchronized
    fun retainOnly(live: (String) -> Boolean) {
        load()
        val stale = entries.keys.count { !live(it) }
        if (stale < COMPACT_MINIMUM || stale * 4 < entries.size) return
        entries.keys.retainAll(live)
        runCatching {
            val temporary = File(journal.parentFile, "${journal.name}.tmp")
            DataOutputStream(FileOutputStream(temporary).buffered()).use { output ->
                entries.forEach { (hash, entry) -> write(output, hash, entry) }
            }
            if (!temporary.renameTo(journal)) temporary.delete()
        }
    }

    @Synchronized
    private fun remember(hash: String, entry: Entry) {
        load()
        if (entries.put(hash, entry) != null) return
        runCatching {
            journal.parentFile?.mkdirs()
            DataOutputStream(FileOutputStream(journal, true).buffered()).use { write(it, hash, entry) }
        }
    }

    /** Caller holds the lock. A torn final record from a crash is cut off. */
    private fun load() {
        if (loaded) return
        loaded = true
        if (!journal.isFile) return
        var valid = 0L
        runCatching {
            DataInputStream(FileInputStream(journal).buffered()).use { input ->
                while (true) {
                    val hash = readDigest(input) ?: break
                    val kind = input.readByte().toInt()
                    val count = input.readInt()
                    require(kind in KIND_NOT_JSON..KIND_JSON && count in 0..MAX_REFERENCES)
                    val references = Array(count) { readDigest(input) ?: throw EOFException() }
                    entries[hash] = Entry(kind, references)
                    valid += DIGEST_BYTES + 5L + count * DIGEST_BYTES.toLong()
                }
            }
        }
        if (journal.length() != valid) runCatching { RandomAccessFile(journal, "rw").use { it.setLength(valid) } }
    }

    private fun write(output: DataOutputStream, hash: String, entry: Entry) {
        output.write(hash.unhex())
        output.writeByte(entry.kind)
        output.writeInt(entry.references.size)
        entry.references.forEach { output.write(it.unhex()) }
    }

    private fun readDigest(input: DataInputStream): String? {
        val bytes = ByteArray(DIGEST_BYTES)
        var read = 0
        while (read < DIGEST_BYTES) {
            val count = input.read(bytes, read, DIGEST_BYTES - read)
            if (count < 0) return if (read == 0) null else throw EOFException()
            read += count
        }
        return bytes.hex()
    }

    companion object {
        const val KIND_NOT_JSON = 0
        const val KIND_INVALID = 1
        const val KIND_JSON = 2
        private const val DIGEST_BYTES = 32
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_REFERENCES = 1_000_000
        private const val COMPACT_MINIMUM = 256
        private val NONE = emptyArray<String>()
        private val instances = HashMap<String, ObjectReferenceIndex>()

        /** One index per journal per process; every store instance shares what it learned. */
        fun at(journal: File): ObjectReferenceIndex = synchronized(instances) {
            instances.getOrPut(journal.absolutePath) { ObjectReferenceIndex(journal) }
        }

        /** Simulates a process restart in tests: only the journal survives. */
        internal fun forgetLoadedIndexes() = synchronized(instances) { instances.clear() }

        /**
         * Distinct SHA-256 string values in one JSON document, or null when it does not
         * parse. Matches the tree walk it replaces: Gson's lenient parser, string values
         * only (never member names or numbers), and the whole input must be one value.
         */
        fun sha256StringValues(input: InputStream): Array<String>? {
            val reader = JsonReader(InputStreamReader(input, StandardCharsets.UTF_8))
            reader.setStrictness(Strictness.LENIENT)
            val found = LinkedHashSet<String>()
            return try {
                var depth = 0
                do {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> { reader.beginObject(); depth++ }
                        JsonToken.BEGIN_ARRAY -> { reader.beginArray(); depth++ }
                        JsonToken.END_OBJECT -> { reader.endObject(); depth-- }
                        JsonToken.END_ARRAY -> { reader.endArray(); depth-- }
                        JsonToken.NAME -> reader.nextName()
                        JsonToken.STRING -> reader.nextString().let { if (isDigest(it)) found.add(it) }
                        JsonToken.NUMBER, JsonToken.BOOLEAN, JsonToken.NULL -> reader.skipValue()
                        JsonToken.END_DOCUMENT, null -> return null
                    }
                } while (depth > 0)
                if (reader.peek() != JsonToken.END_DOCUMENT) null else found.toTypedArray()
            } catch (_: Exception) {
                null
            } catch (_: StackOverflowError) {
                null
            }
        }

        private fun isDigest(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        private fun String.unhex(): ByteArray = ByteArray(length / 2) { index ->
            ((Character.digit(this[index * 2], 16) shl 4) or Character.digit(this[index * 2 + 1], 16)).toByte()
        }

        private fun ByteArray.hex(): String {
            val digits = "0123456789abcdef"
            return buildString(size * 2) {
                for (byte in this@hex) {
                    val value = byte.toInt() and 255
                    append(digits[value ushr 4]); append(digits[value and 15])
                }
            }
        }
    }
}

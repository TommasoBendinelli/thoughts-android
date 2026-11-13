// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import java.io.File

/** Audio OpenRouter already read, by [Synthesis.cacheKey]. */
interface SpeechAudioCache {
    fun get(key: String): ByteArray?

    fun put(key: String, audio: ByteArray)
}

/**
 * MP3 files in [directory] (the app's cache directory, so neither backed up nor exported) named by
 * their synthesis key; the least recently used go first once they take more than [maxBytes].
 */
class DiskSpeechAudioCache(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : SpeechAudioCache {

    override fun get(key: String): ByteArray? {
        if (!KEY.matches(key)) return null
        val file = File(directory, "$key$SUFFIX")
        val audio = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (audio.isEmpty()) return null
        file.setLastModified(System.currentTimeMillis()) // replayed audio stays cached
        return audio
    }

    @Synchronized
    override fun put(key: String, audio: ByteArray) {
        if (!KEY.matches(key) || audio.isEmpty() || audio.size > maxBytes) return
        if (!directory.isDirectory && !directory.mkdirs()) return
        val partial = File(directory, ".partial-$key$SUFFIX")
        partial.writeBytes(audio)
        if (!partial.renameTo(File(directory, "$key$SUFFIX"))) {
            partial.delete()
            return
        }
        evict()
    }

    private fun evict() {
        val files = directory.listFiles { file -> file.name.endsWith(SUFFIX) && !file.name.startsWith(".") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 100L * 1024 * 1024
        private const val SUFFIX = ".mp3"
        private val KEY = Regex("^[0-9a-f]{64}$")
    }
}

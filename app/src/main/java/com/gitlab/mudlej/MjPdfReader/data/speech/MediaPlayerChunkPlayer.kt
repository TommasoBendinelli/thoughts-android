// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One [MediaPlayer] per chunk, each reading its MP3 from a cache file. A chunk queued while another
 * plays is prepared at once and chained with [MediaPlayer.setNextMediaPlayer], so it starts without
 * a gap. Main thread only.
 */
class MediaPlayerChunkPlayer(
    context: Context,
    private val directory: File,
    private val log: (String) -> Unit = {},
) : ChunkAudioPlayer {
    private val appContext = context.applicationContext

    private class Item(val file: File, val player: MediaPlayer) {
        val started = CompletableDeferred<Unit>()
        var prepared = false
    }

    private var current: Item? = null
    private val queue = ArrayDeque<Item>()
    /** The queued item [current] hands over to by itself when it completes. */
    private var chained: Item? = null
    private var paused = false
    private var released = false
    private val idleWaiters = mutableListOf<CompletableDeferred<Unit>>()

    override suspend fun enqueueAndAwaitStart(audio: ByteArray) {
        check(!released) { "The player was released" }
        val file = withContext(Dispatchers.IO) {
            directory.mkdirs()
            File(directory, "${UUID.randomUUID()}.mp3").also { it.writeBytes(audio) }
        }
        if (released) {
            file.delete()
            throw IOException("The player was released")
        }
        val player = MediaPlayer()
        val item = Item(file, player)
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(file.path)
        } catch (error: Exception) {
            player.release()
            file.delete()
            throw IOException("The audio could not be opened", error)
        }
        player.setOnPreparedListener {
            item.prepared = true
            advance()
        }
        player.setOnCompletionListener { onFinished(item) }
        player.setOnErrorListener { _, what, extra ->
            log("player error what=$what extra=$extra")
            onFailed(item)
            true
        }
        queue.addLast(item)
        player.prepareAsync()
        item.started.await()
    }

    override suspend fun awaitIdle() {
        if (current == null && queue.isEmpty()) return
        val waiter = CompletableDeferred<Unit>()
        idleWaiters += waiter
        waiter.await()
    }

    fun pause() {
        paused = true
        current?.player?.takeIf { it.isPlaying }?.pause()
    }

    fun resume() {
        if (!paused) return
        paused = false
        val playing = current
        if (playing != null) {
            playing.player.start()
            chainNext()
        } else {
            advance()
        }
    }

    /** Stops playback for good and deletes the cache files. Suspended callers are cancelled. */
    fun release() {
        released = true
        val items = listOfNotNull(current) + queue
        current = null
        chained = null
        queue.clear()
        items.forEach { item ->
            runCatching { item.player.release() }
            item.file.delete()
            item.started.cancel()
        }
        idleWaiters.forEach { it.cancel() }
        idleWaiters.clear()
    }

    /** Starts the head of the queue when nothing plays, or chains it behind what plays. */
    private fun advance() {
        if (released) return
        if (current == null) {
            if (paused) return
            val head = queue.firstOrNull()?.takeIf { it.prepared } ?: return
            queue.removeFirst()
            current = head
            head.player.start()
            head.started.complete(Unit)
        }
        chainNext()
    }

    private fun chainNext() {
        val playing = current ?: return
        val next = queue.firstOrNull()?.takeIf { it.prepared } ?: return
        if (chained === next) return
        runCatching { playing.player.setNextMediaPlayer(next.player) }
            .onSuccess { chained = next }
    }

    private fun onFinished(item: Item) {
        if (item !== current) return
        current = null
        dispose(item)
        val next = chained
        chained = null
        if (next != null && queue.firstOrNull() === next) {
            // MediaPlayer started it already.
            queue.removeFirst()
            current = next
            next.started.complete(Unit)
            chainNext()
        } else {
            advance()
        }
        signalIdleIfDone()
    }

    private fun onFailed(item: Item) {
        if (item === current) {
            onFinished(item)
            return
        }
        if (chained === item) {
            chained = null
            runCatching { current?.player?.setNextMediaPlayer(null) }
        }
        queue.remove(item)
        dispose(item)
        item.started.completeExceptionally(IOException("The audio could not be played"))
        advance()
        signalIdleIfDone()
    }

    private fun dispose(item: Item) {
        runCatching { item.player.release() }
        item.file.delete()
    }

    private fun signalIdleIfDone() {
        if (current != null || queue.isNotEmpty()) return
        idleWaiters.forEach { it.complete(Unit) }
        idleWaiters.clear()
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Where the working copy of the note sources (LaTeX, Markdown) lives. */
enum class SourceWorkingMode(val stored: String) {
    /** In the app's private storage: no other app can read or change the sources. */
    PRIVATE("private"),

    /** In `Documents/Thoughts Sources`: every app with access to shared storage can read and change them. */
    SHARED("shared");

    companion object {
        fun fromStored(value: String?): SourceWorkingMode? = values().firstOrNull { it.stored == value }
    }
}

/** The working copy as the setting places it right now; a store asks every time, since the setting can move it. */
internal interface SourceWorkingCopy {
    val directory: File

    /** True in the shared folder, where a change can come from another app. */
    val shared: Boolean
}

/**
 * The setting "Keep sources in a shared folder". New installations keep the working copy private.
 * An installation from before the setting keeps the shared folder it already uses, so an update
 * moves nothing. Switching the setting moves the working copy ([SourceWorkingCopyMover]).
 */
internal object SourceWorkingLocation {
    private const val PREFERENCES = "source_working_copy"
    private const val KEY_MODE = "mode"
    private const val SHARED_FOLDER = "Thoughts Sources"
    private const val LEGACY_SHARED_FOLDER = "MJ PDF Sources"

    private class Current(val mode: SourceWorkingMode, override val directory: File) : SourceWorkingCopy {
        override val shared: Boolean get() = mode == SourceWorkingMode.SHARED
    }

    @Volatile
    private var current: Current? = null

    /**
     * The mode of an installation. Thoughts Test ([sandbox]) is always private. A [saved] choice
     * stands. Without one, an installation that already has a working copy ([hasWorkingCopy]) made
     * it in the shared folder, before the setting existed, and keeps it there; a new installation
     * starts private.
     */
    fun decide(sandbox: Boolean, saved: SourceWorkingMode?, hasWorkingCopy: Boolean): SourceWorkingMode = when {
        sandbox -> SourceWorkingMode.PRIVATE
        saved != null -> saved
        hasWorkingCopy -> SourceWorkingMode.SHARED
        else -> SourceWorkingMode.PRIVATE
    }

    fun workingCopy(context: Context): SourceWorkingCopy = resolve(context)

    fun mode(context: Context): SourceWorkingMode = resolve(context).mode

    /** Thoughts Test has no setting: its sources never leave its private storage. */
    fun canBeShared(context: Context): Boolean = !isSandbox(context)

    private fun isSandbox(context: Context): Boolean = context.packageName.endsWith(".sandbox")

    private fun resolve(context: Context): Current {
        current?.let { return it }
        synchronized(this) {
            current?.let { return it }
            val appContext = context.applicationContext ?: context
            val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val saved = SourceWorkingMode.fromStored(preferences.getString(KEY_MODE, null))
            val mode = decide(
                sandbox = isSandbox(appContext),
                saved = saved,
                // Every tracked source has a state file in private storage, wherever its working file is.
                hasWorkingCopy = File(appContext.filesDir, "source-editor/offline/working").list().orEmpty().isNotEmpty(),
            )
            // Recorded at the first look, so that a later first Sync does not read as an old installation.
            if (saved != mode) preferences.edit().putString(KEY_MODE, mode.stored).commit()
            return Current(mode, directory(appContext, mode, forNewCopy = false)).also { current = it }
        }
    }

    private fun directory(context: Context, mode: SourceWorkingMode, forNewCopy: Boolean): File {
        if (mode == SourceWorkingMode.PRIVATE) return File(context.filesDir, "source-editor/working")
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val shared = File(documents, SHARED_FOLDER)
        if (forNewCopy) return shared
        val legacy = File(documents, LEGACY_SHARED_FOLDER)
        if (!shared.exists() && legacy.isDirectory) {
            runCatching { legacy.renameTo(shared) }
        }
        return if (shared.exists() || !legacy.exists()) shared else legacy
    }

    sealed class SwitchResult {
        /** The working copy is in the wanted place; [leftBehind] files changed during the move and stayed in the old one. */
        data class Moved(val files: Int, val leftBehind: List<String>) : SwitchResult()

        /** Nothing moved: a file in the shared folder holds a change that was not made in Thoughts. */
        data class OutsideChange(val sourceFile: String) : SwitchResult()

        /** Nothing moved, and nothing was lost. */
        data class Failed(val reason: String) : SwitchResult()
    }

    /**
     * Moves the working copy to [target]'s place and then changes the setting. Blocking: call it off
     * the main thread, while no Sync runs. Moving into private storage is refused while a shared
     * file holds a change that was not made in Thoughts: once private, nothing would tell it from
     * the app's own changes, and Sync would upload it without asking.
     */
    @Synchronized
    fun switchTo(context: Context, target: SourceWorkingMode): SwitchResult {
        val appContext = context.applicationContext ?: context
        val from = resolve(appContext)
        if (from.mode == target) return SwitchResult.Moved(0, emptyList())
        if (isSandbox(appContext)) return SwitchResult.Failed("Thoughts Test keeps its sources private")
        val to = directory(appContext, target, forNewCopy = true)
        val store = OfflineSourceBundleStore(appContext)
        var refused: String? = null
        val moved = SourceWorkingCopyMover.move(
            from = from.directory,
            to = to,
            accept = { path, sha256 ->
                (target != SourceWorkingMode.PRIVATE || !store.isOutsideContent(path, sha256)).also { accepted ->
                    if (!accepted) refused = path
                }
            },
            commit = {
                check(
                    appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putString(KEY_MODE, target.stored).commit(),
                ) { "The setting could not be saved" }
                current = Current(target, to)
            },
        )
        return when (moved) {
            is SourceWorkingCopyMover.Result.Moved -> {
                SourceCommentSnapshot.invalidate()
                SourceCommentChanges.changes.tryEmit(Unit)
                SwitchResult.Moved(moved.files, moved.leftBehind)
            }
            is SourceWorkingCopyMover.Result.Failed ->
                refused?.let(SwitchResult::OutsideChange) ?: SwitchResult.Failed(moved.reason)
        }
    }
}

/**
 * Moves a working copy between two folders on different storage (the app's private files and the
 * shared Documents folder), so it copies. A file is deleted from the old folder only when an
 * identical, verified copy of it is in the new one: an interrupted or failed move, or a file that
 * changes while the move runs, never loses the only copy of anything.
 */
internal object SourceWorkingCopyMover {
    sealed class Result {
        /** [files] were moved; [leftBehind] changed after they were copied and stayed in the old folder. */
        data class Moved(val files: Int, val leftBehind: List<String>) : Result()

        /** The old folder is as it was and stays the working copy. */
        data class Failed(val reason: String) : Result()
    }

    private class Copied(val path: String, val sha256: String)

    /**
     * Copies every file of [from] to [to] and verifies each copy, asks [accept] about each (path
     * relative to the folder, SHA-256 of the copy), runs [commit] (which makes [to] the working
     * copy), and only then deletes from [from] what the copies replace. Anything already in [to] is
     * set aside in a folder next to it, never overwritten or deleted. When a copy fails, [accept]
     * refuses or [commit] throws, [to] is put back as it was and [from] stays untouched.
     */
    fun move(
        from: File,
        to: File,
        accept: (path: String, sha256: String) -> Boolean = { _, _ -> true },
        commit: () -> Unit,
        timestamp: () -> String = { LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) },
    ): Result {
        val source = from.absoluteFile
        val target = to.absoluteFile
        if (source == target || target.startsWith(source) || source.startsWith(target)) {
            return Result.Failed("The two folders must be apart")
        }
        val files = try {
            regularFiles(source)
        } catch (error: Exception) {
            return Result.Failed(error.message ?: "The working copy could not be read")
        }
        val needed = files.sumOf { it.length() }
        val room = generateSequence(target) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L
        if (files.isNotEmpty() && room in 1 until needed + SPACE_MARGIN_BYTES) {
            return Result.Failed("Not enough free space for the copy")
        }
        // What is already there is somebody's copy too: it is kept beside the new one.
        var setAside: File? = null
        if (target.exists() && (target.isFile || target.list().orEmpty().isNotEmpty())) {
            val aside = File(target.parentFile, "${target.name}.replaced-${timestamp()}")
            if (aside.exists() || !target.renameTo(aside)) return Result.Failed("The folder ${target.name} is in the way")
            setAside = aside
        }
        fun undo() {
            target.deleteRecursively()
            setAside?.renameTo(target)
        }
        val copied = mutableListOf<Copied>()
        try {
            check(target.isDirectory || target.mkdirs()) { "The folder ${target.name} could not be created" }
            for (file in files) {
                val path = file.relativeTo(source).invariantSeparatorsPath
                val destination = File(target, path)
                destination.parentFile?.mkdirs()
                val written = copy(file, destination)
                check(sha256(destination) == written) { "The copy of $path could not be verified" }
                destination.setLastModified(file.lastModified())
                copied += Copied(path, written)
            }
            copied.firstOrNull { !accept(it.path, it.sha256) }?.let { refused ->
                undo()
                return Result.Failed("${refused.path} was not accepted")
            }
            commit()
        } catch (error: Exception) {
            undo()
            return Result.Failed(error.message ?: error.javaClass.simpleName)
        }
        // From here [to] is the working copy. A file that changed after it was copied is not the
        // copy's twin any more: it stays where it is.
        val leftBehind = mutableListOf<String>()
        for (entry in copied) {
            val original = File(source, entry.path)
            if (!original.isFile) continue
            if (runCatching { sha256(original) }.getOrNull() == entry.sha256) original.delete() else leftBehind += entry.path
        }
        source.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() } // Only empty folders go.
        return Result.Moved(copied.size, leftBehind)
    }

    /** Every file below [root]; a link or special file is not something a working copy holds. */
    private fun regularFiles(root: File): List<File> {
        if (!root.exists()) return emptyList()
        check(root.isDirectory) { "${root.name} is not a folder" }
        return root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) || error("${it.name} is a link") }
            .filter { !it.isDirectory }
            .onEach { check(Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)) { "${it.name} is not a regular file" } }
            .toList()
    }

    /** Copies durably and returns the SHA-256 of the bytes written. */
    private fun copy(from: File, to: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(from).use { input ->
            FileOutputStream(to).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024
}

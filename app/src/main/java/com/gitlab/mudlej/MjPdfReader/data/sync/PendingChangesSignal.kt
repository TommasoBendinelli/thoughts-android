// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.os.FileObserver
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-wide invalidation for changes that have already been durably saved locally. */
object PendingChangesSignal {
    private val mutableGeneration = MutableStateFlow(0L)

    val generation: StateFlow<Long> = mutableGeneration.asStateFlow()

    fun notifyChanged() {
        mutableGeneration.update { current -> current + 1L }
    }
}

/**
 * Watches durable roots recursively and their parents one level deep.
 *
 * The direct parent watch detects atomic replacement or first creation of a configured root. It
 * must never be recursive: a review root commonly lives under public Documents, whose unrelated
 * descendants can be both large and slow to enumerate.
 */
class PendingChangesFileObserver(
    private val roots: Collection<File>,
) {
    private val observers = linkedMapOf<String, FileObserver>()
    private var generation = 0L

    fun start() {
        val watchGeneration = synchronized(this) {
            generation += 1
            stopLocked()
            generation
        }
        val plan = pendingChangesWatchPlan(roots)
        plan.directParentRoots.forEach { (parent, childRoots) ->
            observeParent(parent, childRoots, watchGeneration)
        }
        plan.recursiveRoots.forEach { root ->
            observeTree(root, root, watchGeneration)
        }
    }

    @Synchronized
    fun stop() {
        generation += 1
        stopLocked()
    }

    private fun stopLocked() {
        observers.values.forEach(FileObserver::stopWatching)
        observers.clear()
    }

    private fun observeTree(root: File, scopeRoot: File, watchGeneration: Long) {
        pendingChangesRecursiveDirectories(root).forEach { directory ->
            observeDirectory(directory, scopeRoot, watchGeneration)
        }
    }

    private fun observeParent(
        parent: File,
        childRoots: Set<File>,
        watchGeneration: Long,
    ) {
        if (!parent.isDirectory) return
        val key = "parent:${parent.absolutePath}"
        val observer = object : FileObserver(parent, WATCH_MASK) {
            override fun onEvent(event: Int, relativePath: String?) {
                if (!isObserverCurrent(key, this, watchGeneration) || relativePath == null) return
                val changed = normalizedFile(File(parent, relativePath))
                val root = childRoots.firstOrNull { it.absolutePath == changed.absolutePath } ?: return
                PendingChangesSignal.notifyChanged()
                if (event and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0) {
                    removeTreeObserversUnder(root, watchGeneration)
                }
                if (event and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0) {
                    // The new root can have the same path but a different inode. Release every
                    // observer attached to the old tree before installing the replacement tree.
                    removeTreeObserversUnder(root, watchGeneration)
                    observeTree(root, root, watchGeneration)
                }
            }
        }
        installObserver(key, observer, watchGeneration)
    }

    private fun observeDirectory(
        directory: File,
        scopeRoot: File,
        watchGeneration: Long,
    ) {
        val key = "tree:${directory.absolutePath}"
        val observer = object : FileObserver(directory, WATCH_MASK) {
            override fun onEvent(event: Int, relativePath: String?) {
                if (!isObserverCurrent(key, this, watchGeneration)) return
                PendingChangesSignal.notifyChanged()
                if (event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) {
                    removeTreeObserversUnder(directory, watchGeneration)
                    return
                }
                if (relativePath != null) {
                    val changed = normalizedFile(File(directory, relativePath))
                    if (event and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0) {
                        removeTreeObserversUnder(changed, watchGeneration)
                    }
                    if (
                        event and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 &&
                        changed.isWithin(scopeRoot)
                    ) {
                        observeTree(changed, scopeRoot, watchGeneration)
                    }
                }
            }
        }
        installObserver(key, observer, watchGeneration)
    }

    @Synchronized
    private fun installObserver(key: String, observer: FileObserver, watchGeneration: Long) {
        if (generation != watchGeneration || key in observers) return
        observers[key] = observer
        observer.startWatching()
    }

    @Synchronized
    private fun isObserverCurrent(
        key: String,
        observer: FileObserver,
        watchGeneration: Long,
    ): Boolean = generation == watchGeneration && observers[key] === observer

    @Synchronized
    private fun removeTreeObserversUnder(root: File, watchGeneration: Long) {
        if (generation != watchGeneration) return
        pendingChangesTreeObserverKeysUnder(observers.keys, root).forEach { key ->
            observers.remove(key)?.stopWatching()
        }
    }

    private fun File.isWithin(root: File): Boolean {
        val rootPath = root.absolutePath.let { path ->
            if (path.length > 1) path.trimEnd(File.separatorChar) else path
        }
        val path = absolutePath
        return path == rootPath || path.startsWith("$rootPath${File.separator}")
    }

    private companion object {
        const val WATCH_MASK = FileObserver.CLOSE_WRITE or FileObserver.CREATE or
            FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}

internal data class PendingChangesWatchPlan(
    val recursiveRoots: List<File>,
    val directParentRoots: Map<File, Set<File>>,
)

internal fun pendingChangesWatchPlan(roots: Collection<File>): PendingChangesWatchPlan {
    val normalizedRoots = roots.map(::normalizedFile)
        .filter { it.parentFile != null }
        .distinctBy(File::getAbsolutePath)
    return PendingChangesWatchPlan(
        recursiveRoots = normalizedRoots,
        directParentRoots = normalizedRoots.mapNotNull { root ->
            root.parentFile?.let { parent -> normalizedFile(parent) to root }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, children) -> children.toSet() },
    )
}

internal fun pendingChangesRecursiveDirectories(root: File): Sequence<File> {
    val normalizedRoot = normalizedFile(root)
    if (!normalizedRoot.isDirectory) return emptySequence()
    return normalizedRoot.walkTopDown()
        .onEnter { directory -> normalizedFile(directory).isWithinPath(normalizedRoot) }
        .filter(File::isDirectory)
        .map(::normalizedFile)
        .filter { directory -> directory.isWithinPath(normalizedRoot) }
        .distinctBy(File::getAbsolutePath)
}

internal fun pendingChangesTreeObserverKeysUnder(keys: Collection<String>, root: File): List<String> {
    val normalizedRoot = normalizedFile(root)
    return keys.filter { key ->
        key.startsWith(TREE_OBSERVER_KEY_PREFIX) &&
            normalizedFile(File(key.removePrefix(TREE_OBSERVER_KEY_PREFIX))).isWithinPath(normalizedRoot)
    }
}

private fun File.isWithinPath(root: File): Boolean {
    val rootPath = root.absolutePath.let { path ->
        if (path.length > 1) path.trimEnd(File.separatorChar) else path
    }
    val path = absolutePath
    return path == rootPath || path.startsWith("$rootPath${File.separator}")
}

private fun normalizedFile(file: File): File = runCatching { file.canonicalFile }
    .getOrElse { file.absoluteFile }

private const val TREE_OBSERVER_KEY_PREFIX = "tree:"

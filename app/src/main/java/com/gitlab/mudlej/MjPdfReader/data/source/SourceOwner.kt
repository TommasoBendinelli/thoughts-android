// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

/**
 * The source folder owning a docs-root-relative path: a connected `docs-<name>` folder or a
 * Project's default folder `.thoughts/projects/<64 hex>/default` (legacy spelling `…/documents`,
 * still accepted; see [DefaultFolder]). Every owner (project key)
 * is derived here; the first segment alone is never an owner, since every default folder
 * starts with `.thoughts`.
 */
internal object SourceOwner {
    private val connected = Regex("docs-[A-Za-z0-9_.-]+")
    private val projectKey = Regex("[0-9a-f]{64}")

    fun of(path: String): String? {
        val segments = path.split('/')
        if (connected.matches(segments[0])) return segments[0]
        if (segments.size >= 4 && segments[0] == ".thoughts" && segments[1] == "projects" &&
            projectKey.matches(segments[2]) && segments[3] in DefaultFolder.SEGMENTS) return segments.take(4).joinToString("/")
        return null
    }

    fun isOwner(value: String): Boolean = of(value) == value

    /** True for a Project's default folder owner (not a connected docs-* folder). */
    fun isDefaultFolder(owner: String): Boolean = isOwner(owner) && owner.startsWith(".thoughts/")

    /** The path below its owner, or null for a path without one. */
    fun relative(path: String): String? = of(path)?.let { owner -> path.removePrefix(owner).removePrefix("/") }
}

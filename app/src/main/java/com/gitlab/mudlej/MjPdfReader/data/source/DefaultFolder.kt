// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * A Project's default source folder, `.thoughts/projects/<sha256(project id)>/<segment>`.
 *
 * The folder used to be called `documents` (the legacy segment) and is now called `default`
 * (the canonical segment). Both are accepted everywhere until every Library has moved; a
 * Project has exactly one. [rewriteLegacyDefaultFolder] is the one rewrite there is (Rule R
 * of the default-folder rename): Fedora, the desktop and this phone produce the same bytes.
 */
internal object DefaultFolder {
    const val CANONICAL = "default"
    const val LEGACY = "documents"
    val SEGMENTS = setOf(CANONICAL, LEGACY)

    private val legacy = Regex("""\.thoughts/projects/([0-9a-f]{64})/documents(?=/|"|'|\\|$|\s)""")
    private val canonicalOwner = Regex("""^(\.thoughts/projects/[0-9a-f]{64})/default(?=/|$)""")
    private val anyFolder = Regex("""\.thoughts/projects/[0-9a-f]{64}/(default|documents)(?=/|"|'|\\|$|\s)""")
    private val canonicalAnywhere = Regex("""\.thoughts/projects/[0-9a-f]{64}/default(?=/|"|'|\\|$|\s)""")

    /**
     * Rule R: every `.thoughts/projects/<64 hex>/documents` followed by `/`, a quote, a
     * backslash, whitespace or the end becomes `.thoughts/projects/<64 hex>/default`.
     * Textual, so it also rewrites absolute paths, `../` paths and paths inside JSON strings.
     */
    fun rewriteLegacyDefaultFolder(text: String): String =
        if (!text.contains("/documents")) text else legacy.replace(text) { ".thoughts/projects/${it.groupValues[1]}/default" }

    fun containsLegacy(text: String): Boolean = text.contains("/documents") && legacy.containsMatchIn(text)

    /** True when [text] names a default folder in the canonical spelling. */
    fun containsCanonical(text: String): Boolean = text.contains("/default") && canonicalAnywhere.containsMatchIn(text)

    /**
     * The legacy spelling of a docs-root-relative path in a default folder. Only for hash inputs
     * that must never change with the folder name (Rule 4: Library PDF names).
     */
    fun legacySpelling(path: String): String = canonicalOwner.replace(path) { "${it.groupValues[1]}/$LEGACY" }

    /** The segment of [owner] (`default` or `documents`), or null when it is not a default folder. */
    fun segmentOf(owner: String): String? = SourceOwner.of(owner)?.takeIf(SourceOwner::isDefaultFolder)?.substringAfterLast('/')

    fun projectKey(projectId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(projectId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun path(projectId: String, segment: String): String {
        require(segment in SEGMENTS)
        return ".thoughts/projects/${projectKey(projectId)}/$segment"
    }

    /**
     * Rule 5: the segment a new default folder gets — `default` once any known Project's
     * default folder is called so, otherwise `documents`. Fedora decides for real at Sync.
     */
    fun librarySegment(projects: Iterable<JsonElement>): String =
        if (projects.any { value ->
                value.isJsonObject && value.asJsonObject.getAsJsonObject("project_configuration")
                    ?.let(::configuredDefaultFolder)?.let(::segmentOf) == CANONICAL
            }) CANONICAL else LEGACY

    /** The manifest's `source_folders[default_folder_id].path`, when it names one. */
    fun configuredDefaultFolder(configuration: JsonObject): String? {
        val defaultId = configuration.get("default_folder_id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return configuration.getAsJsonArray("source_folders")?.firstOrNull { value ->
            value.isJsonObject && value.asJsonObject.get("folder_id")?.takeIf { it.isJsonPrimitive }?.asString == defaultId
        }?.asJsonObject?.get("path")?.takeIf { it.isJsonPrimitive }?.asString
    }

    /** Rule 2 on disk: `<root>/.thoughts/projects/<key>/default` if it exists, else `…/documents`. */
    fun locate(root: File, projectId: String): File {
        val canonical = File(root, path(projectId, CANONICAL))
        return if (canonical.exists()) canonical else File(root, path(projectId, LEGACY))
    }

    /**
     * Rule R on every string value of [value], except values of keys [skip] selects and
     * multi-line strings (source text, which Fedora never rewrites). Returns [value] itself when
     * nothing changed.
     */
    fun rewriteJsonValues(value: JsonElement, skip: (String) -> Boolean = SOURCE_TEXT): JsonElement = when {
        value.isJsonObject -> {
            val source = value.asJsonObject
            var copy: JsonObject? = null
            for ((key, child) in source.entrySet()) {
                val rewritten = if (skip(key)) child else rewriteJsonValues(child, skip)
                if (rewritten !== child) {
                    if (copy == null) copy = JsonObject().also { target -> source.entrySet().forEach { target.add(it.key, it.value) } }
                    copy.add(key, rewritten)
                }
            }
            copy ?: source
        }
        value.isJsonArray -> {
            val source = value.asJsonArray
            var copy: JsonArray? = null
            source.forEachIndexed { index, child ->
                val rewritten = rewriteJsonValues(child, skip)
                if (rewritten !== child) {
                    if (copy == null) copy = source.deepCopy()
                    copy!!.set(index, rewritten)
                }
            }
            copy ?: source
        }
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
            val text = value.asString
            if ('\n' in text) value else rewriteLegacyDefaultFolder(text).let { if (it == text) value else JsonPrimitive(it) }
        }
        else -> value
    }

    /** Keys holding source text (draft, conflict and comment contents): never rewritten. */
    val SOURCE_TEXT: (String) -> Boolean = { key -> key == "contents" || key.endsWith("_contents") }

    /** True for a string anywhere in [text] that names a default folder of either spelling. */
    fun containsAny(text: String): Boolean = anyFolder.containsMatchIn(text)

    /**
     * Set once this phone's local state has moved to `default` (see DefaultFolderMigration).
     * Source bundles Fedora built before its own migration still name `documents`; they are
     * read through Rule R from then on, so the phone never sees both spellings at once.
     */
    @Volatile var localStateMigrated: Boolean = false
}

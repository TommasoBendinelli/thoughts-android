// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Review tag groups (e.g. Understanding, Interest). Groups only organize the tag list; any tags
 * from any groups may still be chosen. The whole layout is one synced record per account
 * (`review_tag_groups`, entity `groups`), last write wins. Tags that are in no group show in an
 * implicit last group, "Other", which is never stored.
 *
 * A group may carry `scale`: its ordered steps (e.g. "I don't get it" … "I get it"), which are also
 * listed in its `tags` so clients without sliders show them as chips. An answer holds at most one
 * step of a scale ([ReviewTagScales]); the group's other tags stay free. Answers store plain tags.
 * Fields this client does not know (on the record or on a group) are kept when it rewrites the record.
 *
 * A group may also carry `section`: a display header. Groups with the same section (exact match)
 * that are adjacent in record order show under one collapsible header ([sectionRuns]); the section
 * has no tags or note of its own and never reorders the groups.
 *
 * A group may also carry `phase`: when a review asks it (review in two steps). [PHASE_BEFORE]: only
 * before the answer is shown (e.g. Feeling); [PHASE_BEFORE_AND_AFTER]: before (a prediction) and
 * again after it, starting at the prediction (e.g. Recall). Absent: only after the answer. Any other
 * value (a later client's) stays in [Group.extra], unchanged, and the group counts as after.
 */
object ReviewTagGroups {
    const val ENTITY_TYPE = "review_tag_groups"
    const val COLLECTION = ReviewTagMerges.COLLECTION
    const val ENTITY_ID = "groups"
    const val SCHEMA_VERSION = 1
    /** The name of the implicit group; no stored group may use it. */
    const val OTHER_NAME = "Other"
    const val MAX_GROUPS = 50
    const val MAX_GROUP_ID_LENGTH = 64
    const val MAX_NAME_LENGTH = 80
    const val MAX_TAG_LENGTH = 200
    const val MAX_TAGS = 1000
    const val MAX_SECTION_LENGTH = 60
    /** [Group.phase]: asked only before the answer is shown. */
    const val PHASE_BEFORE = "before"
    /** [Group.phase]: asked before the answer (a prediction) and again after it (the actual value). */
    const val PHASE_BEFORE_AND_AFTER = "before_and_after"
    /** The phases this client knows; the server accepts exactly these. */
    val PHASES = listOf(PHASE_BEFORE, PHASE_BEFORE_AND_AFTER)

    /**
     * [scale]: the slider steps in order (each also in [tags]), or null for a group shown as chips.
     * [section]: the header the group shows under (trimmed, non-empty), or null for none.
     * [phase]: one of [PHASES], or null for a group asked only after the answer.
     * [extra]: fields of the stored group this client does not know, written back unchanged.
     */
    data class Group(
        val id: String,
        val name: String,
        val tags: List<String>,
        val scale: List<String>? = null,
        val section: String? = null,
        val phase: String? = null,
        val extra: Map<String, JsonElement> = emptyMap(),
    ) {
        val isScale: Boolean get() = scale != null
        /** Shown in the before panel, while the answer is still hidden. */
        val askedBefore: Boolean get() = phase == PHASE_BEFORE || phase == PHASE_BEFORE_AND_AFTER
        /** Shown in the tag sheet once the answer is shown (every group but the before-only ones). */
        val askedAfter: Boolean get() = phase != PHASE_BEFORE
        /** The group's tags that are not slider steps (all of them for a chip group). */
        val freeTags: List<String> get() = scale?.let { steps -> tags.filter { it !in steps } } ?: tags
    }

    /** [extra]: fields of the stored record this client does not know, written back unchanged. */
    data class Layout(
        val groups: List<Group>,
        val updatedAt: String,
        val originId: String,
        val extra: Map<String, JsonElement> = emptyMap(),
    ) {
        fun payload(): JsonObject = JsonObject().apply {
            addProperty("layout_id", ENTITY_ID)
            addProperty("schema_version", SCHEMA_VERSION)
            add("groups", JsonArray().apply {
                groups.forEach { group ->
                    add(JsonObject().apply {
                        addProperty("group_id", group.id)
                        addProperty("name", group.name)
                        add("tags", JsonArray().apply { group.tags.forEach(::add) })
                        group.scale?.let { steps -> add("scale", JsonArray().apply { steps.forEach(::add) }) }
                        group.section?.let { addProperty("section", it) }
                        group.phase?.let { addProperty("phase", it) }
                        group.extra.forEach { (key, value) -> if (!has(key)) add(key, value.deepCopy()) }
                    })
                }
            })
            addProperty("updated_at", updatedAt)
            addProperty("origin_id", originId)
            extra.forEach { (key, value) -> if (!has(key)) add(key, value.deepCopy()) }
        }

        /** True when the record uses sliders, which only servers with [SCALES_CAPABILITY] accept. */
        val usesScales: Boolean get() = groups.any { it.isScale }

        /** True when the record has sections, which only servers with [SECTIONS_CAPABILITY] accept. */
        val usesSections: Boolean get() = groups.any { it.section != null }

        /** True when a group carries `phase` (also one this client does not know), which needs [PHASES_CAPABILITY]. */
        val usesPhases: Boolean get() = groups.any { it.phase != null || it.extra["phase"]?.isJsonNull == false }
    }

    /** Why an edit cannot be made; the record would no longer be valid. */
    enum class Problem {
        NAME_EMPTY, NAME_TOO_LONG, NAME_RESERVED, NAME_TAKEN, TOO_MANY_GROUPS, TAG_TOO_LONG, TOO_MANY_TAGS, SECTION_TOO_LONG,
        NO_SUCH_GROUP, INVALID,
    }

    class EditRefused(val problem: Problem) : IllegalArgumentException(problem.name)

    sealed class Edit {
        /** A new last group; [tag] (if any) moves into it at once ("Move to group… → New group…"). */
        data class AddGroup(val name: String, val tag: String? = null, val id: String = UUID.randomUUID().toString()) : Edit()
        data class Rename(val groupId: String, val name: String) : Edit()
        /** [offset] -1 moves the group up, +1 down; at either end nothing changes. */
        data class MoveGroup(val groupId: String, val offset: Int) : Edit()
        /** The group's tags go back to Other (they simply leave the record). */
        data class DeleteGroup(val groupId: String) : Edit()
        /** Moves (or, for a new tag, puts) [tag] in a group; a null [groupId] means Other. */
        data class MoveTag(val tag: String, val groupId: String?) : Edit()
        /** Moves several tags at once ("Move tags here…"), saved as one edit. */
        data class MoveTags(val tags: List<String>, val groupId: String?) : Edit()
        /**
         * Shows the group as a slider with [steps] in order ("Show as slider", "Slider steps…"), or as
         * chips again when [steps] is null ("Show as tags"). Steps join the group's tags (leaving any
         * other group); tags that stop being steps stay in the group as free tags.
         */
        data class SetScale(val groupId: String, val steps: List<String>?) : Edit()
        /** Puts the group under the header [section] ("Section…"), or under none when it is null or blank. */
        data class SetSection(val groupId: String, val section: String?) : Edit()
        /**
         * When a review asks the group ("Ask: After the answer / Before the answer / Before and after"):
         * one of [PHASES], or null for after the answer. Replaces a phase this client does not know.
         */
        data class SetPhase(val groupId: String, val phase: String?) : Edit()
    }

    /** A heading and its tags as shown; a null [group] is the implicit Other group. */
    data class Section(val group: Group?, val tags: List<String>)

    data class Upload(val operationId: String, val clientTimestamp: String, val payload: JsonObject)

    /** The server capability for groups with `scale`; older servers refuse the field. */
    const val SCALES_CAPABILITY = "review_tag_scales_v1"
    /** The server capability for groups with `section`; older servers refuse the field. */
    const val SECTIONS_CAPABILITY = "review_tag_sections_v1"
    /** The server capability for groups with `phase`; older servers refuse the field. */
    const val PHASES_CAPABILITY = "review_tag_phases_v1"
    private val KNOWN_LAYOUT_FIELDS = setOf("layout_id", "schema_version", "groups", "updated_at", "origin_id")
    private val KNOWN_GROUP_FIELDS = setOf("group_id", "name", "tags", "scale", "section")

    // ---- Pure rules -------------------------------------------------------------------------

    /** Group names are trimmed and their whitespace collapsed. */
    fun normalizeName(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    /** A section as stored: trimmed; null when blank (no section). */
    fun normalizeSection(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The group whose step the agent predicts (review in two steps): the first one asked before and
     * after the answer that is a slider with steps; null when there is none (nothing is requested).
     */
    fun recallGroup(groups: List<Group>): Group? =
        groups.firstOrNull { it.phase == PHASE_BEFORE_AND_AFTER && !it.scale.isNullOrEmpty() }

    /** The section names in use, in record order, each once ("Section…" offers them). */
    fun sectionNames(groups: List<Group>): List<String> = groups.mapNotNull { it.section }.distinct()

    /**
     * For each group of [groups] (in record order), the index of the section header it shows under:
     * adjacent groups with the same section share one; a section that comes back after other groups
     * gets a new one. Null for a group with no section.
     */
    fun sectionRuns(groups: List<Group>): List<Int?> {
        var run = -1
        return groups.mapIndexed { at, group ->
            group.section?.let { section ->
                if (at == 0 || groups[at - 1].section != section) run++
                run
            }
        }
    }

    fun nameProblem(groups: List<Group>, name: String, exceptGroupId: String? = null): Problem? {
        val normalized = normalizeName(name)
        return when {
            normalized.isEmpty() -> Problem.NAME_EMPTY
            normalized.length > MAX_NAME_LENGTH -> Problem.NAME_TOO_LONG
            normalized.equals(OTHER_NAME, ignoreCase = true) -> Problem.NAME_RESERVED
            groups.any { it.id != exceptGroupId && it.name.equals(normalized, ignoreCase = true) } -> Problem.NAME_TAKEN
            else -> null
        }
    }

    /** Reads a record from the server or from storage; anything that breaks the rules is refused (null). */
    fun parse(payload: JsonObject): Layout? = runCatching {
        require(payload.get("layout_id").asString == ENTITY_ID)
        require(payload.get("schema_version").asInt == SCHEMA_VERSION)
        val updatedAt = payload.get("updated_at").asString.also { require(it.isNotBlank()) }
        val originId = payload.get("origin_id")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val groups = payload.getAsJsonArray("groups").map { element ->
            val group = element.asJsonObject
            val phase = phaseOf(group.get("phase"))
            Group(
                id = group.get("group_id").asString,
                name = group.get("name").asString.trim(),
                tags = group.getAsJsonArray("tags").map { it.asString },
                scale = group.get("scale")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { it.asString },
                section = sectionOf(group.get("section")),
                phase = phase,
                // A phase this client does not know stays as it was.
                extra = extraFields(group, if (phase != null) KNOWN_GROUP_FIELDS + "phase" else KNOWN_GROUP_FIELDS),
            )
        }
        require(problemOf(groups) == null)
        Layout(dedupe(groups), updatedAt, originId, extraFields(payload, KNOWN_LAYOUT_FIELDS))
    }.getOrNull()

    /** A stored section: a string, trimmed, at most [MAX_SECTION_LENGTH]; anything else counts as none. */
    private fun sectionOf(value: JsonElement?): String? =
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.let(::normalizeSection)?.takeIf { it.length <= MAX_SECTION_LENGTH }

    /** A known phase ([PHASES]); anything else is not this client's to read. */
    private fun phaseOf(value: JsonElement?): String? =
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it in PHASES }

    private fun extraFields(json: JsonObject, known: Set<String>): Map<String, JsonElement> =
        json.entrySet().filter { it.key !in known }.associate { it.key to it.value.deepCopy() }

    /** The first rule [groups] break, or null when they form a valid record. */
    internal fun problemOf(groups: List<Group>): Problem? {
        if (groups.size > MAX_GROUPS) return Problem.TOO_MANY_GROUPS
        val ids = hashSetOf<String>()
        groups.forEachIndexed { index, group ->
            if (group.id.isBlank() || group.id.length > MAX_GROUP_ID_LENGTH || !ids.add(group.id)) return Problem.INVALID
            nameProblem(groups.take(index), group.name)?.let { return it }
            if (group.tags.any { it.isBlank() }) return Problem.INVALID
            if (group.tags.any { it.length > MAX_TAG_LENGTH }) return Problem.TAG_TOO_LONG
            group.scale?.let { steps ->
                if (steps.any { it.isBlank() }) return Problem.INVALID
                if (steps.any { it.length > MAX_TAG_LENGTH }) return Problem.TAG_TOO_LONG
            }
            group.section?.let { section ->
                if (normalizeSection(section) != section) return Problem.INVALID
                if (section.length > MAX_SECTION_LENGTH) return Problem.SECTION_TOO_LONG
            }
            if (group.phase != null && group.phase !in PHASES) return Problem.INVALID
        }
        if (groups.sumOf { it.tags.size } > MAX_TAGS) return Problem.TOO_MANY_TAGS
        return null
    }

    /**
     * A tag listed twice stays where it is first listed. A step missing from its group's tags is
     * added to them; a step listed in an earlier group (or twice) is dropped from the scale.
     */
    private fun dedupe(groups: List<Group>): List<Group> {
        val seen = hashSetOf<String>()
        return groups.map { group ->
            val listed = (group.tags + group.scale.orEmpty().filter { it !in group.tags }).distinct()
            val tags = listed.filter { seen.add(it) }
            group.copy(tags = tags, scale = group.scale?.filter { it in tags }?.distinct())
        }
    }

    /** Tag names as the client compares them: normalized, merged names resolved; Skip is not a tag. */
    internal fun canonicalTag(tag: String, mapping: Map<String, String>): String? =
        ReviewTags.normalize(tag)?.let { mapping[it] ?: it }?.takeUnless { it.equals(ReviewTags.SKIP, ignoreCase = true) }

    /** The groups as read: tag names mapped through the merges, duplicates collapsed, Skip dropped. */
    fun resolved(groups: List<Group>, mapping: Map<String, String>): List<Group> =
        dedupe(groups.map { group ->
            group.copy(
                tags = group.tags.mapNotNull { canonicalTag(it, mapping) },
                scale = group.scale?.mapNotNull { canonicalTag(it, mapping) },
            )
        })

    /**
     * Groups in order, then Other with every [known] tag that is in no group. Group headings stay
     * even when empty; Other is hidden when empty. Tags keep the order of [known] (most used first);
     * group tags this phone has not seen yet follow alphabetically.
     */
    fun sections(groups: List<Group>, known: List<String>, mapping: Map<String, String>): List<Section> {
        val shown = resolved(groups, mapping)
        val knownTags = known.mapNotNull { canonicalTag(it, mapping) }.distinct()
        val rank = knownTags.withIndex().associate { it.value to it.index }
        val order = compareBy<String> { rank[it] ?: Int.MAX_VALUE }.thenBy { it.lowercase() }
        val grouped = shown.flatMapTo(hashSetOf()) { it.tags }
        val other = knownTags.filter { it !in grouped }
        return shown.map { Section(it, it.tags.sortedWith(order)) } +
            listOfNotNull(Section(null, other).takeIf { other.isNotEmpty() })
    }

    /** Applies [edit] to [groups], giving the whole new list; refuses edits that break the rules. */
    fun applyEdit(groups: List<Group>, edit: Edit, mapping: Map<String, String> = emptyMap()): List<Group> {
        val current = resolved(groups, mapping)
        fun index(id: String) = current.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: throw EditRefused(Problem.NO_SUCH_GROUP)
        fun withoutTag(tag: String, from: List<Group> = current) =
            from.map { group -> group.copy(tags = group.tags - tag, scale = group.scale?.minus(tag)) }
        val next = when (edit) {
            is Edit.AddGroup -> {
                nameProblem(current, edit.name)?.let { throw EditRefused(it) }
                if (current.size >= MAX_GROUPS) throw EditRefused(Problem.TOO_MANY_GROUPS)
                val tag = edit.tag?.let { canonicalTag(it, mapping) }
                (if (tag == null) current else withoutTag(tag)) + Group(edit.id, normalizeName(edit.name), listOfNotNull(tag))
            }
            is Edit.Rename -> {
                val at = index(edit.groupId)
                nameProblem(current, edit.name, edit.groupId)?.let { throw EditRefused(it) }
                current.toMutableList().apply { this[at] = this[at].copy(name = normalizeName(edit.name)) }
            }
            is Edit.MoveGroup -> {
                val at = index(edit.groupId)
                val to = (at + edit.offset).coerceIn(0, current.lastIndex)
                current.toMutableList().apply { add(to, removeAt(at)) }
            }
            is Edit.DeleteGroup -> current.filterIndexed { at, _ -> at != index(edit.groupId) }
            is Edit.MoveTag -> {
                val tag = canonicalTag(edit.tag, mapping) ?: return current
                val target = edit.groupId?.let(::index)
                withoutTag(tag).mapIndexed { at, group -> if (at == target) group.copy(tags = group.tags + tag) else group }
            }
            is Edit.MoveTags -> edit.tags.fold(current) { groups, tag -> applyEdit(groups, Edit.MoveTag(tag, edit.groupId), mapping) }
            is Edit.SetScale -> {
                val at = index(edit.groupId)
                val steps = edit.steps?.mapNotNull { canonicalTag(it, mapping) }?.distinct()
                if (steps == null) {
                    current.toMutableList().apply { this[at] = this[at].copy(scale = null) }
                } else {
                    // Steps leave other groups; tags of this group that are not steps stay as free tags.
                    val elsewhere = steps.filter { it !in current[at].tags }
                    val cleared = elsewhere.fold(current) { groups, tag -> withoutTag(tag, groups) }
                    cleared.toMutableList().apply {
                        this[at] = this[at].copy(tags = this[at].tags + elsewhere, scale = steps)
                    }
                }
            }
            is Edit.SetSection -> {
                val at = index(edit.groupId)
                val section = normalizeSection(edit.section)
                if (section != null && section.length > MAX_SECTION_LENGTH) throw EditRefused(Problem.SECTION_TOO_LONG)
                current.toMutableList().apply { this[at] = this[at].copy(section = section) }
            }
            is Edit.SetPhase -> {
                val at = index(edit.groupId)
                if (edit.phase != null && edit.phase !in PHASES) throw EditRefused(Problem.INVALID)
                current.toMutableList().apply { this[at] = this[at].copy(phase = edit.phase, extra = this[at].extra - "phase") }
            }
        }
        problemOf(next)?.let { throw EditRefused(it) }
        return next
    }

    /** Last write wins: the later `updated_at`, then the origin id, as the server orders them. */
    internal fun newer(candidate: Layout, than: Layout): Boolean {
        val a = runCatching { Instant.parse(candidate.updatedAt) }.getOrNull()
        val b = runCatching { Instant.parse(than.updatedAt) }.getOrNull()
        val byTime = if (a != null && b != null) a.compareTo(b) else candidate.updatedAt.compareTo(than.updatedAt)
        return if (byTime != 0) byTime > 0 else candidate.originId > than.originId
    }

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    internal fun timestamp(instant: Instant): String = TIMESTAMP.format(instant)

    internal fun uploadOf(layout: Layout): Upload {
        val payload = layout.payload()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val operationId = UUID.nameUUIDFromBytes(
            listOf(ENTITY_TYPE, ENTITY_ID, digest).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8),
        ).toString()
        return Upload(operationId, layout.updatedAt, payload)
    }

    // ---- Local record -----------------------------------------------------------------------

    private data class Stored(val layout: Layout?, val pending: Boolean)

    /** Overridable for tests; the app keeps the layout beside the tag merges. */
    @Volatile var file: () -> File = { File(ThoughtsLocalStore.reviewIncoming().parentFile, "tag-groups.json") }
    @Volatile private var cached: Triple<File, Long, Stored>? = null

    fun layout(): Layout? = load().layout

    fun groups(): List<Group> = layout()?.groups.orEmpty()

    /** True while a local edit has not reached the server (or been overtaken by a later one). */
    fun pending(): Boolean = load().pending

    /** The sections the tag sheet shows for the [known] tags (most used first). */
    fun sections(known: List<String>): List<Section> = sections(groups(), known, ReviewTagMerges.mapping())

    /**
     * Applies a local edit and saves the whole record with a fresh `updated_at`, to be sent on the
     * next Sync. Returns the layout in effect; an edit that changes nothing saves nothing.
     */
    @Synchronized
    fun edit(edit: Edit, originId: String, now: Instant = Instant.now()): Layout? {
        val previous = layout()
        val mapping = ReviewTagMerges.mapping()
        val before = resolved(previous?.groups.orEmpty(), mapping)
        val groups = applyEdit(before, edit, mapping)
        if (groups == before) return previous
        val last = previous?.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        // Always later than the record it replaces, even if the clock went back.
        val at = if (last != null && !now.isAfter(last)) last.plusMillis(1) else now
        return Layout(groups, timestamp(at), originId, previous?.extra.orEmpty()).also { write(Stored(it, pending = true)) }
    }

    /** The local edit still to be sent, if any. */
    fun pendingUpload(): Upload? = load().takeIf { it.pending }?.layout?.let(::uploadOf)

    /**
     * Installs the record delivered by Sync. A pending local edit is kept unless the server holds
     * the same record (it was accepted) or a later one (it lost).
     */
    @Synchronized
    fun installServer(payload: JsonObject?) {
        val server = payload?.let(::parse) ?: return
        val stored = load()
        val local = stored.layout
        when {
            local == server -> if (stored.pending) write(Stored(local, pending = false))
            !stored.pending || local == null || newer(server, local) -> write(Stored(server, pending = false))
        }
    }

    private fun load(): Stored {
        val target = runCatching(file).getOrNull() ?: return Stored(null, false)
        val stamp = if (target.isFile) target.lastModified() xor target.length() else -1L
        cached?.takeIf { it.first == target && it.second == stamp }?.let { return it.third }
        val value = runCatching {
            val saved = JsonParser.parseString(target.readText()).asJsonObject
            Stored(
                saved.get("record")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::parse),
                saved.get("pending")?.asBoolean == true,
            )
        }.getOrDefault(Stored(null, false))
        cached = Triple(target, stamp, value)
        return value
    }

    private fun write(stored: Stored) {
        val target = file()
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(JsonObject().apply {
            addProperty("schema_version", 1)
            stored.layout?.let { add("record", it.payload()) }
            addProperty("pending", stored.pending && stored.layout != null)
        }.toString())
        check(temporary.renameTo(target)) { "Could not save the tag groups." }
        cached = null
    }
}

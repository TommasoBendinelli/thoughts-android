// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.agents.AgentConversations
import com.gitlab.mudlej.MjPdfReader.ui.agents.AgentConversationLinks
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.data.sync.connectedSourceProjects
import com.gitlab.mudlej.MjPdfReader.data.sync.isIncludableSource
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Touch counterpart of the desktop Library project tree. */
class LibraryProjectPane(
    private val context: Context,
    private val scope: CoroutineScope,
    private val functions: HomeItemFunctions,
    private val pickPdfs: () -> Unit,
    private val pickHtml: () -> Unit,
    private val store: LibraryProjectStore = LibraryProjectStore(context),
    /** Visible agent conversations by Project id; read on [Dispatchers.IO]. Overridable for tests. */
    private val loadConversations: suspend () -> Map<String, List<AgentConversations.Record>> = {
        defaultConversations(context)
    },
) : RecyclerView.Adapter<LibraryProjectPane.Holder>() {
    private var projects = JsonArray()
    private var catalogueError: String? = null
    private var items = emptyList<HomeItem>()
    private var sourceIndex = SourceLinkedPdfIndexSnapshot()
    private val pref = Preferences(PreferenceManager.getDefaultSharedPreferences(context))
    private var cardCounts = emptyMap<String, Int>()
    private val expanded = mutableSetOf<String>()
    private val collapsedDocuments = mutableSetOf<String>()
    private val sourceExpanded = mutableSetOf<String>()
    private val conversationsExpanded = mutableSetOf<String>()
    private var conversations = emptyMap<String, List<AgentConversations.Record>>()
    private var importingProject: String? = null
    private var importingHtmlProject: String? = null
    private var importingHtmlSourceProject: String? = null
    private data class Row(val kind: String, val project: JsonObject? = null, val entry: JsonObject? = null, val depth: Int = 0)
    private var rows = listOf(Row("header"))
    class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun JsonObject.text(key: String) = get(key)?.asString.orEmpty()

    fun render(items: List<HomeItem>, index: SourceLinkedPdfIndexSnapshot) {
        this.items = items
        sourceIndex = index
        redraw()
        if (pref.getLibraryShowReviewCounts()) scope.launch {
            val counts = withContext(Dispatchers.IO) {
                runCatching { ReviewStore(context).cards() }.getOrDefault(emptyList())
                    // Active cards only: deleted and retired cards are not studied.
                    .filter { it.approved && it.schedule?.deleted != true && !it.retired }
                    .groupingBy { "${it.pdfStem}.pdf" }.eachCount()
            }
            if (counts != cardCounts) { cardCounts = counts; redraw() }
        }
        reloadConversations()
    }

    private fun reloadConversations() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try { loadConversations() } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    android.util.Log.w("ThoughtsAgentConversations", "Could not read agent conversations", error)
                    null
                }
            } ?: return@launch
            if (loaded != conversations) { conversations = loaded; redraw() }
        }
    }

    /**
     * Card and page counts the Home menu asks to show, or null when both are off. A Project's row
     * ([pagesByKind]) splits its pages into those of source-linked documents and of plain PDFs.
     */
    private fun details(documents: List<JsonObject>, extraFilenames: List<String> = emptyList(), pagesByKind: Boolean = false): String? {
        val parts = buildList {
            if (pref.getLibraryShowReviewCounts()) {
                val cards = (documents.map { it.text("filename") } + extraFilenames).distinct().sumOf { cardCounts[it] ?: 0 }
                add(if (cards == 1) context.getString(R.string.library_count_card) else context.getString(R.string.library_count_cards, cards))
            }
            if (pref.getLibraryShowPageCounts() && pagesByKind) {
                libraryPageCountsText(libraryPageCounts(documents, sourceIndex))?.let(::add)
            } else if (pref.getLibraryShowPageCounts()) {
                val pages = documents.sumOf { it.get("page_count")?.takeIf { value -> value.isJsonPrimitive }?.asInt ?: 0 }
                if (pages > 0) add(if (pages == 1) context.getString(R.string.library_count_page) else context.getString(R.string.library_count_pages, pages))
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun TextView.withDetails(title: CharSequence, details: String?) {
        text = if (details == null) title else android.text.SpannableStringBuilder(title).apply {
            append("\n")
            val start = length
            append(details)
            setSpan(android.text.style.RelativeSizeSpan(0.8f), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(com.google.android.material.color.MaterialColors.getColor(
                this@withDetails, com.google.android.material.R.attr.colorOnSurfaceVariant)), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** PDFs Fedora is building after a Sync that did not wait for them: their rows say "Updating…". */
    private var updatingPdfs: Set<String> = emptySet()

    private fun redraw() {
        updatingPdfs = runCatching {
            com.gitlab.mudlej.MjPdfReader.data.sync.PendingFedoraBuildStore(context).builds().flatMapTo(hashSetOf()) { it.documents }
        }.getOrDefault(emptySet())
        catalogueError = null
        projects = try { store.projects() } catch (error: Exception) {
            catalogueError = error.message ?: "The Project catalogue could not be read."
            JsonArray()
        }
        rows = buildList {
            add(Row("header"))
            if (catalogueError != null) add(Row("catalogue_error"))
            for (value in projects) {
                val project = value.asJsonObject
                add(Row("project", project))
                if (project.text("id") !in expanded) continue
                add(Row("documents", project, depth = 1))
                if (project.text("id") !in collapsedDocuments) {
                    project.getAsJsonArray("documents").forEach { add(Row("document", project, it.asJsonObject, 2)) }
                    if (project.getAsJsonArray("documents").isEmpty) add(Row("empty", project, depth = 2))
                }
                add(Row("sources", project, depth = 1))
                if (project.text("id") in sourceExpanded) project.getAsJsonArray("sources").forEach { location ->
                    val entry = location.asJsonObject
                    add(Row("location", project, entry, 2))
                    if (entry.text("id") in sourceExpanded) {
                        val seenFolders = mutableSetOf<String>()
                        entry.getAsJsonArray("files")?.map { it.asJsonObject }?.sortedBy { it.text("id") }?.forEach { file ->
                            val relative = file.text("id").removePrefix(entry.text("id").trimEnd('/') + "/")
                            val parts = relative.split('/').filter(String::isNotEmpty)
                            var parent = entry.text("id")
                            var visible = true
                            for ((index, name) in parts.dropLast(1).withIndex()) {
                                parent += "/$name"
                                if (visible && seenFolders.add(parent)) {
                                    add(Row("folder", project, JsonObject().apply {
                                        addProperty("id", parent); addProperty("name", name)
                                    }, 3 + index))
                                }
                                visible = visible && parent in sourceExpanded
                            }
                            if (visible) add(Row("source", project, file, 3 + parts.size - 1))
                        }
                    }
                }
                val projectId = project.text("id")
                add(Row("conversations", project, depth = 1))
                if (projectId in conversationsExpanded) {
                    val records = conversations[projectId].orEmpty()
                    records.forEach { add(Row("conversation", project, it.payload, 2)) }
                    if (records.isEmpty()) add(Row("conversations_empty", project, depth = 2))
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val model = rows[position]; val row = holder.row; row.removeAllViews()
        row.setPadding(dp(8 + model.depth * 14), dp(5), dp(2), dp(5)); row.minimumHeight = dp(54)
        row.setOnClickListener(null); row.setOnLongClickListener(null)
        row.alpha = 1f
        row.setBackgroundColor(if (model.kind in setOf("documents", "sources", "conversations"))
            com.google.android.material.color.MaterialColors.getColor(row, if (model.kind == "documents") com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorSurfaceVariant)
            else android.graphics.Color.TRANSPARENT)
        val project = model.project
        val label = TextView(context).apply { textSize = 15f; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun button(title: String, description: String = title, action: () -> Unit) {
            row.addView(MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = title; isAllCaps = false; contentDescription = description; minWidth = dp(48); minimumWidth = dp(48)
                setPadding(dp(8), 0, dp(8), 0); setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        }
        when (model.kind) {
            "header" -> {
                label.text = "Projects"
                // Creating a project was only reachable by long-pressing the PDF button.
                button(context.getString(R.string.library_add_project)) { addProject() }
            }
            "catalogue_error" -> { label.text = catalogueError }
            "project" -> {
                project!!; val id = project.text("id")
                val name = project.text("name")
                val documents = project.getAsJsonArray("documents")?.map { it.asJsonObject }.orEmpty()
                label.withDetails(android.text.SpannableString(name).apply {
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }, details(documents, listOfNotNull(project.text("target").takeIf(String::isNotBlank)), pagesByKind = true))
                label.minimumHeight = dp(48)
                label.gravity = Gravity.CENTER_VERTICAL
                label.contentDescription = context.getString(R.string.library_open_project_pdf, name)
                label.setOnClickListener { openProject(project) }
                label.setOnLongClickListener { projectMenu(project); true }
                val arrow = AppCompatImageButton(context).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    rotation = if (id in expanded) 90f else 0f
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    val background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                    setBackgroundResource(background.getResourceId(0, 0))
                    background.recycle()
                    contentDescription = context.getString(
                        if (id in expanded) R.string.library_collapse_project else R.string.library_expand_project, name)
                    ViewCompat.setStateDescription(this, context.getString(
                        if (id in expanded) R.string.library_project_expanded else R.string.library_project_collapsed))
                    setOnClickListener { if (!expanded.add(id)) expanded.remove(id); redraw() }
                }
                row.addView(arrow, 0, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.setOnClickListener { openProject(project) }
                row.setOnLongClickListener { projectMenu(project); true }
            }
            "documents" -> {
                val id = project!!.text("id")
                label.text = (if (id in collapsedDocuments) "›  " else "⌄  ") + "Documents"
                label.setTypeface(null, android.graphics.Typeface.BOLD)
                row.addView(androidx.appcompat.widget.AppCompatImageView(context).apply {
                    setImageResource(R.drawable.ic_library_documents)
                }, 0, LinearLayout.LayoutParams(dp(24), dp(24)))
                row.setOnClickListener { if (!collapsedDocuments.add(id)) collapsedDocuments.remove(id); redraw() }
                row.setOnLongClickListener { documentsMenu(project!!); true }
            }
            "document" -> {
                val doc = model.entry!!
                // Its new PDF is building on Fedora; the one shown until it arrives is the previous one.
                val updating = context.getString(R.string.document_status_updating).takeIf { doc.text("filename") in updatingPdfs }
                label.withDetails(doc.text("name"), listOfNotNull(details(listOf(doc)), updating).joinToString(" · ").ifEmpty { null })
                val capability = libraryDocumentSourceCapability(doc, sourceIndex)
                val missingSources = libraryDocumentMissingSources(doc, capability)
                val missing = missingSources.isNotEmpty()
                val description = if (missing) context.getString(R.string.library_document_missing_source, missingSources.joinToString(", "))
                    else context.getString(when (capability) {
                    com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability.SOURCE_LINKED -> R.string.library_document_source_linked
                    com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability.NOT_SOURCE_LINKED -> R.string.library_document_source_unlinked
                    else -> R.string.library_document_source_unknown
                })
                val icon = androidx.appcompat.widget.AppCompatImageView(context).apply {
                    setImageResource(if (missing) R.drawable.ic_source_missing else when (capability) {
                        com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability.SOURCE_LINKED -> R.drawable.ic_link
                        com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability.NOT_SOURCE_LINKED -> R.drawable.ic_link_off
                        else -> R.drawable.ic_link_warning
                    })
                    contentDescription = description
                    androidx.appcompat.widget.TooltipCompat.setTooltipText(this, description)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    if (missing) setOnClickListener {
                        MaterialAlertDialogBuilder(context).setTitle(doc.text("name"))
                            .setMessage(description).setPositiveButton(android.R.string.ok, null).show()
                    }
                }
                row.addView(icon, 0, LinearLayout.LayoutParams(dp(32), dp(32)))
                if ((doc.get("start_page")?.asInt ?: 1) <= 0) {
                    row.addView(androidx.appcompat.widget.AppCompatImageView(context).apply {
                        setImageResource(R.drawable.ic_schedule_small)
                        contentDescription = context.getString(R.string.library_document_waiting_first_build)
                        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                    }, 1, LinearLayout.LayoutParams(dp(32), dp(32)))
                }
                row.setOnClickListener { open(doc) }
                row.setOnLongClickListener { documentMenu(project!!, doc); true }
            }
            "empty" -> { label.text = "No documents yet. Use the Documents menu to import a PDF."; label.textSize = 13f }
            "sources", "location", "folder" -> {
                val entry = model.entry ?: project!!; val id = entry.text("id")
                val name = if (model.kind == "sources") "Sources"
                    else entry.text("name")
                val detail = if (model.kind == "location") "\n" + if (entry.text("kind") == "overleaf") "Overleaf · ${entry.text("id")}" else "Local · ${entry.text("id")}" else ""
                label.text = (if (id in sourceExpanded) "⌄  " else "›  ") + name + detail
                val folderIcon = androidx.appcompat.widget.AppCompatImageView(context).apply {
                    setImageResource(R.drawable.ic_folder)
                    contentDescription = null
                }
                row.addView(folderIcon, 0, LinearLayout.LayoutParams(dp(24), dp(24)))
                row.setOnClickListener { if (!sourceExpanded.add(id)) sourceExpanded.remove(id); redraw() }
            }
            "source" -> {
                val source = model.entry!!; label.text = source.text("name")
                val path = source.text("id")
                val html = com.gitlab.mudlej.MjPdfReader.ui.links.HtmlAttachmentLink.isPath(path)
                val openHtml = {
                    com.gitlab.mudlej.MjPdfReader.ui.html.HtmlAttachmentActivity.openSource(context, path)
                    Unit
                }
                if (html) row.setOnClickListener { openHtml() }
                val actions = if (html) listOf("Browser" to openHtml)
                    else if (isIncludableSource(project!!, path)) listOf("Add to final PDF" to {
                        change("include_source", project!!, JsonObject().apply { add("source_file", source.get("id")) })
                    }) else emptyList()
                if (actions.isNotEmpty()) row.setOnLongClickListener { sheet(source.text("name"), actions); true }
            }
            "conversations" -> {
                val id = project!!.text("id")
                val count = conversations[id].orEmpty().size
                label.text = (if (id in conversationsExpanded) "⌄  " else "›  ") +
                    context.getString(R.string.agent_conversations_group, count)
                row.addView(androidx.appcompat.widget.AppCompatImageView(context).apply {
                    setImageResource(R.drawable.ic_agent_conversation)
                    contentDescription = null
                }, 0, LinearLayout.LayoutParams(dp(24), dp(24)))
                row.setOnClickListener { if (!conversationsExpanded.add(id)) conversationsExpanded.remove(id); redraw() }
            }
            "conversations_empty" -> {
                label.text = context.getString(R.string.agent_conversations_empty); label.textSize = 13f
            }
            "conversation" -> {
                val record = AgentConversations.parse(model.entry!!) ?: return
                label.withDetails(record.title.ifBlank { record.conversationId.take(8) }, conversationDetails(record))
                row.addView(androidx.appcompat.widget.AppCompatImageView(context).apply {
                    setImageResource(R.drawable.ic_agent_conversation)
                    contentDescription = agentName(record)
                }, 0, LinearLayout.LayoutParams(dp(24), dp(24)))
                val url = record.claudeUrl
                if (url == null) row.alpha = 0.5f
                row.setOnClickListener { AgentConversationLinks.openOrExplain(context, record) }
                row.setOnLongClickListener { conversationMenu(project!!, record); true }
            }
        }
    }

    private fun agentName(record: AgentConversations.Record) = AgentConversationLinks.agentName(context, record)

    /** "Claude · <last active> · <session name>". */
    private fun conversationDetails(record: AgentConversations.Record): String = listOfNotNull(
        agentName(record),
        AgentConversations.instant(record.lastActiveAt)?.let {
            android.text.format.DateUtils.formatDateTime(context, it.toEpochMilli(),
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or
                    android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
        },
        record.sessionName.takeIf(String::isNotBlank),
    ).joinToString(" · ")

    private fun openConversation(url: String) = AgentConversationLinks.open(context, url)

    private fun conversationMenu(project: JsonObject, record: AgentConversations.Record) = sheet(record.title, buildList<Pair<String, () -> Unit>> {
        record.claudeUrl?.let { url -> add(context.getString(R.string.agent_conversation_open_in_claude) to { openConversation(url) }) }
        record.conversationUrl.takeIf(String::isNotEmpty)?.let { url ->
            add(context.getString(R.string.agent_conversation_copy_link) to { AgentConversationLinks.copyLink(context, url) })
        }
        add(context.getString(R.string.agent_conversation_remove) to {
            MaterialAlertDialogBuilder(context).setTitle(R.string.agent_conversation_remove_title)
                .setMessage(context.getString(R.string.agent_conversation_remove_message, project.text("name"), agentName(record)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.agent_conversation_remove_confirm) { _, _ -> removeConversation(record) }
                .show()
            Unit
        })
    })

    /** Stores the removal pending (it is sent at the next full Sync) and hides the row at once. */
    internal fun removeConversation(record: AgentConversations.Record) {
        val removed = try {
            AgentConversations.remove(record, ReviewStore(context).originIdentity())
        } catch (error: Exception) {
            android.util.Log.w("ThoughtsAgentConversations", "Could not remove an agent conversation", error)
            Toast.makeText(context, R.string.agent_conversation_remove_failed, Toast.LENGTH_LONG).show()
            return
        }
        conversations = conversations.mapValues { (_, records) -> records.filterNot { it.linkId == removed.linkId } }
        redraw()
        Toast.makeText(context, R.string.agent_conversation_removed, Toast.LENGTH_SHORT).show()
        reloadConversations()
    }

    private fun sheet(title: String, actions: List<Pair<String, () -> Unit>>) {
        val dialog = BottomSheetDialog(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
        column.addView(TextView(context).apply { text = title; textSize = 20f; setPadding(0, dp(8), 0, dp(12)) })
        actions.forEach { (title, action) -> column.addView(MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = title; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL; minHeight = dp(52)
            setOnClickListener { dialog.dismiss(); action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))) }
        dialog.setContentView(column); dialog.show()
    }

    private fun documentsMenu(project: JsonObject) = sheet("Documents · ${project.text("name")}", listOf(
        "Manage documents…" to { orderDialog(project) },
        (if (connectedSourceProjects(project).isNotEmpty()) "Import HTML, PDF, Website…" else "Import PDF, Website…") to { importMenu(project) },
    ))

    private fun importMenu(project: JsonObject) = sheet("Import into ${project.text("name")}", buildList {
        if (connectedSourceProjects(project).isNotEmpty()) add("Import HTML…" to { chooseHtmlSource(project) })
        add("Import PDF…" to { importingProject = project.text("id"); pickPdfs() })
        add("Import from website…" to { websiteImportDialog(project) })
    })

    private fun chooseHtmlSource(project: JsonObject) {
        val folders = connectedSourceProjects(project)
        if (folders.size == 1) {
            importingHtmlProject = project.text("id")
            importingHtmlSourceProject = folders.single()
            pickHtml()
            return
        }
        MaterialAlertDialogBuilder(context).setTitle("Choose source folder")
            .setItems(folders.toTypedArray()) { _, selected ->
                importingHtmlProject = project.text("id")
                importingHtmlSourceProject = folders[selected]
                pickHtml()
            }.show()
    }
    private fun websiteImportDialog(project: JsonObject) {
        WebsiteImportDialog(context, scope, project.text("name"), import = { ready, name ->
            val latest = store.projects().firstOrNull { it.asJsonObject.text("id") == project.text("id") }?.asJsonObject
                ?: error("This project is no longer in the Library")
            store.stageImport(latest, ready.file, name)
        }, onImported = {
            expanded.add(project.text("id")); redraw()
            Toast.makeText(context, "Saved locally. Tap Sync to share changes.", Toast.LENGTH_LONG).show()
        }).show()
    }
    private fun projectMenu(project: JsonObject) = sheet(project.text("name"), listOf(
        "Export combined PDF…" to {
            chooseProjectExportDocuments(context, project.getAsJsonArray("documents")?.mapNotNull { value ->
                val doc = value.asJsonObject
                (doc.text("document_id").ifBlank { doc.text("id") }).takeIf(String::isNotBlank)
                    ?.let { ProjectExportDocument(it, doc.text("name").ifBlank { it }) }
            }.orEmpty()) { chosen -> exportProject(project, chosen) }
        },
        "Rename…" to { nameDialog(project) }, "Remove project…" to {
            MaterialAlertDialogBuilder(context).setTitle("Remove ${project.text("name")}?")
                .setMessage("The project, its documents, comments and review cards will leave your Library when you Sync. Original files remain available.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove project") { _, _ -> change("remove", project) }.show()
        }))
    private fun exportProject(project: JsonObject, documentIds: List<String>?) {
        val dialog = MaterialAlertDialogBuilder(context).setTitle("Export combined PDF")
            .setMessage("Exporting the project…").setNegativeButton("Close", null).create()
        val job = scope.launch {
            try {
                val (file, older) = com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository(context).exportProjectPdf(project, documentIds) { message ->
                    withContext(Dispatchers.Main) { dialog.setMessage(message) }
                }
                dialog.dismiss()
                if (older) Toast.makeText(context, "The server has a newer version of this project; this export is the last synced one.", Toast.LENGTH_LONG).show()
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/pdf"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Project PDF", uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(share, "Save or share combined PDF"))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { dialog.dismiss(); Toast.makeText(context, error.message ?: "Export failed", Toast.LENGTH_LONG).show() }
        }
        dialog.setOnCancelListener { job.cancel() }
        dialog.setOnShowListener { dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener { job.cancel(); dialog.dismiss() } }
        dialog.show()
    }

    private fun documentMenu(project: JsonObject, doc: JsonObject) = sheet(doc.text("name"), listOf(
        "Open PDF" to { open(doc) }, "Rename…" to { renameDocumentDialog(project, doc) }, "Remove document…" to {
            MaterialAlertDialogBuilder(context).setTitle("Remove document?").setMessage("Remove this document and its comments and review cards from the project? Original files remain.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ -> change("remove_document", project, JsonObject().apply { add("document_id", doc.get("document_id") ?: doc.get("id")) }) }.show()
        }))

    fun addProject() = nameDialog(null)

    private fun renameDocumentDialog(project: JsonObject, doc: JsonObject) {
        val current = doc.text("name")
        val input = EditText(context).apply { setSingleLine(); hint = "Document name"; setText(current); setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val dialog = MaterialAlertDialogBuilder(context).setTitle("Rename document")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty() || name.length > 200 || name.any { it.code < 32 }) { input.error = "Enter a name between 1 and 200 characters"; return@setOnClickListener }
            if (name != current) change("rename_document", project, JsonObject().apply {
                add("document_id", doc.get("document_id") ?: doc.get("id")); addProperty("name", name)
            })
            dialog.dismiss()
        } }; dialog.show()
    }

    private fun nameDialog(project: JsonObject?) {
        val input = EditText(context).apply { setSingleLine(); hint = "Project name"; setText(project?.text("name").orEmpty()); setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(if (project == null) "Add project" else "Rename project")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton(if (project == null) "Create project" else "Save", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty() || name.length > 200 || name.any { it.code < 32 }) { input.error = "Enter a name between 1 and 200 characters"; return@setOnClickListener }
            if (projects.any { it.asJsonObject.text("name").equals(name, true) && it.asJsonObject.get("id") != project?.get("id") }) { input.error = "A project with this name already exists"; return@setOnClickListener }
            val target = project ?: JsonObject().apply { addProperty("id", "new:" + UUID.randomUUID()); addProperty("revision", 0) }
            expanded.add(target.text("id")); change(if (project == null) "create" else "rename", target, JsonObject().apply { addProperty("name", name) }); dialog.dismiss()
        } }; dialog.show()
    }

    private fun orderDialog(project: JsonObject) {
        val order = project.getAsJsonArray("documents").map { it.asJsonObject }.toMutableList()
        val initialIds = order.map { it.text("id") }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun draw() { column.removeAllViews(); order.forEachIndexed { index, doc ->
            column.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = doc.text("name"); setPadding(dp(20), dp(12), 0, dp(12)) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "↑"; contentDescription = "Move ${doc.text("name")} up"; isEnabled = index > 0
                    setOnClickListener { java.util.Collections.swap(order, index, index - 1); draw() }
                }, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "↓"; contentDescription = "Move ${doc.text("name")} down"; isEnabled = index < order.lastIndex
                    setOnClickListener { java.util.Collections.swap(order, index, index + 1); draw() }
                }, LinearLayout.LayoutParams(dp(52), dp(52)))
            })
        } }
        draw()
        MaterialAlertDialogBuilder(context).setTitle("Manage documents").setView(android.widget.ScrollView(context).apply { addView(column) })
            .setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ -> if (order.map { it.text("id") } != initialIds) change("order", project, JsonObject().apply {
                add("documents", JsonArray().apply { order.forEach { add(it.get("document_id") ?: it.get("id")) } })
            }) }.show()
    }

    fun importPdfs(uris: List<Uri>) {
        val id = importingProject ?: return
        scope.launch {
            for (uri in uris) {
                val project = store.projects().firstOrNull { it.asJsonObject.text("id") == id }?.asJsonObject ?: break
                val result = runCatching { withContext(Dispatchers.IO) { store.stageImport(project, uri) } }
                if (result.isFailure) Toast.makeText(context, result.exceptionOrNull()?.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
            redraw(); if (uris.isNotEmpty()) Toast.makeText(context, "Saved locally. Tap Sync to share changes.", Toast.LENGTH_SHORT).show()
        }
    }

    fun importHtml(uri: Uri?) {
        val id = importingHtmlProject
        val sourceProject = importingHtmlSourceProject
        importingHtmlProject = null
        importingHtmlSourceProject = null
        if (uri == null || id == null || sourceProject == null) return
        scope.launch {
            val project = store.projects().firstOrNull { it.asJsonObject.text("id") == id }?.asJsonObject
            if (project == null) {
                Toast.makeText(context, "This project is no longer in the Library", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                val link = withContext(Dispatchers.IO) { store.stageHtmlImport(project, uri, sourceProject) }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Thoughts HTML link", link))
                Toast.makeText(context, "HTML saved locally. Link copied. Tap Sync to share changes.", Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(context, error.message ?: "HTML import failed", Toast.LENGTH_LONG).show()
            }
            redraw()
        }
    }
    private fun change(action: String, project: JsonObject, values: JsonObject = JsonObject()) {
        try { store.enqueue(action, project, values); redraw(); Toast.makeText(context, "Saved locally. Tap Sync to share changes.", Toast.LENGTH_SHORT).show() }
        catch (error: Exception) { Toast.makeText(context, error.message ?: "The change could not be saved", Toast.LENGTH_LONG).show() }
    }
    private fun openProject(project: JsonObject) {
        val target = project.text("target")
        val file = if (target.isNotBlank()) File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), target)
            else project.getAsJsonArray("documents")?.singleOrNull()?.asJsonObject?.let { doc ->
                doc.text("local_path").takeIf(String::isNotBlank)?.let(::File)
                    ?: doc.text("filename").takeIf(String::isNotBlank)?.let { File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), it) }
            }
        if (file == null) {
            // An empty project has no PDF yet; make its import action available.
            expanded.add(project.text("id"))
            redraw()
            return
        }
        val item = itemForFile(file)
        if (item != null) functions.onProjectDocumentClicked(item, 0, displayTitle = project.text("name"), projectId = project.text("id"))
        else Toast.makeText(context, "Tap Sync to download this PDF", Toast.LENGTH_SHORT).show()
    }

    private fun itemForFile(file: File): HomeItem? = items.firstOrNull { it.fileName == file.name }
        ?: if (file.isFile) HomeItem.fromScan(ScannedPdfEntry(file.absolutePath, file.length(), file.lastModified(), null)) else null

    private fun open(doc: JsonObject) {
        if ((doc.get("start_page")?.asInt ?: 1) <= 0) {
            Toast.makeText(context, "Tap Sync to build this document", Toast.LENGTH_SHORT).show(); return
        }
        val file = doc.text("local_path").takeIf { it.isNotEmpty() }?.let(::File) ?: File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), doc.text("filename"))
        val item = itemForFile(file)
        if (item != null) functions.onProjectDocumentClicked(item, ((doc.get("start_page")?.asInt ?: 1) - 1).coerceAtLeast(0),
            documentId = doc.text("document_id"), displayTitle = doc.text("name"))
        else Toast.makeText(context, "Tap Sync to download this PDF", Toast.LENGTH_SHORT).show()
    }
}

/** The server's agent conversations with this phone's pending removals laid over them. */
private suspend fun defaultConversations(context: Context): Map<String, List<AgentConversations.Record>> {
    val payloads = AppDatabase.getInstance(context.applicationContext).thoughtsSyncDao().agentConversationProjections()
        .mapNotNull { runCatching { com.google.gson.JsonParser.parseString(it.payloadJson).asJsonObject }.getOrNull() }
    return AgentConversations.visibleByProject(payloads, AgentConversations.pending())
}

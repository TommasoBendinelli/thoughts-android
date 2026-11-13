// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.links

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.github.barteksc.pdfviewer.model.LinkTapEvent
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.links.PdfLinkOverrides
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.home.OverviewFilterChoice
import com.gitlab.mudlej.MjPdfReader.ui.home.showOverviewFilterDropdown
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.google.gson.JsonArray
import com.shockwave.pdfium.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Extra of a reader intent that opens a link override's target; the value is the override id. */
const val EXTRA_LINK_OVERRIDE_TARGET = "org.tbend.thoughts.LINK_OVERRIDE_TARGET"

/** A Library document the picker offers. */
internal data class LinkOverrideTarget(val documentId: String, val label: String, val project: String)

/**
 * The documents a link can be pointed at: every Library PDF with a permanent id except the
 * source itself and archived ones, labelled with its display name and project, sorted by name.
 */
internal fun linkOverrideTargets(
    documentIdsByFilename: Map<String, String>,
    displayNamesByFilename: Map<String, String>,
    projectsByDocumentId: Map<String, String>,
    archivedFilenames: Set<String>,
    sourceDocumentId: String,
): List<LinkOverrideTarget> = documentIdsByFilename
    .filter { (filename, id) -> id != sourceDocumentId && filename !in archivedFilenames }
    .entries.distinctBy { it.value }
    .map { (filename, id) ->
        val label = displayNamesByFilename[filename]?.trim().orEmpty().ifBlank { filename.removeSuffix(".pdf") }
        LinkOverrideTarget(id, label, projectsByDocumentId[id].orEmpty())
    }
    .sortedWith(compareBy({ it.label.lowercase() }, { it.project.lowercase() }, { it.documentId }))

/** Project names by member document id, from the Library project catalogue. */
internal fun projectNamesByDocumentId(projects: JsonArray): Map<String, String> {
    val names = linkedMapOf<String, String>()
    projects.forEach { element ->
        val project = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        val name = project.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (name.isEmpty()) return@forEach
        project.get("documents")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { document ->
            val entry = document.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val id = listOf("document_id", "id").firstNotNullOfOrNull { key ->
                entry.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
            } ?: return@forEach
            names.putIfAbsent(id, name)
        }
    }
    return names
}

/**
 * PDF link overrides in the reader: a tap on an overridden link opens the chosen Library document,
 * and a long press on an overridable link offers to set, change or remove the override.
 */
class PdfLinkOverrideController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val doc: DocumentState,
    /** Follows a link as if no override existed. */
    private val followOriginal: (LinkTapEvent) -> Unit,
) {
    private var popup: PopupMenu? = null
    private var pickerAnchor: View? = null

    /** The open document's Library id, when its links can be overridden. */
    private fun sourceDocumentId(): String? =
        doc.documentId?.takeIf { DocumentIdentityStore.validId(it) && doc.projectReading == null }

    private fun clicked(link: PdfDocument.Link): PdfLinkOverrides.ClickedLink? {
        val point = link.destPoint
        return PdfLinkOverrides.clickedLink(link.uri, link.destPageIdx, point?.x, point?.y)
    }

    /** Opens the override's target for a tapped link; false lets the link do what it normally does. */
    fun handleTap(event: LinkTapEvent): Boolean {
        val source = sourceDocumentId() ?: return false
        val clicked = clicked(event.link) ?: return false
        val override = runCatching { PdfLinkOverrides.overrideFor(source, clicked) }
            .onFailure { Log.w(TAG, "Could not read link overrides", it) }.getOrNull() ?: return false
        if (openTarget(override)) return true
        Toast.makeText(activity, R.string.link_override_target_missing, Toast.LENGTH_SHORT).show()
        return false
    }

    private fun openTarget(override: PdfLinkOverrides.Override): Boolean {
        val file = DocumentIdentityStore().fileForId(override.targetDocumentId) ?: return false
        val intent = Intent(activity, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.fromFile(file)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_LINK_OVERRIDE_TARGET, override.id)
        }
        // Delivered directly, like other links followed in the reader, so it is one step of this tab's history.
        activity.handleReaderIntent(intent)
        return true
    }

    /**
     * Shows the link menu when a long press at view point ([x], [y]) hits an overridable link.
     * Returns false (and does nothing) otherwise.
     */
    fun handleLongPress(x: Float, y: Float): Boolean {
        val source = sourceDocumentId() ?: return false
        val event = binding.pdfView.findLinkAt(x, y) ?: return false
        val clicked = clicked(event.link) ?: return false
        val override = runCatching { PdfLinkOverrides.overrideFor(source, clicked) }.getOrNull()
        showMenu(x, y, event, source, clicked, override)
        return true
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun showMenu(
        x: Float,
        y: Float,
        event: LinkTapEvent,
        source: String,
        clicked: PdfLinkOverrides.ClickedLink,
        override: PdfLinkOverrides.Override?,
    ) {
        dismiss()
        val anchor = anchorAt(x, y)
        popup = PopupMenu(activity, anchor).apply {
            if (override == null) {
                menu.add(0, MENU_LINK, 0, R.string.link_override_link_to_library)
            } else {
                menu.add(0, MENU_ORIGINAL, 0, R.string.link_override_open_original)
                menu.add(0, MENU_CHANGE, 1, R.string.link_override_change)
                menu.add(0, MENU_REMOVE, 2, R.string.link_override_remove)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_LINK, MENU_CHANGE -> choose(source, clicked, override, y)
                    MENU_ORIGINAL -> followOriginal(event)
                    MENU_REMOVE -> override?.let(::remove)
                }
                true
            }
            setOnDismissListener { if (popup === this) popup = null }
        }
        val shown = popup
        anchor.doOnLayout { if (popup === shown && !activity.isFinishing && !activity.isDestroyed) shown?.show() }
    }

    private fun choose(
        source: String,
        clicked: PdfLinkOverrides.ClickedLink,
        override: PdfLinkOverrides.Override?,
        y: Float,
    ) {
        activity.lifecycleScope.launch {
            val targets = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshot = SourceLinkedPdfIndex().snapshot()
                    linkOverrideTargets(
                        DocumentIdentityStore().documentIdsByFilename(),
                        snapshot.libraryDisplayNamesByFilename,
                        projectNamesByDocumentId(LibraryProjectStore(activity).projects()),
                        snapshot.archivedLibraryFilenames,
                        source,
                    )
                }.onFailure { Log.w(TAG, "Could not list Library documents", it) }.getOrDefault(emptyList())
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val labels = targets.associate { it.documentId to it.label }
            // Near the top of the screen, so the list has room below.
            val anchor = anchorAt(0f, minOf(y, binding.root.height / 3f))
            anchor.doOnLayout {
                if (activity.isFinishing || activity.isDestroyed) return@doOnLayout
                showOverviewFilterDropdown(
                    anchor,
                    activity.getString(R.string.link_override_choose_document),
                    targets.map { OverviewFilterChoice(it.documentId, it.label, it.project) },
                    override?.targetDocumentId.orEmpty(),
                    searchable = true,
                    searchHint = R.string.link_override_find_document,
                    emptyText = R.string.link_override_no_documents,
                ) { targetId -> save(source, clicked, override, targetId, labels[targetId].orEmpty()) }
            }
        }
    }

    private fun save(
        source: String,
        clicked: PdfLinkOverrides.ClickedLink,
        override: PdfLinkOverrides.Override?,
        targetId: String,
        label: String,
    ) {
        if (override?.targetDocumentId == targetId) return
        val originId = ReviewStore(activity).originIdentity()
        val sha256 = doc.annotationPdfSha256 ?: DocumentIdentityStore().byId(source)?.get("pdf_sha256")?.asString
        val saved = runCatching {
            if (override == null) PdfLinkOverrides.create(source, sha256, clicked, targetId, originId)
            else PdfLinkOverrides.retarget(override.id, targetId, originId)
        }.onFailure { Log.w(TAG, "Could not save link override", it) }.isSuccess
        Toast.makeText(
            activity,
            if (saved) activity.getString(R.string.link_override_saved, label) else activity.getString(R.string.link_override_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun remove(override: PdfLinkOverrides.Override) {
        val removed = runCatching { PdfLinkOverrides.remove(override.id, ReviewStore(activity).originIdentity()) }
            .onFailure { Log.w(TAG, "Could not remove link override", it) }.isSuccess
        Toast.makeText(
            activity, if (removed) R.string.link_override_removed else R.string.link_override_failed, Toast.LENGTH_SHORT,
        ).show()
    }

    /** One small invisible view, moved to where menus should appear. */
    private fun anchorAt(x: Float, y: Float): View {
        val root = binding.root
        val pdfLocation = IntArray(2)
        val rootLocation = IntArray(2)
        binding.pdfView.getLocationOnScreen(pdfLocation)
        root.getLocationOnScreen(rootLocation)
        val size = activity.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        val anchorX = (pdfLocation[0] - rootLocation[0] + x.toInt()).coerceIn(0, (root.width - size).coerceAtLeast(0))
        val anchorY = (pdfLocation[1] - rootLocation[1] + y.toInt()).coerceIn(0, (root.height - size).coerceAtLeast(0))
        val params = ConstraintLayout.LayoutParams(size, size).apply {
            startToStart = ConstraintSet.PARENT_ID
            topToTop = ConstraintSet.PARENT_ID
            marginStart = anchorX
            topMargin = anchorY
        }
        val anchor = pickerAnchor?.takeIf { it.parent === root }
        if (anchor != null) {
            anchor.layoutParams = params
            return anchor
        }
        (pickerAnchor?.parent as? ViewGroup)?.removeView(pickerAnchor)
        return View(activity).apply {
            id = View.generateViewId()
            layoutParams = params
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also {
            root.addView(it)
            pickerAnchor = it
        }
    }

    private companion object {
        const val TAG = "ThoughtsLinkOverrides"
        const val MENU_LINK = 1
        const val MENU_ORIGINAL = 2
        const val MENU_CHANGE = 3
        const val MENU_REMOVE = 4
    }
}

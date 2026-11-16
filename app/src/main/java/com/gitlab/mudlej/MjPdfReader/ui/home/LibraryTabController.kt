// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import java.io.File
import kotlinx.coroutines.CoroutineScope

class LibraryTabController(
    private val context: Context,
    private val pref: Preferences,
    coverCache: CoverCache,
    scope: CoroutineScope,
    functions: HomeItemFunctions,
    selection: () -> Set<String>,
    private val libraryController: HomeLibraryController,
    private val hasFullAccess: () -> Boolean,
    onGrantAccessClicked: () -> Unit,
    private val sourcePdfIndex: SourceLinkedPdfIndex,
    onImportDocuments: () -> Unit,
    onImportHtml: () -> Unit,
) {

    private val projectPane = LibraryProjectPane(context, scope, functions, onImportDocuments, onImportHtml)
    fun addProject() = projectPane.addProject()

    fun importPdfs(uris: List<android.net.Uri>) = projectPane.importPdfs(uris)
    fun importHtml(uri: android.net.Uri?) = projectPane.importHtml(uri)
    /** The item a Project document row opens for [file] (the Home search opens the same). */
    fun projectDocumentItem(file: File): HomeItem? = projectPane.itemForFile(file)

    private var expansionState = LibrarySectionExpansionState()
    private var linkedItems: List<HomeItem> = emptyList()
    private var unlinkedItems: List<HomeItem> = emptyList()
    private var archivedItems: List<HomeItem> = emptyList()

    val libraryAdapter = LibraryAdapter(coverCache, scope, functions, pref, selection)
    private val unlinkedLibraryAdapter = LibraryAdapter(coverCache, scope, functions, pref, selection)
    private val archivedLibraryAdapter = LibraryAdapter(coverCache, scope, functions, pref, selection)
    private val linkedHeadingAdapter = HomeSectionsAdapter(
        coverCache,
        scope,
        functions,
        onHeadingClicked = { toggleSection(LibrarySectionKind.LINKED) },
    )
    private val unlinkedHeadingAdapter = HomeSectionsAdapter(
        coverCache,
        scope,
        functions,
        onHeadingClicked = { toggleSection(LibrarySectionKind.UNLINKED) },
    )
    private val archivedHeadingAdapter = HomeSectionsAdapter(
        coverCache,
        scope,
        functions,
        onHeadingClicked = { toggleSection(LibrarySectionKind.ARCHIVED) },
    )
    private val sectionsAdapter = HomeSectionsAdapter(
        coverCache,
        scope,
        functions,
        onGrantAccessClicked = onGrantAccessClicked,
    )

    fun attach(recyclerView: RecyclerView) {
        libraryAdapter.progressStyle = pref.getHomeProgressStyle()
        unlinkedLibraryAdapter.progressStyle = libraryAdapter.progressStyle
        archivedLibraryAdapter.progressStyle = libraryAdapter.progressStyle

        if (recyclerView.adapter !== projectPane) {
            recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            recyclerView.adapter = projectPane
        }
    }

    fun render(
        allItems: List<HomeItem>,
        sourceIndexSnapshot: SourceLinkedPdfIndexSnapshot,
        pendingLibraryRemovalFilenames: Set<String> = emptySet(),
    ) {
        val displayEntries = libraryDisplayEntries(
            sourceIndexSnapshot,
            PhoneRenderQueue.defaultCanonicalPdfDirectory(),
        ).filterNot { entry ->
            File(entry.path).name in pendingLibraryRemovalFilenames
        }
        val mergedItems = libraryController.sort(
            libraryController.mergeWithEntries(
                allItems,
                displayEntries,
            ).map { item ->
                val filename = item.uri.path?.let(::File)?.name
                val displayName = filename?.let(sourceIndexSnapshot.libraryDisplayNamesByFilename::get).orEmpty()
                if (displayName.isEmpty()) item else item.copy(
                    libraryDisplayName = displayName,
                    sortKey = displayName.lowercase(),
                )
            }
        ).filter { item ->
            !item.hidden && item.uri.path?.let(::File)?.name !in pendingLibraryRemovalFilenames
        }
        val catalogueAvailable = sourceIndexSnapshot.libraryAvailable || sourceIndexSnapshot.available
        val gridItems = if (catalogueAvailable) {
            mergedItems.filter { item ->
                val file = item.uri
                    .takeIf { it.scheme == "file" }
                    ?.path
                    ?.let(::File)
                if (sourceIndexSnapshot.libraryAvailable) {
                    file?.name in sourceIndexSnapshot.libraryFilenames
                } else {
                    file?.let { sourcePdfIndex.capabilityFor(it, sourceIndexSnapshot) } == SourcePdfCapability.SOURCE_LINKED
                }
            }.sortedBy { item ->
                val filename = item.uri.path?.let(::File)?.name
                if (filename != null && sourceIndexSnapshot.libraryKindsByFilename[filename] ==
                    com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind.UNLINKED) 1 else 0
            }
        } else {
            emptyList()
        }
        linkedItems = gridItems.filter { item ->
            val filename = item.uri.path?.let(::File)?.name
            filename !in sourceIndexSnapshot.archivedLibraryFilenames &&
            item.uri.path?.let(::File)?.name?.let(sourceIndexSnapshot.libraryKindsByFilename::get) !=
                com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind.UNLINKED
        }
        unlinkedItems = gridItems.filter { item ->
            val filename = item.uri.path?.let(::File)?.name
            filename !in sourceIndexSnapshot.archivedLibraryFilenames && filename?.let(sourceIndexSnapshot.libraryKindsByFilename::get) ==
                com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind.UNLINKED
        }
        archivedItems = gridItems.filter { item ->
            item.uri.path?.let(::File)?.name in sourceIndexSnapshot.archivedLibraryFilenames
        }
        projectPane.render(gridItems, sourceIndexSnapshot)

        sectionsAdapter.submitList(buildList {
            if (!hasFullAccess()) {
                add(HomeSection.PermissionCard())
            }
            if (gridItems.isEmpty()) {
                add(
                    HomeSection.EmptyState(
                        if (catalogueAvailable) {
                            R.string.home_empty_all_title
                        } else {
                            R.string.home_source_index_waiting_title
                        },
                        if (catalogueAvailable) {
                            R.string.home_empty_all_message
                        } else {
                            R.string.home_source_index_waiting_message
                        },
                    )
                )
            }
        })
    }

    private fun toggleSection(kind: LibrarySectionKind) {
        expansionState = expansionState.toggled(kind)
        submitLibrarySections()
    }

    private fun submitLibrarySections() {
        libraryAdapter.submitList(linkedItems.takeIf {
            expansionState.isExpanded(LibrarySectionKind.LINKED)
        }.orEmpty())
        unlinkedLibraryAdapter.submitList(unlinkedItems.takeIf {
            expansionState.isExpanded(LibrarySectionKind.UNLINKED)
        }.orEmpty())
        archivedLibraryAdapter.submitList(archivedItems.takeIf {
            expansionState.isExpanded(LibrarySectionKind.ARCHIVED)
        }.orEmpty())
        val sectionPresentation = librarySectionPresentation(linkedItems.size, unlinkedItems.size, archivedItems.size)
        val linkedSection = sectionPresentation.firstOrNull { it.kind == LibrarySectionKind.LINKED }
        val unlinkedSection = sectionPresentation.firstOrNull { it.kind == LibrarySectionKind.UNLINKED }
        val archivedSection = sectionPresentation.firstOrNull { it.kind == LibrarySectionKind.ARCHIVED }
        linkedHeadingAdapter.submitList(
            linkedSection?.let {
                listOf(HomeSection.Heading(
                    R.string.home_library_linked_documents,
                    R.drawable.ic_link,
                    it.count,
                    it.showDivider,
                    expansionState.isExpanded(it.kind),
                ))
            } ?: emptyList()
        )
        unlinkedHeadingAdapter.submitList(
            unlinkedSection?.let {
                listOf(HomeSection.Heading(
                    R.string.home_library_unlinked_documents,
                    R.drawable.ic_link_off,
                    it.count,
                    it.showDivider,
                    expansionState.isExpanded(it.kind),
                ))
            } ?: emptyList()
        )
        archivedHeadingAdapter.submitList(
            archivedSection?.let {
                listOf(HomeSection.Heading(
                    R.string.home_library_archived_documents,
                    R.drawable.ic_folder,
                    it.count,
                    it.showDivider,
                    expansionState.isExpanded(it.kind),
                ))
            } ?: emptyList()
        )
    }

    fun applyProgressStyle() {
        val style = pref.getHomeProgressStyle()
        if (libraryAdapter.progressStyle != style) {
            libraryAdapter.progressStyle = style
            unlinkedLibraryAdapter.progressStyle = style
            archivedLibraryAdapter.progressStyle = style
            libraryAdapter.notifyDataSetChanged()
            unlinkedLibraryAdapter.notifyDataSetChanged()
            archivedLibraryAdapter.notifyDataSetChanged()
        }
    }

    fun applyTitleStyle() {
        libraryAdapter.applyTitleStyle()
        unlinkedLibraryAdapter.applyTitleStyle()
        archivedLibraryAdapter.applyTitleStyle()
    }

    fun applyRenderStatus(snapshot: PhoneRenderStatusSnapshot) {
        libraryAdapter.applyRenderStatus(snapshot)
        unlinkedLibraryAdapter.applyRenderStatus(snapshot)
        archivedLibraryAdapter.applyRenderStatus(snapshot)
    }

    fun currentGridItems(): List<HomeItem> = buildList {
        if (expansionState.isExpanded(LibrarySectionKind.LINKED)) addAll(linkedItems)
        if (expansionState.isExpanded(LibrarySectionKind.UNLINKED)) addAll(unlinkedItems)
        if (expansionState.isExpanded(LibrarySectionKind.ARCHIVED)) addAll(archivedItems)
    }

    fun notifySelectionChanged() {
        libraryAdapter.notifySelectionChanged()
        unlinkedLibraryAdapter.notifySelectionChanged()
        archivedLibraryAdapter.notifySelectionChanged()
    }

    fun onCoversChanged() {
        sectionsAdapter.rebindCovers()
        libraryAdapter.notifyDataSetChanged()
        unlinkedLibraryAdapter.notifyDataSetChanged()
        archivedLibraryAdapter.notifyDataSetChanged()
    }
}

internal fun libraryDisplayEntries(
    sourceIndexSnapshot: SourceLinkedPdfIndexSnapshot,
    canonicalPdfDirectory: File,
): List<ScannedPdfEntry> {
    val catalogue = when {
        sourceIndexSnapshot.libraryAvailable ->
            sourceIndexSnapshot.libraryFilenames to sourceIndexSnapshot.libraryPdfSha256ByFilename
        sourceIndexSnapshot.available ->
            sourceIndexSnapshot.filenames to sourceIndexSnapshot.pdfSha256ByFilename
        else -> return emptyList()
    }
    return catalogue.first.sorted().mapNotNull { filename ->
        val file = sourceIndexSnapshot.libraryPdfFile(filename, canonicalPdfDirectory)
        if (!file.isFile) return@mapNotNull null
        ScannedPdfEntry(
            path = file.absolutePath,
            size = file.length(),
            lastModified = file.lastModified(),
            hash = catalogue.second[filename],
        )
    }
}

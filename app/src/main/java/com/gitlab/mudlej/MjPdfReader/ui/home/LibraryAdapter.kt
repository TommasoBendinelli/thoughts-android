// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeListRowBinding
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.core.io.formatRelativeDate
import com.gitlab.mudlej.MjPdfReader.core.text.StringUtil.formatEnumToTitle
import com.google.android.material.color.MaterialColors
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope

enum class ListMetaStyle { LIBRARY, RECENT }

class LibraryAdapter(
    private val coverCache: CoverCache,
    private val scope: CoroutineScope,
    private val functions: HomeItemFunctions,
    private val pref: Preferences,
    private val selection: () -> Set<String> = { emptySet() },
) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(HomeItemComparator()) {

    var metaStyle: ListMetaStyle = ListMetaStyle.LIBRARY
    var progressStyle: HomeProgressStyle = HomeProgressStyle.RING

    private var appliedTitleSignature: String? = null
    private var renderStatusSnapshot = PhoneRenderStatusSnapshot.EMPTY
    private val sourceLinkedPdfIndex by lazy { SourceLinkedPdfIndex() }
    private val canonicalPdfDirectory by lazy {
        runCatching { PhoneRenderQueue.defaultCanonicalPdfDirectory().canonicalPath }.getOrNull()
    }

    fun applyRenderStatus(snapshot: PhoneRenderStatusSnapshot) {
        if (renderStatusSnapshot == snapshot) {
            return
        }
        renderStatusSnapshot = snapshot
        notifyDataSetChanged()
    }

    fun applyTitleStyle() {
        val signature = titleSignature()
        val firstApply = appliedTitleSignature == null
        appliedTitleSignature = signature
        if (!firstApply) {
            notifyDataSetChanged()
        }
    }

    private fun titleSignature(): String {
        return "${pref.getHomeListTitleLines()}|${pref.getHomeTitleEllipsize().name}" +
            "|${pref.getHomeBadgePages()}|${pref.getHomeBadgeProgress()}|${pref.getHomeBadgeLastOpened()}" +
            "|${pref.getHomeBadgeFileSize()}|${pref.getHomeBadgeStatus()}"
    }

    private fun TextView.applyTitleLines(maxLines: Int) {
        this.maxLines = maxLines
        ellipsize = pref.getHomeTitleEllipsize().truncateAt
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ListViewHolder(ItemHomeListRowBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ListViewHolder -> holder.bind(item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.contains(SELECTION_PAYLOAD)) {
            val item = getItem(position)
            when (holder) {
                is ListViewHolder -> holder.applySelection(item)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    fun notifySelectionChanged() {
        notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
    }

    private fun isSelected(item: HomeItem) = item.hash in selection()

    inner class ListViewHolder(
        private val binding: ItemHomeListRowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem) {
            binding.root.alpha = if (item.available) 1f else UNAVAILABLE_ALPHA
            val libraryStyle = metaStyle == ListMetaStyle.LIBRARY
            binding.title.text = if (libraryStyle) item.libraryDisplayName.ifBlank { item.fileName } else item.title
            binding.title.applyTitleLines(pref.getHomeListTitleLines())
            if (libraryStyle) {
                binding.metaBadges.visibility = View.GONE
                bindCompactLibraryMetadata(
                    item,
                    binding.libraryMetadata,
                )
                binding.renderStatusBadge.visibility = View.GONE
                binding.renderStatusBadge.setOnClickListener(null)
                bindRenderStatus(binding.libraryRenderStatusBadge, item)
            } else {
                binding.libraryMetadata.visibility = View.GONE
                binding.libraryRenderStatusBadge.visibility = View.GONE
                binding.libraryRenderStatusBadge.setOnClickListener(null)
                bindMetaBadges(item)
                bindRenderStatus(binding.renderStatusBadge, item)
            }

            applySelection(item)
            coverCache.bind(binding.cover, item.coverKey, item.uri, LIST_COVER_WIDTH_PX, scope)

            binding.listCard.setOnClickListener { functions.onItemClicked(item) }
            binding.listCard.setOnLongClickListener { functions.onItemLongClicked(item) }
            binding.optionsButton.setOnClickListener { functions.onItemOptionsClicked(item) }
        }

        fun applySelection(item: HomeItem) {
            binding.listCard.isChecked = isSelected(item)
        }

        private fun bindMetaBadges(item: HomeItem) {
            val container = binding.metaBadges
            container.removeAllViews()

            val parts = buildMetaParts(item)
            if (parts.isEmpty()) {
                container.visibility = View.GONE
                return
            }
            container.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(container.context)
            parts.forEach { (text, accent) ->
                val layout = if (accent) R.layout.home_meta_badge_accent else R.layout.home_meta_badge
                val badge = inflater.inflate(layout, container, false) as TextView
                badge.text = text
                container.addView(badge)
            }
        }

        private fun buildMetaParts(item: HomeItem): List<Pair<String, Boolean>> {
            val context = binding.root.context
            val parts = mutableListOf<Pair<String, Boolean>>()
            if (pref.getHomeBadgePages() && item.length > 0) {
                if (item.pageNumber > 0) {
                    parts.add("${item.pageNumber + 1}/${item.length}" to false)
                } else {
                    parts.add(
                        context.resources.getQuantityString(
                            R.plurals.home_pages, item.length, item.length
                        ) to false
                    )
                }
            }
            if (pref.getHomeBadgeProgress() && item.progressPercent > 0) {
                parts.add("${item.progressPercent}%" to false)
            }
            if (pref.getHomeBadgeLastOpened() && item.hasBeenOpened) {
                parts.add(formatRelativeDate(context, item.lastOpened) to false)
            }
            if (pref.getHomeBadgeStatus() && metaStyle == ListMetaStyle.RECENT
                && item.readingStatus != ReadingStatus.UNSET
            ) {
                parts.add(item.readingStatus.name.formatEnumToTitle() to true)
            }
            if (item.openCommentThreads > 0) {
                parts.add(
                    context.resources.getQuantityString(
                        R.plurals.home_open_comment_threads,
                        item.openCommentThreads,
                        item.openCommentThreads,
                    ) to true
                )
            }
            if (item.lastCommentAddedMillis > 0) {
                parts.add(
                    context.getString(
                        R.string.home_comments_updated,
                        relativeTime(binding.title, item.lastCommentAddedMillis),
                    ) to false
                )
            }
            return parts
        }
    }

    private fun bindLibraryMetadata(
        item: HomeItem,
        container: View,
        pdfModified: TextView,
        lastComment: TextView,
        unresolvedThreads: TextView,
    ) {
        val context = container.context
        container.visibility = View.VISIBLE
        pdfModified.text = context.getString(
            R.string.home_library_pdf_modified,
            if (item.fileModifiedMillis > 0) {
                relativeTimestamp(item.fileModifiedMillis)
            } else {
                context.getString(R.string.home_library_unknown)
            },
        )
        lastComment.text = context.getString(
            R.string.home_library_last_comment,
            if (item.lastCommentAddedMillis > 0) {
                relativeTimestamp(item.lastCommentAddedMillis)
            } else {
                context.getString(R.string.home_library_never)
            },
        )
        unresolvedThreads.text = context.resources.getQuantityString(
            R.plurals.home_library_unresolved_threads,
            item.openCommentThreads,
            item.openCommentThreads,
        )
    }

    private fun bindCompactLibraryMetadata(
        item: HomeItem,
        summary: TextView,
    ) {
        val context = summary.context
        summary.visibility = View.VISIBLE
        val text = formatCompactLibraryMetadata(
            commentTimestamp = if (item.lastCommentAddedMillis > 0) {
                relativeTimestamp(item.lastCommentAddedMillis)
            } else {
                context.getString(R.string.home_library_never)
            },
            unresolvedThreadCount = item.openCommentThreads,
            formatSummary = { commentTimestamp, unresolvedThreadText ->
                context.getString(
                    R.string.home_library_compact_comment_status,
                    commentTimestamp,
                    unresolvedThreadText,
                )
            },
            formatUnresolvedThreads = { count ->
                context.resources.getQuantityString(
                    R.plurals.home_library_unresolved_threads,
                    count,
                    count,
                )
            },
        )
        summary.text = text
    }

    private fun bindRenderStatus(view: TextView, item: HomeItem) {
        val file = item.uri
            .takeIf { it.scheme == "file" }
            ?.path
            ?.let(::File)
        val isCanonical = file != null &&
            canonicalPdfDirectory != null &&
            runCatching { file.parentFile?.canonicalPath }.getOrNull() == canonicalPdfDirectory
        val status = if (isCanonical) {
            val librarySnapshot = sourceLinkedPdfIndex.snapshot()
            renderStatusSnapshot.statusForPdf(item.fileName)
                ?: librarySnapshot.staleBuildsByFilename[item.fileName]?.let { failure ->
                    PhoneRenderJobStatus(
                        requestId = failure.buildId,
                        target = item.fileName,
                        stage = PhoneRenderStage.FAILED,
                        message = failure.message,
                        bannerEligible = false,
                    )
                }
                ?: verifiedLibraryDocumentStatus(
                    filename = item.fileName,
                    snapshot = librarySnapshot,
                    verifiedAtEpochMillis = File(
                        PhoneRenderQueue.defaultCanonicalPdfDirectory(),
                        SourceLinkedPdfIndex.GENERATION_FILENAME,
                    ).lastModified(),
                )
        } else {
            null
        }
        if (status == null) {
            view.visibility = View.GONE
            view.setOnClickListener(null)
            return
        }
        val presentation = documentStatusPresentation(
            status.stage,
            status.verifiedAtEpochMillis,
        )
        if (!presentation.showOnDocumentRow) {
            view.visibility = View.GONE
            view.setOnClickListener(null)
            return
        }
        view.visibility = View.VISIBLE
        if (presentation.compactVerifiedTick) {
            view.setBackgroundColor(Color.TRANSPARENT)
            view.backgroundTintList = null
            view.text = "✓"
            view.setTextColor(ContextCompat.getColor(view.context, R.color.thoughts_sync_success))
            view.contentDescription = view.context.getString(R.string.document_status_synced_description)
            view.setOnClickListener { functions.onRenderStatusClicked(status) }
            return
        }
        view.setBackgroundResource(R.drawable.home_meta_badge_accent_background)
        view.text = documentStatusLabel(view, presentation.code)
        val backgroundAttr: Int
        val foregroundAttr: Int
        when (status.stage) {
            PhoneRenderStage.BLOCKED,
            PhoneRenderStage.ATTENTION,
            PhoneRenderStage.PARTIAL,
            PhoneRenderStage.FAILED -> {
                backgroundAttr = com.google.android.material.R.attr.colorErrorContainer
                foregroundAttr = com.google.android.material.R.attr.colorOnErrorContainer
            }
            PhoneRenderStage.SYNC_PENDING -> {
                backgroundAttr = com.google.android.material.R.attr.colorPrimaryContainer
                foregroundAttr = com.google.android.material.R.attr.colorOnPrimaryContainer
            }
            else -> {
                backgroundAttr = com.google.android.material.R.attr.colorPrimaryContainer
                foregroundAttr = com.google.android.material.R.attr.colorOnPrimaryContainer
            }
        }
        view.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(view, backgroundAttr)
        )
        view.setTextColor(MaterialColors.getColor(view, foregroundAttr))
        view.contentDescription = view.text
        view.setOnClickListener { functions.onRenderStatusClicked(status) }
    }

    private fun documentStatusLabel(view: View, code: DocumentStatusCode): String =
        view.context.getString(
            when (code) {
                DocumentStatusCode.SYNC_NEEDED -> R.string.document_status_sync_needed
                DocumentStatusCode.SYNCING -> R.string.document_status_syncing
                DocumentStatusCode.SYNCED -> R.string.document_status_synced
                DocumentStatusCode.ERROR -> R.string.document_status_error
            }
        )

    private fun relativeTimestamp(epochMillis: Long): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            epochMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
    }

    private fun relativeTime(view: View, epochMillis: Long): String {
        val time = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(epochMillis),
            ZoneId.systemDefault(),
        )
        return formatRelativeDate(view.context, time)
    }

    companion object {
        private const val DEFAULT_COVER_WIDTH_PX = 320
        private const val LIST_COVER_WIDTH_PX = 160
        private const val SELECTION_PAYLOAD = "selection"
        private const val UNAVAILABLE_ALPHA = 0.45f
    }
}

internal fun verifiedLibraryDocumentStatus(
    filename: String,
    snapshot: com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot,
    verifiedAtEpochMillis: Long,
): PhoneRenderJobStatus? {
    if (
        filename !in snapshot.activatedPdfSha256ByFilename ||
        filename in snapshot.staleBuildsByFilename ||
        verifiedAtEpochMillis <= 0
    ) {
        return null
    }
    return PhoneRenderJobStatus(
        requestId = "local-library:${snapshot.libraryRevision}:$filename",
        target = filename,
        stage = PhoneRenderStage.UPDATED,
        completedAtEpochMillis = verifiedAtEpochMillis,
        verifiedAtEpochMillis = verifiedAtEpochMillis,
        bannerEligible = false,
        changesDetected = false,
    )
}

class HomeItemComparator : DiffUtil.ItemCallback<HomeItem>() {

    override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean {
        return oldItem.hash == newItem.hash
    }

    override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean {
        return oldItem == newItem
    }
}

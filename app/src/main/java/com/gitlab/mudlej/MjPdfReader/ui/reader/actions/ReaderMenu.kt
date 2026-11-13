// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.actions

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.doOnPreDraw
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentStudyMode
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity

private const val PREWARM_ICON_BATCH = 6
private const val SYNC_ACTION_KEY = "reader-sync"
private const val HTML_VIEW_ACTION_KEY = "reader-html-view"

internal data class ReaderSyncActionPresentation(
    val visible: Boolean,
    val inProgress: Boolean,
    val canStart: Boolean,
)

/** The reader's Sync runs a full Library Sync, so it is offered for every document. */
internal fun readerSyncActionPresentation(
    operationActive: Boolean,
    startRequested: Boolean,
): ReaderSyncActionPresentation {
    val inProgress = operationActive || startRequested
    return ReaderSyncActionPresentation(
        visible = true,
        inProgress = inProgress,
        canStart = !inProgress,
    )
}

internal data class ReaderMenuActionLayout(
    val primary: List<ConfigurableAction>,
    val navigation: List<ConfigurableAction>,
    val pages: List<ConfigurableAction>,
)

internal fun readerMenuActionLayout(): ReaderMenuActionLayout {
    return ReaderMenuActionLayout(
        primary = listOf(
            ConfigurableAction.OPEN_LOCAL,
            ConfigurableAction.PUT_IN_LIBRARY,
            ConfigurableAction.SWITCH_THEME,
            ConfigurableAction.SEARCH,
            ConfigurableAction.BOOKMARK_PAGE,
            ConfigurableAction.READING_DIRECTION,
            ConfigurableAction.FULLSCREEN,
            ConfigurableAction.SHARE,
            ConfigurableAction.ROTATE,
        ),
        navigation = listOf(
            ConfigurableAction.NAV_BACK,
            ConfigurableAction.NAV_FORWARD,
            ConfigurableAction.NAV_HISTORY,
        ),
        pages = listOf(
            ConfigurableAction.TABLE_OF_CONTENTS,
            ConfigurableAction.REVIEW_CARDS,
            ConfigurableAction.USER_NOTES,
            ConfigurableAction.AGENT_EDITS,
        ),
    )
}

class ReaderMenu(
    private val activity: MainActivity,
    private val actionResolver: ConfigurableActionResolver,
    private val hasFile: () -> Boolean,
) {

    private var iconsPrewarmed = false
    private var openDialog: AlertDialog? = null
    private var syncTile: ReaderActionTileController? = null
    private var syncOperationActive = false
    private var syncStartRequested = false

    fun show() {
        val startMs = if (BuildConfig.DEBUG) SystemClock.uptimeMillis() else 0L
        val dialog = showReaderActionsDialog(activity, menuContent()) { key, controller ->
            if (key == SYNC_ACTION_KEY) syncTile = controller
        }
        openDialog = dialog
        dialog.setOnDismissListener {
            if (openDialog === dialog) {
                openDialog = null
                syncTile = null
                syncStartRequested = false
            }
        }
        refreshSyncState()
        if (BuildConfig.DEBUG) {
            dialog.window!!.decorView.doOnPreDraw {
                Log.d("MjPdfPerf", "menu open: ${SystemClock.uptimeMillis() - startMs}ms")
            }
        }
    }

    fun refreshSyncState() {
        val tile = syncTile ?: return
        activity.globalSyncActive { active ->
            if (syncTile !== tile) return@globalSyncActive
            syncOperationActive = active
            tile.setInProgress(active || syncStartRequested)
        }
    }

    fun prewarmIcons() {
        if (iconsPrewarmed) {
            return
        }
        iconsPrewarmed = true
        // Icons do not depend on whether this PDF can Sync; resolving that reads the Library index.
        val icons = menuContent().sections.flatMap { it.actions }.map { it.iconRes }.distinct()
        if (icons.isEmpty()) {
            return
        }
        var index = 0
        Looper.getMainLooper().queue.addIdleHandler {
            val end = minOf(index + PREWARM_ICON_BATCH, icons.size)
            while (index < end) {
                AppCompatResources.getDrawable(activity, icons[index])
                index++
            }
            index < icons.size
        }
    }

    private fun menuContent(): ReaderMenuContent {
        return ReaderMenuContent(
            // One grid: the reader's actions, then its lists (contents, cards, comments, edits, study mode).
            sections = listOf(ReaderMenuSection(actionsSection() + pagesSection())),
        )
    }

    private fun actionsSection(): List<ReaderAction> {
        val layout = readerMenuActionLayout()
        val syncPresentation = readerSyncActionPresentation(
            operationActive = syncOperationActive,
            startRequested = syncStartRequested,
        )
        val syncAction = ReaderAction(
            R.string.sync,
            R.drawable.ic_refresh,
            visible = syncPresentation.visible,
            enabled = syncPresentation.visible,
            key = SYNC_ACTION_KEY,
            inProgress = syncPresentation.inProgress,
            dismissOnClick = false,
        ) {
            if (syncStartRequested || syncOperationActive) {
                return@ReaderAction
            }
            syncStartRequested = true
            syncTile?.setInProgress(true)
            activity.syncLibrary { queued ->
                syncStartRequested = false
                if (queued) {
                    refreshSyncState()
                } else {
                    syncTile?.setInProgress(syncOperationActive)
                }
            }
        }
        return listOf(syncAction) +
            layout.primary.mapNotNull(::action) +
            layout.navigation.mapNotNull(::navAction) + listOf(
                ReaderAction(R.string.pdf_highlights, R.drawable.ic_highlight, visible = hasFile()) {
                    activity.showHighlightOptions()
                },
                ReaderAction(R.string.document_status_title, R.drawable.info_icon, visible = hasFile()) {
                    activity.showDocumentStatus()
                },
                // The document's versions with their comments; a version opens in its own tab.
                ReaderAction(R.string.document_history, R.drawable.ic_versions, visible = hasFile()) {
                    activity.showDocumentHistory()
                },
            ) + htmlViewActions()
    }

    /**
     * The HTML view's own actions while it is shown: back to the PDF (the view is remembered per
     * document, so this is the way out), its text size and the cards it does not show. The menu no
     * longer offers to switch to it.
     */
    private fun htmlViewActions(): List<ReaderAction> {
        val html = activity.htmlReader
        return listOf(
            ReaderAction(R.string.html_view_show_pdf, R.drawable.ic_text, visible = hasFile() && html.isActive, key = HTML_VIEW_ACTION_KEY) {
                html.toggle()
            },
            ReaderAction(R.string.html_view_text_size, R.drawable.ic_zoom_out, visible = html.isActive) {
                html.showTextSizeDialog()
            },
            ReaderAction(R.string.html_view_not_shown_title, R.drawable.ic_view_list,
                visible = html.isActive && html.notShownCards().isNotEmpty()) {
                html.showNotShownList()
            },
        )
    }

    private fun pagesSection(): List<ReaderAction> {
        return readerMenuActionLayout().pages.mapNotNull(::action) + ReaderAction(
            R.string.agent_notes_study_mode,
            if (AgentStudyMode.isOn(activity)) R.drawable.ic_agent_note else R.drawable.ic_agent_note_outline,
        ) {
            activity.showStudyMode()
        }
    }

    private fun action(action: ConfigurableAction): ReaderAction? {
        val configuredAction = actionResolver.action(action) ?: return null
        return ReaderAction(
            configuredAction.titleRes,
            configuredAction.iconRes,
            visible = configuredAction.visible,
        ) {
            configuredAction.run()
        }
    }

    private fun navAction(action: ConfigurableAction): ReaderAction? {
        val configuredAction = actionResolver.action(action) ?: return null
        return ReaderAction(
            configuredAction.titleRes,
            configuredAction.iconRes,
            visible = hasFile(),
            enabled = configuredAction.visible,
        ) {
            configuredAction.run()
        }
    }
}

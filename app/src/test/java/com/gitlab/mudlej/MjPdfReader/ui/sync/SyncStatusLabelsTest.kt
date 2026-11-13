// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Resources that name an overall Sync state must use the shared vocabulary verbatim. */
class SyncStatusLabelsTest {
    private val strings: Map<String, String> by lazy {
        File("src/main/res/values").listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            (0 until nodes.length).map { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }.toMap()
    }

    @Test fun stateResourcesUseTheSharedLabels() {
        mapOf(
            "document_status_syncing" to SyncStatusLabels.SYNCING,
            "reader_project_sync_up_to_date" to SyncStatusLabels.UP_TO_DATE,
            "document_status_synced" to SyncStatusLabels.UP_TO_DATE,
            "reader_project_sync_pending" to SyncStatusLabels.CHANGES_PENDING,
            "home_pending_syncs" to SyncStatusLabels.CHANGES_PENDING,
            "reader_project_sync_build_failed" to SyncStatusLabels.PDF_BUILD_FAILED,
            "artifact_sync_status_failed" to SyncStatusLabels.SYNC_FAILED,
            "home_render_show_errors" to SyncStatusLabels.SHOW_LOGS,
            "home_render_error_show_log" to SyncStatusLabels.SHOW_LOGS,
        ).forEach { (name, label) -> assertEquals(name, label, strings[name]) }
    }
}

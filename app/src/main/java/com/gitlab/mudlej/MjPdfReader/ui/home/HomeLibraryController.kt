// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.util.Log
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCatalogue
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCatalogueSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class HomeLibraryData(
    val items: List<HomeItem>,
    val comments: CommentCatalogueSnapshot,
)

class HomeLibraryController(
    private val pdfRepository: PdfRepository,
    private val pref: Preferences,
    private val commentCatalogue: CommentCatalogue = CommentCatalogue(),
) {

    suspend fun loadLibrary(availabilityProbe: AvailabilityProbe, annotatedTitleFormat: String): List<HomeItem> {
        return loadLibraryData(availabilityProbe, annotatedTitleFormat).items
    }

    suspend fun loadLibraryData(
        availabilityProbe: AvailabilityProbe,
        annotatedTitleFormat: String,
    ): HomeLibraryData = coroutineScope {
        val startedAt = System.nanoTime()
        val showPdfTitle = pref.getHomeShowPdfTitle()
        val recordsDeferred = async {
            val queryStartedAt = System.nanoTime()
            pdfRepository.findAllRecords().also {
                logTiming(
                    "Loaded ${it.size} PDF records in " +
                        "${elapsedMillis(queryStartedAt)} ms"
                )
            }
        }
        val commentsDeferred = async(Dispatchers.IO) {
            commentCatalogue.loadSnapshot(usePersistentCacheImmediately = true)
        }
        val records = recordsDeferred.await()
        val comments = commentsDeferred.await()
        val items = withContext(Dispatchers.IO) {
            records
                .filter { it.fileName.isNotEmpty() }
                .map { record ->
                    withCommentMetadata(
                        HomeItem.from(
                            record,
                            showPdfTitle,
                            availabilityProbe.availabilityOf(record.uri),
                            annotatedTitleFormat,
                        ),
                        comments,
                    )
                }
        }
        HomeLibraryData(
            items = sort(items),
            comments = comments,
        ).also {
            logTiming("Prepared complete home catalogue in ${elapsedMillis(startedAt)} ms")
        }
    }

    fun sort(items: List<HomeItem>): List<HomeItem> {
        return items.sortedWith(compareBy<HomeItem> { it.sortKey }.thenBy { it.uri.toString() })
    }

    fun mergeWithEntries(records: List<HomeItem>, entries: List<ScannedPdfEntry>): List<HomeItem> {
        val recordsByPath = records
            .filter { it.uri.scheme == "file" }
            .associateBy { it.uri.path }
        val recordsByHash = records.associateBy { it.hash }
        return entries
            .filter { isLibraryPdfCandidate(it.path) }
            .map { entry ->
                val match = recordsByPath[entry.path] ?: entry.hash?.let { recordsByHash[it] }
                match?.copy(
                    fileName = java.io.File(entry.path).name,
                    sizeBytes = if (match.sizeBytes > 0) match.sizeBytes else entry.size,
                    length = if (match.length > 0) match.length else entry.pageCount,
                    fileModifiedMillis = entry.lastModified.takeIf { it > 0 }
                        ?: match.fileModifiedMillis,
                ) ?: withCommentMetadata(HomeItem.fromScan(entry))
            }
            .distinctBy { it.hash }
    }

    fun continueReading(items: List<HomeItem>): List<HomeItem> {
        return items
            .filter { it.hasBeenOpened }
            .sortedByDescending { it.lastOpened }
            .take(HERO_COUNT)
    }

    fun recents(items: List<HomeItem>, excluding: List<HomeItem>): List<HomeItem> {
        val excludedHashes = excluding.map { it.hash }.toSet()
        return items
            .filter { it.hasBeenOpened && it.hash !in excludedHashes }
            .sortedByDescending { it.lastOpened }
            .take(RECENTS_COUNT)
    }

    fun filterByQuery(items: List<HomeItem>, query: String): List<HomeItem> {
        if (query.isBlank()) {
            return items
        }
        return items.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.fileName.contains(query, ignoreCase = true) ||
                it.libraryDisplayName.contains(query, ignoreCase = true) ||
                it.commentSearchText.contains(query, ignoreCase = true)
        }
    }

    fun searchAll(records: List<HomeItem>, query: String): List<HomeItem> {
        if (query.isBlank()) {
            return records
        }
        return sort(filterByQuery(records, query))
    }

    private fun withCommentMetadata(
        item: HomeItem,
        snapshot: CommentCatalogueSnapshot? = null,
    ): HomeItem {
        val comments = snapshot?.metadataFor(item.pdfStem)
            ?: commentCatalogue.metadataFor(item.pdfStem)
        return item.copy(
            openCommentThreads = comments.openThreads,
            lastCommentAddedMillis = comments.lastCommentAddedMillis,
            commentSearchText = comments.searchableText,
        )
    }

    companion object {
        private const val TAG = "HomeLibrary"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val HERO_COUNT = 6
        private const val RECENTS_COUNT = 12

        private fun elapsedMillis(startedAt: Long): Long {
            return (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        }

        private fun logTiming(message: String) {
            runCatching { Log.d(TAG, message) }
        }
    }
}

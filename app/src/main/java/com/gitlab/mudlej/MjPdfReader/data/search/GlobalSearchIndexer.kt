// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Keeps the page text of every Library PDF on the phone in [GlobalSearchIndex]. An update reads
 * only PDFs that are new or changed since the last one and drops PDFs that are gone, so after the
 * first run it costs almost nothing.
 */
class GlobalSearchIndexer private constructor(context: Context) {

    /** One PDF to index: [ref] is its URI string, [identity] changes whenever its content does. */
    data class Pdf(val ref: String, val identity: String, val uri: Uri)

    data class Progress(val done: Int, val total: Int)

    private val app = context.applicationContext
    private val mutex = Mutex()
    private val pdfText by lazy { GlobalSearchPdfText(app) }
    private val index by lazy {
        val file = app.getDatabasePath(DATABASE_NAME).also { it.parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.enableWriteAheadLogging()
        GlobalSearchIndex(AndroidGlobalSearchSql(db)).also(GlobalSearchIndex::ensureSchema)
    }

    /** Non-null while an update reads PDFs. */
    @Volatile
    var progress: Progress? = null
        private set

    /** Brings the index up to date with [pdfs]; [onProgress] runs on the calling thread's IO worker. */
    suspend fun update(pdfs: List<Pdf>, onProgress: (Progress?) -> Unit = {}) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val startedAt = SystemClock.elapsedRealtime()
            val wanted = pdfs.distinctBy(Pdf::ref)
            val byKey = wanted.associateBy { GlobalSearchIndex.PDF_PREFIX + it.ref }
            val plan = planGlobalSearchUpdate(
                wanted.map { GlobalSearchItem(GlobalSearchIndex.PDF_PREFIX + it.ref, it.identity) },
                index.items(),
                setOf(GlobalSearchIndex.PDF_PREFIX),
            )
            plan.removed.forEach(index::remove)
            if (plan.changed.isEmpty()) {
                Log.i(TAG, "index current: ${wanted.size} PDFs, removed ${plan.removed.size}, " +
                    "${SystemClock.elapsedRealtime() - startedAt} ms")
                return@withLock
            }
            var pages = 0
            var failed = 0
            try {
                plan.changed.forEachIndexed { done, item ->
                    coroutineContext.ensureActive()
                    progress = Progress(done, plan.changed.size).also(onProgress)
                    val pdf = byKey.getValue(item.key)
                    val texts = try {
                        pdfText.pages(pdf.uri) { Thread.yield() }
                    } catch (error: Throwable) {
                        Log.w(TAG, "could not read ${pdf.ref}: ${error.javaClass.simpleName}")
                        failed++
                        // Remembered as read, so a broken file is not retried until it changes.
                        emptyList()
                    }
                    pages += texts.size
                    index.replace(item, texts.mapIndexedNotNull { page, text ->
                        text.takeIf(String::isNotBlank)?.let {
                            GlobalSearchEntry(GlobalSearchKind.PAGE, pdf.ref, page + 1, "", it)
                        }
                    })
                }
            } finally {
                progress = null
                onProgress(null)
            }
            Log.i(TAG, "indexed ${plan.changed.size} PDFs ($pages pages, $failed unreadable), " +
                "removed ${plan.removed.size}, ${index.entryCount()} pages in index, " +
                "${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    /** Pages matching every word of [query]. */
    suspend fun searchPages(query: GlobalSearchQuery, limit: Int = PAGE_LIMIT): List<GlobalSearchRow> =
        withContext(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            index.search(query, GlobalSearchKind.PAGE, limit).also {
                Log.d(TAG, "query \"${query.raw}\": ${it.size} pages in ${SystemClock.elapsedRealtime() - startedAt} ms")
            }
        }

    companion object {
        const val TAG = "ThoughtsGlobalSearch"
        private const val DATABASE_NAME = "global-search.sqlite"
        private const val PAGE_LIMIT = 400

        @Volatile
        private var instance: GlobalSearchIndexer? = null

        fun get(context: Context): GlobalSearchIndexer =
            instance ?: synchronized(this) { instance ?: GlobalSearchIndexer(context).also { instance = it } }
    }
}

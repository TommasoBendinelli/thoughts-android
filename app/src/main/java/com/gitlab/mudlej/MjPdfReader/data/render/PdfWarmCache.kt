// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.barteksc.pdfviewer.AndroidPreviewBitmaps
import com.github.barteksc.pdfviewer.PreviewBucketPolicy
import com.github.barteksc.pdfviewer.util.Constants
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Background warm cache for library PDFs: page geometry for every PDF, plus disk previews of
 * the first [PdfWarmCache.FIRST_PAGE_COUNT] pages of the most recently published PDFs, so
 * those documents open with their first pages already drawn.
 *
 * Previews are bounded by [PdfWarmCache.WARM_BUDGET_BYTES] and convergent: see
 * [PreviewWarmPlanner].
 */
class PdfWarmCache(private val context: Context) {

    internal data class Summary(
        val documents: Int,
        val geometryWritten: Int,
        val previews: PreviewWarmPlanner.Summary,
        val durationMs: Long,
    ) {
        fun logLine(): String =
            "pdf_warm_cache documents=$documents geometry_written=$geometryWritten " +
                "warm_documents=${previews.targetDocuments} opened=${previews.documentsOpened} " +
                "pages_rendered=${previews.pagesRendered} bytes_written=${previews.bytesWritten} " +
                "bytes_held=${previews.bytesHeld} budget=${previews.budgetBytes} " +
                "pages_deleted=${previews.pagesDeleted} bucket=${previews.bucket} " +
                "budget_reached=${previews.budgetReached} stopped=${previews.stopped} " +
                "duration_ms=$durationMs"
    }

    /** [pdfs] must be ordered newest first; that order decides which documents get previews. */
    internal fun warm(pdfs: List<Pair<File, String>>, shouldContinue: () -> Boolean): Summary {
        val started = SystemClock.elapsedRealtime()
        deleteLegacyPreviewDirectory(context)
        val valid = pdfs.filter { (pdf, sha256) -> pdf.isFile && sha256.matches(SHA256) }
        var geometryWritten = 0
        for ((pdf, sha256) in valid) {
            if (!shouldContinue()) break
            if (geometryFile(context, sha256, pdf.length()).isFile) continue
            runCatching {
                PdfiumPreviewSource(context, pdf).use { source ->
                    writeGeometry(context, sha256, pdf.length(), source.geometry())
                }
                geometryWritten += 1
            }
        }
        val planner = PreviewWarmPlanner(
            root = File(context.cacheDir, PREVIEW_DIRECTORY),
            bucket = previewBucket(context),
            budgetBytes = WARM_BUDGET_BYTES,
            pagesPerDocument = FIRST_PAGE_COUNT,
        )
        val previews = planner.run(
            valid.map { (pdf, sha256) ->
                PreviewWarmPlanner.Candidate(previewDocumentKey(sha256, pdf.length())) {
                    PdfiumPreviewSource(context, pdf)
                }
            },
            shouldContinue,
        )
        return Summary(valid.size, geometryWritten, previews, SystemClock.elapsedRealtime() - started)
    }

    private class PdfiumPreviewSource(context: Context, pdf: File) : PreviewWarmPlanner.Source {
        private val core = PdfiumCore(context)
        private val document: PdfDocument =
            core.newDocument(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY))

        override val pageCount: Int = core.getPageCount(document)

        fun geometry(): FloatArray {
            val geometry = FloatArray(pageCount * 4)
            for (page in 0 until pageCount) {
                val pixelSize = core.getPageSize(document, page)
                val pointSize = core.getPageSizePoint(document, page)
                val offset = page * 4
                geometry[offset] = pixelSize.width.toFloat()
                geometry[offset + 1] = pixelSize.height.toFloat()
                geometry[offset + 2] = pointSize.width
                geometry[offset + 3] = pointSize.height
            }
            return geometry
        }

        override fun encode(page: Int, bucket: Int): ByteArray? {
            val pointSize = core.getPageSizePoint(document, page)
            if (pointSize.width <= 0f || pointSize.height <= 0f) return null
            val height = (bucket * pointSize.height / pointSize.width).roundToInt().coerceIn(1, MAX_PREVIEW_HEIGHT)
            val bitmap = Bitmap.createBitmap(bucket, height, Bitmap.Config.RGB_565)
            try {
                bitmap.eraseColor(Color.WHITE)
                if (core.openPage(document, page) == 0L) return null
                core.renderPageBitmap(document, bitmap, page, 0, 0, bucket, height, false)
                val output = ByteArrayOutputStream(64 * 1024)
                if (!bitmap.compress(AndroidPreviewBitmaps.previewWebpFormat(), Constants.PREVIEW_WEBP_QUALITY, output)) {
                    return null
                }
                return output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }

        override fun close() {
            core.closeDocument(document)
        }
    }

    companion object {
        private const val GEOMETRY_MAGIC = 0x54484731
        private const val GEOMETRY_DIRECTORY = "pdf-geometry-v1"

        /**
         * v3: bounded warm set, one bucket, low-effort WebP. The reader and this warmer share
         * the directory; the old unbounded "previews" directory (up to 256 MB) is deleted once.
         */
        const val PREVIEW_DIRECTORY = "previews-v3"
        private const val LEGACY_PREVIEW_DIRECTORY = "previews"
        const val FIRST_PAGE_COUNT = 8
        private const val MAX_PREVIEW_HEIGHT = 4096
        private const val MAX_GEOMETRY_VALUES = 4_000_000

        /**
         * Two thirds of the reader's own trim budget for the shared directory, so previews the
         * reader writes for opened documents rarely push warmed files out.
         */
        val WARM_BUDGET_BYTES: Long = Constants.PREVIEW_DISK_BUDGET_BYTES * 2 / 3
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        fun previewDocumentKey(fileIdentity: String, sizeBytes: Long?): String {
            val safeIdentity = fileIdentity.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return "$safeIdentity-${sizeBytes ?: "ns"}-v2"
        }

        fun previewFilename(page: Int, bucket: Int): String = "p$page-w$bucket-t0.webp"

        fun deleteLegacyPreviewDirectory(context: Context) {
            val legacy = File(context.cacheDir, LEGACY_PREVIEW_DIRECTORY)
            if (legacy.exists()) legacy.deleteRecursively()
        }

        fun loadGeometry(context: Context, sha256: String, sizeBytes: Long?): FloatArray? {
            if (!sha256.matches(SHA256) || sizeBytes == null || sizeBytes < 0) return null
            val file = geometryFile(context, sha256, sizeBytes)
            if (!file.isFile) return null
            return runCatching {
                // Buffered: each readFloat on a bare FileInputStream is four read() system calls.
                DataInputStream(java.io.BufferedInputStream(FileInputStream(file), 64 * 1024)).use { input ->
                    check(input.readInt() == GEOMETRY_MAGIC)
                    val count = input.readInt()
                    check(count > 0 && count % 4 == 0 && count <= MAX_GEOMETRY_VALUES)
                    FloatArray(count) { input.readFloat() }
                }
            }.getOrNull()
        }

        /**
         * The one preview width the reader asks for on this device: fit-width pages in portrait,
         * with the reader's own low-tier rule.
         */
        internal fun previewBucket(context: Context): Int {
            val metrics = context.resources.displayMetrics
            val portraitWidth = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
            return PreviewBucketPolicy.bucketFor(portraitWidth.toFloat(), PreviewBucketPolicy.isLowTier(context))
        }

        private fun geometryFile(context: Context, sha256: String, sizeBytes: Long): File {
            val density = context.resources.displayMetrics.densityDpi
            return File(File(context.cacheDir, GEOMETRY_DIRECTORY), "$sha256-$sizeBytes-d$density.bin")
        }

        private fun writeGeometry(context: Context, sha256: String, sizeBytes: Long, geometry: FloatArray) {
            if (geometry.isEmpty()) return
            val destination = geometryFile(context, sha256, sizeBytes)
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { fileOutput ->
                    DataOutputStream(java.io.BufferedOutputStream(fileOutput, 64 * 1024)).use { output ->
                        output.writeInt(GEOMETRY_MAGIC)
                        output.writeInt(geometry.size)
                        geometry.forEach(output::writeFloat)
                        output.flush()
                        fileOutput.fd.sync()
                    }
                }
                check(temporary.renameTo(destination)) { "Could not activate PDF geometry cache" }
            } finally {
                temporary.delete()
            }
        }
    }
}

/**
 * Chooses and fills the warmed preview set. Pure file logic, so it is unit-tested without
 * Pdfium.
 *
 * Walk the candidates newest first, pages 0 until min(pageCount, pagesPerDocument). A page
 * whose file exists costs its length; a missing page costs its size from the ledger, or is
 * encoded to learn it. The walk stops at the first page that would push the held bytes over
 * the budget, without writing it. The set is therefore a prefix of the candidate order that
 * fits the budget.
 *
 * The ledger (a top-level file, which the reader's trim never touches) remembers each
 * document's page count and page sizes, including the size of the page that stopped the
 * walk. A second run over an unchanged library thus opens no PDF and renders nothing.
 *
 * After a complete walk, warmed files that left the set (displaced by newer documents) are
 * deleted. Files counted in the current run are never deleted. A stopped (cancelled) run
 * deletes nothing and keeps the ledger entries it did not reach.
 */
internal class PreviewWarmPlanner(
    private val root: File,
    private val bucket: Int,
    private val budgetBytes: Long,
    private val pagesPerDocument: Int,
) {

    interface Source : Closeable {
        val pageCount: Int

        /** WebP bytes of [page] at [bucket] px width, or null when the page cannot be rendered. */
        fun encode(page: Int, bucket: Int): ByteArray?
    }

    class Candidate(val docKey: String, val open: () -> Source)

    internal data class Summary(
        val targetDocuments: Int,
        val documentsOpened: Int,
        val pagesRendered: Int,
        val bytesWritten: Long,
        val bytesHeld: Long,
        val pagesDeleted: Int,
        val budgetBytes: Long,
        val bucket: Int,
        val budgetReached: Boolean,
        val stopped: Boolean,
    )

    private class Entry(val pageCount: Int, val sizes: List<Long>)

    private class Ledger(val bucket: Int, val entries: LinkedHashMap<String, Entry>)

    fun run(candidates: List<Candidate>, shouldContinue: () -> Boolean = { true }): Summary {
        root.mkdirs()
        val old = readLedger()
        var pagesDeleted = 0
        val oldEntries = if (old != null && old.bucket != bucket) {
            // Another device width: the old files can never be read again.
            old.entries.forEach { (docKey, entry) -> pagesDeleted += deletePages(docKey, entry, old.bucket, emptySet()) }
            LinkedHashMap()
        } else {
            old?.entries ?: LinkedHashMap()
        }

        val newEntries = LinkedHashMap<String, Entry>()
        val kept = HashSet<String>()
        var held = 0L
        var opened = 0
        var rendered = 0
        var written = 0L
        var budgetReached = false
        var stopped = false

        for (candidate in candidates) {
            if (!shouldContinue()) {
                stopped = true
                break
            }
            if (newEntries.containsKey(candidate.docKey)) continue
            val known = oldEntries[candidate.docKey]
            var source: Source? = null
            fun source(): Source = source ?: candidate.open().also {
                source = it
                opened += 1
            }
            try {
                val pageCount = known?.pageCount ?: source().pageCount
                val sizes = ArrayList<Long>()
                for (page in 0 until min(pageCount, pagesPerDocument)) {
                    if (!shouldContinue()) {
                        stopped = true
                        break
                    }
                    val file = pageFile(candidate.docKey, page, bucket)
                    val existing = if (file.isFile) file.length() else 0L
                    if (existing > 0L) {
                        sizes += existing
                        if (held + existing > budgetBytes) {
                            budgetReached = true
                            break
                        }
                        held += existing
                        kept += file.path
                        continue
                    }
                    val knownSize = known?.sizes?.getOrNull(page)
                    if (knownSize == FAILED) {
                        sizes += FAILED
                        continue
                    }
                    if (knownSize != null && held + knownSize > budgetBytes) {
                        sizes += knownSize
                        budgetReached = true
                        break
                    }
                    val bytes = runCatching { source().encode(page, bucket) }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        sizes += FAILED
                        continue
                    }
                    sizes += bytes.size.toLong()
                    if (held + bytes.size > budgetBytes) {
                        budgetReached = true
                        break
                    }
                    write(file, bytes)
                    rendered += 1
                    written += bytes.size
                    held += bytes.size
                    kept += file.path
                }
                newEntries[candidate.docKey] = Entry(pageCount, sizes)
            } catch (error: Exception) {
                // An unreadable PDF gets no previews and is not opened again; a later version
                // has another key.
                newEntries.putIfAbsent(candidate.docKey, Entry(0, emptyList()))
            } finally {
                runCatching { source?.close() }
            }
            if (budgetReached || stopped) break
        }

        if (stopped) {
            oldEntries.forEach { (docKey, entry) -> newEntries.putIfAbsent(docKey, entry) }
        } else {
            oldEntries.forEach { (docKey, entry) -> pagesDeleted += deletePages(docKey, entry, bucket, kept) }
        }
        writeLedger(Ledger(bucket, newEntries))
        return Summary(
            targetDocuments = newEntries.count { (docKey, _) -> pageFile(docKey, 0, bucket).path in kept },
            documentsOpened = opened,
            pagesRendered = rendered,
            bytesWritten = written,
            bytesHeld = held,
            pagesDeleted = pagesDeleted,
            budgetBytes = budgetBytes,
            bucket = bucket,
            budgetReached = budgetReached,
            stopped = stopped,
        )
    }

    private fun pageFile(docKey: String, page: Int, bucket: Int): File =
        File(File(root, docKey), PdfWarmCache.previewFilename(page, bucket))

    private fun deletePages(docKey: String, entry: Entry, bucket: Int, kept: Set<String>): Int {
        var deleted = 0
        for (page in 0 until min(entry.pageCount, pagesPerDocument)) {
            val file = pageFile(docKey, page, bucket)
            if (file.path !in kept && file.isFile && file.delete()) deleted += 1
        }
        File(root, docKey).delete() // only when empty
        return deleted
    }

    private fun write(destination: File, bytes: ByteArray) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            // No fsync: a torn preview fails to decode and the reader deletes it.
            FileOutputStream(temporary).use { it.write(bytes) }
            check(temporary.renameTo(destination)) { "Could not activate PDF preview" }
        } finally {
            temporary.delete()
        }
    }

    private fun ledgerFile(): File = File(root, LEDGER_FILENAME)

    private fun readLedger(): Ledger? = runCatching {
        val lines = ledgerFile().takeIf(File::isFile)?.readLines() ?: return null
        val header = lines.firstOrNull()?.split(' ') ?: return null
        if (header.size != 2 || header[0] != LEDGER_HEADER) return null
        val entries = LinkedHashMap<String, Entry>()
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            if (fields.size != 3) continue
            val pageCount = fields[1].toIntOrNull() ?: continue
            val sizes = if (fields[2].isEmpty()) emptyList() else fields[2].split(',').map(String::toLong)
            entries[fields[0]] = Entry(pageCount, sizes)
        }
        Ledger(header[1].toInt(), entries)
    }.getOrNull()

    private fun writeLedger(ledger: Ledger) {
        val text = buildString {
            append(LEDGER_HEADER).append(' ').append(ledger.bucket).append('\n')
            ledger.entries.forEach { (docKey, entry) ->
                append(docKey).append('\t').append(entry.pageCount).append('\t')
                append(entry.sizes.joinToString(",")).append('\n')
            }
        }
        val temporary = File(root, ".$LEDGER_FILENAME.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(text)
            check(temporary.renameTo(ledgerFile())) { "Could not activate preview warm ledger" }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val LEDGER_FILENAME = "warm-ledger.txt"
        private const val LEDGER_HEADER = "warm-ledger-v1"
        private const val FAILED = -1L
    }
}

class PdfWarmCacheWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val canonicalRoot = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val snapshot = SourceLinkedPdfIndex(canonicalRoot).snapshot()
        if (!snapshot.libraryAvailable || !snapshot.generationCurrent) return Result.success()
        val pdfs = snapshot.libraryPdfSha256ByFilename
            .toList()
            .sortedWith(
                compareByDescending<Pair<String, String>> { (filename, _) ->
                    snapshot.libraryPublishedAtByFilename[filename].orEmpty()
                }.thenBy { (filename, _) -> filename },
            )
            .map { (filename, sha256) -> File(canonicalRoot, filename) to sha256 }
        val summary = PdfWarmCache(applicationContext).warm(pdfs) { !isStopped }
        runCatching { Log.i(TIMING_TAG, summary.logLine()) }
        return Result.success()
    }

    companion object {
        private const val TIMING_TAG = "ThoughtsSyncTiming"

        // New name: WorkManager keeps the constraints of an enqueued request under KEEP, so
        // the old idle-only request must not be reused.
        private const val WORK_NAME = "thoughts-pdf-warm-cache-v2"
        private const val LEGACY_WORK_NAME = "thoughts-pdf-warm-cache"

        fun enqueue(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PdfWarmCacheWorker>()
                    .setConstraints(pdfWarmCacheConstraints())
                    .build(),
            )
        }

        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(WORK_NAME)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)
        }
    }
}

/** Plugged in, idle and battery not low: the warm cache never runs on battery. */
internal fun pdfWarmCacheConstraints(): Constraints = Constraints.Builder()
    .setRequiresCharging(true)
    .setRequiresDeviceIdle(true)
    .setRequiresBatteryNotLow(true)
    .build()

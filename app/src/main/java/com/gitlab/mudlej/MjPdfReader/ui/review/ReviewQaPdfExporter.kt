// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument as AndroidPdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.shockwave.pdfium.PdfDocument as PdfiumDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReviewQaExportRequest(
    val pdfStem: String,
    val appendSourcePdf: Boolean,
)

internal data class ReviewQaExportResult(
    val cardCount: Int,
    val qaPageCount: Int,
    val sourcePdfAppended: Boolean,
)

internal fun reviewQaExportFileName(request: ReviewQaExportRequest): String {
    val safeStem = request.pdfStem
        .trim()
        .replace(INVALID_FILENAME_CHARACTERS, "_")
        .trim(' ', '.')
        .ifBlank { "Review" }
    val appendixSuffix = if (request.appendSourcePdf) ".with-source" else ""
    return "$safeStem.questions-and-answers$appendixSuffix.pdf"
}

internal fun reviewQaExportCards(
    cards: List<ReviewBrowserCard>,
    pdfStem: String,
): List<ReviewBrowserCard> = cards.asSequence()
    .filter { item -> item.card.pdfStem == pdfStem && item.canReview() }
    .sortedWith(
        compareBy<ReviewBrowserCard> { item -> item.card.page }
            .thenBy { item -> item.card.rect.top }
            .thenBy { item -> item.card.rect.left }
            .thenBy { item -> item.card.id },
    )
    .toList()

internal class ReviewQaPdfExporter(
    private val context: Context,
    private val reviewStore: ReviewStore = ReviewStore(context.applicationContext),
    private val sourceIndex: SourceLinkedPdfIndex = SourceLinkedPdfIndex(),
) {

    suspend fun export(
        request: ReviewQaExportRequest,
        destinationUri: Uri,
    ): ReviewQaExportResult = withContext(Dispatchers.IO) {
        sourceIndex.invalidate()
        val snapshot = sourceIndex.snapshot()
        check(snapshot.libraryAvailable && snapshot.generationCurrent) {
            context.getString(R.string.review_qa_export_library_unavailable)
        }

        val sourceFilename = "${request.pdfStem}.pdf"
        val canonicalSha256 = snapshot.libraryPdfSha256ByFilename[sourceFilename]
            ?: error(context.getString(R.string.review_qa_export_source_unavailable))
        val sourcePdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), sourceFilename)
        check(sourcePdf.isFile && sourcePdf.length() > 0L) {
            context.getString(R.string.review_qa_export_source_unavailable)
        }

        val canonicalPdf = mapOf(sourceFilename to canonicalSha256)
        val cards = reviewQaExportCards(
            ReviewAnswerTextLoader(context.applicationContext).populate(
                reviewStore.browserCards(canonicalPdfSha256ByFilename = canonicalPdf),
                canonicalPdf,
            ),
            request.pdfStem,
        )
        check(cards.isNotEmpty()) {
            context.getString(R.string.review_qa_export_no_cards)
        }

        val exportDirectory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        purgeExpiredExports(exportDirectory)
        val exportId = UUID.randomUUID().toString()
        val qaPdf = File(exportDirectory, "$exportId-qa.pdf")
        val mergedPdf = File(exportDirectory, "$exportId-merged.pdf")
        try {
            val qaPageCount = writeQaPdf(qaPdf, request.pdfStem, cards)
            val completedPdf = if (request.appendSourcePdf) {
                mergeWithSourcePdf(qaPdf, sourcePdf, mergedPdf)
                mergedPdf
            } else {
                qaPdf
            }
            copyToDestination(completedPdf, destinationUri)
            ReviewQaExportResult(
                cardCount = cards.size,
                qaPageCount = qaPageCount,
                sourcePdfAppended = request.appendSourcePdf,
            )
        } finally {
            qaPdf.delete()
            mergedPdf.delete()
        }
    }

    private fun writeQaPdf(
        destination: File,
        pdfStem: String,
        cards: List<ReviewBrowserCard>,
    ): Int {
        val writer = ReviewQaDocumentWriter(context, pdfStem, cards.size)
        return try {
            cards.forEachIndexed(writer::writeCard)
            FileOutputStream(destination).use(writer::finish)
        } finally {
            writer.close()
        }
    }

    private fun mergeWithSourcePdf(
        qaPdf: File,
        sourcePdf: File,
        destination: File,
    ) {
        val core = PdfiumCore(context)
        val qaDescriptor = ParcelFileDescriptor.open(qaPdf, ParcelFileDescriptor.MODE_READ_ONLY)
        var qaDocument: PdfiumDocument? = null
        var sourceDescriptor: ParcelFileDescriptor? = null
        var sourceDocument: PdfiumDocument? = null
        try {
            qaDocument = core.newDocument(qaDescriptor)
            sourceDescriptor = ParcelFileDescriptor.open(
                sourcePdf,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            sourceDocument = core.newDocument(sourceDescriptor)
            ParcelFileDescriptor.open(
                destination,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE,
            ).use { output ->
                if (!core.writeMergedDocuments(qaDocument, sourceDocument, output)) {
                    throw IOException("PDFium could not append the source PDF")
                }
            }
        } finally {
            if (sourceDocument != null) {
                core.closeDocument(sourceDocument)
            } else {
                sourceDescriptor?.close()
            }
            if (qaDocument != null) {
                core.closeDocument(qaDocument)
            } else {
                qaDescriptor.close()
            }
        }
    }

    private fun copyToDestination(source: File, destinationUri: Uri) {
        val output = if (destinationUri.scheme == "file") {
            FileOutputStream(File(requireNotNull(destinationUri.path)))
        } else {
            context.contentResolver.openOutputStream(destinationUri, "wt")
                ?: context.contentResolver.openOutputStream(destinationUri, "w")
                ?: throw IOException("Could not open the selected export destination")
        }
        source.inputStream().use { input ->
            output.use { destination -> input.copyTo(destination) }
        }
    }

    private fun purgeExpiredExports(directory: File) {
        val expiredBefore = System.currentTimeMillis() - EXPORT_MAX_AGE_MILLIS
        directory.listFiles()
            ?.filter { file -> file.isFile && file.lastModified() < expiredBefore }
            ?.forEach(File::delete)
    }

    companion object {
        private const val EXPORT_DIRECTORY = "review-qa-exports"
        private const val EXPORT_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    }
}

private class ReviewQaDocumentWriter(
    private val context: Context,
    private val pdfStem: String,
    cardCount: Int,
) : AutoCloseable {
    private val document = AndroidPdfDocument()
    private var currentPage: AndroidPdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var pageNumber = 0
    private var y = CONTENT_TOP
    private var finished = false

    private val titlePaint = textPaint(25f, Color.rgb(28, 27, 31), Typeface.BOLD)
    private val subtitlePaint = textPaint(12f, Color.rgb(73, 69, 79), Typeface.NORMAL)
    private val labelPaint = textPaint(10f, Color.rgb(0, 105, 119), Typeface.BOLD)
    private val questionPaint = textPaint(13f, Color.rgb(28, 27, 31), Typeface.BOLD)
    private val answerPaint = textPaint(11.5f, Color.rgb(28, 27, 31), Typeface.NORMAL)
    private val sourcePaint = textPaint(9f, Color.rgb(73, 69, 79), Typeface.NORMAL)
    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(121, 116, 126)
        textSize = 8.5f
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(202, 196, 208)
        strokeWidth = 0.8f
    }

    init {
        startPage()
        drawTextFlow(pdfStem, titlePaint)
        y += 5f
        drawTextFlow(
            context.getString(R.string.review_qa_export_document_subtitle, cardCount),
            subtitlePaint,
        )
        y += 16f
        drawDivider()
        y += 18f
    }

    fun writeCard(index: Int, item: ReviewBrowserCard) {
        ensureSpace(MIN_CARD_START_HEIGHT)
        drawTextFlow(
            context.getString(R.string.review_qa_export_question_number, index + 1),
            labelPaint,
        )
        y += 4f
        drawTextFlow(item.card.question.trim(), questionPaint)
        y += 13f
        ensureSpace(MIN_ANSWER_START_HEIGHT)
        drawTextFlow(context.getString(R.string.review_qa_export_answer), labelPaint)
        y += 4f
        val answer = item.answerText.trim().ifBlank {
            context.getString(R.string.review_qa_export_answer_unavailable, item.card.page)
        }
        drawTextFlow(answer, answerPaint)
        y += 8f
        drawTextFlow(
            context.getString(R.string.review_qa_export_source_page, item.card.page),
            sourcePaint,
        )
        y += 15f
        drawDivider()
        y += 17f
    }

    fun finish(output: FileOutputStream): Int {
        check(!finished) { "The Q&A PDF has already been finished" }
        finishPage()
        document.writeTo(output)
        output.flush()
        finished = true
        return pageNumber
    }

    override fun close() {
        if (!finished && currentPage != null) {
            runCatching { finishPage() }
        }
        document.close()
    }

    private fun startPage() {
        pageNumber += 1
        val info = AndroidPdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        currentPage = document.startPage(info)
        canvas = currentPage?.canvas
        y = CONTENT_TOP
    }

    private fun finishPage() {
        val page = currentPage ?: return
        val currentCanvas = checkNotNull(canvas)
        currentCanvas.drawLine(
            HORIZONTAL_MARGIN,
            FOOTER_LINE_Y,
            PAGE_WIDTH - HORIZONTAL_MARGIN,
            FOOTER_LINE_Y,
            dividerPaint,
        )
        currentCanvas.drawText(
            "$pdfStem · $pageNumber",
            HORIZONTAL_MARGIN,
            FOOTER_TEXT_Y,
            footerPaint,
        )
        document.finishPage(page)
        currentPage = null
        canvas = null
    }

    private fun newPage() {
        finishPage()
        startPage()
    }

    private fun ensureSpace(minimumHeight: Float) {
        if (CONTENT_BOTTOM - y < minimumHeight) newPage()
    }

    private fun drawDivider() {
        checkNotNull(canvas).drawLine(
            HORIZONTAL_MARGIN,
            y,
            PAGE_WIDTH - HORIZONTAL_MARGIN,
            y,
            dividerPaint,
        )
    }

    private fun drawTextFlow(rawText: String, paint: TextPaint) {
        var remaining = rawText.trim().ifEmpty { " " }
        while (remaining.isNotEmpty()) {
            val availableHeight = (CONTENT_BOTTOM - y).toInt()
            if (availableHeight <= paint.textSize.toInt()) {
                newPage()
                continue
            }
            val layout = textLayout(remaining, paint)
            var fittingLineCount = 0
            while (
                fittingLineCount < layout.lineCount &&
                layout.getLineBottom(fittingLineCount) <= availableHeight
            ) {
                fittingLineCount += 1
            }
            if (fittingLineCount == 0) {
                newPage()
                continue
            }

            val drawnHeight = layout.getLineBottom(fittingLineCount - 1)
            val currentCanvas = checkNotNull(canvas)
            currentCanvas.save()
            currentCanvas.translate(HORIZONTAL_MARGIN, y)
            currentCanvas.clipRect(0f, 0f, CONTENT_WIDTH.toFloat(), drawnHeight.toFloat())
            layout.draw(currentCanvas)
            currentCanvas.restore()
            y += drawnHeight

            val consumedCharacters = layout.getLineEnd(fittingLineCount - 1)
            remaining = remaining.substring(consumedCharacters).trimStart()
            if (remaining.isNotEmpty()) newPage()
        }
    }

    private fun textLayout(text: String, paint: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, CONTENT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(2f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()

    private fun textPaint(size: Float, color: Int, style: Int) = TextPaint(
        Paint.ANTI_ALIAS_FLAG,
    ).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, style)
    }

    companion object {
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val HORIZONTAL_MARGIN = 48f
        private const val CONTENT_TOP = 48f
        private const val CONTENT_BOTTOM = 790f
        private const val CONTENT_WIDTH = PAGE_WIDTH - 96
        private const val FOOTER_LINE_Y = 806f
        private const val FOOTER_TEXT_Y = 824f
        private const val MIN_CARD_START_HEIGHT = 105f
        private const val MIN_ANSWER_START_HEIGHT = 48f
    }
}

private val INVALID_FILENAME_CHARACTERS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

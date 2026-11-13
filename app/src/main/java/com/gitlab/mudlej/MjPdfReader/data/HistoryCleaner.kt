// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationJournal
import com.gitlab.mudlej.MjPdfReader.data.annotation.SourceKey
import com.gitlab.mudlej.MjPdfReader.data.usage.ReadingUsageService
import com.gitlab.mudlej.MjPdfReader.ui.home.CoverCache

class HistoryCleaner(
    private val pdfRepository: PdfRepository,
    private val annotationJournal: AnnotationJournal,
    private val coverCache: CoverCache,
    private val readingUsageService: ReadingUsageService,
) {

    suspend fun deleteDocument(fileHash: String) {
        val record = pdfRepository.findRecord(fileHash)
        pdfRepository.removeUserBookmarksByFileHash(fileHash)
        pdfRepository.removeAnnotationSaveDestinationByLastSavedHash(fileHash)
        if (record != null) {
            readingUsageService.deleteDocumentUri(record.uri)
            annotationJournal.delete(record.uri)
            pdfRepository.removeAnnotationSaveDestinationBySourceKey(SourceKey.of(record.uri))
            pdfRepository.removeRecords(listOf(fileHash))
        }
        coverCache.invalidate(fileHash)
    }

    suspend fun deleteDocuments(fileHashes: List<String>) {
        fileHashes.forEach { deleteDocument(it) }
    }
}

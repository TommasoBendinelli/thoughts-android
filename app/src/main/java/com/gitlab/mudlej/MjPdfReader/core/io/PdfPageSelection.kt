// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

sealed class PdfPageSelection {
    data object All : PdfPageSelection()

    data class Range(
        val firstPageIndex: Int,
        val lastPageIndex: Int,
    ) : PdfPageSelection() {
        init {
            require(firstPageIndex >= 0) { "The first page index must be non-negative" }
            require(lastPageIndex >= firstPageIndex) {
                "The last page index must not precede the first"
            }
        }

        val pageCount: Int get() = lastPageIndex - firstPageIndex + 1

        fun pageIndices(): IntArray = IntArray(pageCount) { firstPageIndex + it }
    }

    fun includes(pageIndex: Int): Boolean = when (this) {
        All -> true
        is Range -> pageIndex in firstPageIndex..lastPageIndex
    }

    fun sharedPageIndex(originalPageIndex: Int): Int = when (this) {
        All -> originalPageIndex
        is Range -> originalPageIndex - firstPageIndex
    }
}

sealed class PdfPageRangeValidation {
    data class Valid(val selection: PdfPageSelection.Range) : PdfPageRangeValidation()
    data object MissingPage : PdfPageRangeValidation()
    data object OutOfBounds : PdfPageRangeValidation()
    data object Reversed : PdfPageRangeValidation()
}

fun validatePdfPageRange(
    firstPageText: String,
    lastPageText: String,
    pageCount: Int,
): PdfPageRangeValidation {
    val firstPage = firstPageText.trim().toIntOrNull()
        ?: return PdfPageRangeValidation.MissingPage
    val lastPage = lastPageText.trim().toIntOrNull()
        ?: return PdfPageRangeValidation.MissingPage
    if (pageCount < 1 || firstPage !in 1..pageCount || lastPage !in 1..pageCount) {
        return PdfPageRangeValidation.OutOfBounds
    }
    if (firstPage > lastPage) {
        return PdfPageRangeValidation.Reversed
    }
    return PdfPageRangeValidation.Valid(
        PdfPageSelection.Range(firstPage - 1, lastPage - 1)
    )
}

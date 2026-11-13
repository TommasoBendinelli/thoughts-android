// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

/** What the reader does with a review card it was asked to show. */
internal enum class ReviewOpenOutcome {
    /** The PDF shown is the card's: show it. */
    SHOW,
    /** The PDF is still loading or reloading (no hash or no pages yet); its load reopens the card. */
    WAIT_FOR_DOCUMENT,
    /** The installed Library PDF is the card's but the reader shows an older one: reload it. */
    RELOAD_INSTALLED,
    /** The installed PDF really differs from the card's: the card is unavailable here. */
    STALE,
}

/**
 * Decides how to open a card. [shownPdfSha256] is the verified hash of the PDF the reader shows
 * (empty while it is not known) and [pageCount] its loaded pages (0 while loading). A card is
 * only called unavailable when a loaded PDF really differs from it, or after the reader already
 * waited once for a PDF that never finished loading ([alreadyWaited]).
 */
/**
 * A card made in the HTML view and not linked yet has no place in the PDF (no page, rectangle or
 * regions): reviewed in the PDF view it gets no mask, only the "Placed at next Sync" notice with
 * Skip and the switch to the HTML view.
 */
internal fun reviewNeedsHtmlViewNotice(card: com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard, htmlShown: Boolean): Boolean =
    !htmlShown && !card.hasPdfPlacement && card.htmlPending

internal fun reviewOpenOutcome(
    shownPdfSha256: String,
    pageCount: Int,
    cardPdfSha256: String,
    cardHasAreas: Boolean,
    installedPdfSha256: String?,
    reloadAlreadyRequested: Boolean,
    alreadyWaited: Boolean,
    placingAgain: Boolean,
): ReviewOpenOutcome {
    val shown = shownPdfSha256.lowercase()
    val card = cardPdfSha256.lowercase()
    val installed = installedPdfSha256?.lowercase()
    val loading = shown.isEmpty() || pageCount <= 0
    if (loading && !alreadyWaited) return ReviewOpenOutcome.WAIT_FOR_DOCUMENT
    if (installed != null && installed == card && installed != shown && !reloadAlreadyRequested) {
        return ReviewOpenOutcome.RELOAD_INSTALLED
    }
    if (loading) return ReviewOpenOutcome.STALE
    // Placing a card again works on the PDF shown, whatever version the card last had.
    if (!placingAgain && (shown != card || !cardHasAreas)) return ReviewOpenOutcome.STALE
    return ReviewOpenOutcome.SHOW
}

/**
 * Whether a request for [requestedCardId] is already satisfied: the reader shows that card on the
 * same document (URI and hash) with its pages loaded. Coming back from another app then keeps the
 * card and the answer in progress instead of opening it anew.
 */
internal fun reviewCardAlreadyShown(
    shownCardId: String?,
    requestedCardId: String,
    reviewActive: Boolean,
    openPending: Boolean,
    shownForDocument: Pair<Any?, String?>?,
    currentDocument: Pair<Any?, String?>,
    pageCount: Int,
    placingAgain: Boolean,
): Boolean = shownCardId == requestedCardId && reviewActive && !openPending && !placingAgain &&
    pageCount > 0 && shownForDocument == currentDocument

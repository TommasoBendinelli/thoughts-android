// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tbend.thoughts.sdk.ServerException

/**
 * A card made in an HTML attachment page that the server refuses with 409
 * `html_attachment_unknown` is kept on the phone as not sent instead of blocking every Sync.
 */
class UnknownAttachmentCardRefusalTest {
    private fun row(entityType: String) = ThoughtsOutboxEntity(
        operationId = "op", deviceSequence = 1, entityType = entityType, entityId = "card", collection = "cards",
        baseRevision = null, clientTimestamp = "2026-10-27T00:00:00Z", payloadJson = "{}", createdAtEpochMillis = 1,
    )

    private val unknown = ServerException(409,
        """{"detail":"The HTML attachment docs-e2e/tex/support/html/a.html (6b07ef11e7ea) is not one e2e.pdf links to","error_code":"html_attachment_unknown"}""")

    @Test fun onlyTheCodedRefusalOfACardIsKeptAsNotSent() {
        assertTrue(isUnknownAttachmentCardRefusal(row("review_card"), unknown))
        assertFalse(isUnknownAttachmentCardRefusal(row("review_event"), unknown))
        assertFalse(isUnknownAttachmentCardRefusal(row("review_card"), ServerException(409, """{"detail":"conflict"}""")))
        assertFalse(isUnknownAttachmentCardRefusal(row("review_card"), ServerException(409, "plain text")))
        assertFalse(isUnknownAttachmentCardRefusal(row("review_card"),
            ServerException(422, """{"detail":"x","error_code":"html_attachment_unknown"}""")))
    }

    @Test fun theReasonTellsThePersonWhatToDo() {
        assertTrue(UNKNOWN_ATTACHMENT_CARD_DETAIL.startsWith("Not sent: the server does not know the version of the HTML page"))
        assertTrue(UNKNOWN_ATTACHMENT_CARD_DETAIL.contains("stays on this phone"))
    }
}

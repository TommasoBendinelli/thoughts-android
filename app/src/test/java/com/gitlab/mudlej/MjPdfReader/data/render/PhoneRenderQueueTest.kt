// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentAnchorRequirement
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentDeliveryRecord
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneRenderQueueTest {

    private lateinit var root: File
    private lateinit var requests: File
    private lateinit var receipts: File
    private lateinit var pdfs: File
    private lateinit var queue: PhoneRenderQueue

    @Before
    fun setUp() {
        root = Files.createTempDirectory("phone-render-queue").toFile()
        requests = File(root, "phone-to-mac/docs-render-requests")
        receipts = File(root, "mac-to-phone/docs-render-receipts")
        pdfs = File(root, "docs-clean-pdfs").apply { mkdirs() }
        File(pdfs, SourceLinkedPdfIndex.INDEX_FILENAME).writeText(
            """
            {
              "schema_version": 1,
              "pdfs": [
                {"filename": "template.pdf", "project": "docs-template"},
                {"filename": "first.pdf", "project": "docs-first"},
                {"filename": "second.pdf", "project": "docs-second"}
              ]
            }
            """.trimIndent()
        )
        queue = PhoneRenderQueue(requests, receipts, pdfs) {}
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun targetedRequestUsesCanonicalFilenameAndVersionedJson() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("pdf") }

        val result = queue.enqueuePdf(pdf)

        assertFalse(result.alreadyPending)
        val payload = JsonParser.parseString(
            File(requests, "${result.requestId}.json").readText()
        ).asJsonObject
        assertEquals(7, payload["schema_version"].asInt)
        assertEquals("pdf", payload["action"].asString)
        assertEquals("template.pdf", payload["pdf_filename"].asString)
        assertEquals(result.requestId, payload["request_id"].asString)
        assertTrue(payload["required_comments"].asJsonArray.isEmpty)
        assertTrue(payload["comment_records"].asJsonArray.isEmpty)
    }

    @Test
    fun newRequestTriggersImmediateServerSync() {
        val requestsToSync = mutableListOf<String>()
        val serverQueue = PhoneRenderQueue(
            requests,
            receipts,
            pdfs,
            requestScan = requestsToSync::add,
            directorySync = {},
        )

        val request = serverQueue.enqueueAll()

        assertEquals(
            listOf("${request.requestId}.json"),
            requestsToSync,
        )
    }

    @Test
    fun duplicatePendingTargetReturnsExistingRequest() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("pdf") }
        val first = queue.enqueuePdf(pdf)

        val second = queue.enqueuePdf(pdf)

        assertTrue(second.alreadyPending)
        assertEquals(first.requestId, second.requestId)
        assertEquals(1, requests.listFiles().orEmpty().size)
    }

    @Test
    fun duplicatePendingAllRequestReturnsExistingRequest() {
        val first = queue.enqueueAll()

        val second = queue.enqueueAll()

        assertTrue(second.alreadyPending)
        assertEquals(first.requestId, second.requestId)
        assertEquals(1, requests.listFiles().orEmpty().size)
    }

    @Test
    fun codexRepairIsExplicitAndDoesNotDedupeAgainstNormalRetry() {
        val normal = queue.enqueueAll()
        val repair = queue.enqueueCodexRepair(PhoneRenderQueue.ACTION_ALL)
        val duplicateRepair = queue.enqueueCodexRepair(PhoneRenderQueue.ACTION_ALL)

        assertFalse(repair.alreadyPending)
        assertTrue(duplicateRepair.alreadyPending)
        assertEquals(repair.requestId, duplicateRepair.requestId)
        assertTrue(normal.requestId != repair.requestId)
        val payload = JsonParser.parseString(
            File(requests, "${repair.requestId}.json").readText()
        ).asJsonObject
        assertEquals(PhoneRenderQueue.REPAIR_CODEX, payload["repair_mode"].asString)
    }

    @Test
    fun conditionalAllRequestUsesDedicatedAction() {
        val request = queue.enqueueCheckAll()
        val payload = JsonParser.parseString(
            File(requests, "${request.requestId}.json").readText()
        ).asJsonObject

        assertEquals(PhoneRenderQueue.ACTION_CHECK_ALL, payload["action"].asString)
        val snapshot = queue.loadStatusSnapshot()
        assertEquals(PhoneRenderQueue.ACTION_ALL, snapshot.primary?.target)
        assertTrue(snapshot.primary?.conditionalCheck == true)
        assertNull(snapshot.statusForPdf("first.pdf"))
        assertNull(snapshot.statusForPdf("second.pdf"))
    }

    @Test
    fun conditionalRunningReceiptOnlyMarksAffectedPdfs() {
        val request = queue.enqueueCheckAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 6,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "running",
              "exit_code": 0,
              "message": "Building first.pdf",
              "affected_pdfs": ["first.pdf"],
              "progress": {
                "phase": "compiling",
                "completed": 0,
                "total": 1,
                "unit": "pdfs",
                "detail": "Building first.pdf"
              }
            }
            """.trimIndent()
        )

        val snapshot = queue.loadStatusSnapshot()
        assertEquals(PhoneRenderStage.REBUILDING, snapshot.statusForPdf("first.pdf")?.stage)
        assertNull(snapshot.statusForPdf("second.pdf"))
    }

    @Test
    fun noChangeReceiptSettlesImmediately() {
        val request = queue.enqueueCheckAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 6,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "message": "PDFs are already current",
              "changes_detected": false
            }
            """.trimIndent()
        )

        assertTrue(queue.isRequestSettled(request.requestId))
    }

    @Test
    fun allAndTargetedRequestsAreIndependent() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("pdf") }

        val targeted = queue.enqueuePdf(pdf)
        val all = queue.enqueueAll()

        assertFalse(targeted.alreadyPending)
        assertFalse(all.alreadyPending)
        assertEquals(2, requests.listFiles().orEmpty().size)
    }

    @Test
    fun onlyDirectCanonicalPdfEntriesCanBeRequested() {
        val canonical = File(pdfs, "template.pdf")
        val nested = File(pdfs, "archive/template.pdf")
        val other = File(root, "other/template.pdf")
        val uppercase = File(pdfs, "template.PDF")
        val unmatched = File(pdfs, "reference.pdf")

        assertTrue(queue.canRequestPdf(canonical))
        assertFalse(queue.canRequestPdf(nested))
        assertFalse(queue.canRequestPdf(other))
        assertFalse(queue.canRequestPdf(uppercase))
        assertFalse(queue.canRequestPdf(unmatched))
        assertEquals(SourcePdfCapability.NOT_SOURCE_LINKED, queue.pdfCapability(unmatched))
    }

    @Test
    fun missingSourceIndexFailsClosed() {
        File(pdfs, SourceLinkedPdfIndex.INDEX_FILENAME).delete()
        val unavailableQueue = PhoneRenderQueue(requests, receipts, pdfs) {}
        val canonical = File(pdfs, "template.pdf")

        assertFalse(unavailableQueue.canRequestPdf(canonical))
        assertEquals(
            SourcePdfCapability.INDEX_UNAVAILABLE,
            unavailableQueue.pdfCapability(canonical),
        )
    }

    @Test
    fun forceAllAfterSuccessfulBuildCreatesFreshRequestWithoutSourceChanges() {
        val first = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${first.requestId}.json").writeText(
            """{"schema_version":1,"request_id":"${first.requestId}","target":"all","status":"success","exit_code":0}"""
        )

        val next = queue.enqueueAll()

        assertFalse(next.alreadyPending)
        assertFalse(first.requestId == next.requestId)
        val payload = File(requests, "${next.requestId}.json").readText()
        assertTrue(payload.contains("\"action\":\"all\""))
    }

    @Test
    fun completedReceiptIsReturnedAndRequestIsDeleted() {
        val request = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 1,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "message": "Render completed."
            }
            """.trimIndent()
        )

        val completed = queue.consumeCompletedReceipts()

        assertEquals(1, completed.size)
        assertTrue(completed.single().succeeded)
        assertFalse(File(requests, "${request.requestId}.json").exists())
        assertTrue(queue.consumeCompletedReceipts().isEmpty())
    }

    @Test
    fun malformedOrMismatchedReceiptDoesNotConsumeRequest() {
        val request = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """{"schema_version":1,"request_id":"wrong"}"""
        )

        assertTrue(queue.consumeCompletedReceipts().isEmpty())
        assertTrue(File(requests, "${request.requestId}.json").exists())
    }

    @Test
    fun receiptWithWrongPublishedPdfTypeIsIgnoredSafely() {
        val request = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 1,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "message": "Render completed.",
              "published_pdf": "not-an-object"
            }
            """.trimIndent()
        )

        val completed = queue.consumeCompletedReceipts()

        assertEquals(1, completed.size)
        assertEquals(null, completed.single().publishedPdf)
    }

    @Test
    fun receiptIgnoresHiddenTemporaryAndConflictPdfIdentities() {
        val request = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 4,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "message": "Render completed.",
              "published_pdfs": [
                {
                  "filename": "first.pdf",
                  "modified_epoch_ms": 1,
                  "size_bytes": 5,
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                },
                {
                  "filename": ".temporary.pdf",
                  "modified_epoch_ms": 1,
                  "size_bytes": 5,
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                },
                {
                  "filename": "first.sync-conflict-phone.pdf",
                  "modified_epoch_ms": 1,
                  "size_bytes": 5,
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                },
                {
                  "filename": "../outside.pdf",
                  "modified_epoch_ms": 1,
                  "size_bytes": 5,
                  "sha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
                }
              ]
            }
            """.trimIndent()
        )

        val completed = queue.consumeCompletedReceipts().single()

        assertEquals(listOf("first.pdf"), completed.publishedPdfs.map { it.filename })
    }

    @Test
    fun anchorRequirementsAreVersionedAndCoveredRequestsAreDeduplicated() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("pdf") }
        val first = queue.enqueuePdf(
            pdf,
            listOf(CommentAnchorRequirement("comment-a", 2)),
            listOf(deliveryRecord("comment-a", 2)),
        )

        val covered = queue.enqueuePdf(
            pdf,
            listOf(CommentAnchorRequirement("comment-a", 1)),
            listOf(deliveryRecord("comment-a", 1)),
        )
        val uncovered = queue.enqueuePdf(
            pdf,
            listOf(CommentAnchorRequirement("comment-b", 1)),
            listOf(deliveryRecord("comment-b", 1)),
        )

        assertTrue(covered.alreadyPending)
        assertEquals(first.requestId, covered.requestId)
        assertFalse(uncovered.alreadyPending)
        assertEquals(2, requests.listFiles().orEmpty().size)
        val firstPayload = JsonParser.parseString(
            File(requests, "${first.requestId}.json").readText()
        ).asJsonObject
        val requirement = firstPayload["required_comments"]
            .asJsonArray
            .single()
            .asJsonObject
        assertEquals("comment-a", requirement["id"].asString)
        assertEquals(2, requirement["revision"].asInt)
        val record = firstPayload["comment_records"]
            .asJsonArray
            .single()
            .asJsonObject
        assertEquals("comment-a", record["id"].asString)
        assertEquals(2, record["revision"].asInt)
    }

    @Test
    fun v2ReceiptParsesAnchorResults() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("pdf") }
        val request = queue.enqueuePdf(
            pdf,
            listOf(CommentAnchorRequirement("comment-a", 1)),
            listOf(deliveryRecord("comment-a", 1)),
        )
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 2,
              "request_id": "${request.requestId}",
              "target": "template.pdf",
              "status": "success",
              "exit_code": 0,
              "message": "Render completed.",
              "published_pdf": {
                "filename": "template.pdf",
                "modified_epoch_ms": 1,
                "size_bytes": 3,
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              },
              "anchor_results": [
                {
                  "id": "comment-a",
                  "revision": 1,
                  "status": "needs_anchor",
                  "confidence": "medium"
                }
              ]
            }
            """.trimIndent()
        )

        val receipt = queue.consumeCompletedReceipts("template.pdf").single()

        assertEquals(2, receipt.schemaVersion)
        assertEquals("comment-a", receipt.anchorResults.single().id)
        assertTrue(receipt.anchorResults.single().requiresAttention)
        assertTrue(receipt.requiresAttention)
        assertEquals(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            receipt.publishedPdf?.sha256,
        )
    }

    @Test
    fun publishedPdfMatchUsesSha256RatherThanTimestamp() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("pdf") }
        val published = PhoneRenderReceipt.PublishedPdf(
            filename = "template.pdf",
            modifiedEpochMillis = 0,
            sizeBytes = pdf.length(),
            sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        assertFalse(queue.matchesPublishedPdf(pdf, published))
        assertTrue(
            queue.matchesPublishedPdf(
                pdf,
                published.copy(
                    sha256 = "c35b21d6ca39aa7cc3b79a705d989f1a6e88b99ab43988d74048799e3db926a3",
                ),
            ),
        )
    }

    @Test
    fun filteredReceiptConsumptionLeavesOtherPdfPending() {
        val firstPdf = File(pdfs, "first.pdf").apply { writeText("pdf") }
        val secondPdf = File(pdfs, "second.pdf").apply { writeText("pdf") }
        val first = queue.enqueuePdf(firstPdf)
        val second = queue.enqueuePdf(secondPdf)
        receipts.mkdirs()
        listOf(first to "first.pdf", second to "second.pdf").forEach { (request, target) ->
            File(receipts, "${request.requestId}.json").writeText(
                """
                {
                  "schema_version": 2,
                  "request_id": "${request.requestId}",
                  "target": "$target",
                  "status": "success",
                  "exit_code": 0,
                  "message": "Render completed."
                }
                """.trimIndent()
            )
        }

        val completed = queue.consumeCompletedReceipts("first.pdf")

        assertEquals(listOf("first.pdf"), completed.map(PhoneRenderReceipt::target))
        assertFalse(File(requests, "${first.requestId}.json").exists())
        assertTrue(File(requests, "${second.requestId}.json").exists())
    }

    @Test
    fun targetedRequestWithoutReceiptReportsQueued() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("pdf") }

        queue.enqueuePdf(pdf)

        assertEquals(
            PhoneRenderStage.QUEUED,
            queue.loadStatusSnapshot().statusForPdf("template.pdf")?.stage,
        )
    }

    @Test
    fun pendingSourceEditOutranksOtherUnsyncedDocumentChanges() {
        val snapshot = PhoneRenderStatusSnapshot(
            listOf(
                PhoneRenderJobStatus(
                    requestId = "local-sync:template.pdf",
                    target = "template.pdf",
                    stage = PhoneRenderStage.SYNC_NEEDED,
                ),
                PhoneRenderJobStatus(
                    requestId = "local-source:template.pdf",
                    target = "template.pdf",
                    stage = PhoneRenderStage.SOURCE_SYNC_NEEDED,
                ),
                PhoneRenderJobStatus(
                    requestId = "remote-status:template.pdf",
                    target = "template.pdf",
                    stage = PhoneRenderStage.OUT_OF_SYNC,
                ),
            )
        )

        assertEquals(
            PhoneRenderStage.SOURCE_SYNC_NEEDED,
            snapshot.statusForPdf("template.pdf")?.stage,
        )
    }

    @Test
    fun queuedRequestReportsPersistedSyncFailureInsteadOfWaitingForever() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("pdf") }
        queue.enqueuePdf(pdf)
        val attempt = System.currentTimeMillis() + 1_000
        val blocked = queue.loadStatusSnapshot(
            syncHealth = PhoneRenderSyncHealth(
                lastAttemptEpochMillis = attempt,
                lastSuccessEpochMillis = 0,
                errorCode = "http_401",
                errorMessage = "Fedora rejected the saved credential.",
            ),
        ).statusForPdf("template.pdf")

        assertEquals(PhoneRenderStage.BLOCKED, blocked?.stage)
        assertEquals("Fedora rejected the saved credential.", blocked?.message)

        val retrying = queue.loadStatusSnapshot(
            syncHealth = PhoneRenderSyncHealth(
                lastAttemptEpochMillis = attempt,
                lastSuccessEpochMillis = attempt,
            ),
        ).statusForPdf("template.pdf")
        assertEquals(PhoneRenderStage.QUEUED, retrying?.stage)
    }

    @Test
    fun runningReceiptKeepsRequestPendingAndReportsRebuilding() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("pdf") }
        val request = queue.enqueuePdf(pdf)
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 5,
              "request_id": "${request.requestId}",
              "target": "template.pdf",
              "status": "running",
              "exit_code": -1,
              "started_at": "2026-07-30T08:00:00Z",
              "message": "Compiling template.pdf.",
              "progress": {
                "phase": "compiling",
                "completed": 2,
                "total": 5,
                "unit": "files",
                "detail": "Compiling template.pdf",
                "updated_at": "2026-07-30T08:00:05Z"
              }
            }
            """.trimIndent()
        )

        assertTrue(queue.hasPendingPdf(pdf))
        val status = queue.loadStatusSnapshot().statusForPdf("template.pdf")
        assertEquals(PhoneRenderStage.REBUILDING, status?.stage)
        assertEquals("compiling", status?.progress?.phase)
        assertEquals(2, status?.progress?.completed)
        assertEquals(5, status?.progress?.total)
        assertEquals("Compiling template.pdf", status?.progress?.detail)
        assertTrue(queue.consumeCompletedReceipts().isEmpty())
    }

    @Test
    fun acceptedFedoraQueueIsActiveAndDoesNotLookLikeANewBuildRequest() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("pdf") }
        val request = queue.enqueuePdf(pdf)
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 5,
              "request_id": "${request.requestId}",
              "target": "template.pdf",
              "status": "running",
              "exit_code": -1,
              "message": "Queued on Fedora.",
              "progress": {
                "phase": "queued",
                "completed": 0,
                "total": 1,
                "unit": "steps",
                "detail": "Waiting for a Fedora build worker",
                "updated_at": "2026-08-18T13:14:30Z"
              }
            }
            """.trimIndent()
        )

        val status = queue.loadStatusSnapshot().statusForPdf("template.pdf")

        assertEquals(PhoneRenderStage.FEDORA_QUEUED, status?.stage)
        assertTrue(status?.isActive == true)
    }

    @Test
    fun allRequestAndCheckingReceiptReportChecking() {
        val request = queue.enqueueAll()
        assertEquals(PhoneRenderStage.QUEUED, queue.loadStatusSnapshot().primary?.stage)
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 5,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "running",
              "exit_code": -1,
              "message": "Checking for PDF updates on Mac.",
              "progress": {
                "phase": "checking",
                "completed": 0,
                "total": 1,
                "unit": "steps",
                "detail": "Checking changed inputs",
                "updated_at": "2026-08-02T10:00:00Z"
              }
            }
            """.trimIndent()
        )

        assertEquals(PhoneRenderStage.CHECKING, queue.loadStatusSnapshot().primary?.stage)
    }

    @Test
    fun unchangedAllReceiptIsNotExposedAsPersistentStatus() {
        val request = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 5,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "message": "Already up to date.",
              "changes_detected": false,
              "completed_at": "2026-08-02T10:00:00Z"
            }
            """.trimIndent()
        )

        val receipt = queue.receiptForRequest(request.requestId)

        assertNull(queue.loadStatusSnapshot().primary)
        assertNull(queue.loadStatusSnapshot().statusForPdf("template.pdf"))
        assertEquals(false, receipt?.changesDetected)
        assertEquals("Already up to date.", receipt?.message)
        assertEquals(false, queue.consumeCompletedReceipts().single().changesDetected)
        assertTrue(queue.consumeCompletedReceipts().isEmpty())
    }

    @Test
    fun unchangedAllReceiptClearsOlderDocumentFailureStatus() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("current") }
        val failed = queue.enqueuePdf(pdf)
        receipts.mkdirs()
        File(receipts, "${failed.requestId}.json").writeText(
            """
            {
              "schema_version": 6,
              "request_id": "${failed.requestId}",
              "target": "template.pdf",
              "status": "failure",
              "exit_code": 1,
              "message": "Old failure",
              "completed_at": "2026-08-05T10:00:00Z"
            }
            """.trimIndent()
        )
        val all = queue.enqueueAll()
        File(receipts, "${all.requestId}.json").writeText(
            """
            {
              "schema_version": 6,
              "request_id": "${all.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "message": "Already up to date.",
              "changes_detected": false,
              "completed_at": "2026-08-05T11:00:00Z"
            }
            """.trimIndent()
        )

        val snapshot = queue.loadStatusSnapshot()

        assertNull(snapshot.primary)
        assertNull(snapshot.statusForPdf("template.pdf"))
    }

    @Test
    fun successfulReceiptWaitsForExactPhonePdfThenReportsUpdated() {
        val pdf = File(pdfs, "template.pdf").apply {
            writeText("old")
            setLastModified(1)
        }
        val request = queue.enqueuePdf(pdf)
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 3,
              "request_id": "${request.requestId}",
              "target": "template.pdf",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T08:01:00Z",
              "message": "Render completed.",
              "published_pdf": {
                "filename": "template.pdf",
                "modified_epoch_ms": 1,
                "size_bytes": 3,
                "sha256": "11507a0e2f5e69d5dfa40a62a1bd7b6ee57e6bcd85c67c9b8431b36fff21c437"
              }
            }
            """.trimIndent()
        )

        assertEquals(
            PhoneRenderStage.WAITING_FOR_SYNC,
            queue.loadStatusSnapshot().statusForPdf("template.pdf")?.stage,
        )

        pdf.writeText("new")
        pdf.setLastModified(pdf.lastModified() + 2_000)
        assertEquals(
            PhoneRenderStage.UPDATED,
            queue.loadStatusSnapshot().statusForPdf("template.pdf")?.stage,
        )
    }

    @Test
    fun newerLocalPdfSupersedesOlderHealthyReceipt() {
        val pdf = File(pdfs, "template.pdf").apply {
            writeText("new")
            setLastModified(10_000)
        }
        val request = queue.enqueuePdf(pdf)
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 3,
              "request_id": "${request.requestId}",
              "target": "template.pdf",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T08:01:00Z",
              "message": "Render completed.",
              "published_pdf": {
                "filename": "template.pdf",
                "modified_epoch_ms": 1000,
                "size_bytes": 3,
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              }
            }
            """.trimIndent()
        )

        assertNull(queue.loadStatusSnapshot().statusForPdf("template.pdf"))
    }

    @Test
    fun supersededReceiptStillReportsUnanchoredCommentAttention() {
        val pdf = File(pdfs, "template.pdf").apply {
            writeText("new")
            setLastModified(10_000)
        }
        val request = queue.enqueuePdf(pdf)
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 4,
              "request_id": "${request.requestId}",
              "target": "template.pdf",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T08:01:00Z",
              "message": "Render completed; 1 comment could not be anchored.",
              "attention_required": true,
              "published_pdf": {
                "filename": "template.pdf",
                "modified_epoch_ms": 1000,
                "size_bytes": 3,
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              },
              "anchor_results": [
                {
                  "id": "comment-a",
                  "revision": 1,
                  "status": "needs_anchor",
                  "confidence": "medium"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            PhoneRenderStage.ATTENTION,
            queue.loadStatusSnapshot().statusForPdf("template.pdf")?.stage,
        )
    }

    @Test
    fun suppressedNewerSuccessDoesNotResurrectOlderAttentionReceipt() {
        val pdf = File(pdfs, "template.pdf").apply {
            writeText("new")
            setLastModified(10_000)
        }
        receipts.mkdirs()
        val older = queue.enqueuePdf(pdf)
        File(receipts, "${older.requestId}.json").writeText(
            """
            {
              "schema_version": 4,
              "request_id": "${older.requestId}",
              "target": "template.pdf",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T08:00:00Z",
              "message": "Comment needs attention.",
              "attention_required": true,
              "published_pdf": {
                "filename": "template.pdf",
                "modified_epoch_ms": 1000,
                "size_bytes": 3,
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              }
            }
            """.trimIndent()
        )
        val newer = queue.enqueuePdf(pdf)
        File(receipts, "${newer.requestId}.json").writeText(
            """
            {
              "schema_version": 4,
              "request_id": "${newer.requestId}",
              "target": "template.pdf",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T09:00:00Z",
              "message": "Render completed.",
              "published_pdf": {
                "filename": "template.pdf",
                "modified_epoch_ms": 2000,
                "size_bytes": 3,
                "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
              }
            }
            """.trimIndent()
        )

        assertNull(queue.loadStatusSnapshot().statusForPdf("template.pdf"))
    }

    @Test
    fun synchronizedReceiptWithUnanchoredCommentReportsAttention() {
        val pdf = File(pdfs, "template.pdf").apply { writeText("new") }
        val request = queue.enqueuePdf(pdf)
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 4,
              "request_id": "${request.requestId}",
              "target": "template.pdf",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T08:01:00Z",
              "message": "Render completed; 1 comment could not be anchored.",
              "attention_required": true,
              "published_pdf": {
                "filename": "template.pdf",
                "modified_epoch_ms": 1,
                "size_bytes": 3,
                "sha256": "11507a0e2f5e69d5dfa40a62a1bd7b6ee57e6bcd85c67c9b8431b36fff21c437"
              },
              "anchor_results": [
                {
                  "id": "comment-a",
                  "revision": 1,
                  "status": "needs_anchor",
                  "confidence": "medium"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            PhoneRenderStage.ATTENTION,
            queue.loadStatusSnapshot().statusForPdf("template.pdf")?.stage,
        )
    }

    @Test
    fun rebuildAllReportsPartialPhoneSyncProgress() {
        File(pdfs, "first.pdf").apply {
            writeText("first")
            setLastModified(1)
        }
        File(pdfs, "second.pdf").apply {
            writeText("old")
            setLastModified(1)
        }
        val request = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 3,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T08:02:00Z",
              "message": "Render completed.",
              "published_pdfs": [
                {
                  "filename": "first.pdf",
                  "modified_epoch_ms": 1,
                  "size_bytes": 5,
                  "sha256": "a7937b64b8caa58f03721bb6bacf5c78cb235febe0e70b1b84cd99541461a08e"
                },
                {
                  "filename": "second.pdf",
                  "modified_epoch_ms": 1,
                  "size_bytes": 6,
                  "sha256": "16367aacb67a4a017c8da8ab95682ccb7b55d7807506c196891766d700e1b721"
                }
              ]
            }
            """.trimIndent()
        )

        val status = queue.loadStatusSnapshot().primary

        assertEquals(PhoneRenderStage.WAITING_FOR_SYNC, status?.stage)
        assertEquals(1, status?.syncedPdfCount)
        assertEquals(2, status?.publishedPdfCount)
    }

    @Test
    fun authoritativeAllInventoryReplacesStaleTargetedAttention() {
        val pdf = File(pdfs, "template.pdf").apply {
            writeText("new")
            setLastModified(1)
        }
        receipts.mkdirs()
        val older = queue.enqueuePdf(pdf)
        File(receipts, "${older.requestId}.json").writeText(
            """
            {
              "schema_version": 5,
              "request_id": "${older.requestId}",
              "target": "template.pdf",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T08:00:00Z",
              "message": "Old warning.",
              "attention_required": true,
              "published_pdf": {
                "filename": "template.pdf",
                "modified_epoch_ms": 1,
                "size_bytes": 3,
                "sha256": "11507a0e2f5e69d5dfa40a62a1bd7b6ee57e6bcd85c67c9b8431b36fff21c437"
              }
            }
            """.trimIndent()
        )
        val all = queue.enqueueAll()
        val allReceipt = File(receipts, "${all.requestId}.json")
        fun writeAllReceipt(attentionPdfs: String, attentionRequired: Boolean) {
            allReceipt.writeText(
                """
                {
                  "schema_version": 5,
                  "request_id": "${all.requestId}",
                  "target": "all",
                  "status": "success",
                  "exit_code": 0,
                  "completed_at": "2026-07-30T09:00:00Z",
                  "message": "Render completed.",
                  "attention_required": $attentionRequired,
                  "attention_pdfs": $attentionPdfs,
                  "published_pdfs": [{
                    "filename": "template.pdf",
                    "modified_epoch_ms": 1,
                    "size_bytes": 3,
                    "sha256": "11507a0e2f5e69d5dfa40a62a1bd7b6ee57e6bcd85c67c9b8431b36fff21c437"
                  }]
                }
                """.trimIndent()
            )
        }

        writeAllReceipt("[]", false)
        val cleared = queue.loadStatusSnapshot()
        assertTrue(cleared.attentionJobs.isEmpty())
        assertEquals(PhoneRenderStage.UPDATED, cleared.statusForPdf("template.pdf")?.stage)

        writeAllReceipt(
            """[{"filename":"template.pdf","comment_count":13}]""",
            true,
        )
        val current = queue.loadStatusSnapshot()
        assertEquals(1, current.attentionJobs.size)
        assertEquals("13 comment(s) could not be anchored.", current.attentionJobs.single().message)
        assertEquals(PhoneRenderStage.ATTENTION, current.statusForPdf("template.pdf")?.stage)
    }

    @Test
    fun unchangedAllReceiptDoesNotResurrectStaleTargetedAttention() {
        val template = File(pdfs, "template.pdf").apply {
            writeText("new")
            setLastModified(1)
        }
        File(pdfs, "first.pdf").apply {
            writeText("first")
            setLastModified(1)
        }
        receipts.mkdirs()
        val oldTargeted = queue.enqueuePdf(template)
        File(receipts, "${oldTargeted.requestId}.json").writeText(
            """
            {
              "schema_version": 5,
              "request_id": "${oldTargeted.requestId}",
              "target": "template.pdf",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T08:00:00Z",
              "message": "Old warning.",
              "attention_required": true,
              "published_pdf": {
                "filename": "template.pdf",
                "modified_epoch_ms": 1,
                "size_bytes": 3,
                "sha256": "11507a0e2f5e69d5dfa40a62a1bd7b6ee57e6bcd85c67c9b8431b36fff21c437"
              }
            }
            """.trimIndent()
        )
        val authoritativeAll = queue.enqueueAll()
        File(receipts, "${authoritativeAll.requestId}.json").writeText(
            """
            {
              "schema_version": 5,
              "request_id": "${authoritativeAll.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T09:00:00Z",
              "message": "Render completed; 1 comment could not be anchored.",
              "attention_required": true,
              "attention_pdfs": [{"filename":"first.pdf","comment_count":1}],
              "published_pdfs": [
                {
                  "filename": "template.pdf",
                  "modified_epoch_ms": 1,
                  "size_bytes": 3,
                  "sha256": "11507a0e2f5e69d5dfa40a62a1bd7b6ee57e6bcd85c67c9b8431b36fff21c437"
                },
                {
                  "filename": "first.pdf",
                  "modified_epoch_ms": 1,
                  "size_bytes": 5,
                  "sha256": "a7937b64b8caa58f03721bb6bacf5c78cb235febe0e70b1b84cd99541461a08e"
                }
              ]
            }
            """.trimIndent()
        )
        val unchangedAll = queue.enqueueAll()
        File(receipts, "${unchangedAll.requestId}.json").writeText(
            """
            {
              "schema_version": 5,
              "request_id": "${unchangedAll.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T10:00:00Z",
              "message": "Already up to date.",
              "changes_detected": false
            }
            """.trimIndent()
        )

        val afterUnchangedAll = queue.loadStatusSnapshot()

        assertNull(afterUnchangedAll.statusForPdf("template.pdf"))
        assertEquals(
            PhoneRenderStage.ATTENTION,
            afterUnchangedAll.statusForPdf("first.pdf")?.stage,
        )
        assertEquals(
            "1 comment(s) could not be anchored.",
            afterUnchangedAll.statusForPdf("first.pdf")?.message,
        )

        val newerTargeted = queue.enqueuePdf(template)
        File(receipts, "${newerTargeted.requestId}.json").writeText(
            """
            {
              "schema_version": 5,
              "request_id": "${newerTargeted.requestId}",
              "target": "template.pdf",
              "status": "success",
              "exit_code": 0,
              "completed_at": "2026-07-30T11:00:00Z",
              "message": "New warning.",
              "attention_required": true,
              "published_pdf": {
                "filename": "template.pdf",
                "modified_epoch_ms": 1,
                "size_bytes": 3,
                "sha256": "11507a0e2f5e69d5dfa40a62a1bd7b6ee57e6bcd85c67c9b8431b36fff21c437"
              }
            }
            """.trimIndent()
        )

        assertEquals(
            "New warning.",
            queue.loadStatusSnapshot().statusForPdf("template.pdf")?.message,
        )
    }

    @Test
    fun legacyAllReceiptWithoutPublishedIdentitiesIsUnverified() {
        val request = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 2,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "message": "Render completed."
            }
            """.trimIndent()
        )

        assertEquals(
            PhoneRenderStage.UNVERIFIED,
            queue.loadStatusSnapshot().primary?.stage,
        )
    }

    @Test
    fun consumedLegacyAllReceiptWithoutIdentitiesDisappears() {
        val request = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 2,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "message": "Render completed."
            }
            """.trimIndent()
        )

        queue.consumeCompletedReceipts()

        assertTrue(queue.loadStatusSnapshot().jobs.isEmpty())
    }

    @Test
    fun legacyUnverifiedAllReceiptNeverDecoratesIndividualPdfRows() {
        val request = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 2,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "success",
              "exit_code": 0,
              "message": "Render completed."
            }
            """.trimIndent()
        )

        val snapshot = queue.loadStatusSnapshot()

        assertEquals(PhoneRenderStage.UNVERIFIED, snapshot.primary?.stage)
        assertNull(snapshot.statusForPdf("template.pdf"))
    }

    @Test
    fun activeAllRequestStillDecoratesIndividualPdfRows() {
        queue.enqueueAll()

        assertEquals(
            PhoneRenderStage.QUEUED,
            queue.loadStatusSnapshot().statusForPdf("template.pdf")?.stage,
        )
    }

    @Test
    fun partialAllReceiptDistinguishesUpdatedAndFailedDocuments() {
        File(pdfs, "first.pdf").apply {
            writeText("first")
            setLastModified(1)
        }
        File(pdfs, "second.pdf").apply {
            writeText("last known good")
            setLastModified(1)
        }
        val request = queue.enqueueAll()
        receipts.mkdirs()
        File(receipts, "${request.requestId}.json").writeText(
            """
            {
              "schema_version": 6,
              "request_id": "${request.requestId}",
              "target": "all",
              "status": "partial_success",
              "exit_code": 2,
              "completed_at": "2026-08-05T16:14:00Z",
              "message": "Updated with errors; 1 source file failed.",
              "attention_required": true,
              "log_reference": ".runtime/target/phone-render/${request.requestId}.log",
              "published_pdfs": [
                {
                  "filename": "first.pdf",
                  "modified_epoch_ms": 1,
                  "size_bytes": 5,
                  "sha256": "a7937b64b8caa58f03721bb6bacf5c78cb235febe0e70b1b84cd99541461a08e"
                }
              ],
              "failed_documents": [
                {
                  "pdf_filename": "second.pdf",
                  "source": "docs-second/tex/source/main.tex",
                  "message": "Missing ${'$'} inserted."
                }
              ]
            }
            """.trimIndent()
        )

        val snapshot = queue.loadStatusSnapshot()
        val allStatus = snapshot.jobs.single { it.affectsAll }
        val updatedStatus = snapshot.statusForPdf("first.pdf")
        val failedStatus = snapshot.statusForPdf("second.pdf")

        assertEquals(PhoneRenderStage.PARTIAL, allStatus.stage)
        assertEquals("Updated with errors; 1 source file failed.", allStatus.message)
        assertEquals(PhoneRenderStage.UPDATED, updatedStatus?.stage)
        assertEquals("", updatedStatus?.message)
        assertEquals(emptyList<PhoneRenderFailure>(), updatedStatus?.failedDocuments)
        assertEquals("", updatedStatus?.logReference)
        assertEquals(PhoneRenderStage.PARTIAL, failedStatus?.stage)
        assertEquals("Missing ${'$'} inserted.", failedStatus?.message)
        assertEquals(
            "docs-second/tex/source/main.tex",
            failedStatus?.failedDocuments?.single()?.source,
        )
        assertEquals(
            ".runtime/target/phone-render/${request.requestId}.log",
            failedStatus?.logReference,
        )
        assertEquals(
            PhoneRenderReceipt.STATUS_PARTIAL_SUCCESS,
            queue.consumeCompletedReceipts().single().status,
        )
    }

    @Test
    fun allPdfStatusIsProjectedToOnlyTheSelectedPdf() {
        val progress = PhoneRenderProgress(
            phase = "publishing",
            completed = 1,
            total = 2,
            unit = "pdfs",
            detail = "Publishing second.pdf",
            updatedAtEpochMillis = 25,
        )
        val snapshot = PhoneRenderStatusSnapshot(
            listOf(
                PhoneRenderJobStatus(
                    requestId = "all",
                    target = PhoneRenderQueue.ACTION_ALL,
                    stage = PhoneRenderStage.WAITING_FOR_SYNC,
                    message = "Updated with errors; 1 source file failed.",
                    createdAtEpochMillis = 10,
                    startedAtEpochMillis = 20,
                    completedAtEpochMillis = 30,
                    publishedPdfCount = 2,
                    syncedPdfCount = 1,
                    progress = progress,
                    publishedPdfFilenames = setOf("first.pdf", "second.pdf"),
                    pendingPdfFilenames = setOf("second.pdf"),
                    failedDocuments = listOf(
                        PhoneRenderFailure(
                            pdfFilename = "failed.pdf",
                            source = "docs-failed/tex/source/main.tex",
                            message = "Undefined control sequence.",
                        )
                    ),
                    logReference = ".runtime/target/phone-render/all.log",
                )
            )
        )

        val synced = snapshot.statusForPdf("first.pdf")
        assertEquals("first.pdf", synced?.target)
        assertEquals(PhoneRenderStage.UPDATED, synced?.stage)
        assertEquals("", synced?.message)
        assertEquals(0L, synced?.createdAtEpochMillis)
        assertEquals(0L, synced?.startedAtEpochMillis)
        assertEquals(30L, synced?.completedAtEpochMillis)
        assertEquals(1, synced?.publishedPdfCount)
        assertEquals(1, synced?.syncedPdfCount)
        assertEquals(null, synced?.progress)
        assertEquals(emptyList<PhoneRenderFailure>(), synced?.failedDocuments)
        assertEquals("", synced?.logReference)

        val pending = snapshot.statusForPdf("second.pdf")
        assertEquals("second.pdf", pending?.target)
        assertEquals(PhoneRenderStage.WAITING_FOR_SYNC, pending?.stage)
        assertEquals(1, pending?.publishedPdfCount)
        assertEquals(0, pending?.syncedPdfCount)

        val failed = snapshot.statusForPdf("failed.pdf")
        assertEquals("failed.pdf", failed?.target)
        assertEquals(PhoneRenderStage.PARTIAL, failed?.stage)
        assertEquals("Undefined control sequence.", failed?.message)
        assertEquals(
            "docs-failed/tex/source/main.tex",
            failed?.failedDocuments?.single()?.source,
        )
        assertEquals(".runtime/target/phone-render/all.log", failed?.logReference)
    }

    @Test
    fun unattributedAllPdfFailureDoesNotMarkEveryPdfFailed() {
        val snapshot = PhoneRenderStatusSnapshot(
            listOf(
                PhoneRenderJobStatus(
                    requestId = "older",
                    target = "first.pdf",
                    stage = PhoneRenderStage.UPDATED,
                    completedAtEpochMillis = 10,
                ),
                PhoneRenderJobStatus(
                    requestId = "all",
                    target = PhoneRenderQueue.ACTION_ALL,
                    stage = PhoneRenderStage.FAILED,
                    message = "Renderer could not start.",
                    completedAtEpochMillis = 20,
                )
            )
        )

        assertEquals(null, snapshot.statusForPdf("first.pdf"))
        assertEquals(PhoneRenderStage.FAILED, snapshot.primary?.stage)
        assertEquals("Renderer could not start.", snapshot.primary?.message)
    }

    @Test
    fun latestApplicableTargetedJobOverridesOlderAllJobForBadge() {
        val snapshot = PhoneRenderStatusSnapshot(
            listOf(
                PhoneRenderJobStatus(
                    requestId = "all",
                    target = "all",
                    stage = PhoneRenderStage.UPDATED,
                    completedAtEpochMillis = 10,
                ),
                PhoneRenderJobStatus(
                    requestId = "target",
                    target = "template.pdf",
                    stage = PhoneRenderStage.QUEUED,
                    createdAtEpochMillis = 20,
                ),
            )
        )

        assertEquals(
            PhoneRenderStage.QUEUED,
            snapshot.statusForPdf("template.pdf")?.stage,
        )
        assertEquals(
            PhoneRenderStage.UPDATED,
            snapshot.statusForPdf("other.pdf")?.stage,
        )
    }

    private fun deliveryRecord(id: String, revision: Int): CommentDeliveryRecord {
        return CommentDeliveryRecord(
            id,
            revision,
            """{"id":"$id","revision":$revision,"page":1}""",
        )
    }
}

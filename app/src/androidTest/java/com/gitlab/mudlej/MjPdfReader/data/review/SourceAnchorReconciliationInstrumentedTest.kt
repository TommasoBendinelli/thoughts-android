// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.SourceAnnotationMarkup
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class SourceAnchorReconciliationInstrumentedTest {
    @Test fun arrivingServerRevisionReconcilesAlreadyScannedSourceWithoutRestoringOldPlacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val id = UUID.randomUUID().toString()
        val path = "docs-test/tex/source/${TestRunIdentity.id}-$id.tex"
        val text = "An unrelated paragraph.\n\\ReviewCardMaskStart{$id}{0}%\nAnswer.\n\\ReviewCardMaskEnd{$id}{0}%\n"
        val sources = OfflineSourceBundleStore(context)
        assertTrue(sources.writeWorkingContents(path, text))
        val scanned = SourceAnnotationMarkup.scan(text, false).apply { addProperty("project", "docs-test") }
        sources.annotationIndex.install(JsonObject().apply {
            addProperty("schema_version", 1)
            add("projects", JsonObject().apply { addProperty("docs-test", true) })
            add("files", JsonObject().apply { add(path, scanned) })
        })
        val segment = sources.annotationIndex.reviewEvidence(id, "docs-test").single().deepCopy().apply {
            addProperty("source_file", path)
            addProperty("source_sha256", "c".repeat(64))
        }
        val card = JsonParser.parseString("""{"schema_version":3,"card_id":"$id","status":"approved","revision":97,"origin_id":"fixture","created_at":"2026-09-23T10:00:00Z","updated_at":"2026-09-23T10:00:00Z","primary":{"pdf_stem":"fixture","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"Keep this question?","source_anchor":{"version":2,"kind":"source_markers","segments":[$segment]}},"reanchor":{"state":"needs_attention","target_pdf_sha256":"${"d".repeat(64)}"}}""").asJsonObject
        val store = ReviewStore(context)
        store.cards()
        val recordPath = java.io.File(ReviewStore.defaultIncomingRoot(), "cards/server/$id.json").path
        fun install(revision: Int) {
            card.addProperty("revision", revision)
            assertTrue(store.installServerRecords(listOf(Triple(recordPath, "cards", card.deepCopy()))))
        }
        install(97)
        fun assertReconciled(revision: Int) {
            val current = requireNotNull(store.card(id))
            assertEquals(revision, current.revision)
            assertEquals("Keep this question?", current.question)
            assertEquals("a".repeat(64), current.pdfSha256)
            assertEquals("needs_attention", current.reanchorState)
            assertEquals("c".repeat(64),
                current.sourceAnchor!!.getAsJsonArray("segments")[0].asJsonObject.get("source_sha256").asString)
            assertEquals(text, sources.sourceWorkingFile(path)!!.readText())
        }
        assertReconciled(97)
        install(99) // A concurrent server revision supersedes the first local repair.
        assertReconciled(99)
        store.reconcileSourceAnchors()
        assertReconciled(99)
        assertTrue(store.pendingPayloads("cards").orEmpty().none { it.get("card_id").asString == id })
    }

    @Test fun failedCardLinksWhenSourceMarkersAppear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val id = UUID.randomUUID().toString()
        val path = "docs-test/tex/source/${TestRunIdentity.id}-$id.tex"
        val before = "A complete answer.\n"
        val marked = "\\ReviewCardMaskStart{$id}{0}%\n$before\\ReviewCardMaskEnd{$id}{0}%\n"
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val sourceBytes = before.toByteArray()
        val sourceHash = hash(sourceBytes)
        val lookup = "isolated review lookup".toByteArray()
        val lookupHash = hash(lookup)
        val manifest = JsonObject().apply {
            addProperty("schema_version", 3)
            add("sources", JsonArray().apply { add(JsonObject().apply {
                addProperty("path", path); addProperty("sha256", sourceHash)
                addProperty("object", sourceHash); addProperty("bytes", sourceBytes.size)
            }) })
            add("pdfs", JsonArray().apply { add(JsonObject().apply {
                addProperty("filename", "fixture.pdf"); addProperty("pdf_sha256", "a".repeat(64))
                addProperty("page_count", 1)
                add("segments", JsonArray().apply { add(JsonObject().apply {
                    addProperty("start_page", 1); addProperty("page_count", 1)
                    addProperty("source_available", true); addProperty("primary_source_file", path)
                    addProperty("lookup_object", lookupHash); addProperty("lookup_sha256", lookupHash)
                    add("inputs", JsonArray().apply { add(JsonObject().apply {
                        addProperty("source_file", path); addProperty("source_sha256", sourceHash)
                        addProperty("synctex_path", "/workspace/$path")
                    }) })
                }) })
            }) })
        }.toString().toByteArray()
        val bundleId = hash(manifest)
        val indexBytes = JsonObject().apply {
            addProperty("schema_version", 3); addProperty("bundle_id", bundleId)
            addProperty("manifest_object", bundleId)
        }.toString().toByteArray()
        val sources = OfflineSourceBundleStore(context)
        assertNotNull(sources.installDownloaded(indexBytes, mapOf(sourceHash to sourceBytes,
            lookupHash to lookup, bundleId to manifest)))
        assertTrue(sources.writeWorkingContents(path, marked))
        val scanned = SourceAnnotationMarkup.scan(marked, false).apply { addProperty("project", "docs-test") }
        assertTrue(scanned.get("valid").asBoolean)
        sources.annotationIndex.install(JsonObject().apply {
            addProperty("schema_version", 1)
            add("projects", JsonObject().apply { addProperty("docs-test", true) })
            add("files", JsonObject().apply { add(path, scanned) })
        })

        val card = JsonParser.parseString("""{"schema_version":3,"card_id":"$id","status":"approved","revision":1,"origin_id":"fixture","created_at":"2026-09-23T10:00:00Z","updated_at":"2026-09-23T10:00:00Z","primary":{"pdf_stem":"fixture","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"Fixture?"},"source_link":{"state":"linking_failed","source_file":"$path"}}""").asJsonObject
        val store = ReviewStore(context)
        store.cards()
        val database = ReviewDatabase(context)
        database.transaction { database.put("fixture/server/$id.json", "cards", 1, card) }
        store.reconcileSourceAnchors()

        val linked = requireNotNull(store.card(id))
        assertEquals("linking_failed", linked.sourceLinkState)
        assertEquals(marked, sources.sourceWorkingFile(path)!!.readText())
        assertTrue(store.pendingPayloads("cards").orEmpty().none { it.get("card_id").asString == id })

    }

    @Test fun validLocalMarkerReplacesLegacyRangesWithoutPdfBuild() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        val id = UUID.randomUUID().toString()
        val path = "docs-test/tex/source/${TestRunIdentity.id}-$id.tex"
        val text = "\\ReviewCardMaskStart{$id}{1}%\nAnswer.\n\\ReviewCardMaskEnd{$id}{1}%\n"
        val sources = OfflineSourceBundleStore(context)
        assertTrue(sources.writeWorkingContents(path, text))
        val scanned = SourceAnnotationMarkup.scan(text, false).apply { addProperty("project", "docs-test") }
        assertTrue(scanned.get("valid").asBoolean)
        val card = JsonParser.parseString("""{"schema_version":3,"card_id":"$id","status":"approved","revision":1,"origin_id":"fixture","created_at":"2026-09-23T10:00:00Z","updated_at":"2026-09-23T10:00:00Z","primary":{"pdf_stem":"fixture","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"Fixture?","source_anchor":{"version":2,"kind":"source_markers","segments":[${(0..3).joinToString(",") { "{\"segment_id\":\"$it\",\"source_file\":\"$path\",\"content_sha256\":\"${"f".repeat(64)}\"}" }}]}}}""").asJsonObject
        val store = ReviewStore(context)
        store.cards() // Initialize the durable review database before seeding the fixture.
        val database = ReviewDatabase(context)
        database.transaction {
            database.put("fixture/server/$id.json", "cards", 1, card)
        }
        sources.annotationIndex.install(JsonObject().apply {
            addProperty("schema_version", 1)
            add("projects", JsonObject().apply { addProperty("docs-test", true) })
            add("files", JsonObject().apply { add(path, scanned) })
        })
        assertEquals("linked", sources.annotationIndex.locate("review_card", id, "docs-test").get("state").asString)
        assertEquals(1, sources.annotationIndex.reviewEvidence(id, "docs-test").size)
        assertTrue(sources.sourceWorkingFile(path)?.isFile == true)
        store.reconcileSourceAnchors()
        val current = requireNotNull(store.card(id))
        val segments = requireNotNull(current.sourceAnchor).getAsJsonArray("segments")
        assertEquals(4, segments.size())
        assertEquals("needs_attention", current.reanchorState)
        assertEquals("a".repeat(64), current.pdfSha256)
        assertTrue(store.pendingPayloads("cards").orEmpty().none { it.get("card_id").asString == id })

    }
}

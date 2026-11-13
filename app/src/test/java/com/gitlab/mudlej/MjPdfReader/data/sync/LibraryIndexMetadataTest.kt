// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryIndexMetadataTest {
    @Test
    fun updatesOnlyTheNamedPublicationMetadata() {
        val index = JsonParser.parseString(
            """{"schema_version":1,"pdfs":[{"filename":"paper.pdf","pdf_sha256":"$SHA256","kind":"unlinked"},{"filename":"other.pdf","pdf_sha256":"$OTHER_SHA256","kind":"unlinked"}]}"""
        ).asJsonObject

        updateLibraryIndexEntryMetadata(index, "paper.pdf", SHA256) { entry ->
            entry.addProperty("display_name", DISPLAY_NAME)
        }

        val entries = index.getAsJsonArray("pdfs")
        assertEquals(DISPLAY_NAME, entries[0].asJsonObject.get("display_name").asString)
        assertNull(entries[1].asJsonObject.get("display_name"))
        assertEquals(SHA256, entries[0].asJsonObject.get("pdf_sha256").asString)
    }

    @Test
    fun rejectsAStalePublicationIdentity() {
        val index = JsonParser.parseString(
            """{"schema_version":1,"pdfs":[{"filename":"paper.pdf","pdf_sha256":"$SHA256","kind":"unlinked"}]}"""
        ).asJsonObject

        assertThrows(IllegalStateException::class.java) {
            updateLibraryIndexEntryMetadata(index, "paper.pdf", OTHER_SHA256) { entry ->
                entry.addProperty("display_name", DISPLAY_NAME)
            }
        }
        assertNull(index.getAsJsonArray("pdfs")[0].asJsonObject.get("display_name"))
    }

    private companion object {
        const val SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_SHA256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val DISPLAY_NAME =
            "ProRL: Prolonged Reinforcement Learning Expands Reasoning Boundaries in Large Language Models"
    }
}

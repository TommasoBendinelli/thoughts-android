// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewProjectIndexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readingUnchangedRecordsDoesNotRewriteTheWatchedIndex() {
        val root = temporary.newFolder()
        val file = File(root, "membership.json")
        val record = File(root, "card.json").apply {
            writeText("""{"card_id":"card","primary":{"pdf_stem":"first"}}""")
        }
        val index = ReviewProjectIndex(file)
        index.payload(record)
        index.persist()
        val original = file.readText()
        assertTrue(file.setLastModified(1234000L))
        // A fresh reader reparses payloads after process death but ownership is unchanged.
        val reopened = ReviewProjectIndex(file)
        reopened.payload(record)
        reopened.persist()
        assertEquals(1234000L, file.lastModified())
        assertEquals(original, file.readText())
        val changed = JsonParser.parseString(record.readText()).asJsonObject
        changed.getAsJsonObject("primary").addProperty("pdf_stem", "second")
        reopened.record(record, changed)
        reopened.persist()
        assertEquals("second", ReviewProjectIndex(file).projectForCard("card"))
    }
}

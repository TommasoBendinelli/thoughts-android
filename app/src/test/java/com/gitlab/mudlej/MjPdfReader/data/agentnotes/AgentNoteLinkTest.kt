// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentNoteLinkTest {
    private val primer = "Distillation_primer__example.org__0a1b2c3d4e5f_0f1e2d3c.pdf"

    private fun note(links: String) = AgentNote.fromJson(JsonParser.parseString(
        """{"note_id": "n1", "title": "Primer", "image_width": 1200, "image_height": 800, "links": $links}""",
    ).asJsonObject)

    @Test fun linksOfANoteRecordAreRead() {
        val links = note(
            """[{"rect": [0.5, 0.5, 0.1, 0.05], "href": "thoughts-pdf-link:$primer?page=52", "label": "p. 53"},
               {"rect": [0, 0, 1, 0.1], "href": "https://example.org/abs/1234.56789"}]""",
        ).links
        assertEquals(listOf(
            AgentNoteLink(0.5f, 0.5f, 0.1f, 0.05f, "thoughts-pdf-link:$primer?page=52", "p. 53"),
            AgentNoteLink(0f, 0f, 1f, 0.1f, "https://example.org/abs/1234.56789"),
        ), links)
    }

    @Test fun linksThisPhoneCannotOpenAreLeftOut() {
        val links = note(
            """[{"rect": [0.1, 0.1, 0.1, 0.1], "href": "javascript:alert(1)"},
               {"rect": [0.1, 0.1, 0.1], "href": "https://a.org"},
               {"rect": [0.1, 0.1, 0, 0.1], "href": "https://a.org"},
               {"rect": "x", "href": "https://a.org"},
               {"href": "https://a.org"},
               "https://a.org",
               {"rect": [0.1, 0.1, 0.1, 0.1], "href": "https://kept.org"}]""",
        ).links
        assertEquals(listOf("https://kept.org"), links.map { it.href })
    }

    @Test fun notesFromServersWithoutLinksHaveNone() {
        val json = JsonParser.parseString("""{"note_id": "n1", "title": "Old"}""").asJsonObject
        assertEquals(emptyList<AgentNoteLink>(), AgentNote.fromJson(json).links)
    }

    @Test fun linksSurviveTheStoreRoundTrip() {
        val original = note("""[{"rect": [0.25, 0.5, 0.125, 0.0625], "href": "https://a.org/x?y=1", "label": "A"}]""")
        assertEquals(original.links, AgentNote.fromJson(original.toJson()).links)
    }

    @Test fun aTapFindsTheSmallestLinkUnderIt() {
        val big = AgentNoteLink(0f, 0f, 1f, 0.5f, "https://big.org")
        val small = AgentNoteLink(0.4f, 0.2f, 0.1f, 0.05f, "https://small.org")
        val links = listOf(big, small)
        assertEquals(small, AgentNoteLink.at(links, 0.45f, 0.22f, 0.01f, 0.01f))
        assertEquals(big, AgentNoteLink.at(links, 0.9f, 0.4f, 0.01f, 0.01f))
    }

    @Test fun aTapJustBesideALinkStillOpensIt() {
        val left = AgentNoteLink(0.1f, 0.5f, 0.1f, 0.05f, "https://left.org")
        val right = AgentNoteLink(0.3f, 0.5f, 0.1f, 0.05f, "https://right.org")
        assertEquals(left, AgentNoteLink.at(listOf(left, right), 0.205f, 0.52f, 0.02f, 0.02f))
        assertEquals(right, AgentNoteLink.at(listOf(left, right), 0.29f, 0.52f, 0.02f, 0.02f))
        assertNull(AgentNoteLink.at(listOf(left, right), 0.25f, 0.52f, 0.02f, 0.02f))
        assertNull(AgentNoteLink.at(listOf(left, right), 0.15f, 0.7f, 0.02f, 0.02f))
    }
}

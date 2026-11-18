// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentAuthorTest {

    private fun resolve(savedName: String?, serverBaseUrl: String?, ownerName: String? = OWNER) =
        CommentAuthor.resolve(savedName, serverBaseUrl, ownerName, OWNER_HOSTS)

    @Test
    fun aSavedNameAlwaysWins() {
        assertEquals("Sam", resolve("Sam", "https://thoughts.example.org"))
        assertEquals("Sam", resolve("  Sam \n", "https://example.org"))
        assertEquals("Sam", resolve("Sam", null))
    }

    @Test
    fun theOwnersServerKeepsTheNameEarlierCommentsCarry() {
        for (address in listOf(
            "https://thoughts.example.org",
            "https://THOUGHTS.example.org/",
            "https://server.example.ts.net",
            "https://server.example.ts.net:8443",
        )) {
            assertEquals(address, OWNER, resolve(null, address))
            assertEquals(address, OWNER, resolve("   ", address))
        }
    }

    @Test
    fun anyOtherPhoneGetsANeutralName() {
        assertEquals(CommentAuthor.GENERIC_DEFAULT, resolve(null, null))
        assertEquals(CommentAuthor.GENERIC_DEFAULT, resolve(null, "https://my-server.example.net"))
        assertEquals(CommentAuthor.GENERIC_DEFAULT, resolve(null, "https://other.example.ts.net"))
        assertEquals(CommentAuthor.GENERIC_DEFAULT, resolve(null, "https://thoughts.example.org.example.net"))
        assertEquals(CommentAuthor.GENERIC_DEFAULT, resolve(null, "not a url"))
    }

    @Test
    fun aBuildThatNamesNoOwnerWritesTheNeutralNameEverywhere() {
        for (owner in listOf(null, "", "  \n ")) {
            assertEquals(CommentAuthor.GENERIC_DEFAULT, resolve(null, "https://thoughts.example.org", owner))
            assertEquals(CommentAuthor.GENERIC_DEFAULT, resolve(null, "https://server.example.ts.net", owner))
        }
        // The build's own options: its owner name applies to its own public host only when it names one.
        assertEquals(
            CommentAuthor.OWNER_DEFAULT ?: CommentAuthor.GENERIC_DEFAULT,
            CommentAuthor.resolve(null, ThoughtsServerAddress.PUBLIC_BASE_URL),
        )
    }

    @Test
    fun namesAreOneTrimmedLineOfLimitedLength() {
        assertEquals("Ada Lovelace", CommentAuthor.normalized(" Ada\n\tLovelace  "))
        assertNull(CommentAuthor.normalized(""))
        assertNull(CommentAuthor.normalized(" \n "))
        assertNull(CommentAuthor.normalized(null))
        assertEquals(CommentAuthor.MAX_LENGTH, CommentAuthor.normalized("x".repeat(500))!!.length)
    }

    @Test
    fun newNotesCarryTheGivenAuthor() {
        val payload = newNotePayload("note", "Text.", "2026-10-21T12:00:00Z", author = "Sam")
        assertEquals("Sam", payload.get("author").asString)
    }

    private companion object {
        const val OWNER = "Alex Example"
        val OWNER_HOSTS = setOf("thoughts.example.org", "server.example.ts.net")
    }
}

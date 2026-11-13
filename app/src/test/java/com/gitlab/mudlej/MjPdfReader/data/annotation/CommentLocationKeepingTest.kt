// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every store that saves an edited comment writes its place through [keepOrReplaceCommentLocation]. */
class CommentLocationKeepingTest {
    private val stored = """{"confirmed_at":"2026-07-20T10:59:00Z","label":"Library","latitude":48.8335,"longitude":2.3758,""" +
        """"google_place_id":"place-id","source":"google_maps","unknown":[1,2]}"""

    private fun record() = JsonParser.parseString("""{"id":"c-1","contents":"x","location":$stored}""").asJsonObject

    @Test
    fun anUnchangedPlaceStaysExactlyAsStored() {
        val record = record()
        val read = requireNotNull(CommentLocation.fromJson(record.getAsJsonObject("location")))

        record.keepOrReplaceCommentLocation("location", read, removeWhenAbsent = true)

        // Not rewritten: the stored key order and the unknown field are still there.
        assertEquals(stored, record.get("location").toString())
        // Writing the place again from what was read would have changed the stored bytes.
        assertFalse(read.toJson().toString() == stored)
    }

    @Test
    fun anotherPlaceReplacesTheStoredOne() {
        val record = record()
        val other = CommentLocation("Office", 46.0, 7.0, source = CommentLocation.SOURCE_MAP_PIN)

        record.keepOrReplaceCommentLocation("location", other, removeWhenAbsent = true)

        assertEquals(other, CommentLocation.fromJson(record.getAsJsonObject("location")))
        // A record without a place gets the edit's place.
        val empty = JsonParser.parseString("""{"id":"c-2"}""").asJsonObject
        empty.keepOrReplaceCommentLocation("location", other, removeWhenAbsent = false)
        assertEquals(other.toJson(), empty.get("location"))
    }

    @Test
    fun removingThePlaceFollowsTheStoresForm() {
        val sidecar = record()
        sidecar.keepOrReplaceCommentLocation("location", null, removeWhenAbsent = true)
        assertFalse(sidecar.has("location"))

        val logical = record()
        logical.keepOrReplaceCommentLocation("location", null, removeWhenAbsent = false)
        assertTrue(logical.get("location").isJsonNull)
    }
}

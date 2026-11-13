// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class JsonUtf8Test {
    @Test
    fun streamingEncodingPreservesContentAddressedJsonBytes() {
        val gson = Gson()
        val value = JsonObject().apply {
            addProperty("text", "Résumé 日本語 💬 <source>\n\\CommentAnchor{a}")
            addProperty("number", 0.18232548195132373)
        }
        assertArrayEquals(gson.toJson(value).toByteArray(Charsets.UTF_8), gson.toUtf8Bytes(value))
        assertArrayEquals((gson.toJson(value) + "\n").toByteArray(Charsets.UTF_8), gson.toUtf8Bytes(value, true))
    }
}

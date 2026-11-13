// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Read the history scope without allocating the embedded source and history JSON trees. */
internal fun proposalHistorySourceFile(payload: String): String = runCatching {
    JsonReader(StringReader(payload)).use { reader ->
        var sourceFile = ""
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "snapshot" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "source_file" && reader.peek() == JsonToken.STRING) {
                        sourceFile = reader.nextString()
                    } else reader.skipValue()
                }
                reader.endObject()
            } else reader.skipValue()
        }
        reader.endObject()
        sourceFile
    }
}.getOrDefault("")

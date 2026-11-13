// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/** Per-stream string references keep the disposable index small and restore shared strings directly. */
internal fun sourceProjectionGson(): Gson = SourceProjectionRowCodec().gson

/** Reuse reflection adapters, but never share a string dictionary between database rows. */
internal class SourceProjectionRowCodec {
    private val strings = object : TypeAdapter<String>() {
        val written = HashMap<String, Int>()
        val read = ArrayList<String>()

        override fun write(out: JsonWriter, value: String?) {
            if (value == null) { out.nullValue(); return }
            val previous = written[value]
            if (previous != null) out.value(previous)
            else {
                written[value] = written.size
                out.value(value)
            }
        }

        override fun read(input: JsonReader): String? = when (input.peek()) {
            JsonToken.NULL -> { input.nextNull(); null }
            JsonToken.STRING -> input.nextString().also { read.add(it) }
            JsonToken.NUMBER -> {
                val reference = input.nextString().toIntOrNull()
                require(reference != null && reference in read.indices) { "Invalid projection string reference" }
                read[reference]
            }
            else -> error("Invalid projection string")
        }
    }
    // Map keys are JSON names, not values; keep them outside the string-reference sequence.
    private val hashes = object : TypeAdapter<Map<String, String>>() {
        override fun write(out: JsonWriter, value: Map<String, String>?) {
            if (value == null) { out.nullValue(); return }
            out.beginObject()
            value.forEach { (key, hash) -> out.name(key); strings.write(out, hash) }
            out.endObject()
        }
        override fun read(input: JsonReader): Map<String, String> {
            val result = linkedMapOf<String, String>()
            input.beginObject()
            while (input.hasNext()) {
                val key = input.nextName()
                require(key !in result)
                result[key] = requireNotNull(strings.read(input))
            }
            input.endObject()
            return result
        }
    }
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(String::class.java, strings)
        .registerTypeHierarchyAdapter(Map::class.java, hashes)
        .create()

    fun encode(value: Any): String {
        strings.written.clear()
        return gson.toJson(value)
    }

    fun <T> decode(value: String, type: Class<T>): T {
        strings.read.clear()
        return gson.fromJson(value, type)
    }
}

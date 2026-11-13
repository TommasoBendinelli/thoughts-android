// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability
import com.google.gson.JsonObject

internal fun libraryDocumentSourceCapability(
    document: JsonObject,
    index: SourceLinkedPdfIndexSnapshot,
): SourcePdfCapability {
    fun text(key: String) = document.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    // Imported components can share the filename of a source-linked project PDF.
    if (document.get("imported")?.takeUnless { it.isJsonNull }?.asBoolean == true) {
        return SourcePdfCapability.NOT_SOURCE_LINKED
    }
    val source = text("source_file")
    if (source.isNotBlank()) {
        return if (source.endsWith(".pdf", ignoreCase = true)) SourcePdfCapability.NOT_SOURCE_LINKED
        else SourcePdfCapability.SOURCE_LINKED
    }
    val filename = text("filename")
    return when {
        index.libraryKindsByFilename[filename] == LibraryPdfKind.UNLINKED -> SourcePdfCapability.NOT_SOURCE_LINKED
        filename in index.filenames || index.libraryKindsByFilename[filename] == LibraryPdfKind.SOURCE_LINKED -> SourcePdfCapability.SOURCE_LINKED
        index.available -> SourcePdfCapability.NOT_SOURCE_LINKED
        else -> SourcePdfCapability.INDEX_UNAVAILABLE
    }
}

internal fun libraryDocumentMissingSources(document: JsonObject, capability: SourcePdfCapability): List<String> {
    if (capability != SourcePdfCapability.SOURCE_LINKED) return emptyList()
    return document.get("missing_sources")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
        it.takeIf { value -> value.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
    }?.distinct().orEmpty()
}

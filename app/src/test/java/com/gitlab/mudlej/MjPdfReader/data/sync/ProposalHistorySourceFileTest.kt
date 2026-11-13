// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProposalHistorySourceFileTest {
    @Test fun extractsOnlySnapshotScopeAndDecodesEscapedPaths() {
        val path = "docs/quote\"é\\_100%.tex"
        val payload = JsonObject().apply {
            addProperty("source_file", "wrong-root.tex")
            addProperty("contents", "Large embedded source text. ".repeat(10000))
            add("snapshot", JsonObject().apply {
                addProperty("source_file", path)
                add("nested", JsonObject().apply { addProperty("source_file", "wrong-nested.tex") })
            })
        }
        assertEquals(path, proposalHistorySourceFile(payload.toString()))
    }

    @Test fun absentOrMalformedScopesCannotMatchARealSource() {
        for (payload in listOf("{}", "{\"snapshot\":null}", "{\"snapshot\":{\"source_file\":null}}", "{bad")) {
            assertEquals("", proposalHistorySourceFile(payload))
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SourceArtifactGeometryTest {
    @Test fun authoritativeGeometryIsPreservedAndLegacyOnlyProposalsKeepTheirFallback() {
        val artifact = JsonParser.parseString("""{
          "review_correspondence_schema_version":2,
          "review_correspondences":[{"review_key":"proposal:current","locations":[{"regions":[[0.1,0.2,0.3,0.4]],"proof":{"verified":true}}]}],
          "agent_proposals":[
            {"id":"current","placement":{"regions":[{"large":"duplicate"}]},"comment":"keep","before":{"preview":"original"}},
            {"id":"legacy","placement":{"regions":[{"left":1}]}}
          ]
        }""").asJsonObject
        val authoritative = artifact.get("review_correspondences").deepCopy()
        val legacy = artifact.getAsJsonArray("agent_proposals")[1].deepCopy()
        discardRedundantProposalGeometry(artifact)
        assertEquals(authoritative, artifact.get("review_correspondences"))
        assertEquals(legacy, artifact.getAsJsonArray("agent_proposals")[1])
        val current = artifact.getAsJsonArray("agent_proposals")[0].asJsonObject
        assertFalse(current.has("placement"))
        assertEquals("keep", current.get("comment").asString)
        assertEquals("original", current.getAsJsonObject("before").get("preview").asString)
    }

    @Test fun oldArtifactsRetainTheirOnlyGeometry() {
        val artifact = JsonParser.parseString("""{"agent_proposals":[{"id":"old","placement":{"points":[1]}}]}""").asJsonObject
        val before = artifact.deepCopy()
        discardRedundantProposalGeometry(artifact)
        assertEquals(before, artifact)
    }
}

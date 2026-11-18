// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentalModelPageTest {
    @Test
    fun theMentalModelPageNeedsAnHttpsSite() {
        // A build that names no card-pilot site, or one over plain HTTP, has no Mental Model button.
        assertNull(mentalModelPageHost(""))
        assertNull(mentalModelPageHost("  "))
        assertNull(mentalModelPageHost("http://pilot.example.org:8445/"))
        assertEquals("pilot.example.org", mentalModelPageHost("https://Pilot.Example.org:8445/"))
        assertEquals("server.example.ts.net", mentalModelPageHost("https://server.example.ts.net:8445/"))
    }
}

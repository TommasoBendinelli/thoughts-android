// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCrashReportingTest {
    @Test
    fun crashReportsNeedAnEndpoint() {
        assertFalse(App.crashReportingConfigured(null))
        assertFalse(App.crashReportingConfigured(""))
        assertFalse(App.crashReportingConfigured("  "))
        assertFalse(App.crashReportingConfigured("null"))
        assertTrue(App.crashReportingConfigured("https://crash.example.org/report"))
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.testing

import androidx.test.platform.app.InstrumentationRegistry

object TestRunIdentity {
    val id: String get() = requireNotNull(InstrumentationRegistry.getArguments().getString("isolation_run_id"))
}

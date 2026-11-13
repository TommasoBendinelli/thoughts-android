// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optional maintenance yields to initial data loading, with a bound for launches without Home. */
internal object StartupReadiness {
    private val ready = CountDownLatch(1)
    fun ready() = ready.countDown()
    fun awaitMaintenanceWindow() { ready.await(10, TimeUnit.SECONDS) }
}

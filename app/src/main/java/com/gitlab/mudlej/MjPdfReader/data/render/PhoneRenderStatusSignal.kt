// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide notice that the stored PDF statuses changed ([PhoneRenderStatusHistory]), for the
 * screens that show them. The server's status arrives beside a Sync, possibly after it ended, so
 * the end of the Sync is not the moment to read it.
 */
object PhoneRenderStatusSignal {
    private val mutableGeneration = MutableStateFlow(0L)

    val generation: StateFlow<Long> = mutableGeneration.asStateFlow()

    fun notifyChanged() {
        mutableGeneration.update { current -> current + 1L }
    }
}

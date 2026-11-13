// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import kotlinx.coroutines.CancellationException

/** One project's failure does not suppress another project's already-authorized work. */
internal suspend fun <T> attemptIndependentProjects(
    projects: List<T>,
    name: (T) -> String,
    action: suspend (T) -> Unit,
): Map<String, Exception> {
    val failures = linkedMapOf<String, Exception>()
    for (project in projects) {
        try { action(project) }
        catch (error: CancellationException) { throw error }
        catch (error: ArtifactPlanSupersededException) { throw error }
        catch (error: Exception) { failures[name(project)] = error }
    }
    return failures
}

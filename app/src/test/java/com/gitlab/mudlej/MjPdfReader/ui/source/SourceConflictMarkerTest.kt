// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import androidx.lifecycle.ViewModelProvider
import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceConflictMarkerTest {

    @Test
    fun conflictEditorStateCanBeConstructedByAndroidx() {
        val state = ViewModelProvider.NewInstanceFactory()
            .create(ConflictEditorViewModel::class.java)

        assertNull(state.draft)
        assertNull(state.viewName)
    }

    @Test
    fun stableGitMarkersBlockSaving() {
        val source = """
            before
            <<<<<<< Fedora current
            canonical
            ||||||| shared base
            base
            =======
            phone
            >>>>>>> incoming draft
            after
        """.trimIndent()

        assertTrue(source.hasUnresolvedSourceConflictMarkers())
    }

    @Test
    fun resolvingEveryMarkerAllowsSaving() {
        assertFalse("before\nresolved text\nafter".hasUnresolvedSourceConflictMarkers())
    }

    @Test
    fun markerTextInsideTexProseDoesNotBlockSaving() {
        val source = "A sentence may quote <<<<<<< without creating a conflict marker."

        assertFalse(source.hasUnresolvedSourceConflictMarkers())
    }

    @Test
    fun anyIndividualStructuralMarkerStillBlocksSaving() {
        assertTrue("text\n=======\ntext".hasUnresolvedSourceConflictMarkers())
        // Line endings are those of lineSequence(): \r\n, \n and \r.
        assertTrue("text\r\n=======\r\ntext".hasUnresolvedSourceConflictMarkers())
        assertTrue("text\r>>>>>>> phone".hasUnresolvedSourceConflictMarkers())
        assertTrue("=======".hasUnresolvedSourceConflictMarkers())
        assertFalse("text\n========\n ======= \n".hasUnresolvedSourceConflictMarkers())
        assertFalse("".hasUnresolvedSourceConflictMarkers())
    }

    @Test
    fun editorOpensAtTheFirstConflictInsteadOfTheEndOfTheFile() {
        val source = "preface\nmore context\n<<<<<<< Fedora current\ncanonical"

        assertEquals(source.indexOf("<<<<<<<"), source.firstSourceConflictMarkerOffset())
    }

    @Test
    fun conflictVersionTimesAcceptUtcAndOffsetRfc3339() {
        val expected = Instant.parse("2026-08-30T07:27:11.590Z").toEpochMilli()

        assertEquals(expected, sourceConflictTimestampEpochMillis("2026-08-30T07:27:11.590Z"))
        assertEquals(expected, sourceConflictTimestampEpochMillis("2026-08-30T09:27:11.590+02:00"))
    }

    @Test
    fun missingOrMalformedConflictVersionTimesStayUnknown() {
        assertNull(sourceConflictTimestampEpochMillis(null))
        assertNull(sourceConflictTimestampEpochMillis(""))
        assertNull(sourceConflictTimestampEpochMillis("when the conflict opened"))
    }

    @Test
    fun resolvedDirtyMergedTextCanBeSaved() {
        assertTrue(
            conflictResolutionCanSave(
                editorLoaded = true,
                saving = false,
                mergedViewSelected = true,
                dirty = true,
                contents = "resolved TeX",
            ),
        )
    }

    @Test
    fun readOnlyVersionsAndUnchangedTextCannotBeSaved() {
        assertFalse(
            conflictResolutionCanSave(
                editorLoaded = true,
                saving = false,
                mergedViewSelected = false,
                dirty = true,
                contents = "Fedora version",
            ),
        )
        assertFalse(
            conflictResolutionCanSave(
                editorLoaded = true,
                saving = false,
                mergedViewSelected = true,
                dirty = false,
                contents = "merged version",
            ),
        )
    }

    @Test
    fun conflictListKeepsEveryConflictAndExcludesOrdinaryFailures() {
        val mutations = listOf(
            pendingMutation("b.tex", "b", SourceMutationState.CONFLICT),
            pendingMutation("failed.tex", "f", SourceMutationState.FAILED),
            pendingMutation("a.tex", "a", SourceMutationState.CONFLICT),
        )

        val visible = visibleSourceConflicts(mutations)

        assertEquals(listOf("a.tex", "b.tex"), visible.map(PendingSourceMutation::sourceFile))
    }

    private fun pendingMutation(
        sourceFile: String,
        requestId: String,
        state: SourceMutationState,
    ) = PendingSourceMutation(
        requestId = requestId,
        sourceFile = sourceFile,
        pdfFilename = "fixture.pdf",
        state = state,
        conflictId = if (state == SourceMutationState.CONFLICT) "conflict-$requestId" else null,
    )
}

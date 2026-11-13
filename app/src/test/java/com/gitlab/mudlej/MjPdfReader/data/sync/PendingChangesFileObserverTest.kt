// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingChangesFileObserverTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `parent is direct-only while recursion stays inside configured root`() {
        val documents = temporary.newFolder("Documents")
        val reviewRoot = File(documents, "docs-clean-reviews").apply { mkdirs() }
        val nested = File(reviewRoot, "events/origin").apply { mkdirs() }
        val unrelated = File(documents, "large-unrelated-library").apply { mkdirs() }
        repeat(1_000) { index -> File(unrelated, "project-$index/source").mkdirs() }

        val plan = pendingChangesWatchPlan(listOf(reviewRoot))
        lateinit var recursiveDirectories: List<File>
        val elapsed = measureTimeMillis {
            recursiveDirectories = pendingChangesRecursiveDirectories(reviewRoot).toList()
        }

        assertEquals(listOf(reviewRoot.canonicalFile), plan.recursiveRoots)
        assertEquals(setOf(reviewRoot.canonicalFile), plan.directParentRoots[documents.canonicalFile])
        assertEquals(
            setOf(reviewRoot.canonicalFile, File(reviewRoot, "events").canonicalFile, nested.canonicalFile),
            recursiveDirectories.map(File::getCanonicalFile).toSet(),
        )
        assertFalse(recursiveDirectories.any { it.canonicalPath.startsWith(unrelated.canonicalPath) })
        assertTrue("Scoped traversal took ${elapsed}ms", elapsed < 500L)
    }

    @Test
    fun `missing root remains an exact direct-parent target for atomic creation`() {
        val parent = temporary.newFolder("sync-roots")
        val missingRoot = File(parent, "review-incoming")

        val plan = pendingChangesWatchPlan(listOf(missingRoot))

        assertEquals(listOf(missingRoot.canonicalFile), plan.recursiveRoots)
        assertEquals(setOf(missingRoot.canonicalFile), plan.directParentRoots[parent.canonicalFile])
        assertTrue(pendingChangesRecursiveDirectories(missingRoot).none())
    }

    @Test
    fun `shared parent targets only configured children`() {
        val parent = temporary.newFolder("review-sync")
        val events = File(parent, "outgoing-events").apply { mkdirs() }
        val cards = File(parent, "outgoing-cards").apply { mkdirs() }
        File(parent, "unrelated-cache").mkdirs()

        val plan = pendingChangesWatchPlan(listOf(events, cards, events))

        assertEquals(setOf(events.canonicalFile, cards.canonicalFile), plan.recursiveRoots.toSet())
        assertEquals(
            setOf(events.canonicalFile, cards.canonicalFile),
            plan.directParentRoots[parent.canonicalFile],
        )
    }

    @Test
    fun `root replacement releases every old tree observer but not sibling roots`() {
        val parent = temporary.newFolder("replacement")
        val root = File(parent, "review-incoming").apply { mkdirs() }
        val sibling = File(parent, "review-outgoing").apply { mkdirs() }
        val keys = listOf(
            "parent:${parent.canonicalPath}",
            "tree:${root.canonicalPath}",
            "tree:${File(root, "cards/origin").canonicalPath}",
            "tree:${sibling.canonicalPath}",
        )

        assertEquals(
            listOf(keys[1], keys[2]),
            pendingChangesTreeObserverKeysUnder(keys, root),
        )
    }
}

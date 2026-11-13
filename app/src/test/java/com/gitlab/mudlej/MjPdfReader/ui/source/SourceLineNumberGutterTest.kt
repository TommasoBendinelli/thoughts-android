// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceLineNumberGutterTest {
    private fun linesBefore(text: CharSequence, offset: Int) = text.subSequence(0, offset).count { it == '\n' }

    @Test
    fun cachedLineIndexFollowsScrollingInBothDirections() {
        val text = (1..200).joinToString("\n") { "line $it" }
        val cache = LogicalLineCache()
        listOf(0, 50, 900, 300, text.length, 0, 1).forEach { offset ->
            assertEquals(linesBefore(text, offset), cache.lineIndexAt(text, offset))
        }
    }

    @Test
    fun editsBeforeInsideAndAfterTheCachedPointKeepItExact() {
        val random = java.util.Random(11)
        val text = StringBuilder((1..80).joinToString("\n") { "row $it with words" })
        val cache = LogicalLineCache()
        repeat(2_000) {
            // Move the cached point as a draw would.
            cache.lineIndexAt(text, random.nextInt(text.length + 1))
            val start = random.nextInt(text.length + 1)
            val removed = random.nextInt(minOf(12, text.length - start) + 1)
            val inserted = buildString {
                repeat(random.nextInt(8)) { append(if (random.nextInt(3) == 0) '\n' else 'x') }
            }
            cache.beforeChange(text, start, removed)
            text.replace(start, start + removed, inserted)
            cache.afterChange(text, start, inserted.length)
            assertEquals(linesBefore(text, cache.cachedOffset), cache.cachedLineIndex)
            val probe = random.nextInt(text.length + 1)
            assertEquals(linesBefore(text, probe), cache.lineIndexAt(text, probe))
        }
    }

    @Test
    fun replacingTheWholeTextResetsTheCache() {
        val text = StringBuilder("a\nb\nc\nd")
        val cache = LogicalLineCache()
        assertEquals(3, cache.lineIndexAt(text, text.length))
        cache.beforeChange(text, 0, text.length)
        text.replace(0, text.length, "x\ny")
        cache.afterChange(text, 0, 3)
        assertEquals(0, cache.cachedOffset)
        assertEquals(1, cache.lineIndexAt(text, 3))
    }
}

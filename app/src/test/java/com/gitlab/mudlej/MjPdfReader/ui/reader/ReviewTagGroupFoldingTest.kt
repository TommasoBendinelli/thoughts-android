// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTagGroupFoldingTest {
    private fun group(id: String, name: String) = ReviewTagGroups.Group(id, name, emptyList())

    @Test fun understandingAndRecallStartOpenEveryOtherGroupClosed() {
        assertTrue(reviewTagGroupOpenByDefault(group("6f1c", "Understanding"), hasGroups = true))
        assertTrue(reviewTagGroupOpenByDefault(group("a9e2", "recall"), hasGroups = true))
        assertTrue("by id too", reviewTagGroupOpenByDefault(group("recall", "Erinnern"), hasGroups = true))
        for (name in listOf("Interest", "Effort", "Structure", "Style", "Source", "A group made later"))
            assertFalse(name, reviewTagGroupOpenByDefault(group("x", name), hasGroups = true))
        // Other is open only when it is the only row.
        assertFalse(reviewTagGroupOpenByDefault(null, hasGroups = true))
        assertTrue(reviewTagGroupOpenByDefault(null, hasGroups = false))
    }

    @Test fun theUsersChoiceWinsOverTheDefault() {
        assertTrue(reviewTagGroupOpen("g", byDefault = false, opened = setOf("g"), closed = emptySet()))
        assertFalse(reviewTagGroupOpen("g", byDefault = true, opened = emptySet(), closed = setOf("g")))
        assertTrue(reviewTagGroupOpen("g", byDefault = true, opened = setOf("h"), closed = setOf("h2")))
        assertFalse(reviewTagGroupOpen("g", byDefault = false, opened = emptySet(), closed = emptySet()))
    }

    @Test fun theHeaderSummaryNamesTheStepThenTheChosenTags() {
        val tags = listOf("Submitted", "Not interesting", "Somewhat", "Interesting", "Asked about it")
        val steps = listOf("Not interesting", "Somewhat", "Interesting")
        assertEquals("Somewhat, Submitted",
            reviewTagGroupChoiceSummary(tags, steps, setOf("Submitted", "Somewhat", "Unrelated"), { it }))
        assertEquals("", reviewTagGroupChoiceSummary(tags, steps, setOf("Unrelated"), { it }))
        assertEquals("Asked about it", reviewTagGroupChoiceSummary(tags, emptyList(), setOf("Asked about it"), { it }))
    }

    @Test fun aClosedSectionNamesWhatItsGroupsHold() {
        val children = listOf("Structure" to "Missing steps", "Style" to "Clean, Short", "Content" to "")
        val summary = reviewTagSectionSummary(children)
        assertEquals("Structure: Missing steps · Style: Clean, Short", summary)
        assertEquals("Work left · Structure: Missing steps · Style: Clean, Short", reviewTagSectionText("Work left", summary))
        // Nothing chosen: no summary, the header is the name alone.
        assertEquals("", reviewTagSectionSummary(listOf("Structure" to "", "Style" to "")))
        assertEquals("Work done", reviewTagSectionText("Work done", ""))
        // Each group's part is its own header summary: the step first, then its chosen tags.
        val structure = reviewTagGroupChoiceSummary(listOf("Fine", "Missing steps", "Asked AI"), listOf("Missing steps", "Fine"),
            setOf("Asked AI", "Fine"), { it })
        assertEquals("Structure: Fine, Asked AI", reviewTagSectionSummary(listOf("Structure" to structure)))
    }

    @Test fun sectionsAreRememberedBySectionNameAndStartOpen() {
        val folding = ReviewTagGroupFolding(FakePreferences())
        assertTrue(folding.isSectionOpen("Work left"))
        folding.setSectionOpen("Work left", false)
        assertFalse(folding.isSectionOpen("Work left"))
        assertTrue("other sections keep their default", folding.isSectionOpen("Work done"))
        // Apart from the groups: a group whose key happens to be the section name is not affected.
        assertFalse(folding.isOpen("Work left", byDefault = false))
        assertTrue(folding.isOpen("Work left", byDefault = true))
        folding.setSectionOpen("Work left", true)
        assertTrue(folding.isSectionOpen("Work left"))
    }

    /** Just enough SharedPreferences for string sets. */
    private class FakePreferences : android.content.SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toMutableSet() }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { values += changes }
        }
    }
}

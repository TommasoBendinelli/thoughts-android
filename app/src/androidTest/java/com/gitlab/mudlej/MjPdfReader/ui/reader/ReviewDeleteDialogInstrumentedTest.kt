// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewDeleteDialogInstrumentedTest {
    @Test fun unsupportedServerCannotQueueSourceDeletion() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var result: Boolean? = null
            scenario.onActivity { activity ->
                val dialog = createReviewDeleteDialog(activity, true) { result = it }
                dialog.show()
                val checkbox = dialog.findViewById<CheckBox>(R.id.delete_enclosed_text)!!
                assertFalse(checkbox.isEnabled)
                assertFalse(checkbox.isChecked)
                // Even a stale programmatic selection cannot bypass the capability gate.
                checkbox.isChecked = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.onActivity { assertEquals(false, result) }
        }
    }

    @Test fun sourceDeletionRequiresExplicitSelectionAndConfirmation() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var result: Boolean? = null
            scenario.onActivity { activity ->
                val dialog = createReviewDeleteDialog(activity, true, serverSupportsSourceDeletion = true) { result = it }
                dialog.show()
                assertFalse(dialog.findViewById<CheckBox>(R.id.delete_enclosed_text)!!.isChecked)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.onActivity { activity ->
                assertEquals(false, result)

                result = null
                val dialog = createReviewDeleteDialog(activity, true, serverSupportsSourceDeletion = true) { result = it }
                dialog.show()
                dialog.findViewById<CheckBox>(R.id.delete_enclosed_text)!!.isChecked = true
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            scenario.onActivity { activity ->
                assertNull(result)

                val dialog = createReviewDeleteDialog(activity, true, serverSupportsSourceDeletion = true) { result = it }
                dialog.show()
                val checkbox = dialog.findViewById<CheckBox>(R.id.delete_enclosed_text)!!
                assertFalse(checkbox.isChecked)
                checkbox.isChecked = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.onActivity { activity ->
                assertEquals(true, result)

                val dialog = createReviewDeleteDialog(activity, false) { result = it }
                dialog.show()
                assertNull(dialog.findViewById<CheckBox>(R.id.delete_enclosed_text))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.onActivity {
                assertEquals(false, result)
            }
        }
    }
}

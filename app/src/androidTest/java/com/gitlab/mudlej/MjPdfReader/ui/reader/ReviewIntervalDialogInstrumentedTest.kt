// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.graphics.Rect
import android.view.ViewGroup
import android.widget.DatePicker
import android.view.inspector.WindowInspector
import android.widget.CheckBox
import android.widget.RadioButton
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewSkipScope
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags
import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityServiceInfo
import java.io.File
import android.widget.EditText
import android.widget.AutoCompleteTextView
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import java.math.BigDecimal
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewIntervalUnit
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewIntervalDialogInstrumentedTest {
    @Before fun dismissFirstRunScreens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = com.gitlab.mudlej.MjPdfReader.data.Preferences(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
        preferences.setFirstInstall(false)
        preferences.setShowFeaturesDialog(false)
        // Otherwise a fresh install opens What's New over the dialog under test.
        preferences.setLastSeenVersionCode(com.gitlab.mudlej.MjPdfReader.BuildConfig.VERSION_CODE)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500) // Wait for the bottom sheet or calendar entrance animation.
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun skipDialogsDefaultToDueCardsAndCanIncludeFutureCards() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val interval = ReviewInterval(10, ReviewIntervalUnit.MINUTES)
                var applied: Triple<ReviewInterval?, ReviewSkipScope, Boolean>? = null
                fun showSkip() = createReviewIntervalDialog(activity, interval,
                    skipOptions = ReviewSkipDialogOptions("paper.pdf", "project") { value, scope, dueOnly ->
                        applied = Triple(value, scope, dueOnly)
                    }, onApply = {}).also { it.show() }

                var dialog = showSkip()
                assertFalse(dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.skipWhichCardsContainer)!!.visibility)
                dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked = true
                assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.skipWhichCardsContainer)!!.visibility)
                assertTrue(dialog.findViewById<RadioButton>(R.id.skipDueOnly)!!.isChecked)
                dialog.findViewById<View>(R.id.apply)!!.performClick()
                assertEquals(Triple(interval, ReviewSkipScope.DOCUMENT, true), applied)

                dialog = showSkip()
                dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked = true
                dialog.findViewById<RadioButton>(R.id.skipAllCards)!!.isChecked = true
                dialog.findViewById<AutoCompleteTextView>(R.id.reviewScopePicker)!!.setText("Project", false)
                dialog.findViewById<View>(R.id.apply)!!.performClick()
                assertEquals(Triple(interval, ReviewSkipScope.PROJECT, false), applied)

                dialog = showSkip()
                dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked = true
                dialog.findViewById<AutoCompleteTextView>(R.id.reviewScopePicker)!!.setText("Project", false)
                dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked = false
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.skipWhichCardsContainer)!!.visibility)
                dialog.findViewById<View>(R.id.apply)!!.performClick()
                assertEquals(Triple(interval, ReviewSkipScope.CARD, true), applied)

                val timing = createReviewTimingDialog(activity, interval, ReviewTags.BUILT_IN, setOf(ReviewTags.SKIP),
                    projectAvailable = true) { _, value, scope, dueOnly ->
                    applied = Triple(value, scope, dueOnly)
                }.also { it.show() }
                assertEquals(View.GONE, timing.findViewById<View>(R.id.skipWhichCardsContainer)!!.visibility)
                timing.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked = true
                assertEquals(View.VISIBLE, timing.findViewById<View>(R.id.skipWhichCardsContainer)!!.visibility)
                timing.findViewById<RadioButton>(R.id.skipAllCards)!!.isChecked = true
                timing.findViewById<View>(R.id.reviewTimingNext)!!.performClick()
                assertEquals(Triple(interval, ReviewSkipScope.DOCUMENT, false), applied)
            }
        }
    }

    @Test fun editingValidatesAndAppliesTheSelectedAmountAndUnit() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var chosen: ReviewInterval? = null
            lateinit var dialog: AlertDialog
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialog = createReviewIntervalDialog(activity, ReviewInterval(2, ReviewIntervalUnit.WEEKS)) { chosen = it }
                dialog.show()
                assertEquals("2", dialog.findViewById<EditText>(R.id.amount)!!.text.toString())
                val unitPicker = dialog.findViewById<AutoCompleteTextView>(R.id.unit)!!
                assertEquals("weeks", unitPicker.text.toString())
                assertEquals(listOf("minutes", "hours", "days", "weeks", "months"),
                    (0 until unitPicker.adapter.count).map { unitPicker.adapter.getItem(it).toString() })
                dialog.findViewById<EditText>(R.id.amount)!!.setText("")
                assertFalse(dialog.findViewById<View>(R.id.apply)!!.isEnabled)
                dialog.findViewById<EditText>(R.id.amount)!!.setText("0")
                assertFalse(dialog.findViewById<View>(R.id.apply)!!.isEnabled)
                dialog.findViewById<EditText>(R.id.amount)!!.setText("1,5")
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            val automation = instrumentation.uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            scenario.onActivity { dialog.findViewById<AutoCompleteTextView>(R.id.unit)!!.showDropDown() }
            instrumentation.waitForIdleSync()
            // Material uses an accessible modal popup, separate from AutoCompleteTextView's base popup.
            instrumentation.uiAutomation.waitForIdle(500, 5000)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "review-interval-units.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val item = automation.windows.flatMap { it.root?.findAccessibilityNodeInfosByText("months").orEmpty() }
                .first { it.text?.toString() == "months" }
            assertTrue(item.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500) // Capture after the dialog entrance animation.
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "review-interval-check.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            scenario.onActivity {
                assertTrue(dialog.findViewById<View>(R.id.apply)!!.isEnabled)
                assertTrue(dialog.findViewById<TextView>(R.id.due)!!.text.startsWith("Due "))
                dialog.findViewById<View>(R.id.apply)!!.performClick()
                assertEquals(ReviewInterval(BigDecimal("1.5"), ReviewIntervalUnit.MONTHS), chosen)
                assertEquals("1.5 months", chosen!!.label(it))
            }
        }
    }

    @Test fun timingEditorAppliesAmountAndScopeInOneStep() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val interval = ReviewInterval(2, ReviewIntervalUnit.WEEKS)
                var applied: Pair<ReviewInterval?, ReviewSkipScope>? = null
                var appliedTags: List<String>? = null
                fun show(selected: Set<String> = setOf(ReviewTags.CORRECT)) = createReviewTimingDialog(activity,
                    interval, ReviewTags.BUILT_IN + "notation", selected, projectAvailable = true,
                    onApply = { tags, value, scope, _ -> appliedTags = tags; applied = value to scope }).also { it.show() }
                var dialog = show(emptySet())
                val chips = dialog.findViewById<com.google.android.material.chip.ChipGroup>(R.id.reviewTags)!!
                assertEquals(listOf("Correct", "Wrong", "notation", "New tag"),
                    (0 until chips.childCount).map { (chips.getChildAt(it) as TextView).text.toString() })
                assertFalse("A tag is required", dialog.findViewById<View>(R.id.reviewTimingNext)!!.isEnabled)
                (chips.getChildAt(1) as CheckBox).isChecked = true
                (chips.getChildAt(2) as CheckBox).isChecked = true
                dialog.findViewById<View>(R.id.reviewTimingNext)!!.performClick()
                assertEquals(listOf(ReviewTags.WRONG, "notation"), appliedTags)
                assertEquals(interval to ReviewSkipScope.CARD, applied)
                dialog = show()
                assertFalse(dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.reviewScopePickerLayout)!!.visibility)
                assertEquals("2", dialog.findViewById<EditText>(R.id.amount)!!.text.toString())
                assertEquals("weeks", dialog.findViewById<AutoCompleteTextView>(R.id.unit)!!.text.toString())
                dialog.findViewById<EditText>(R.id.amount)!!.setText("3.5")
                assertEquals("Next", dialog.findViewById<TextView>(R.id.reviewTimingNext)!!.text.toString())
                dialog.findViewById<View>(R.id.reviewTimingNext)!!.performClick()
                assertEquals(ReviewInterval(BigDecimal("3.5"), ReviewIntervalUnit.WEEKS) to ReviewSkipScope.CARD, applied)
                assertFalse(dialog.isShowing)
                dialog = show()
                dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked = true
                dialog.findViewById<AutoCompleteTextView>(R.id.reviewScopePicker)!!.setText("Project", false)
                dialog.findViewById<View>(R.id.reviewTimingNext)!!.performClick()
                assertEquals(interval to ReviewSkipScope.PROJECT, applied)
                dialog = show()
                dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked = true
                assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.skipWhichCardsContainer)!!.visibility)
                dialog.findViewById<EditText>(R.id.amount)!!.setText("")
                assertFalse(dialog.findViewById<View>(R.id.reviewTimingNext)!!.isEnabled)
                dialog.findViewById<EditText>(R.id.amount)!!.setText("0")
                assertFalse(dialog.findViewById<View>(R.id.reviewTimingNext)!!.isEnabled)
                dialog.findViewById<EditText>(R.id.amount)!!.setText("1,5")
                dialog.findViewById<View>(R.id.reviewTimingNext)!!.performClick()
                assertEquals(ReviewInterval(BigDecimal("1.5"), ReviewIntervalUnit.WEEKS) to ReviewSkipScope.DOCUMENT, applied)
            }
        }
    }

    @Test fun applyAndNextStayAboveTheKeyboardWhileTypingTheAmount() {
        val interval = ReviewInterval(15, ReviewIntervalUnit.DAYS)
        // The reader's Interval button opens the plain dialog; the tag cell opens the sheet.
        assertActionAboveKeyboard("review-interval-keyboard.png", R.id.apply) { activity ->
            createReviewIntervalDialog(activity, interval) {}
        }
        assertActionAboveKeyboard("review-timing-keyboard.png", R.id.reviewTimingNext) { activity ->
            createReviewTimingDialog(activity, interval, ReviewTags.BUILT_IN + listOf("notation", "intuition", "derivation"),
                setOf(ReviewTags.CORRECT), projectAvailable = true, onApply = { _, _, _, _ -> })
        }
    }

    private fun assertActionAboveKeyboard(screenshot: String, actionId: Int, create: (HomeActivity) -> android.app.Dialog) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            lateinit var dialog: android.app.Dialog
            scenario.onActivity { activity -> dialog = create(activity).also { it.show() } }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            scenario.onActivity {
                val amount = dialog.findViewById<EditText>(R.id.amount)!!
                amount.requestFocus()
                (amount.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager).showSoftInput(amount, 0)
            }
            // Measure once the keyboard and the dialog have stopped moving.
            var imeTop = 0
            var visibleAction = Rect()
            var actionHeight = 0
            var previous = ""
            var stableSince = SystemClock.uptimeMillis()
            val deadline = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(150)
                scenario.onActivity { activity ->
                    // A resized dialog window gets no IME inset; the full-screen activity window does.
                    val decor = activity.window.decorView
                    val ime = decor.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
                    imeTop = if (ime > 0) decor.height - ime else 0
                    val action = dialog.findViewById<View>(actionId)!!
                    val window = IntArray(2).also(dialog.window!!.decorView::getLocationOnScreen)
                    visibleAction = Rect().also {
                        if (action.getGlobalVisibleRect(it)) it.offset(window[0], window[1]) else it.setEmpty()
                    }
                    actionHeight = action.height
                }
                val state = "$imeTop $visibleAction"
                if (state != previous) { previous = state; stableSince = SystemClock.uptimeMillis() }
                else if (imeTop > 0 && SystemClock.uptimeMillis() - stableSince > 600) break
            }
            capture(screenshot)
            scenario.onActivity { dialog.dismiss() }
            assertTrue("The keyboard did not open", imeTop > 0)
            assertEquals("$screenshot: the button is cut off: $visibleAction", actionHeight, visibleAction.height())
            assertTrue("$screenshot: the button ends at ${visibleAction.bottom}, under the keyboard at $imeTop",
                visibleAction.bottom <= imeTop)
        }
    }

    @Test fun customOpensCalendarImmediatelyAndNextAppliesDateAndScope() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var applied: Pair<ReviewInterval?, ReviewSkipScope>? = null
            lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
            val date = LocalDate.now().plusDays(9)
            scenario.onActivity { activity ->
                val initial = ReviewInterval(2, ReviewIntervalUnit.WEEKS)
                dialog = createReviewTimingDialog(activity, initial, ReviewTags.BUILT_IN, setOf(ReviewTags.CORRECT),
                    projectAvailable = true, onApply = { _, value, scope, _ -> applied = value to scope })
                dialog.show()
                dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked = true
            }
            capture("review-timing-editor.png")
            scenario.onActivity {
                dialog.findViewById<EditText>(R.id.amount)!!.setText("")
                assertEquals("Custom…", dialog.findViewById<TextView>(R.id.pickDate)!!.text.toString())
                dialog.findViewById<View>(R.id.pickDate)!!.performClick()
            }
            capture("review-timing-calendar.png")
            scenario.onActivity {
                fun findPicker(view: View): DatePicker? {
                    if (view is DatePicker) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount)
                        findPicker(view.getChildAt(i))?.let { return it }
                    return null
                }
                val calendarRoot = WindowInspector.getGlobalWindowViews().single { findPicker(it) != null }
                val picker = checkNotNull(findPicker(calendarRoot))
                picker.updateDate(date.year, date.monthValue - 1, date.dayOfMonth)
                assertNull(applied)
                calendarRoot.findViewById<View>(android.R.id.button1).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(dialog.isShowing)
                assertNull(applied)
                assertTrue(dialog.findViewById<View>(R.id.reviewTimingNext)!!.isEnabled)
                dialog.findViewById<View>(R.id.reviewTimingNext)!!.performClick()
                assertEquals(date, applied!!.first!!.date)
                assertEquals(ReviewSkipScope.DOCUMENT, applied!!.second)
            }
        }
    }

    @Test fun neverCanBeAppliedOrReplacedByAnEditableInterval() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val initial = ReviewInterval(10, ReviewIntervalUnit.MINUTES)
                var applied: Pair<ReviewInterval?, ReviewSkipScope>? = null
                fun show(tag: String) = createReviewTimingDialog(
                    activity, initial, ReviewTags.BUILT_IN, setOf(tag), projectAvailable = false,
                    onApply = { _, value, scope, _ -> applied = value to scope }).also { it.show() }
                var dialog = show(ReviewTags.WRONG)
                assertFalse(dialog.findViewById<CheckBox>(R.id.reviewNever)!!.isChecked)
                dialog.findViewById<CheckBox>(R.id.reviewNever)!!.isChecked = true
                dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked = true
                assertFalse(dialog.findViewById<AutoCompleteTextView>(R.id.reviewScopePicker)!!.isEnabled)
                dialog.findViewById<CheckBox>(R.id.reviewApplyAll)!!.isChecked = false
                dialog.findViewById<View>(R.id.reviewTimingNext)!!.performClick()
                assertEquals(null to ReviewSkipScope.CARD, applied)
                dialog = show(ReviewTags.WRONG)
                dialog.findViewById<CheckBox>(R.id.reviewNever)!!.isChecked = true
                dialog.findViewById<EditText>(R.id.amount)!!.setText("25")
                assertFalse(dialog.findViewById<CheckBox>(R.id.reviewNever)!!.isChecked)
                dialog.findViewById<View>(R.id.reviewTimingNext)!!.performClick()
                assertEquals(ReviewInterval(25, ReviewIntervalUnit.MINUTES) to ReviewSkipScope.CARD, applied)
                dialog = show(ReviewTags.SKIP)
                assertEquals(View.VISIBLE, dialog.findViewById<CheckBox>(R.id.reviewNever)!!.visibility)
                dialog.findViewById<CheckBox>(R.id.reviewNever)!!.isChecked = true
                dialog.findViewById<View>(R.id.reviewTimingNext)!!.performClick()
                assertEquals(null to ReviewSkipScope.CARD, applied)
            }
        }
    }

    @Test fun reopeningPreservesDateAndCancelDoesNotApply() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            val initial = ReviewInterval(2, ReviewIntervalUnit.WEEKS, LocalDate.now().plusDays(9))
            lateinit var dialog: AlertDialog
            var chosen: ReviewInterval? = null
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialog = createReviewIntervalDialog(activity, initial) { chosen = it }
                dialog.show()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                dialog.findViewById<View>(R.id.apply)!!.performClick()
                assertEquals(initial, chosen)
                chosen = null
                dialog = createReviewIntervalDialog(activity, initial) { chosen = it }
                dialog.show()
                dialog.findViewById<EditText>(R.id.amount)!!.setText("5")
                dialog.findViewById<View>(R.id.cancel)!!.performClick()
                assertNull(chosen)
                dialog = createReviewIntervalDialog(activity, ReviewInterval(12, ReviewIntervalUnit.HOURS)) { chosen = it }
                dialog.show()
                assertEquals("hours", dialog.findViewById<AutoCompleteTextView>(R.id.unit)!!.text.toString())
                assertEquals("12", dialog.findViewById<EditText>(R.id.amount)!!.text.toString())
                dialog.findViewById<View>(R.id.cancel)!!.performClick()
                assertNull(chosen)
            }
        }
    }

    @Test fun skipRandomPutsSampledWholeDaysInTheIntervalFieldsUntilSkipCardApplies() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val draws = ArrayDeque(listOf(0.0, 0.95, 0.95))
                var applied: ReviewInterval? = null
                fun showSkip() = createReviewIntervalDialog(activity, ReviewInterval(10, ReviewIntervalUnit.MINUTES),
                    skipOptions = ReviewSkipDialogOptions("paper.pdf", null,
                        randomUniform = { draws.removeFirst() }) { value, _, _ -> applied = value }, onApply = {}).also { it.show() }

                var dialog = showSkip()
                val random = dialog.findViewById<View>(R.id.randomSkipDate)!!
                assertEquals(View.VISIBLE, random.visibility)
                val due = dialog.findViewById<TextView>(R.id.due)!!
                val before = due.text.toString()
                random.performClick()
                val first = due.text.toString()
                assertNotEquals(before, first)
                assertTrue(first.startsWith("Skipped until "))
                // The draw fills the interval fields (whole days, no date): u = 0 gives 2 days.
                assertEquals("2", dialog.findViewById<EditText>(R.id.amount)!!.text.toString())
                assertEquals("days", dialog.findViewById<AutoCompleteTextView>(R.id.unit)!!.text.toString())
                // Each tap resamples (u = 0.95 gives 14 days); Skip card applies the shown interval.
                random.performClick()
                assertNotEquals(first, due.text.toString())
                assertEquals("14", dialog.findViewById<EditText>(R.id.amount)!!.text.toString())
                dialog.findViewById<View>(R.id.apply)!!.performClick()
                assertEquals(0, applied!!.amount.compareTo(BigDecimal.valueOf(14)))
                assertEquals(ReviewIntervalUnit.DAYS, applied!!.unit)
                assertNull(applied!!.date)

                // Cancel discards the drawn date.
                applied = null
                dialog = showSkip()
                dialog.findViewById<View>(R.id.randomSkipDate)!!.performClick()
                dialog.findViewById<View>(R.id.cancel)!!.performClick()
                assertNull(applied)

                // Only Skip offers it.
                val plain = createReviewIntervalDialog(activity, ReviewInterval(1, ReviewIntervalUnit.DAYS)) {}.also { it.show() }
                assertEquals(View.GONE, plain.findViewById<View>(R.id.randomSkipDate)!!.visibility)
                plain.dismiss()
            }
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.Dialog
import android.app.DatePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.text.method.DigitsKeyListener
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewSkipScope
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewIntervalUnit
import com.gitlab.mudlej.MjPdfReader.databinding.DialogReviewIntervalBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun ReviewInterval.label(context: Context): String {
    date?.let { return it.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) }
    val plural = when (unit) {
        ReviewIntervalUnit.MINUTES -> R.plurals.review_custom_minutes
        ReviewIntervalUnit.HOURS -> R.plurals.review_custom_hours
        ReviewIntervalUnit.DAYS -> R.plurals.review_custom_days
        ReviewIntervalUnit.WEEKS -> R.plurals.review_custom_weeks
        ReviewIntervalUnit.MONTHS -> R.plurals.review_custom_months
    }
    return context.resources.getQuantityString(plural, if (amount.compareTo(BigDecimal.ONE) == 0) 1 else 2, amountText())
}

internal fun ReviewInterval.amountText(): String = amount.stripTrailingZeros().toPlainString()
    .replace('.', DecimalFormatSymbols.getInstance().decimalSeparator)

internal data class ReviewSkipDialogOptions(
    val pdfFilename: String,
    val project: String?,
    /** "Random"'s uniform source in [0, 1); tests fix it. */
    val randomUniform: () -> Double = { kotlin.random.Random.nextDouble() },
    val onApply: (ReviewInterval, ReviewSkipScope, Boolean) -> Unit,
)

internal fun createReviewIntervalDialog(
    context: Context,
    initial: ReviewInterval,
    skipOptions: ReviewSkipDialogOptions? = null,
    onApply: (ReviewInterval) -> Unit,
): AlertDialog = createReviewIntervalEditorDialog(context, initial, skipOptions = skipOptions, onApply = onApply) as AlertDialog

internal fun createReviewIntervalEditorDialog(
    context: Context,
    initial: ReviewInterval,
    skipOptions: ReviewSkipDialogOptions? = null,
    timingOptions: ReviewTimingDialogOptions? = null,
    onApply: (ReviewInterval) -> Unit,
): Dialog {
    val binding = DialogReviewIntervalBinding.inflate(LayoutInflater.from(context))
    val projectAvailable = timingOptions?.projectAvailable ?: (skipOptions?.project != null)
    val documentLabel = context.getString(R.string.review_scope_document_short)
    val projectLabel = context.getString(R.string.review_scope_project_short)
    binding.reviewScopePicker.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line,
        if (projectAvailable) listOf(documentLabel, projectLabel) else listOf(documentLabel)))
    binding.reviewScopePicker.setText(documentLabel, false)
    binding.reviewScopePicker.setSelection(0)
    binding.reviewScopePicker.isEnabled = projectAvailable
    val selectedTags = linkedSetOf<String>().apply { timingOptions?.selectedTags?.let(::addAll) }
    var refreshApply: () -> Unit = {}
    timingOptions?.let { options ->
        binding.title.setText(R.string.review_again_in)
        binding.reviewTagsContainer.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(context)
        fun addTagChip(tag: String): Chip = (inflater.inflate(R.layout.item_review_tag_chip, binding.reviewTags, false) as Chip).apply {
            id = View.generateViewId()
            text = reviewTagShortLabel(context, tag)
            this.tag = tag
            contentDescription = reviewTagLabel(context, tag)
            if (text.toString() != contentDescription) androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
            isCheckable = true
            isChecked = tag in selectedTags
            setOnCheckedChangeListener { _, checked ->
                if (checked) selectedTags += tag else selectedTags -= tag
                refreshApply()
            }
        }.also { binding.reviewTags.addView(it, binding.reviewTags.childCount - 1) }
        binding.reviewTags.addView((inflater.inflate(R.layout.item_review_new_tag_chip, binding.reviewTags, false) as Chip).apply {
            id = View.generateViewId()
            setOnClickListener {
                showNewReviewTagDialog(context) { tag ->
                    val existing = (0 until binding.reviewTags.childCount - 1)
                        .map { binding.reviewTags.getChildAt(it) as Chip }
                        .firstOrNull { (it.tag as? String)?.equals(tag, ignoreCase = true) == true }
                    if (existing != null) existing.isChecked = true
                    else {
                        options.onTagCreated(tag)
                        selectedTags += tag
                        addTagChip(tag)
                        refreshApply()
                    }
                }
            }
        })
        options.tags.forEach(::addTagChip)
        binding.pickDate.setText(R.string.review_action_custom)
        binding.reviewNever.visibility = View.VISIBLE
        binding.reviewScopeContainer.visibility = View.VISIBLE
        binding.apply.id = R.id.reviewTimingNext
        binding.apply.setText(R.string.review_action_next)
    }
    skipOptions?.let {
        binding.title.setText(R.string.review_skip)
        binding.reviewScopeContainer.visibility = View.VISIBLE
        binding.randomSkipDate.visibility = View.VISIBLE
    }
    fun selectedScope(): ReviewSkipScope = when {
        !binding.reviewApplyAll.isChecked -> ReviewSkipScope.CARD
        projectAvailable && binding.reviewScopePicker.text.toString() == projectLabel -> ReviewSkipScope.PROJECT
        else -> ReviewSkipScope.DOCUMENT
    }
    val zone = ZoneId.systemDefault()
    val selectableUnits = ReviewIntervalUnit.entries.toList()
    val pickerInitial = if (initial.unit in selectableUnits) initial
        else ReviewInterval(amount = BigDecimal.ONE, unit = ReviewIntervalUnit.DAYS, date = initial.date)
    var selectedDate = pickerInitial.date
    var selectedUnit = pickerInitial.unit
    binding.amount.keyListener = DigitsKeyListener.getInstance("0123456789.,")
    binding.amount.setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
    binding.amount.setText(pickerInitial.amountText())
    val units = context.resources.getStringArray(R.array.review_interval_units)
    binding.unit.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, units))
    binding.unit.setText(units[selectableUnits.indexOf(selectedUnit)], false)
    val dialog: Dialog = if (timingOptions == null)
        MaterialAlertDialogBuilder(context).setView(binding.root).create()
    else BottomSheetDialog(context).apply { setContentView(binding.root) }
    fun selection(): ReviewInterval? = ReviewInterval.parseAmount(binding.amount.text.toString())?.let {
        ReviewInterval(it, selectedUnit, selectedDate)
    }
    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.amount.windowToken, 0)
        binding.amount.clearFocus()
    }
    fun refresh() {
        val now = Instant.now()
        val choice = selection()
        val due = runCatching { choice?.dueAt(now, zone) }.getOrNull()
        val never = binding.reviewNever.isChecked
        binding.due.text = if (never) context.getString(R.string.review_action_never) else if (due == null) context.getString(
            if (selectedDate != null) R.string.review_interval_future_date else R.string.review_interval_invalid,
        ) else context.getString(if (skipOptions == null) R.string.review_interval_due else R.string.review_skip_until, due.atZone(zone).format(
            DateTimeFormatter.ofPattern(
                if (due.atZone(zone).year == now.atZone(zone).year) "EEE, d MMM · HH:mm" else "EEE, d MMM yyyy · HH:mm",
                Locale.getDefault(),
            ),
        ))
        binding.apply.isEnabled = (never || due != null) && (timingOptions == null || selectedTags.isNotEmpty())
        if (skipOptions != null) binding.apply.setText(
            if (selectedScope() == ReviewSkipScope.CARD) R.string.review_skip_card else R.string.review_skip_cards,
        )
    }
    fun refreshScope() {
        val appliesAll = binding.reviewApplyAll.isChecked
        binding.reviewScopePickerLayout.visibility = if (appliesAll) View.VISIBLE else View.GONE
        val scopeName = when (selectedScope()) {
            ReviewSkipScope.DOCUMENT -> skipOptions?.pdfFilename
            ReviewSkipScope.PROJECT -> skipOptions?.project
            ReviewSkipScope.CARD -> null
        }
        binding.reviewScopeDetail.visibility = if (appliesAll && scopeName != null) View.VISIBLE else View.GONE
        binding.reviewScopeDetail.text = scopeName.orEmpty()
        binding.reviewProjectUnavailable.visibility = if (appliesAll && !projectAvailable) View.VISIBLE else View.GONE
        binding.skipWhichCardsContainer.visibility = if (appliesAll &&
            (skipOptions != null || timingOptions != null)) View.VISIBLE else View.GONE
        refresh()
    }
    refreshApply = ::refresh
    binding.reviewApplyAll.setOnCheckedChangeListener { _, _ -> refreshScope() }
    binding.reviewScopePicker.setOnItemClickListener { _, _, _, _ ->
        binding.reviewScopePicker.setSelection(0)
        refreshScope()
    }
    binding.reviewNever.setOnCheckedChangeListener { _, checked ->
        if (checked) hideKeyboard()
        refresh()
    }
    binding.amount.doAfterTextChanged {
        selectedDate = null
        binding.reviewNever.isChecked = false
        refresh()
    }
    // Coming from the amount field, the closing keyboard resizes the sheet and drops the unit list.
    binding.unit.setOnFocusChangeListener { _, focused ->
        if (!focused) return@setOnFocusChangeListener
        hideKeyboard()
        binding.unit.postDelayed({
            if (binding.unit.isAttachedToWindow && binding.unit.hasFocus() && !binding.unit.isPopupShowing) binding.unit.showDropDown()
        }, 250)
    }
    binding.unit.setOnItemClickListener { _, _, position, _ ->
        selectedUnit = selectableUnits[position]
        binding.reviewNever.isChecked = false
        selectedDate = null
        hideKeyboard()
        refresh()
    }
    binding.amount.setOnEditorActionListener { _, action, _ ->
        if (action == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
    }
    var picker: DatePickerDialog? = null
    binding.pickDate.setOnClickListener {
        hideKeyboard()
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val date = selectedDate?.takeIf { !it.isBefore(tomorrow) }
            ?: runCatching { selection()?.dueAt(Instant.now(), zone)?.atZone(zone)?.toLocalDate() }
                .getOrNull()?.takeIf { !it.isBefore(tomorrow) } ?: tomorrow
        picker = DatePickerDialog(context, { _, year, month, day ->
            // Restore a valid amount if the calendar was opened from an invalid number.
            if (selection() == null) binding.amount.setText("1")
            selectedDate = LocalDate.of(year, month + 1, day)
            binding.reviewNever.isChecked = false
            refresh()
        }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
            datePicker.minDate = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
            show()
        }
    }
    // Fills the interval fields with a sampled whole number of days (no date); each tap draws
    // again, and "Skipped until" follows from the interval as usual. Skip card applies it.
    skipOptions?.let { options ->
        binding.randomSkipDate.setOnClickListener {
            hideKeyboard()
            val days = randomSkipDays(options.randomUniform)
            selectedUnit = ReviewIntervalUnit.DAYS
            binding.unit.setText(units[selectableUnits.indexOf(ReviewIntervalUnit.DAYS)], false)
            binding.amount.setText(days.toString())
            selectedDate = null
            binding.reviewNever.isChecked = false
            refresh()
        }
    }
    binding.cancel.setOnClickListener { dialog.dismiss() }
    binding.apply.setOnClickListener {
        val choice = if (binding.reviewNever.isChecked) null else {
            val interval = selection() ?: return@setOnClickListener
            if (runCatching { interval.dueAt(Instant.now(), zone) }.isFailure) { refresh(); return@setOnClickListener }
            interval
        }
        hideKeyboard()
        dialog.dismiss()
        when {
            timingOptions != null -> timingOptions.onApply(selectedTags.toList(), choice, selectedScope(), binding.skipDueOnly.isChecked)
            skipOptions != null -> skipOptions.onApply(checkNotNull(choice), selectedScope(), binding.skipDueOnly.isChecked)
            else -> onApply(checkNotNull(choice))
        }
    }
    dialog.setOnShowListener {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        refreshScope()
    }
    binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { picker?.dismiss() }
    })
    // Set Next's state now; the show listener runs only after show() returns.
    refreshScope()
    return dialog
}

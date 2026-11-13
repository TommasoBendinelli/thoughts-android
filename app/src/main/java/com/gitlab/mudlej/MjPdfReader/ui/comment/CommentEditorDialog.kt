// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.comment

import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.app.Activity
import android.content.res.ColorStateList
import androidx.core.view.ViewCompat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCreationMetadata
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentPriority
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.HighlightPalette
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

internal data class CommentEditorDocument(
    val id: String,
    val label: String,
)

internal data class CommentEditorValue(
    val text: String,
    val color: Int = CommentCreationMetadata.DEFAULT_POPUP_COLOR,
    val priority: CommentPriority = CommentPriority.NORMAL,
    val location: CommentLocation? = null,
    val noteDetails: com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails? = null,
    val needsAction: Boolean = false,
    val linking: com.google.gson.JsonObject? = null,
)

internal data class CommentEditorResult(
    val documentId: String?,
    val value: CommentEditorValue,
)

internal data class CommentEditorNeutralAction(
    @StringRes val labelRes: Int,
    val isVisible: Boolean = true,
    val onSave: (CommentEditorResult) -> Boolean,
)

internal data class CommentEditorConfig(
    @StringRes val titleRes: Int,
    val initialValue: CommentEditorValue,
    val documents: List<CommentEditorDocument> = emptyList(),
    val selectedDocumentId: String? = null,
    val showMetadata: Boolean = true,
    val focusText: Boolean = true,
    val allowEmptyText: Boolean = false,
    val neutralAction: CommentEditorNeutralAction? = null,
)

internal class CommentEditorDialog(
    private val activity: Activity,
) {
    fun show(
        config: CommentEditorConfig,
        onSave: (CommentEditorResult) -> Boolean,
        onDismiss: () -> Unit = {},
        onAutosave: (suspend (CommentEditorResult) -> Boolean)? = null,
    ): AlertDialog {
        val content = LayoutInflater.from(activity).inflate(
            R.layout.dialog_comment_editor,
            null,
            false,
        )
        val documentLayout = content.findViewById<TextInputLayout>(
            R.id.commentEditorDocumentLayout,
        )
        val documentInput = content.findViewById<MaterialAutoCompleteTextView>(
            R.id.commentEditorDocument,
        )
        val textLayout = content.findViewById<TextInputLayout>(R.id.commentEditorTextLayout)
        val textInput = content.findViewById<TextInputEditText>(R.id.commentEditorText)
        val toggle = content.findViewById<MaterialButton>(R.id.commentEditorOptionsToggle)
        val options = content.findViewById<View>(R.id.commentEditorOptions)
        val swatchRow = content.findViewById<LinearLayout>(R.id.commentEditorColorSwatches)
        val priorityInput = content.findViewById<MaterialAutoCompleteTextView>(
            R.id.commentEditorPriority,
        )
        val locationSummary = content.findViewById<TextView>(
            R.id.commentEditorLocationSummary,
        )
        val locationRemove = content.findViewById<MaterialButton>(
            R.id.commentEditorLocationRemove,
        )

        var changed: () -> Unit = {}
        val noteFields = config.initialValue.noteDetails?.let {
            com.gitlab.mudlej.MjPdfReader.ui.notes.NoteDetailsFields(activity, it) { changed() }.also { fields ->
                val column = textLayout.parent as LinearLayout
                column.addView(fields, column.indexOfChild(textLayout) + 1)
            }
        }

        if (noteFields != null) textInput.setHint(R.string.quick_note_hint)
        textInput.setText(config.initialValue.text)
        textInput.setSelection(textInput.text?.length ?: 0)

        var selectedDocumentId = config.selectedDocumentId
        if (config.documents.isNotEmpty()) {
            documentLayout.visibility = View.VISIBLE
            documentInput.setAdapter(
                ArrayAdapter(
                    activity,
                    android.R.layout.simple_dropdown_item_1line,
                    config.documents.map(CommentEditorDocument::label),
                ),
            )
            val selected = config.documents.firstOrNull { it.id == selectedDocumentId }
                ?: config.documents.first()
            selectedDocumentId = selected.id
            documentInput.setText(selected.label, false)
            documentInput.setOnItemClickListener { _, _, position, _ ->
                selectedDocumentId = config.documents[position].id
                documentLayout.error = null
            }
        } else {
            documentLayout.visibility = View.GONE
            selectedDocumentId = null
        }

        var selectedColor = config.initialValue.color
        var selectedPriority = config.initialValue.priority
        var selectedLocation = config.initialValue.location
        var expanded = false
        val swatches = linkedMapOf<Int, View>()
        val palette = buildList {
            if (selectedColor !in HighlightPalette.entries.map(HighlightPalette::colorValue)) {
                add(selectedColor to colorLabel(selectedColor))
            }
            HighlightPalette.entries.forEach { add(it.colorValue to activity.getString(it.labelRes)) }
        }.distinctBy { it.first }

        fun colorBackground(color: Int, selected: Boolean, sizeDp: Int): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(
                    dp(if (selected) 3 else 1),
                    if (selected) {
                        MaterialColors.getColor(content, androidx.appcompat.R.attr.colorPrimary)
                    } else {
                        MaterialColors.getColor(content, com.google.android.material.R.attr.colorOutline)
                    },
                )
                setSize(dp(sizeDp), dp(sizeDp))
            }

        fun renderSwatches() {
            swatches.forEach { (color, view) ->
                view.background = colorBackground(color, color == selectedColor, 40)
                view.isSelected = color == selectedColor
            }
        }

        palette.forEach { (color, label) ->
            val swatch = View(activity).apply {
                val size = dp(40)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(8)
                }
                contentDescription = label
                tooltipText = label
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedColor = color
                    renderSwatches()
                    changed()
                }
            }
            swatches[color] = swatch
            swatchRow.addView(swatch)
        }
        renderSwatches()

        val priorities = CommentPriority.entries.toTypedArray()
        priorityInput.setAdapter(
            ArrayAdapter(
                activity,
                android.R.layout.simple_dropdown_item_1line,
                priorities.map(::priorityLabel),
            ),
        )
        priorityInput.setText(priorityLabel(selectedPriority), false)
        priorityInput.setOnItemClickListener { _, _, position, _ ->
            selectedPriority = priorities[position]
            changed()
        }

        // A place a comment already has is shown and kept as it is, or removed. A new one cannot be
        // chosen (the map picker is gone), so a comment without a place has no place row.
        fun renderLocation() {
            val placed = if (selectedLocation == null) View.GONE else View.VISIBLE
            locationSummary.text = selectedLocation?.let(::locationSummary).orEmpty()
            locationSummary.visibility = placed
            locationRemove.visibility = placed
        }
        renderLocation()

        val showLinking = config.showMetadata && config.initialValue.noteDetails == null
        content.findViewById<TextView>(R.id.commentEditorLinkingAttempt).visibility = if (showLinking) View.VISIBLE else View.GONE
        content.findViewById<TextView>(R.id.commentEditorLinkingMethod).visibility = if (showLinking) View.VISIBLE else View.GONE
        val linking = config.initialValue.linking.takeIf { showLinking }
        fun linkingValue(key: String) = linking?.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val method = when (val value = linkingValue("method")) {
            "saved_pdf_source_map" -> "Saved PDF source map"
            "existing_source_anchor" -> "Existing source anchor"
            "legacy_build_text_fallback" -> "Build-cache lookup → text-search fallback"
            "legacy_build_lookup" -> "Build-cache lookup"
            "" -> "Not recorded"
            else -> value
        }
        val outcome = when (linkingValue("status")) {
            "succeeded" -> "Linked"
            "needs_attention", "failed" -> "Needs attention"
            "queued", "running", "retry" -> "Linking"
            else -> "No recorded attempt"
        }
        val time = runCatching { java.time.Instant.parse(linkingValue("attempted_at"))
            .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)) }.getOrNull()
        val attempt = listOfNotNull(time, outcome).joinToString(" — ") + linkingValue("reason").let { if (it.isBlank()) "" else ": $it" }
        content.findViewById<TextView>(R.id.commentEditorLinkingAttempt).text = activity.getString(R.string.comment_linking_attempt, attempt)
        content.findViewById<TextView>(R.id.commentEditorLinkingMethod).text = activity.getString(R.string.comment_linking_method, method)
        val title = content.findViewById<TextView>(R.id.commentEditorTitle)
        title.setText(if (config.initialValue.noteDetails == null && config.showMetadata) R.string.comment else config.titleRes)
        val flag = content.findViewById<MaterialButton>(R.id.commentEditorActionFlag)
        flag.visibility = if (config.showMetadata && config.initialValue.noteDetails == null) View.VISIBLE else View.GONE
        flag.isCheckable = true
        flag.isChecked = config.initialValue.needsAction
        fun renderFlag() {
            val checked = flag.isChecked
            flag.setIconResource(if (checked) R.drawable.ic_comment_flag_filled else R.drawable.ic_comment_flag_outline)
            val dark = androidx.core.graphics.ColorUtils.calculateLuminance(
                MaterialColors.getColor(content, com.google.android.material.R.attr.colorSurface)) < 0.5
            flag.iconTint = ColorStateList.valueOf(if (checked) {
                if (dark) 0xFFFFCA70.toInt() else 0xFFBC7800.toInt()
            } else MaterialColors.getColor(content, com.google.android.material.R.attr.colorOnSurfaceVariant))
            flag.backgroundTintList = ColorStateList.valueOf(if (checked) {
                if (dark) 0xFF4A360C.toInt() else 0xFFFFF0D5.toInt()
            } else Color.TRANSPARENT)
            flag.tooltipText = activity.getString(if (checked) R.string.comment_action_remove else R.string.comment_action_mark)
        }
        flag.addOnCheckedChangeListener { _, _ -> renderFlag() }
        renderFlag()
        val neutralButton = content.findViewById<MaterialButton>(R.id.commentEditorNeutral)
        toggle.visibility = if (config.showMetadata) View.VISIBLE else View.GONE
        options.visibility = View.GONE
        ViewCompat.setStateDescription(toggle, activity.getString(R.string.comment_details_collapsed))
        toggle.setOnClickListener {
            expanded = !expanded
            toggle.setIconResource(if (expanded) R.drawable.ic_small_arrow_down else R.drawable.ic_small_arrow_right)
            ViewCompat.setStateDescription(toggle, activity.getString(
                if (expanded) R.string.comment_details_expanded else R.string.comment_details_collapsed))
            options.visibility = if (expanded) View.VISIBLE else View.GONE
            neutralButton.visibility = if (expanded && config.neutralAction?.isVisible == true) View.VISIBLE else View.GONE
        }
        val dialog = MaterialAlertDialogBuilder(activity).setView(content).create()
        fun unvalidatedValue() = CommentEditorValue(textInput.text?.toString().orEmpty(), selectedColor, selectedPriority,
            selectedLocation, noteFields?.value, flag.isChecked)
        val openedValue = unvalidatedValue()
        fun confirmDiscard() {
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.comment_editor_discard_question)
                .setNegativeButton(R.string.note_keep_editing, null)
                .setPositiveButton(R.string.source_editor_discard) { _, _ -> dialog.dismiss() }
                .show()
        }

        fun result(): CommentEditorResult? {
            val text = textInput.text?.toString().orEmpty()
            textLayout.error = null
            documentLayout.error = null
            if (text.isBlank() && !config.allowEmptyText) {
                textLayout.error = activity.getString(R.string.overview_comment_required)
                textInput.requestFocus()
                return null
            }
            val documentId = selectedDocumentId?.takeIf { selectedId ->
                val selected = config.documents.firstOrNull { it.id == selectedId }
                selected?.label == documentInput.text?.toString()
            }
            if (config.documents.isNotEmpty() && documentId == null) {
                documentLayout.error = activity.getString(R.string.overview_add_document)
                return null
            }
            return CommentEditorResult(
                documentId,
                CommentEditorValue(text, selectedColor, selectedPriority, selectedLocation, noteFields?.value, flag.isChecked),
            )
        }

        // Serialize durable saves independently of the dialog lifetime.
        val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val saveMutex = Mutex()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var lastSaved = if (onAutosave != null) result() else null
        var lastFailed: CommentEditorResult? = null
        fun savePending() {
            val save = onAutosave ?: return
            val snapshot = result() ?: return
            saveScope.launch {
                saveMutex.withLock {
                    if (snapshot == lastSaved) return@withLock
                    val success = runCatching { save(snapshot) }.getOrDefault(false)
                    if (success) lastSaved = snapshot
                    // Report a failing value once instead of on every periodic retry.
                    else if (snapshot != lastFailed) android.widget.Toast.makeText(activity.applicationContext,
                        R.string.overview_edit_failed, android.widget.Toast.LENGTH_LONG).show()
                    lastFailed = if (success) null else snapshot
                }
            }
        }
        val pendingSave = Runnable { savePending() }
        val periodicSave = object : Runnable {
            override fun run() {
                savePending()
                handler.postDelayed(this, 2000)
            }
        }
        fun closeAfterSaving() {
            if (onAutosave == null) { dialog.dismiss(); return }
            handler.removeCallbacks(pendingSave)
            savePending()
            saveScope.launch {
                saveMutex.withLock {
                    // A note that cannot be saved (for example, deleted elsewhere) must still be closable.
                    if (lastSaved == result()) dialog.dismiss() else confirmDiscard()
                }
            }
        }
        val lifecycle = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) savePending()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                // A recreated activity drops the window without dismissing, so stop the periodic save here.
                handler.removeCallbacks(pendingSave)
                handler.removeCallbacks(periodicSave)
            }
        }
        // A stray tap outside or Back must not throw away typed text.
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, key, event ->
            if (key == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.action == android.view.KeyEvent.ACTION_UP) {
                    if (onAutosave != null) closeAfterSaving()
                    else if (unvalidatedValue() != openedValue) confirmDiscard()
                    else dialog.dismiss()
                }
                true
            } else false
        }
        if (onAutosave != null) {
            handler.postDelayed(periodicSave, 2000)
            lifecycle?.addObserver(observer)
        }
        changed = {
            if (onAutosave != null) {
                handler.removeCallbacks(pendingSave)
                handler.postDelayed(pendingSave, 300)
            }
        }
        if (onAutosave != null) {
            textInput.doAfterTextChanged { changed() }
            textInput.setOnFocusChangeListener { _, focused -> if (!focused) savePending() }
        }

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            content.findViewById<MaterialButton>(R.id.commentEditorSave).setOnClickListener {
                if (onAutosave != null) closeAfterSaving()
                else result()?.let { if (onSave(it)) dialog.dismiss() }
            }
            if (onAutosave != null) {
                content.findViewById<MaterialButton>(R.id.commentEditorSave).setText(R.string.close)
                content.findViewById<MaterialButton>(R.id.commentEditorCancel).visibility = View.GONE
            }
            content.findViewById<MaterialButton>(R.id.commentEditorCancel).setOnClickListener { dialog.dismiss() }
            dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            val neutral = config.neutralAction
            neutralButton.apply {
                neutral?.let { setText(it.labelRes) }
                setOnClickListener {
                    result()?.let { if (neutral?.onSave(it) == true) dialog.dismiss() }
                }
            }
            if (config.focusText) {
                textInput.requestFocus()
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                )
            }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(pendingSave)
            handler.removeCallbacks(periodicSave)
            lifecycle?.removeObserver(observer)
            savePending()
            saveScope.launch { saveMutex.withLock { } ; saveScope.cancel() }
            onDismiss()
        }
        locationRemove.setOnClickListener {
            selectedLocation = null
            renderLocation()
            changed()
        }
        dialog.show()
        return dialog
    }

    private fun priorityLabel(priority: CommentPriority): String = activity.getString(
        when (priority) {
            CommentPriority.NORMAL -> R.string.overview_priority_normal
            CommentPriority.HIGH -> R.string.overview_priority_high
        },
    )

    private fun locationSummary(location: CommentLocation): String = activity.getString(
        R.string.overview_location_detail,
        location.label,
        location.latitude,
        location.longitude,
    )

    private fun colorLabel(color: Int): String = HighlightPalette.fromColor(color)?.let {
        activity.getString(it.labelRes)
    } ?: if ((color and 0x00FFFFFF) == (Color.YELLOW and 0x00FFFFFF)) {
        activity.getString(R.string.color_yellow)
    } else {
        "${activity.getString(R.string.comment_color)} #${String.format("%08X", color)}"
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}

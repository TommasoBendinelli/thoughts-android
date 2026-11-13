// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.settings

import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentAuthor
import android.content.DialogInterface
import android.net.Uri
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesReceiving
import com.gitlab.mudlej.MjPdfReader.data.glasses.GlassesCamera
import com.gitlab.mudlej.MjPdfReader.data.speech.OpenRouterKeyStore
import com.gitlab.mudlej.MjPdfReader.data.speech.ReadAloudMessages
import com.gitlab.mudlej.MjPdfReader.data.speech.ReadAloudSession
import com.gitlab.mudlej.MjPdfReader.data.translation.DictionaryInstaller
import com.gitlab.mudlej.MjPdfReader.data.translation.DictionaryStore
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationEngine
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationLanguages
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationUrlBuilder
import com.gitlab.mudlej.MjPdfReader.core.PermissionManager
import com.gitlab.mudlej.MjPdfReader.data.source.SourceWorkingLocation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceWorkingMode
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerAddress
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.ui.reader.AgentPromptTemplates
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeProgressStyle
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTitleEllipsize
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ConfigurableAction
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.PdfThemeController
import com.gitlab.mudlej.MjPdfReader.core.ui.SegmentedButtonStyler
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tbend.thoughts.sdk.SyncClient
import kotlin.math.roundToInt

internal class SettingsPreferenceFactory(
    private val fragment: PreferenceFragmentCompat,
    private val appPreferences: Preferences,
) {
    private val context get() = fragment.requireContext()
    private val allEntries by lazy {
        SettingsEntryProvider(appPreferences).entries()
    }

    fun entries(): List<SettingEntry> = allEntries

    fun entriesFor(page: SettingsPage): List<SettingEntry> {
        return allEntries.filter { it.page == page }
    }

    fun navigationPreference(page: SettingsPage, onSelected: (SettingsPage) -> Unit): Preference {
        return Preference(context).apply {
            title = getString(page.titleRes)
            summary = getString(page.summaryRes)
            setIcon(page.iconRes)
            widgetLayoutResource = R.layout.preference_widget_chevron
            isIconSpaceReserved = true
            setOnPreferenceClickListener {
                onSelected(page)
                true
            }
        }
    }

    fun noSearchResultsPreference(): Preference {
        return Preference(context).apply {
            title = getString(R.string.settings_search_no_results)
            isSelectable = false
            isIconSpaceReserved = false
        }
    }

    fun thoughtsServerPreference(breadcrumb: String?): Preference {
        val secretStore = ThoughtsServerSecretStore(context)
        val configured = secretStore.config()
        return Preference(context).apply {
            title = getString(R.string.thoughts_server_title)
            summary = formatSummary(
                breadcrumb,
                configured?.baseUrl ?: getString(R.string.thoughts_server_not_configured),
            )
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showThoughtsServerDialog(secretStore)
                true
            }
        }
    }

    /**
     * "Keep sources in a shared folder". Not persisted by the preference: the switch shows where the
     * working copy is, and changes only after the copy was moved.
     */
    fun sharedSourceFolderPreference(breadcrumb: String?): Preference = SwitchPreferenceCompat(context).apply {
        key = "source_shared_folder"
        isPersistent = false
        title = getString(R.string.source_shared_folder_title)
        summary = formatSummary(breadcrumb, getString(R.string.source_shared_folder_summary))
        isChecked = SourceWorkingLocation.mode(context) == SourceWorkingMode.SHARED
        isIconSpaceReserved = false
        setOnPreferenceChangeListener { _, newValue ->
            confirmSourceFolderMove(if (newValue == true) SourceWorkingMode.SHARED else SourceWorkingMode.PRIVATE)
            false
        }
    }

    private fun confirmSourceFolderMove(target: SourceWorkingMode) {
        if (target == SourceWorkingMode.SHARED && !PermissionManager.hasFullAccess(context)) {
            Toast.makeText(context, R.string.source_shared_folder_needs_access, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.source_shared_folder_title)
            .setMessage(
                if (target == SourceWorkingMode.SHARED) R.string.source_shared_folder_turn_on_message
                else R.string.source_shared_folder_turn_off_message,
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.source_shared_folder_move) { _, _ -> moveSourceFolder(target) }
            .show()
    }

    private fun moveSourceFolder(target: SourceWorkingMode) {
        val appContext = context.applicationContext
        val progress = MaterialAlertDialogBuilder(context)
            .setMessage(R.string.source_shared_folder_moving)
            .setCancelable(false)
            .show()
        fragment.lifecycleScope.launch {
            // Null: a Sync is running or waiting, and it reads and writes the working copy.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (SyncOperationStore(appContext).pendingOperations().isNotEmpty()) null
                    else SourceWorkingLocation.switchTo(appContext, target)
                }.getOrElse { SourceWorkingLocation.SwitchResult.Failed(it.message ?: it.javaClass.simpleName) }
            }
            if (!fragment.isAdded) return@launch
            progress.dismiss()
            val message = when (result) {
                null -> getString(R.string.source_shared_folder_sync_running)
                is SourceWorkingLocation.SwitchResult.Moved ->
                    if (result.leftBehind.isEmpty()) getString(R.string.source_shared_folder_moved)
                    else context.getString(R.string.source_shared_folder_left_behind, result.leftBehind.size)
                is SourceWorkingLocation.SwitchResult.OutsideChange ->
                    context.getString(R.string.source_shared_folder_outside_change, result.sourceFile)
                is SourceWorkingLocation.SwitchResult.Failed -> context.getString(R.string.source_shared_folder_failed, result.reason)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            (fragment as? SettingsFragment)?.refreshPreferences()
        }
    }

    fun commentAuthorPreference(breadcrumb: String?): Preference = Preference(context).apply {
        key = "thoughts_comment_author"
        title = getString(R.string.comment_author_title)
        summary = formatSummary(breadcrumb, CommentAuthor.current(context))
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
            showCommentAuthorDialog()
            true
        }
    }

    private fun showCommentAuthorDialog() {
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(CommentAuthor.saved(context).orEmpty())
            setSingleLine(true)
        }
        val layout = TextInputLayout(context).apply {
            hint = getString(R.string.comment_author_hint)
            placeholderText = CommentAuthor.current(context)
            addView(input)
        }
        val horizontalPadding = (24 * context.resources.displayMetrics.density).roundToInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(layout)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.comment_author_title)
            .setMessage(R.string.comment_author_message)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                CommentAuthor.save(context, input.text?.toString())
                (fragment as? SettingsFragment)?.refreshPreferences()
            }
            .show()
    }

    fun syncBatteryPreference(breadcrumb: String?): Preference = Preference(context).apply {
        key = "thoughts_sync_battery"
        title = getString(R.string.sync_battery_settings_title)
        summary = formatSummary(breadcrumb, com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatterySetup.statusText(context))
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
            com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatterySetup.showExplanation(
                this@SettingsPreferenceFactory.fragment.requireActivity() as androidx.appcompat.app.AppCompatActivity,
            )
            true
        }
    }

    fun synchronizationThresholdPreference(breadcrumb: String?): Preference {
        val megabytes = appPreferences.getSynchronizationThresholdMegabytes()
        return Preference(context).apply {
            title = getString(R.string.synchronization_threshold_title)
            key = Preferences.synchronizationThresholdMegabytesKey
            summary = formatSummary(
                breadcrumb,
                this@SettingsPreferenceFactory.fragment.getString(
                    R.string.synchronization_threshold_value,
                    Formatter.formatShortFileSize(context, megabytes * 1_000_000L),
                ),
            )
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSynchronizationThresholdDialog()
                true
            }
        }
    }

    fun agentPromptTemplatePreference(kind: AgentPromptTemplates.Kind, breadcrumb: String?): Preference {
        val custom = appPreferences.getAgentPromptTemplate(kind) != AgentPromptTemplates.defaultTemplate(kind)
        return Preference(context).apply {
            key = agentPromptPreferenceKey(kind)
            title = getString(agentPromptTitle(kind))
            summary = formatSummary(breadcrumb, getString(if (custom) R.string.agent_prompt_custom else R.string.agent_prompt_default))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showAgentPromptTemplateDialog(kind)
                true
            }
        }
    }

    /** Per phone (its own preferences file), so it is read and written here, not persisted by the preference. */
    fun receiveAgentNotesPreference(breadcrumb: String?): Preference = SwitchPreferenceCompat(context).apply {
        fun summaryFor(on: Boolean) = formatSummary(
            breadcrumb,
            getString(if (on) R.string.agent_notes_receive_summary_on else R.string.agent_notes_receive_summary_off),
        )
        val on = AgentNotesReceiving.isOn(context)
        key = "agent_notes_receive_on_this_phone"
        isPersistent = false
        title = getString(R.string.agent_notes_receive_title)
        isChecked = on
        summary = summaryFor(on)
        isIconSpaceReserved = false
        setOnPreferenceChangeListener { preference, newValue ->
            val checked = newValue == true
            AgentNotesReceiving.set(context, checked)
            preference.summary = summaryFor(checked)
            true
        }
    }

    fun readAloudKeyPreference(breadcrumb: String?): Preference = Preference(context).apply {
        val saved = OpenRouterKeyStore(context).key()
        title = getString(R.string.read_aloud_key_title)
        summary = formatSummary(
            breadcrumb,
            saved?.let { context.getString(R.string.read_aloud_key_saved, OpenRouterKeyStore.ending(it)) }
                ?: getString(R.string.read_aloud_key_not_set),
        )
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
            showReadAloudKeyDialog()
            true
        }
    }

    fun readAloudTestPreference(breadcrumb: String?): Preference = Preference(context).apply {
        title = getString(R.string.read_aloud_test_title)
        summary = formatSummary(breadcrumb, getString(R.string.read_aloud_test_summary))
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
            val appContext = context.applicationContext
            val started = ReadAloudSession.get(appContext).start(
                getString(R.string.read_aloud_test_sentence),
                owner = this@SettingsPreferenceFactory.fragment.requireActivity(),
                readCache = false,
            ) { outcome ->
                Toast.makeText(appContext, ReadAloudMessages.testOutcome(appContext, outcome), Toast.LENGTH_LONG).show()
            }
            if (!started) Toast.makeText(context, R.string.read_aloud_unavailable, Toast.LENGTH_SHORT).show()
            true
        }
    }

    /** Masked field; Save encrypts the key with the Android Keystore, Remove deletes it. */
    private fun showReadAloudKeyDialog() {
        val store = OpenRouterKeyStore(context)
        val saved = store.key()
        val input = TextInputEditText(context).apply {
            id = R.id.readAloudKeyEditor
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            // Keep password managers and the keyboard's clipboard suggestions away from the key.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            isSaveEnabled = false
        }
        val layout = TextInputLayout(context).apply {
            hint = getString(R.string.read_aloud_key_title)
            if (saved != null) {
                helperText = context.getString(R.string.read_aloud_key_keep_hint, OpenRouterKeyStore.ending(saved))
            }
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(input)
        }
        val padding = dp(24)
        val container = LinearLayout(context).apply {
            setPadding(padding, 0, padding, 0)
            addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.read_aloud_key_title)
            .setMessage(R.string.read_aloud_key_dialog_message)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (saved != null) {
                    setNeutralButton(R.string.read_aloud_key_remove) { _, _ ->
                        store.remove()
                        Toast.makeText(context, R.string.read_aloud_key_removed_toast, Toast.LENGTH_SHORT).show()
                        (fragment as? SettingsFragment)?.refreshPreferences()
                    }
                }
            }
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            // Validates before closing, so a mistyped key can be corrected in place.
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val result = runCatching { store.save(input.text?.toString().orEmpty()) }
                val error = result.exceptionOrNull()
                if (error != null) {
                    layout.error = (error as? IllegalArgumentException)?.message
                        ?: getString(R.string.read_aloud_unavailable)
                    return@setOnClickListener
                }
                input.text?.clear()
                dialog.dismiss()
                Toast.makeText(context, R.string.read_aloud_key_saved_toast, Toast.LENGTH_SHORT).show()
                (fragment as? SettingsFragment)?.refreshPreferences()
            }
        }
        dialog.show()
    }

    fun mentraPhotoFolderPreference(breadcrumb: String?): Preference = Preference(context).apply {
        key = Preferences.mentraPhotoFolderKey
        title = getString(R.string.agent_prompt_mentra_folder)
        summary = formatSummary(breadcrumb, appPreferences.getMentraPhotoFolder())
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
            showMentraPhotoFolderDialog()
            true
        }
    }

    private fun agentPromptTitle(kind: AgentPromptTemplates.Kind) = when (kind) {
        AgentPromptTemplates.Kind.AGENT -> R.string.copy_document_for_agent
        AgentPromptTemplates.Kind.STUDY_MODE -> R.string.agent_prompt_study_mode
    }

    private fun agentPromptPreferenceKey(kind: AgentPromptTemplates.Kind) = when (kind) {
        AgentPromptTemplates.Kind.AGENT -> Preferences.agentPromptTemplateKey
        AgentPromptTemplates.Kind.STUDY_MODE -> Preferences.studyModePromptTemplateKey
    }

    /**
     * A multi-line editor for the template, the placeholders (a tap inserts one at the cursor) and
     * the rules. Reset to default puts the default text in the editor; Save keeps it.
     */
    private fun showAgentPromptTemplateDialog(kind: AgentPromptTemplates.Kind) {
        val padding = dp(24)
        val input = TextInputEditText(context).apply {
            id = R.id.agentPromptTemplateEditor
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            minLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setText(appPreferences.getAgentPromptTemplate(kind))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(8), padding, 0)
            addView(TextInputLayout(context).apply { addView(input) })
            addView(TextView(context).apply {
                text = getString(R.string.agent_prompt_placeholders)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(4))
            })
            AgentPromptTemplates.placeholders(kind).forEach { (name, description) ->
                addView(TextView(context).apply {
                    tag = "agentPromptPlaceholder:$name"
                    text = android.text.SpannableStringBuilder().apply {
                        val start = length
                        append("{$name}")
                        setSpan(android.text.style.TypefaceSpan("monospace"), start, length, 0)
                        append("  ")
                        append(description)
                    }
                    setPadding(0, dp(6), 0, dp(6))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val editable = input.text ?: return@setOnClickListener
                        val start = input.selectionStart.coerceAtLeast(0)
                        val end = input.selectionEnd.coerceAtLeast(start)
                        editable.replace(start, end, "{$name}")
                        input.requestFocus()
                    }
                })
            }
            addView(TextView(context).apply {
                text = getString(R.string.agent_prompt_rules)
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(agentPromptTitle(kind))
            .setView(android.widget.ScrollView(context).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.agent_prompt_reset_default, null)
            .setPositiveButton(R.string.save) { _, _ ->
                appPreferences.setAgentPromptTemplate(kind, input.text?.toString().orEmpty())
                (fragment as? SettingsFragment)?.refreshPreferences()
            }
            .create()
        dialog.setOnShowListener {
            // Puts the default in the editor and keeps the dialog open, like the desktop's button.
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                input.setText(AgentPromptTemplates.defaultTemplate(kind))
            }
        }
        dialog.show()
    }

    fun glassesCameraPreference(breadcrumb: String?): Preference = Preference(context).apply {
        key = Preferences.glassesCameraUrlKey
        title = getString(R.string.glasses_camera_title)
        summary = formatSummary(breadcrumb, appPreferences.getGlassesCameraUrl() ?: getString(R.string.glasses_camera_not_set))
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
            showGlassesCameraDialog()
            true
        }
    }

    private fun showGlassesCameraDialog() {
        val input = TextInputEditText(context).apply {
            id = R.id.glassesCameraUrlEditor
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            hint = getString(R.string.glasses_camera_hint)
            setText(appPreferences.getGlassesCameraUrl().orEmpty())
        }
        val padding = dp(24)
        val container = LinearLayout(context).apply {
            setPadding(padding, 0, padding, 0)
            addView(TextInputLayout(context).apply { addView(input) }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.glasses_camera_title)
            .setMessage(R.string.glasses_camera_dialog_message)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val typed = input.text?.toString().orEmpty()
                val url = GlassesCamera.normalizedBaseUrl(typed)
                if (typed.isNotBlank() && url == null) {
                    Toast.makeText(context, R.string.glasses_camera_invalid, Toast.LENGTH_LONG).show()
                } else {
                    appPreferences.setGlassesCameraUrl(url)
                    (fragment as? SettingsFragment)?.refreshPreferences()
                }
            }
            .show()
    }

    private fun showMentraPhotoFolderDialog() {
        val input = TextInputEditText(context).apply {
            id = R.id.agentPromptMentraFolderEditor
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(appPreferences.getMentraPhotoFolder())
        }
        val padding = dp(24)
        val container = LinearLayout(context).apply {
            setPadding(padding, 0, padding, 0)
            addView(TextInputLayout(context).apply { addView(input) }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.agent_prompt_mentra_folder)
            .setMessage(R.string.agent_prompt_mentra_folder_help)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.agent_prompt_reset_default, null)
            .setPositiveButton(R.string.save) { _, _ ->
                appPreferences.setMentraPhotoFolder(input.text?.toString().orEmpty())
                (fragment as? SettingsFragment)?.refreshPreferences()
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                input.setText(AgentPromptTemplates.DEFAULT_MENTRA_FOLDER)
            }
        }
        dialog.show()
    }

    private fun showSynchronizationThresholdDialog() {
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(appPreferences.getSynchronizationThresholdMegabytes().toString())
            selectAll()
        }
        val layout = TextInputLayout(context).apply {
            hint = getString(R.string.synchronization_threshold_megabytes_hint)
            addView(input)
        }
        val horizontalPadding = (24 * context.resources.displayMetrics.density).roundToInt()
        val container = LinearLayout(context).apply {
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(layout)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.synchronization_threshold_title)
            .setMessage(R.string.synchronization_threshold_dialog_message)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset) { _, _ ->
                appPreferences.setSynchronizationThresholdMegabytes(
                    Preferences.synchronizationThresholdMegabytesDefault,
                )
                (fragment as? SettingsFragment)?.refreshPreferences()
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val value = input.text?.toString()?.toIntOrNull()
                if (
                    value == null ||
                    value !in Preferences.synchronizationThresholdMegabytesMin..
                        Preferences.synchronizationThresholdMegabytesMax
                ) {
                    Toast.makeText(
                        context,
                        this@SettingsPreferenceFactory.fragment.getString(
                            R.string.synchronization_threshold_invalid,
                            Preferences.synchronizationThresholdMegabytesMin,
                            Preferences.synchronizationThresholdMegabytesMax,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    appPreferences.setSynchronizationThresholdMegabytes(value)
                    (fragment as? SettingsFragment)?.refreshPreferences()
                }
            }
            .show()
    }

    private fun showThoughtsServerDialog(secretStore: ThoughtsServerSecretStore) {
        val configured = secretStore.config()
        val serverInput = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // A new install starts blank: each person runs their own Thoughts server. An address
            // saved without its token (it was changed to another server) is shown again.
            setText(ThoughtsServerAddress.dialogPrefill(configured?.baseUrl ?: secretStore.baseUrl()))
            setSingleLine(true)
        }
        val tokenInput = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val serverLayout = TextInputLayout(context).apply {
            hint = getString(R.string.thoughts_server_url_hint)
            placeholderText = ThoughtsServerAddress.EXAMPLE_BASE_URL
            addView(serverInput)
        }
        val tokenLayout = TextInputLayout(context).apply {
            hint = getString(R.string.thoughts_server_token_hint)
            if (configured != null) helperText = getString(R.string.thoughts_server_token_keep_hint)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(tokenInput)
        }
        val horizontalPadding = (24 * context.resources.displayMetrics.density).roundToInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(serverLayout)
            addView(tokenLayout)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.thoughts_server_title)
            .setMessage(R.string.thoughts_server_dialog_message)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (configured != null) {
                    setNeutralButton(R.string.thoughts_server_clear) { _, _ ->
                        secretStore.clear()
                        (fragment as? SettingsFragment)?.refreshPreferences()
                    }
                }
            }
            .setPositiveButton(R.string.thoughts_server_save_test) { _, _ ->
                val server = serverInput.text?.toString().orEmpty()
                // A blank token keeps the saved one while the address stays on the same server
                // (another path, a trailing slash). The saved token is never sent to another
                // server: for a new address it is removed and asked for again.
                val token = ThoughtsServerAddress.tokenForAddress(
                    tokenInput.text?.toString(), configured?.token, configured?.baseUrl, server,
                )
                if (token == null && configured != null) {
                    val moved = runCatching { secretStore.setAddressAwaitingToken(server) }
                    Toast.makeText(
                        context,
                        moved.exceptionOrNull()?.let { it.message ?: getString(R.string.thoughts_server_connection_failed) }
                            ?: getString(R.string.thoughts_server_token_needed),
                        Toast.LENGTH_LONG,
                    ).show()
                    if (moved.isSuccess) {
                        (fragment as? SettingsFragment)?.refreshPreferences()
                        showThoughtsServerDialog(secretStore)
                    }
                    return@setPositiveButton
                }
                val saved = runCatching { secretStore.set(server, token.orEmpty()) }
                if (saved.isFailure) {
                    Toast.makeText(
                        context,
                        saved.exceptionOrNull()?.message ?: getString(R.string.thoughts_server_connection_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setPositiveButton
                }
                fragment.lifecycleScope.launch {
                    val connected = withContext(Dispatchers.IO) {
                        runCatching {
                            val config = checkNotNull(secretStore.config())
                            SyncClient(
                                config.baseUrl, config.token,
                                connectionFactory = com.gitlab.mudlej.MjPdfReader.data.sync.TokenConnections.open,
                            ).library()
                        }.isSuccess
                    }
                    if (fragment.isAdded) {
                        Toast.makeText(
                            context,
                            if (connected) {
                                ThoughtsSyncScheduler.enqueue(context)
                                R.string.thoughts_server_connected
                            } else {
                                R.string.thoughts_server_connection_failed
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                        (fragment as? SettingsFragment)?.refreshPreferences()
                    }
                }
            }
            .show()
    }

    fun interfaceThemePreference(breadcrumb: String?): Preference {
        return ThemeChoicePreference(
            context = context,
            titleText = formatSummary(breadcrumb, getString(R.string.dark_theme_for_app)) ?: getString(R.string.dark_theme_for_app),
            initialSelectedMode = appPreferences.getInterfaceTheme(),
        ) { mode ->
            appPreferences.setInterfaceTheme(mode)
            setDefaultNightMode(PdfThemeController.interfaceNightMode(appPreferences))
        }
    }

    fun pdfPagesThemePreference(breadcrumb: String?): Preference {
        return ThemeChoicePreference(
            context = context,
            titleText = formatSummary(breadcrumb, getString(R.string.dark_theme_for_pdf)) ?: getString(R.string.dark_theme_for_pdf),
            initialSelectedMode = appPreferences.getPdfPagesTheme(),
        ) { mode ->
            appPreferences.setPdfPagesTheme(mode)
        }
    }

    private val titleLineOptions = listOf(1, 2, 3, 4, 5)

    private fun progressStyleLabel(style: HomeProgressStyle): String {
        return when (style) {
            HomeProgressStyle.RING -> getString(R.string.home_progress_style_ring)
            HomeProgressStyle.BAR -> getString(R.string.home_progress_style_bar)
        }
    }

    fun homeProgressStylePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val styles = HomeProgressStyle.entries
        return Preference(context).apply {
            title = getString(R.string.home_progress_style_title)
            key = Preferences.homeProgressStyleKey
            summary = formatSummary(breadcrumb, progressStyleLabel(appPreferences.getHomeProgressStyle()))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.home_progress_style_title),
                    items = styles.map { progressStyleLabel(it) },
                    checkedIndex = styles.indexOf(appPreferences.getHomeProgressStyle()),
                    onReset = {
                        appPreferences.setHomeProgressStyle(HomeProgressStyle.valueOf(Preferences.homeProgressStyleDefault))
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setHomeProgressStyle(styles[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun homeTitleEllipsizePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val options = HomeTitleEllipsize.entries
        return Preference(context).apply {
            title = getString(R.string.home_title_ellipsize_title)
            key = Preferences.homeTitleEllipsizeKey
            summary = formatSummary(breadcrumb, getString(appPreferences.getHomeTitleEllipsize().labelRes))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.home_title_ellipsize_title),
                    items = options.map { getString(it.labelRes) },
                    checkedIndex = options.indexOf(appPreferences.getHomeTitleEllipsize()),
                    onReset = {
                        appPreferences.setHomeTitleEllipsize(HomeTitleEllipsize.valueOf(Preferences.homeTitleEllipsizeDefault))
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setHomeTitleEllipsize(options[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun homeListTitleLinesPreference(breadcrumb: String?): Preference {
        return titleLinesPreference(
            titleRes = R.string.home_list_title_lines_title,
            key = Preferences.homeListTitleLinesKey,
            currentValue = appPreferences.getHomeListTitleLines(),
            defaultValue = Preferences.homeListTitleLinesDefault,
            breadcrumb = breadcrumb,
            onSelected = appPreferences::setHomeListTitleLines,
        )
    }

    private fun titleLinesPreference(
        @StringRes titleRes: Int,
        key: String,
        currentValue: Int,
        defaultValue: Int,
        breadcrumb: String?,
        onSelected: (Int) -> Unit,
    ): Preference {
        val host = fragment as? SettingsFragment
        val labels = titleLineOptions.map {
            context.resources.getQuantityString(R.plurals.home_title_lines, it, it)
        }
        return Preference(context).apply {
            title = getString(titleRes)
            this.key = key
            summary = formatSummary(breadcrumb, labels.getOrNull(titleLineOptions.indexOf(currentValue)))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(titleRes),
                    items = labels,
                    checkedIndex = titleLineOptions.indexOf(currentValue),
                    onReset = {
                        onSelected(defaultValue)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    onSelected(titleLineOptions[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun switchPreference(
        @StringRes titleRes: Int,
        key: String,
        defaultValue: Boolean,
        @StringRes summaryRes: Int?,
        breadcrumb: String?,
    ): SwitchPreferenceCompat {
        return SwitchPreferenceCompat(context).apply {
            title = getString(titleRes)
            this.key = key
            setDefaultValue(defaultValue)
            summary = formatSummary(breadcrumb, summaryRes?.let(::getString))
            isIconSpaceReserved = false
        }
    }

    fun actionPreference(
        @StringRes titleRes: Int,
        key: String,
        defaultValue: String,
        currentValue: String,
        actions: List<ConfigurableAction>,
        breadcrumb: String?,
        onActionSelected: (String) -> Unit,
    ): Preference {
        val resetActionId = defaultValue.takeIf { value -> actions.any { it.id == value } }
            ?: actions.first().id
        var selectedActionId = currentValue.takeIf { value -> actions.any { it.id == value } }
            ?: resetActionId

        return Preference(context).apply {
            title = getString(titleRes)
            this.key = key
            updateActionSummary(selectedActionId, breadcrumb)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showActionPreferenceDialog(
                    title = getString(titleRes),
                    actions = actions,
                    currentValue = selectedActionId,
                    resetValue = resetActionId,
                ) { actionId ->
                    selectedActionId = actionId
                    onActionSelected(actionId)
                    updateActionSummary(actionId, breadcrumb)
                }
                true
            }
        }
    }

    fun fullScreenButtonsPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.fullscreen_buttons)
            key = Preferences.fullScreenOverlayActionsKey
            summary = formatSummary(breadcrumb, getString(R.string.fullscreen_buttons_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showFullScreenButtonsPreferenceDialog(context, appPreferences) {}
                true
            }
        }
    }

    fun listCardBadgesPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.home_list_badges_title)
            key = Preferences.homeBadgePagesKey
            summary = formatSummary(breadcrumb, getString(R.string.home_list_badges_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showListCardBadgesPreferenceDialog(context, appPreferences)
                true
            }
        }
    }

    fun scrollingInfoCardPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.scrolling_info_card)
            key = Preferences.fullScreenInfoShowPageNumberKey
            summary = formatSummary(breadcrumb, getString(R.string.scrolling_info_card_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showScrollingInfoCardPreferenceDialog(context, appPreferences)
                true
            }
        }
    }

    fun shortcutBarButtonsPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.shortcut_bar_buttons)
            key = Preferences.shortcutBarActionsKey
            summary = formatSummary(breadcrumb, getString(R.string.shortcut_bar_buttons_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showShortcutBarButtonsPreferenceDialog(context, appPreferences) {}
                true
            }
        }
    }

    fun highlightColorsPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.highlight_colors)
            key = Preferences.highlightColorsKey
            summary = formatSummary(breadcrumb, getString(R.string.highlight_colors_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showHighlightColorsPreferenceDialog(context, appPreferences) {}
                true
            }
        }
    }

    fun translationModePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val modes = listOf(
            Preferences.translationModeApps to R.string.translation_mode_installed_apps,
            Preferences.translationModeWeb to R.string.translation_mode_web_translator,
        )
        val currentMode = appPreferences.getTranslationMode()
        val detailRes = modes.firstOrNull { it.first == currentMode }?.second
            ?: R.string.translation_mode_installed_apps
        return Preference(context).apply {
            title = getString(R.string.translate_with_title)
            key = Preferences.translationModeKey
            summary = formatSummary(breadcrumb, getString(detailRes))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.translate_with_title),
                    items = modes.map { getString(it.second) },
                    checkedIndex = modes.indexOfFirst { it.first == appPreferences.getTranslationMode() },
                    onReset = {
                        appPreferences.setTranslationMode(Preferences.translationModeDefault)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setTranslationMode(modes[index].first)
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    private fun engineLabel(engine: TranslationEngine): String {
        return if (engine.unstable) {
            fragment.getString(R.string.translation_engine_unstable, getString(engine.titleRes))
        } else {
            getString(engine.titleRes)
        }
    }

    fun translationEnginePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val engines = TranslationEngine.entries
        val currentEngine = TranslationEngine.fromId(appPreferences.getTranslationEngine())
        val detail = buildString {
            append(engineLabel(currentEngine))
            if (currentEngine == TranslationEngine.CUSTOM) {
                val templateHost = Uri.parse(appPreferences.getTranslationCustomUrl()).host
                if (!templateHost.isNullOrBlank()) {
                    append(" (").append(templateHost).append(")")
                }
            }
        }
        return Preference(context).apply {
            title = getString(R.string.translation_engine_title)
            key = Preferences.translationEngineKey
            summary = formatSummary(breadcrumb, detail)
            isEnabled = appPreferences.getTranslationMode() == Preferences.translationModeWeb
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.translation_engine_title),
                    items = engines.map { engineLabel(it) },
                    checkedIndex = engines.indexOf(TranslationEngine.fromId(appPreferences.getTranslationEngine())),
                    onReset = {
                        appPreferences.setTranslationEngine(Preferences.translationEngineDefault)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    val engine = engines[index]
                    if (engine == TranslationEngine.CUSTOM) {
                        showCustomTranslationUrlDialog(host)
                    } else {
                        appPreferences.setTranslationEngine(engine.id)
                        host?.refreshPreferences()
                    }
                }
                true
            }
        }
    }

    fun translationTargetLanguagePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val codes = TranslationLanguages.codes.sortedBy { TranslationLanguages.displayName(it) }
        val stored = appPreferences.getTranslationTargetLanguage()
        val deviceLabel = fragment.getString(
            R.string.translation_device_language,
            TranslationLanguages.displayName(TranslationLanguages.deviceLanguage()),
        )
        val detail = if (stored.isBlank()) deviceLabel else TranslationLanguages.displayName(stored)
        return Preference(context).apply {
            title = getString(R.string.translation_target_language_title)
            key = Preferences.translationTargetLanguageKey
            summary = formatSummary(breadcrumb, detail)
            isEnabled = appPreferences.getTranslationMode() == Preferences.translationModeWeb
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                val current = appPreferences.getTranslationTargetLanguage()
                showSingleChoiceDialog(
                    title = getString(R.string.translation_target_language_title),
                    items = listOf(deviceLabel) + codes.map { TranslationLanguages.displayName(it) },
                    checkedIndex = if (current.isBlank()) 0 else codes.indexOf(current) + 1,
                    onReset = {
                        appPreferences.setTranslationTargetLanguage(Preferences.translationTargetLanguageDefault)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setTranslationTargetLanguage(if (index == 0) "" else codes[index - 1])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun dictionaryPreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val installed = DictionaryStore.isInstalled(context)
        val detail = if (installed) {
            fragment.getString(
                R.string.dictionary_summary_installed,
                Formatter.formatShortFileSize(context, DictionaryStore.installedSize(context)),
            )
        } else {
            fragment.getString(
                R.string.dictionary_summary_not_installed,
                Formatter.formatShortFileSize(context, DictionaryInstaller.downloadSizeBytes),
            )
        }
        return Preference(context).apply {
            title = getString(R.string.dictionary_title)
            key = "offlineDictionary"
            summary = formatSummary(breadcrumb, detail)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (DictionaryStore.isInstalled(context)) {
                    host?.startDictionaryDelete()
                } else {
                    host?.startDictionaryInstall()
                }
                true
            }
        }
    }

    fun dictionaryDefineWordsPreference(breadcrumb: String?): Preference {
        return SwitchPreferenceCompat(context).apply {
            title = getString(R.string.dictionary_define_words_title)
            key = Preferences.dictionaryDefineWordsKey
            setDefaultValue(Preferences.dictionaryDefineWordsDefault)
            summary = formatSummary(breadcrumb, getString(R.string.dictionary_define_words_summary))
            isEnabled = DictionaryStore.isInstalled(context)
            isIconSpaceReserved = false
        }
    }

    private fun showSingleChoiceDialog(
        title: String,
        items: List<String>,
        checkedIndex: Int,
        onReset: () -> Unit,
        onSelected: (Int) -> Unit,
    ) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), checkedIndex) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                onReset()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showCustomTranslationUrlDialog(host: SettingsFragment?) {
        val inputLayout = fragment.layoutInflater.inflate(R.layout.input_layout, null) as TextInputLayout
        inputLayout.hint = getString(R.string.translation_custom_url_hint)
        inputLayout.setStartIconDrawable(R.drawable.ic_link)
        inputLayout.editText?.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(appPreferences.getTranslationCustomUrl())
            setSelection(text?.length ?: 0)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.translation_engine_custom)
            .setView(inputLayout)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = inputLayout.editText?.text?.toString()?.trim().orEmpty()
                val hasScheme = value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true)
                if (!hasScheme || !value.contains(TranslationUrlBuilder.textPlaceholder)) {
                    inputLayout.error = getString(R.string.translation_custom_url_invalid)
                    return@setOnClickListener
                }
                appPreferences.setTranslationCustomUrl(value)
                appPreferences.setTranslationEngine(TranslationEngine.CUSTOM.id)
                dialog.dismiss()
                host?.refreshPreferences()
            }
        }
        dialog.show()
    }

    fun floatPreference(
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int,
        key: String,
        currentValue: Float,
        defaultValue: Float,
        minValue: Float,
        maxValue: Float,
        breadcrumb: String?,
        onValueSelected: (Float) -> Unit,
    ): Preference {
        return Preference(context).apply {
            title = getString(titleRes)
            this.key = key
            updateFloatPreferenceSummary(
                breadcrumb = breadcrumb,
                summaryRes = summaryRes,
                value = currentValue,
            )
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showFloatPreferenceDialog(
                    titleRes = titleRes,
                    currentValue = currentValue,
                    defaultValue = defaultValue,
                    minValue = minValue,
                    maxValue = maxValue,
                ) { value ->
                    onValueSelected(value)
                    updateFloatPreferenceSummary(
                        breadcrumb = breadcrumb,
                        summaryRes = summaryRes,
                        value = value,
                    )
                }
                true
            }
        }
    }

    private fun showActionPreferenceDialog(
        title: String,
        actions: List<ConfigurableAction>,
        currentValue: String,
        resetValue: String,
        onActionSelected: (String) -> Unit,
    ) {
        val checkedIndex = actions.indexOfFirst { it.id == currentValue }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(actions.toEntryTitles(), checkedIndex) { dialog, which ->
                onActionSelected(actions[which].id)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                onActionSelected(resetValue)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showFloatPreferenceDialog(
        @StringRes titleRes: Int,
        currentValue: Float,
        defaultValue: Float,
        minValue: Float,
        maxValue: Float,
        onValueSelected: (Float) -> Unit,
    ) {
        val min = minValue.roundToInt()
        val max = maxValue.roundToInt()
        val valueText = TextView(context).apply {
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        val seekBar = SeekBar(context).apply {
            this.max = max - min
            progress = currentValue.roundToInt().coerceIn(min, max) - min
        }

        fun selectedValue() = min + seekBar.progress

        fun updateValueText() {
            valueText.text = selectedValue().toString()
        }

        updateValueText()
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateValueText()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(
                valueText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                seekBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(layout)
            .setPositiveButton(R.string.apply, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                onValueSelected(selectedValue().toFloat())
                dialog.dismiss()
            }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                onValueSelected(defaultValue)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun getString(@StringRes stringRes: Int): String {
        return fragment.getString(stringRes)
    }

    private fun List<ConfigurableAction>.toEntryTitles(): Array<String> {
        return map { getString(it.titleRes) }.toTypedArray()
    }

    private fun Preference.updateActionSummary(actionId: String, breadcrumb: String?) {
        summary = formatSummary(
            breadcrumb = breadcrumb,
            detail = getString(ConfigurableAction.fromId(actionId).titleRes),
        )
    }

    private fun Preference.updateFloatPreferenceSummary(
        breadcrumb: String?,
        @StringRes summaryRes: Int,
        value: Float,
    ) {
        val currentValue = context.getString(
            R.string.settings_current_value,
            value.roundToInt(),
        )
        val detail = "${getString(summaryRes)}\n$currentValue"
        summary = formatSummary(breadcrumb = breadcrumb, detail = detail)
    }

    private fun formatSummary(breadcrumb: String?, detail: String?): String? {
        return when {
            breadcrumb.isNullOrBlank() -> detail
            detail.isNullOrBlank() -> breadcrumb
            else -> "$breadcrumb: $detail"
        }
    }

    private fun dp(value: Int): Int {
        return (value * fragment.resources.displayMetrics.density).roundToInt()
    }

    private class ThemeChoicePreference(
        context: android.content.Context,
        private val titleText: String,
        initialSelectedMode: String,
        private val onModeSelected: (String) -> Unit,
    ) : Preference(context) {
        private var selectedMode = initialSelectedMode

        init {
            layoutResource = R.layout.preference_theme_choice
            title = titleText
            isSelectable = false
            isIconSpaceReserved = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            (holder.findViewById(R.id.theme_choice_title) as TextView).text = titleText

            val group = holder.findViewById(R.id.theme_choice_group) as MaterialButtonToggleGroup
            group.clearOnButtonCheckedListeners()
            group.check(selectedMode.toButtonId())
            styleSegments(group)
            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                selectedMode = checkedId.toThemeMode()
                styleSegments(group)
                onModeSelected(selectedMode)
            }
        }

        private fun styleSegments(group: MaterialButtonToggleGroup) {
            SegmentedButtonStyler.style(group)
        }

        private fun String.toButtonId(): Int {
            return when (this) {
                Preferences.themeDark -> R.id.theme_choice_dark
                Preferences.themeLight -> R.id.theme_choice_light
                else -> R.id.theme_choice_system
            }
        }

        private fun Int.toThemeMode(): String {
            return when (this) {
                R.id.theme_choice_dark -> Preferences.themeDark
                R.id.theme_choice_light -> Preferences.themeLight
                else -> Preferences.themeSystem
            }
        }
    }
}

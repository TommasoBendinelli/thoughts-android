// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.settings

import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.translation.DictionaryInstaller
import com.gitlab.mudlej.MjPdfReader.data.translation.DictionaryStore
import com.gitlab.mudlej.MjPdfReader.core.ui.confirmDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onResume() {
        super.onResume()
        if (::preferenceFactory.isInitialized) refreshPreferences()
    }

    interface Navigation {
        fun onSettingsPageSelected(page: SettingsPage)
    }

    private var navigation: Navigation? = null
    private lateinit var preferenceFactory: SettingsPreferenceFactory
    private lateinit var appPreferences: Preferences
    private var searchQuery = ""
    private val page: SettingsPage?
        get() = arguments?.getString(ARG_PAGE)?.let(SettingsPage::valueOf)

    override fun onAttach(context: Context) {
        super.onAttach(context)
        navigation = context as? Navigation
    }

    override fun onDetach() {
        navigation = null
        super.onDetach()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        searchQuery = arguments?.getString(ARG_SEARCH_QUERY).orEmpty()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        appPreferences = Preferences(requireNotNull(preferenceManager.sharedPreferences))
        preferenceFactory = SettingsPreferenceFactory(this, appPreferences)
        rebuildPreferences()
    }

    fun setSearchQuery(query: String) {
        searchQuery = query
        if (isAdded && ::preferenceFactory.isInitialized && page == null) {
            rebuildPreferences()
        }
    }

    fun refreshPreferences() = rebuildPreferences()

    private fun rebuildPreferences() {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        val currentPage = page
        if (currentPage == null) {
            buildRootScreen(screen)
        } else {
            buildPageScreen(screen, currentPage)
        }
        preferenceScreen = screen
    }

    private fun buildRootScreen(screen: PreferenceScreen) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            SettingsPage.values().forEach { page ->
                screen.addPreference(preferenceFactory.navigationPreference(page, ::selectPage))
            }
            return
        }

        val results = preferenceFactory.entries().filter { it.matches(requireContext(), query) }
        if (results.isEmpty()) {
            screen.addPreference(preferenceFactory.noSearchResultsPreference())
            return
        }

        results.forEach { entry ->
            screen.addPreference(entry.createPreference(preferenceFactory, getString(entry.page.titleRes)))
        }
    }

    private fun buildPageScreen(screen: PreferenceScreen, page: SettingsPage) {
        var target: PreferenceGroup = screen
        var currentSection: Int? = null
        preferenceFactory.entriesFor(page).forEach { entry ->
            if (entry.sectionRes != currentSection) {
                currentSection = entry.sectionRes
                target = entry.sectionRes?.let { sectionRes ->
                    PreferenceCategory(requireContext()).apply {
                        setTitle(sectionRes)
                        isIconSpaceReserved = false
                        screen.addPreference(this)
                    }
                } ?: screen
            }
            target.addPreference(entry.createPreference(preferenceFactory, breadcrumb = null))
        }
    }

    private fun selectPage(page: SettingsPage) {
        navigation?.onSettingsPageSelected(page)
    }

    fun startDictionaryInstall() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dictionary_download_title)
            .setMessage(
                getString(
                    R.string.dictionary_download_message,
                    Formatter.formatShortFileSize(requireContext(), DictionaryInstaller.downloadSizeBytes),
                    Formatter.formatShortFileSize(requireContext(), DictionaryInstaller.installedSizeBytes),
                )
            )
            .setPositiveButton(R.string.dictionary_download_action) { _, _ -> runDictionaryDownload() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun startDictionaryDelete() {
        confirmDialog(
            requireContext(),
            R.string.dictionary_delete_title,
            getString(R.string.dictionary_delete_message),
            R.string.delete,
        ) {
            val appContext = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { DictionaryStore.delete(appContext) }
                if (isAdded) {
                    rebuildPreferences()
                }
            }
        }
    }

    private fun runDictionaryDownload() {
        val density = resources.displayMetrics.density
        val progressIndicator = LinearProgressIndicator(requireContext()).apply {
            isIndeterminate = true
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding((24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt(), 0)
            addView(
                progressIndicator,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dictionary_downloading)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
        val appContext = requireContext().applicationContext
        job = viewLifecycleOwner.lifecycleScope.launch {
            val result = DictionaryInstaller.install(appContext) { percent ->
                progressIndicator.post {
                    progressIndicator.isIndeterminate = false
                    progressIndicator.progress = percent
                }
            }
            dialog.dismiss()
            if (!isAdded) {
                return@launch
            }
            result.fold(
                onSuccess = { rebuildPreferences() },
                onFailure = { error ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.dictionary_download_failed)
                        .setMessage(error.localizedMessage ?: error.toString())
                        .setPositiveButton(R.string.ok, null)
                        .show()
                },
            )
        }
    }

    companion object {
        private const val ARG_PAGE = "settingsPage"
        private const val ARG_SEARCH_QUERY = "settingsSearchQuery"

        fun root(searchQuery: String = ""): SettingsFragment {
            return SettingsFragment().apply {
                arguments = Bundle().apply { putString(ARG_SEARCH_QUERY, searchQuery) }
            }
        }

        fun page(page: SettingsPage): SettingsFragment {
            return SettingsFragment().apply {
                arguments = Bundle().apply { putString(ARG_PAGE, page.name) }
            }
        }
    }
}

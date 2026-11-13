// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.settings

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.reader.AgentPromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Settings > Agent notes and prompts: the card template, the study-mode prompt and the Mentra folder, each with Reset to default. */
@RunWith(AndroidJUnit4::class)
class AgentPromptsSettingsInstrumentedTest {
    @Test fun templatesAndMentraFolderAreEditedSavedAndReset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val shared = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = listOf(Preferences.agentPromptTemplateKey, Preferences.studyModePromptTemplateKey,
            Preferences.mentraPhotoFolderKey)
        val saved = keys.associateWith { shared.getString(it, null) }
        shared.edit().apply { keys.forEach(::remove) }.commit()
        val preferences = Preferences(shared)
        val intent = Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_PAGE, SettingsPage.AGENT_PROMPTS.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<SettingsActivity>(intent).use { scenario ->
                fun awaitUi(message: String, condition: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 10_000
                    var matched = false
                    while (!matched && SystemClock.uptimeMillis() < deadline) {
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { matched = condition() }
                        if (!matched) SystemClock.sleep(50)
                    }
                    if (!matched) fail(message)
                }
                // On the main thread only (inside onActivity or awaitUi).
                lateinit var current: SettingsActivity
                scenario.onActivity { current = it }
                fun preference(key: String): Preference? =
                    (current.supportFragmentManager.findFragmentById(R.id.fragment_container_view) as? SettingsFragment)
                        ?.findPreference(key)
                // The dialog's root view, found by its editor.
                fun dialogRoot(editorId: Int): View? =
                    WindowInspector.getGlobalWindowViews().asReversed().firstOrNull { it.findViewById<View>(editorId)?.isShown == true }
                fun open(key: String, editorId: Int): View {
                    awaitUi("$key is listed") { preference(key) != null }
                    scenario.onActivity { preference(key)!!.performClick() }
                    var root: View? = null
                    awaitUi("$key opens its editor") { dialogRoot(editorId).also { root = it } != null }
                    return root!!
                }
                fun click(root: View, buttonId: Int) {
                    scenario.onActivity { root.findViewById<View>(buttonId).performClick() }
                    instrumentation.waitForIdleSync()
                }

                awaitUi("Agent prompts page") { preference(Preferences.agentPromptTemplateKey) != null }
                scenario.onActivity {
                    assertEquals("Copy for agent", preference(Preferences.agentPromptTemplateKey)!!.title)
                    assertEquals("Study mode prompt", preference(Preferences.studyModePromptTemplateKey)!!.title)
                    assertEquals("Default", preference(Preferences.agentPromptTemplateKey)!!.summary)
                    assertEquals(AgentPromptTemplates.DEFAULT_MENTRA_FOLDER, preference(Preferences.mentraPhotoFolderKey)!!.summary)
                }

                // The editor shows the default; a tapped placeholder is inserted at the cursor; Save keeps it.
                var root = open(Preferences.agentPromptTemplateKey, R.id.agentPromptTemplateEditor)
                scenario.onActivity {
                    val editor = root.findViewById<EditText>(R.id.agentPromptTemplateEditor)
                    assertEquals(AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE, editor.text.toString())
                    assertNull(root.findViewWithTag<View>("agentPromptPlaceholder:mentra_folder"))
                    editor.setText("Card ")
                    editor.setSelection(editor.length())
                    root.findViewWithTag<View>("agentPromptPlaceholder:card_id").performClick()
                    assertEquals("Card {card_id}", editor.text.toString())
                }
                click(root, android.R.id.button1)
                assertEquals("Card {card_id}", preferences.getAgentPromptTemplate(AgentPromptTemplates.Kind.AGENT))
                awaitUi("Summary says Custom") { preference(Preferences.agentPromptTemplateKey)?.summary == "Custom" }

                // Reset to default fills the editor and keeps the dialog open; Save removes the entry.
                root = open(Preferences.agentPromptTemplateKey, R.id.agentPromptTemplateEditor)
                click(root, android.R.id.button3)
                scenario.onActivity {
                    assertTrue(root.isShown)
                    assertEquals(AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE,
                        root.findViewById<EditText>(R.id.agentPromptTemplateEditor).text.toString())
                }
                click(root, android.R.id.button1)
                assertFalse(shared.contains(Preferences.agentPromptTemplateKey))
                awaitUi("Summary says Default") { preference(Preferences.agentPromptTemplateKey)?.summary == "Default" }

                // The study-mode prompt: only {mentra_folder} is offered.
                root = open(Preferences.studyModePromptTemplateKey, R.id.agentPromptTemplateEditor)
                scenario.onActivity {
                    val editor = root.findViewById<EditText>(R.id.agentPromptTemplateEditor)
                    assertEquals(AgentPromptTemplates.DEFAULT_STUDY_MODE_TEMPLATE, editor.text.toString())
                    assertNotNull(root.findViewWithTag<View>("agentPromptPlaceholder:mentra_folder"))
                    assertNull(root.findViewWithTag<View>("agentPromptPlaceholder:card_id"))
                    editor.setText("Watch {mentra_folder}\n")
                }
                click(root, android.R.id.button1)
                assertEquals("Watch {mentra_folder}\n",
                    preferences.getAgentPromptTemplate(AgentPromptTemplates.Kind.STUDY_MODE))

                // The Mentra folder.
                root = open(Preferences.mentraPhotoFolderKey, R.id.agentPromptMentraFolderEditor)
                scenario.onActivity { root.findViewById<EditText>(R.id.agentPromptMentraFolderEditor).setText("/tmp/photos") }
                click(root, android.R.id.button1)
                assertEquals("/tmp/photos", preferences.getMentraPhotoFolder())
                awaitUi("Folder summary") { preference(Preferences.mentraPhotoFolderKey)?.summary == "/tmp/photos" }
                root = open(Preferences.mentraPhotoFolderKey, R.id.agentPromptMentraFolderEditor)
                click(root, android.R.id.button3)
                scenario.onActivity {
                    assertEquals(AgentPromptTemplates.DEFAULT_MENTRA_FOLDER,
                        root.findViewById<EditText>(R.id.agentPromptMentraFolderEditor).text.toString())
                }
                click(root, android.R.id.button1)
                assertFalse(shared.contains(Preferences.mentraPhotoFolderKey))
            }
        } finally {
            shared.edit().apply {
                saved.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
            }.commit()
        }
    }
}

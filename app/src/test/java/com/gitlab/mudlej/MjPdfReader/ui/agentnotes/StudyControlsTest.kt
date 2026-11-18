// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.PushOffReason
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.PushStatus
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The Home menu's and the reader panel's controls for receiving notes, per push state, and their texts. */
class StudyControlsTest {
    @Test fun withPushTheSwitchIsGoneAndTheMenuSaysNotesArriveInstantly() {
        val controls = studyControls(pushActive = true, receiving = true, instantNotesOn = false)
        assertFalse(controls.showInstantNotesSwitch)
        assertTrue(controls.showNotesArriveInstantly)
    }

    @Test fun withoutPushTheInstantNotesSwitchStays() {
        val controls = studyControls(pushActive = false, receiving = true, instantNotesOn = false)
        assertTrue(controls.showInstantNotesSwitch)
        assertTrue(controls.instantNotesSwitchEnabled)
        assertFalse(controls.showNotesArriveInstantly)
        // A phone that does not receive notes cannot listen; it can still turn the switch off.
        assertFalse(studyControls(pushActive = false, receiving = false, instantNotesOn = false).instantNotesSwitchEnabled)
        assertTrue(studyControls(pushActive = false, receiving = false, instantNotesOn = true).instantNotesSwitchEnabled)
    }

    private val strings: Map<String, String> by lazy {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.replace("\\'", "'")
        }
    }

    @Test fun theSwitchIsNowInstantNotesAndTheRowStartsAStudySession() {
        assertEquals("Instant notes", strings["agent_notes_study_mode"])
        assertEquals("Notes pop up even when Thoughts is closed; pauses after an hour without use", strings["agent_notes_study_mode_detail"])
        assertEquals("Notes arrive instantly", strings["agent_notes_push_arrives"])
        assertEquals("Start a new study session", strings["agent_notes_start_study_session"])
        assertEquals(
            "The prompt is on the clipboard. Paste it into a new Claude session on the computer where your glasses captures arrive: " +
                "in the Claude app, Code, then that computer, then a new session. You do not need a new session to reply to a note: " +
                "replies and glasses captures already reach an agent.",
            strings["agent_notes_study_mode_prompt_copied"],
        )
        // It says where to paste without naming a host: public builds show it too.
        assertFalse(strings.getValue("agent_notes_study_mode_prompt_copied").contains("fedora", ignoreCase = true))
        assertEquals("Study session prompt", strings["agent_prompt_study_mode"])
        assertEquals("Push works: a test from the server", strings["agent_notes_push_test_notification"])
        assertFalse(strings.containsKey("agent_notes_study_mode_copy_prompt"))
        // Nothing on screen calls the switch "Study mode" any more.
        val switchTexts = strings.filterKeys { it.startsWith("agent_notes_study_mode") || it == "agent_prompt_study_mode" || it == "settings_agent_prompts_summary" }
        switchTexts.forEach { (name, text) -> assertFalse("$name: $text", text.contains("study mode", ignoreCase = true)) }
    }

    @Test fun aNewStudySessionStartsFromAgentNotesNotFromTheHomeMenu() {
        // The Home menu keeps only Agent notes (and Instant notes without push): no status line, no session row.
        val home = File("src/main/res/layout/dialog_home_menu.xml").readText()
        assertFalse(home.contains("@+id/pushActiveLine"))
        assertFalse(home.contains("@+id/startStudySessionRow"))
        assertTrue(home.contains("@+id/agentNotesRow"))
        assertTrue(home.contains("@+id/studyModeSwitch"))
        // Agent notes has New session at the top right, always shown as its words.
        val menu = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/menu/agent_notes_list_menu.xml"))
        val items = menu.getElementsByTagName("item")
        assertEquals(1, items.length)
        val item = items.item(0) as Element
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("@+id/agentNotesNewSession", item.getAttributeNS(android, "id"))
        assertEquals("@string/agent_notes_new_session", item.getAttributeNS(android, "title"))
        assertEquals("always", item.getAttributeNS("http://schemas.android.com/apk/res-auto", "showAsAction"))
        assertEquals("New session", strings["agent_notes_new_session"])
    }

    @Test fun everyReasonPushIsOffHasItsOwnPlainTextNamingTheServerNotAHost() {
        val texts = PushOffReason.entries.map(::pushOffReasonText)
        assertEquals(texts.size, texts.toSet().size)
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val pushStrings = strings.filterKeys { it.startsWith("agent_notes_push") }
        assertTrue(pushStrings.size > 10)
        for (id in texts) assertTrue(names[id] in pushStrings)
        pushStrings.values.forEach { text ->
            assertFalse(text, Regex("""\b[a-z0-9-]+\.[a-z]{2,}\b""").containsMatchIn(text) || text.contains("fedora", ignoreCase = true))
        }
    }

    @Test fun settingsSaysActiveOrOffWithTheReason() {
        val getString = { id: Int -> "s$id" }
        val format = { id: Int, argument: String -> "f$id($argument)" }
        assertEquals("s${R.string.agent_notes_push_active}", pushStatusText(PushStatus(true, null, true), getString, format))
        assertEquals(
            "f${R.string.agent_notes_push_off}(s${R.string.agent_notes_push_off_server_without_push})",
            pushStatusText(PushStatus.off(PushOffReason.SERVER_WITHOUT_PUSH), getString, format),
        )
    }

    @Test
    fun aNewSessionsPromptSaysWhatItMayUse() {
        val default = com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteReplySources.LIBRARY_AND_MENTAL_MODEL
        // The default adds nothing: the prompt is as it was before the choice.
        assertEquals(null, sessionSourcesLine(default))
        val none = sessionSourcesLine(com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteReplySources.NONE)!!
        assertTrue(none.startsWith("- Sources for this session:") && none.contains("no knowledge_profile"))
        val linked = sessionSourcesLine(com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteReplySources.LINKED_PDF)!!
        assertTrue(linked.contains("own_topic") && linked.contains("skip knowledge_profile"))
        val library = sessionSourcesLine(com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteReplySources.LIBRARY)!!
        assertTrue(library.contains("not the Mental Model"))
    }
}

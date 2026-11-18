// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteUnseen
import kotlinx.coroutines.launch

/** The dot on an Agent notes entry while a note that arrived is not opened yet; [button] says so to screen readers. */
object AgentNotesDot {
    fun bind(activity: AppCompatActivity, dot: View, button: View? = null, label: Int = R.string.agent_notes) {
        AgentNoteUnseen.load(activity)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentNoteUnseen.ids.collect { ids -> show(dot, button, ids.isNotEmpty(), label) }
            }
        }
    }

    /** For a view shown once with what is unseen now, such as a menu row. */
    fun showNow(activity: AppCompatActivity, dot: View) {
        show(dot, null, AgentNoteUnseen.load(activity).isNotEmpty(), R.string.agent_notes)
    }

    private fun show(dot: View, button: View?, visible: Boolean, label: Int) {
        dot.visibility = if (visible) View.VISIBLE else View.GONE
        val context = dot.context
        button?.contentDescription =
            if (visible) context.getString(R.string.agent_notes_with_new, context.getString(label)) else context.getString(label)
    }
}

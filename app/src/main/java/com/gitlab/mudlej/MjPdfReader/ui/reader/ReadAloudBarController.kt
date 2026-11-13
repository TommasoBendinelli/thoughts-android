// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.speech.OpenRouterKeyStore
import com.gitlab.mudlej.MjPdfReader.data.speech.ReadAloudEvent
import com.gitlab.mudlej.MjPdfReader.data.speech.ReadAloudMessages
import com.gitlab.mudlej.MjPdfReader.data.speech.ReadAloudSession
import com.gitlab.mudlej.MjPdfReader.data.speech.ReadAloudState
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsActivity
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsPage
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * The reader's floating "Reading aloud" bar: a spinner until the first audio plays, Pause/Resume
 * and Stop. It shows any reading in progress, also one another reader started.
 */
class ReadAloudBarController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {
    private val session = ReadAloudSession.get(activity)

    fun configure() {
        binding.readAloudPauseButton.setOnClickListener { session.togglePause() }
        binding.readAloudStopButton.setOnClickListener { session.stop() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { session.state.collect(::render) }
                launch { session.events.collect(::onEvent) }
            }
        }
    }

    private fun onEvent(event: ReadAloudEvent) {
        when (event) {
            is ReadAloudEvent.CloudVoiceUnavailable -> AppSnackbar.make(
                binding.root,
                activity.getString(
                    R.string.read_aloud_cloud_unavailable_reason,
                    ReadAloudMessages.shortReason(activity, event.reason),
                ),
                Snackbar.LENGTH_SHORT,
            ).show()
            // Once, until the key is saved and removed again; the bar says "system voice" anyway.
            ReadAloudEvent.NoCloudKey -> if (OpenRouterKeyStore(activity).consumeNoKeyHint()) {
                AppSnackbar.make(binding.root, R.string.read_aloud_add_key_hint, Snackbar.LENGTH_LONG)
                    .setAction(R.string.read_aloud_add_key_hint_action) {
                        activity.startActivity(
                            Intent(activity, SettingsActivity::class.java)
                                .putExtra(SettingsActivity.EXTRA_PAGE, SettingsPage.READING.name),
                        )
                    }
                    .show()
            }
        }
    }

    /** Reads the selected [text] aloud, replacing any reading in progress. */
    fun read(text: String) {
        if (!session.start(text, activity)) {
            AppSnackbar.make(binding.root, R.string.read_aloud_unavailable, Snackbar.LENGTH_SHORT).show()
        }
    }

    /** Leaving the reader ends the reading it started; a configuration change does not. */
    fun onActivityDestroyed() {
        if (activity.isFinishing) session.stopIfOwnedBy(activity)
    }

    private fun render(state: ReadAloudState) {
        if (!state.active) {
            binding.readAloudBar.visibility = View.GONE
            return
        }
        binding.readAloudStatus.setText(
            when {
                state.paused -> R.string.read_aloud_paused
                state.systemVoice -> R.string.read_aloud_reading_system_voice
                else -> R.string.read_aloud_reading
            },
        )
        val loading = state.loading && !state.paused
        binding.readAloudProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.readAloudIcon.visibility = if (loading) View.GONE else View.VISIBLE
        binding.readAloudPauseButton.setIconResource(if (state.paused) R.drawable.ic_play_arrow else R.drawable.ic_pause)
        binding.readAloudPauseButton.contentDescription =
            activity.getString(if (state.paused) R.string.read_aloud_resume else R.string.read_aloud_pause)
        binding.readAloudBar.visibility = View.VISIBLE
        binding.readAloudBar.bringToFront()
    }
}

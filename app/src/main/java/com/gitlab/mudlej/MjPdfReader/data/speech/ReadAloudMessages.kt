// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.R

/** What the reader and Settings say about the cloud voice. */
object ReadAloudMessages {
    /** A few words for the snackbar, e.g. "key rejected". Null [reason]: the audio could not be played. */
    fun shortReason(context: Context, reason: SpeechFailureReason?): String = context.getString(
        when (reason) {
            null -> R.string.read_aloud_reason_unplayable
            SpeechFailureReason.NO_KEY -> R.string.read_aloud_reason_no_key
            SpeechFailureReason.KEY_REJECTED -> R.string.read_aloud_reason_key_rejected
            SpeechFailureReason.NO_CREDITS -> R.string.read_aloud_reason_no_credits
            SpeechFailureReason.RATE_LIMITED -> R.string.read_aloud_reason_rate_limited
            SpeechFailureReason.PROVIDER_ERROR -> R.string.read_aloud_reason_provider_error
            SpeechFailureReason.REQUEST_REFUSED -> R.string.read_aloud_reason_request_refused
            SpeechFailureReason.NOT_AUDIO -> R.string.read_aloud_reason_not_audio
            SpeechFailureReason.TIMED_OUT -> R.string.read_aloud_reason_timed_out
            SpeechFailureReason.NO_CONNECTION -> R.string.read_aloud_reason_no_connection
        },
    )

    /** The reason with the HTTP status and OpenRouter's message, for Test voice. */
    fun longReason(context: Context, failure: SpeechUnavailableException?): String {
        val short = shortReason(context, failure?.reason)
        val status = failure?.statusCode ?: return short
        val detail = failure.detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        return "$short (HTTP $status$detail)"
    }

    fun testOutcome(context: Context, outcome: ReadAloudOutcome): String = when (outcome) {
        is ReadAloudOutcome.CloudVoice -> context.getString(
            R.string.read_aloud_test_cloud,
            context.getString(
                if (outcome.model == OpenRouterSpeech.MODEL) R.string.read_aloud_model_free else R.string.read_aloud_model_paid,
            ),
        )
        is ReadAloudOutcome.SystemVoice -> if (outcome.noKey) {
            context.getString(R.string.read_aloud_test_no_key)
        } else {
            context.getString(R.string.read_aloud_test_failed, longReason(context, outcome.failure))
        }
    }
}

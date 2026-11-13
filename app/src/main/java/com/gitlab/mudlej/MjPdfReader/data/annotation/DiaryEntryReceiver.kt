// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Adds a diary entry for another app signed with the same key (TobiBoard's note field), guarded by
 * the signature permission [PERMISSION]. Extras: [EXTRA_TEXT], optionally [EXTRA_ID] (a repeated id
 * rewrites the same entry instead of adding one) and [EXTRA_AT] (epoch milliseconds, else now).
 * Ordered senders get [Activity.RESULT_OK] once the entry is saved on the phone; it is published at
 * the next Sync, as entries written in the Diary tab are.
 */
class DiaryEntryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADD) return
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) {
            resultCode = Activity.RESULT_CANCELED
            return
        }
        val id = intent.getStringExtra(EXTRA_ID)?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        val at = intent.getLongExtra(EXTRA_AT, 0L).takeIf { it > 0L }
            ?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
            ?: ZonedDateTime.now()
        val pending = goAsync()
        val store = DiaryStore(context)
        DiaryStore.writes.launch {
            val saved = runCatching { DiaryStore.inOrder { store.add(text, at, id) } }
                .onFailure { Log.e(TAG, "Unable to add a diary entry from another app", it) }
                .isSuccess
            pending.resultCode = if (saved) Activity.RESULT_OK else Activity.RESULT_CANCELED
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "DiaryEntryReceiver"
        const val PERMISSION = "org.tbend.thoughts.permission.ADD_DIARY_ENTRY"
        const val ACTION_ADD = "org.tbend.thoughts.action.ADD_DIARY_ENTRY"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
        const val EXTRA_AT = "at"
    }
}

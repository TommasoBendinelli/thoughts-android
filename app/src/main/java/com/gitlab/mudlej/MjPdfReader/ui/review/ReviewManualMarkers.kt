// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/** The start/end marker pair a person pastes around a card's answer when automatic linking failed. */
internal data class ReviewManualMarkers(val start: String, val end: String) {
    companion object {
        /** Markers for [path]; TeX unless the source is Markdown. No trailing `%`, so a marker pasted mid-line keeps the rest of the line. */
        fun forCard(cardId: String, path: String?): ReviewManualMarkers =
            if (path != null && (path.endsWith(".md") || path.endsWith(".markdown"))) ReviewManualMarkers(
                "<!-- thoughts:v1:start review:$cardId:answer:0 -->",
                "<!-- thoughts:v1:end review:$cardId:answer:0 -->",
            ) else ReviewManualMarkers("\\ReviewCardMaskStart{$cardId}{0}", "\\ReviewCardMaskEnd{$cardId}{0}")

        /** Drops the "Place the markers manually for card <id>." sentence that the marker box replaces. */
        fun withoutPlacementHint(reason: String): String =
            reason.replace(Regex("\\s*Place the (start/end )?markers manually for card [0-9a-fA-F-]+\\.\\s*$"), "").trim()

        fun copy(context: Context, label: String, text: String) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(label, text))
            // Android 13 and later confirm copies itself.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        }
    }
}

// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.LinearLayoutCompat

/**
 * Keeps the custom view and the buttons of a dialog with both a message and a custom view on screen.
 *
 * AlertDialogLayout fits only one middle panel to the screen. With both a message and a custom view it
 * measures them as a plain column, so a long message pushes the custom view and the buttons below the
 * screen. Giving the message panel only the height left over lets its own scroll view scroll the message.
 * Call it after the builder's create() and before show().
 */
internal fun AlertDialog.scrollMessageAboveCustomView() {
    create()
    val content = findViewById<View>(androidx.appcompat.R.id.contentPanel) ?: return
    val params = content.layoutParams as? LinearLayoutCompat.LayoutParams ?: return
    params.height = 0
    params.weight = 1f
    content.layoutParams = params
}

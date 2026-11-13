// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.AtomicFile
import android.util.AttributeSet
import android.util.Log
import android.widget.Toast
import android.view.ViewTreeObserver
import androidx.appcompat.widget.AppCompatEditText
import com.gitlab.mudlej.MjPdfReader.R
import java.io.File
import java.util.UUID

/** Keep large text and undo history out of the activity's Binder transaction. */
class SourceEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {
    private var recoveryId = UUID.randomUUID().toString()
    var restoredFromDisk = false
        private set
    internal var clipDrawingToViewport = true
    private val visibleViewport = Rect()
    private val drawnViewport = Rect()
    private val viewportListener = ViewTreeObserver.OnPreDrawListener {
        // A ScrollView can reuse this tall child's display list while scrolling.
        // Re-record it when the visible range changes so newly exposed text is drawn.
        if (clipDrawingToViewport && getLocalVisibleRect(visibleViewport) && visibleViewport != drawnViewport) {
            invalidate()
        }
        true
    }

    init {
        // TeX uses the platform's Unicode rendering. EmojiCompat's watcher performs
        // a whole-document DynamicLayout reflow even for ordinary composing text.
        isEmojiCompatEnabled = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(viewportListener)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(viewportListener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (!clipDrawingToViewport) {
            super.onDraw(canvas)
            return
        }
        if (!getLocalVisibleRect(visibleViewport)) return
        drawnViewport.set(visibleViewport)
        val checkpoint = canvas.save()
        canvas.clipRect(visibleViewport)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    private fun recoveryFile() = AtomicFile(File(context.noBackupFilesDir,
        "source-editor-state/$recoveryId.bin"))

    override fun onSaveInstanceState(): Parcelable {
        val state = super.onSaveInstanceState()
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(state, 0)
            val file = recoveryFile()
            val output = file.startWrite()
            try {
                output.write(parcel.marshall())
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        } catch (error: Exception) {
            Log.e("SourceEditText", "Could not persist editor recovery state", error)
            Toast.makeText(context, R.string.source_editor_recovery_write_failed, Toast.LENGTH_LONG).show()
        } finally {
            parcel.recycle()
        }
        return Bundle().apply { putString(RECOVERY_ID, recoveryId) }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        val id = (state as? Bundle)?.getString(RECOVERY_ID)
        if (id == null) {
            // Accept state written by older versions of this editor.
            super.onRestoreInstanceState(state)
            return
        }
        if (!id.matches(Regex("[0-9a-f-]{36}"))) return
        recoveryId = id
        val parcel = Parcel.obtain()
        try {
            val bytes = recoveryFile().readFully()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            val restored = parcel.readParcelable<Parcelable>(javaClass.classLoader)
            super.onRestoreInstanceState(restored)
            restoredFromDisk = true
        } catch (error: Exception) {
            Log.e("SourceEditText", "Could not restore editor recovery state", error)
            Toast.makeText(context, R.string.source_editor_recovery_read_failed, Toast.LENGTH_LONG).show()
        } finally {
            parcel.recycle()
        }
    }

    fun discardRecoveryState() = recoveryFile().delete()

    private companion object {
        const val RECOVERY_ID = "source_editor_recovery_id"
    }
}

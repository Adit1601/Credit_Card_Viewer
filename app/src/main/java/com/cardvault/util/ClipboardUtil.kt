package com.cardvault.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import com.cardvault.R
import com.google.android.material.snackbar.Snackbar

/**
 * Implements §1.4 / §6.4 — copying a sensitive field puts it on the clipboard, shows a
 * "will clear in 30 seconds" snackbar, and then clears the clipboard 30s later (only if the
 * value we wrote is still there, so we don't overwrite whatever the user copied since).
 */
object ClipboardUtil {

    private const val LABEL = "cardvault"
    private const val CLEAR_DELAY_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())

    private var pendingToken: Any? = null

    fun copyPlain(context: Context, text: String, hapticAnchor: View) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(LABEL, text))
        hapticAnchor.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun copySensitive(context: Context, text: String, snackbarAnchor: View) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(LABEL, text))
        snackbarAnchor.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        Snackbar.make(snackbarAnchor, R.string.clipboard_will_clear, Snackbar.LENGTH_SHORT).show()

        val token = Any()
        pendingToken = token
        handler.postDelayed({
            if (pendingToken !== token) return@postDelayed
            val current = cm.primaryClip
            val stillOurs = current != null &&
                current.itemCount > 0 &&
                current.getItemAt(0).text?.toString() == text
            if (stillOurs) {
                // setPrimaryClip with empty text works on every supported API (26+); clearPrimaryClip
                // exists on 28+ but doesn't add value for us.
                cm.setPrimaryClip(ClipData.newPlainText(LABEL, ""))
            }
        }, CLEAR_DELAY_MS)
    }
}

package com.cardvault.ui.auth

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.cardvault.R
import com.cardvault.crypto.CryptoManager
import com.cardvault.data.db.AppDatabase
import com.cardvault.data.prefs.SecurePreferences
import com.cardvault.data.repository.VaultMetadataRepository
import com.cardvault.util.clearCharsSecurely
import com.cardvault.util.readCharsSecurely
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modal password re-entry, used for CVV reveal / copy per §1.3 and §6.2. Uses the same
 * canary-decrypt verification path as [AppPasswordFragment], so an incorrect password
 * fails GCM auth-tag validation and never leaks anything.
 *
 * Result is delivered via [setResult] into the parent FragmentManager under [REQUEST_KEY].
 */
class AppPasswordDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_app_password, null, false)
        val message = view.findViewById<TextView>(R.id.dialogMessage)
        val layout = view.findViewById<TextInputLayout>(R.id.dialogPasswordLayout)
        val input = view.findViewById<TextInputEditText>(R.id.dialogPasswordInput)

        message.setText(arguments?.getInt(ARG_MESSAGE_RES) ?: R.string.password_dialog_reveal_cvv)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.password_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.action_confirm, null)
            .setNegativeButton(R.string.action_cancel) { _, _ -> deliver(false) }
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                // Disable immediately so a rapid second tap can't launch a second PBKDF2
                // derivation in parallel and deliver() the result twice. Re-enabled on any
                // failure path so the user can retry.
                positive.isEnabled = false
                layout.error = null
                val pwChars = input.readCharsSecurely()
                if (pwChars.isEmpty()) {
                    positive.isEnabled = true
                    layout.error = getString(R.string.error_required); return@setOnClickListener
                }
                input.clearCharsSecurely()
                lifecycleScope.launch {
                    val ok = try {
                        withContext(Dispatchers.Default) { verify(pwChars) }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        false
                    }
                    if (ok) {
                        deliver(true)
                        dismiss()
                    } else {
                        positive.isEnabled = true
                        layout.error = getString(R.string.error_wrong_password)
                    }
                }
            }
        }
        return dialog
    }

    private suspend fun verify(password: CharArray): Boolean {
        try {
            val ctx = requireContext().applicationContext
            val db = AppDatabase.get(ctx)
            val metadata = VaultMetadataRepository(db, db.metadataDao(), SecurePreferences(ctx))
            val saltB64 = metadata.getSalt() ?: return false
            val canary = metadata.getCanary() ?: return false
            val salt = CryptoManager.base64Decode(saltB64)
            val derived = CryptoManager.deriveKey(password, salt)
            return CryptoManager.verifyCanary(derived, canary)
        } finally {
            password.fill(' ')
        }
    }

    private fun deliver(success: Boolean) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply { putBoolean(RESULT_OK, success) }
        )
    }

    companion object {
        const val REQUEST_KEY = "app_password_dialog"
        const val RESULT_OK = "ok"

        private const val ARG_MESSAGE_RES = "message_res"

        fun show(fm: FragmentManager, messageRes: Int) {
            AppPasswordDialogFragment().apply {
                arguments = Bundle().apply { putInt(ARG_MESSAGE_RES, messageRes) }
            }.show(fm, "app_password_dialog")
        }
    }
}

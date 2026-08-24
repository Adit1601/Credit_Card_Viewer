package com.cardvault.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cardvault.MainActivity
import com.cardvault.R
import com.cardvault.crypto.CryptoManager
import com.cardvault.data.db.AppDatabase
import com.cardvault.data.prefs.SecurePreferences
import com.cardvault.data.repository.VaultMetadataRepository
import com.cardvault.security.SessionManager
import com.cardvault.ui.onboarding.OnboardingActivity
import com.cardvault.util.clearCharsSecurely
import com.cardvault.util.readCharsSecurely
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layer 2 of the security model (§1.2). Runs inside [BiometricLockActivity] after biometric
 * succeeds. On correct password, unlocks [SessionManager] and starts [MainActivity].
 *
 * Forgot-password flow wipes the vault (Room + SecurePreferences + Keystore alias) and restarts
 * onboarding — there is no recovery per §1.2.
 */
class AppPasswordFragment : Fragment(R.layout.fragment_app_password) {

    private lateinit var metadata: VaultMetadataRepository

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext().applicationContext
        val db = AppDatabase.get(ctx)
        metadata = VaultMetadataRepository(db, db.metadataDao(), SecurePreferences(ctx))

        val passwordLayout = view.findViewById<TextInputLayout>(R.id.passwordLayout)
        val passwordInput = view.findViewById<TextInputEditText>(R.id.passwordInput)
        val unlockButton = view.findViewById<MaterialButton>(R.id.unlockButton)
        val forgotButton = view.findViewById<MaterialButton>(R.id.forgotButton)
        val progress = view.findViewById<ProgressBar>(R.id.progress)

        unlockButton.setOnClickListener {
            passwordLayout.error = null
            // Pull chars straight from the widget — no intermediate String on the heap.
            val pwChars = passwordInput.readCharsSecurely()
            if (pwChars.isEmpty()) {
                passwordLayout.error = getString(R.string.error_required)
                return@setOnClickListener
            }
            passwordInput.clearCharsSecurely()
            unlockButton.isEnabled = false
            progress.visibility = View.VISIBLE

            viewLifecycleOwner.lifecycleScope.launch {
                // tryUnlock() owns pwChars and zeros it in its finally.
                val ok = try {
                    withContext(Dispatchers.Default) { tryUnlock(pwChars) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    false
                }
                progress.visibility = View.GONE
                unlockButton.isEnabled = true
                if (ok) {
                    goToMain()
                } else {
                    passwordLayout.error = getString(R.string.error_wrong_password)
                }
            }
        }

        forgotButton.setOnClickListener { showForgotDialog() }
    }

    private suspend fun tryUnlock(password: CharArray): Boolean {
        try {
            val saltB64 = metadata.getSalt() ?: return false
            val canary = metadata.getCanary() ?: return false
            val salt = CryptoManager.base64Decode(saltB64)

            val derived = CryptoManager.deriveKey(password, salt)
            // Verify BEFORE installing anything in Keystore, so wrong-password attempts are cheap.
            val inMemoryOk = CryptoManager.verifyCanary(derived, canary)
            if (!inMemoryOk) return false

            val keystoreKey = CryptoManager.installMasterKey(derived)
            SessionManager.setMasterKey(keystoreKey)
            return true
        } finally {
            password.fill(' ')
        }
    }

    private fun showForgotDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_password_forgot_dialog_title)
            .setMessage(R.string.app_password_forgot_dialog_body)
            .setPositiveButton(R.string.action_reset_app) { _, _ -> resetAndRestart() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun resetAndRestart() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val ctx = requireContext().applicationContext
                AppDatabase.wipe(ctx)
                SecurePreferences(ctx).clear()
                SessionManager.lock()
            }
            startActivity(
                Intent(requireContext(), OnboardingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            requireActivity().finish()
        }
    }

    private fun goToMain() {
        startActivity(
            Intent(requireContext(), MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        requireActivity().finish()
    }
}

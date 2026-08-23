package com.cardvault.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cardvault.MainActivity
import com.cardvault.R
import com.cardvault.crypto.CryptoManager
import com.cardvault.data.prefs.SecurePreferences
import com.cardvault.security.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetPasswordFragment : Fragment(R.layout.fragment_set_password) {

    private lateinit var prefs: SecurePreferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SecurePreferences(requireContext())

        val passwordLayout = view.findViewById<TextInputLayout>(R.id.passwordLayout)
        val confirmLayout = view.findViewById<TextInputLayout>(R.id.passwordConfirmLayout)
        val passwordInput = view.findViewById<TextInputEditText>(R.id.passwordInput)
        val confirmInput = view.findViewById<TextInputEditText>(R.id.passwordConfirmInput)
        val continueButton = view.findViewById<MaterialButton>(R.id.continueButton)
        val progress = view.findViewById<ProgressBar>(R.id.progress)

        continueButton.setOnClickListener {
            passwordLayout.error = null
            confirmLayout.error = null

            val pw = passwordInput.text?.toString().orEmpty()
            val confirm = confirmInput.text?.toString().orEmpty()

            if (pw.length < MIN_PASSWORD) {
                passwordLayout.error = getString(R.string.error_password_min_length)
                return@setOnClickListener
            }
            if (pw != confirm) {
                confirmLayout.error = getString(R.string.error_password_mismatch)
                return@setOnClickListener
            }

            continueButton.isEnabled = false
            progress.visibility = View.VISIBLE

            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { withContext(Dispatchers.Default) { provision(pw) } }
                    .onSuccess { goToMain() }
                    .onFailure {
                        progress.visibility = View.GONE
                        continueButton.isEnabled = true
                        passwordLayout.error = it.message ?: "Failed to provision"
                    }
            }
        }
    }

    private fun provision(password: String) {
        val salt = CryptoManager.randomSalt()
        val rawKey = CryptoManager.deriveKey(password.toCharArray(), salt)
        val keystoreKey = CryptoManager.installMasterKey(rawKey)
        val canary = CryptoManager.createCanary(keystoreKey)

        prefs.setSalt(CryptoManager.base64Encode(salt))
        prefs.setCanary(canary)
        prefs.setOnboardingDone(true)

        SessionManager.setMasterKey(keystoreKey)
    }

    private fun goToMain() {
        startActivity(
            Intent(requireContext(), MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        requireActivity().finish()
    }

    companion object {
        private const val MIN_PASSWORD = 4
    }
}

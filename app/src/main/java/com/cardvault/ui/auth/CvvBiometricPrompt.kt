package com.cardvault.ui.auth

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.cardvault.R

/**
 * Biometric shortcut for CVV re-auth. Substitute for the app-password dialog — success is
 * delivered on the same [AppPasswordDialogFragment.REQUEST_KEY] channel, so callers listen
 * on exactly one result key regardless of which path the user took.
 *
 * Class-3 (BIOMETRIC_STRONG) only: DEVICE_CREDENTIAL is deliberately excluded because the
 * phone PIN may differ from the app password, and letting it unlock CVV would collapse the
 * two-layer separation for this action.
 *
 * Behavior:
 *   - No STRONG biometric available/enrolled — skip the prompt, show the password dialog.
 *   - Success — deliver RESULT_OK=true and return.
 *   - Negative button ("Use password") — show the password dialog.
 *   - USER_CANCELED / CANCELED — silent, same as pressing Cancel on the password dialog.
 *   - Any other error (lockout, hw unavailable, timeout, …) — fall through to the password
 *     dialog so a transient failure doesn't leave the user with no route to their CVV.
 */
object CvvBiometricPrompt {

    fun request(host: Fragment, @StringRes messageRes: Int) {
        val ctx = host.requireContext()
        val canStrong = BiometricManager.from(ctx)
            .canAuthenticate(Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

        if (!canStrong) {
            AppPasswordDialogFragment.show(host.parentFragmentManager, messageRes)
            return
        }

        val executor = ContextCompat.getMainExecutor(ctx)
        val prompt = BiometricPrompt(host, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                host.parentFragmentManager.setFragmentResult(
                    AppPasswordDialogFragment.REQUEST_KEY,
                    Bundle().apply { putBoolean(AppPasswordDialogFragment.RESULT_OK, true) }
                )
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> Unit
                    else -> AppPasswordDialogFragment.show(host.parentFragmentManager, messageRes)
                }
            }
        })

        val subtitleRes = when (messageRes) {
            R.string.password_dialog_copy_cvv -> R.string.cvv_biometric_prompt_subtitle_copy
            else -> R.string.cvv_biometric_prompt_subtitle_reveal
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(host.getString(R.string.cvv_biometric_prompt_title))
            .setSubtitle(host.getString(subtitleRes))
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(host.getString(R.string.cvv_biometric_use_password))
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info)
    }
}

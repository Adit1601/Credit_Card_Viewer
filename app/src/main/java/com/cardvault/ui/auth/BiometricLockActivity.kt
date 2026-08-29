package com.cardvault.ui.auth

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.cardvault.R

/**
 * Layer 1 of the security model (§1.1). Shown on every cold launch and on every foreground
 * resume after the app was backgrounded, before the in-app password fragment is displayed.
 *
 * Behavior:
 *   - If the device is not secure (no PIN/pattern/biometric) — soft warning, then continue to
 *     the app-password fragment directly (user's decision, per REQUIREMENTS clarification).
 *   - If biometric hardware is unavailable or has no enrolled biometric — fall back to a
 *     DEVICE_CREDENTIAL-only prompt (PIN/pattern/password).
 *   - On success — swap in the [AppPasswordFragment] for Layer 2.
 *   - On failure/cancel — finishAffinity(), which closes the whole task per §1.1.
 */
class BiometricLockActivity : AppCompatActivity() {

    private var passwordFragmentShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_biometric_lock)

        // A configuration change rebuilds this activity, and the FragmentManager restores
        // AppPasswordFragment on its own — but setContentView has just reset password_container to
        // the `gone` the layout declares, and the guard flag to false. Both halves matter: without
        // the visibility the restored fragment sits inside an invisible container, and without the
        // flag onStart re-fires the biometric prompt over the password screen the user was already
        // typing into.
        //
        // Only a configuration change puts the flag in the bundle — see onSaveInstanceState.
        if (savedInstanceState?.getBoolean(STATE_PASSWORD_SHOWN) == true) {
            passwordFragmentShown = true
            revealPasswordContainer()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Saved for a configuration change ONLY. A non-null bundle in onCreate does not mean "the
        // screen rotated" — the system also hands one back after it has killed the process, and the
        // task is then restored from Recents into a brand-new process. Persisting the flag there
        // restored it as `true` on what is genuinely a cold launch, onStart early-returned, and
        // layer 1 was skipped entirely: the app opened straight onto the password screen with no
        // biometric or device-credential check. Measured on a Pixel 9 Pro emulator — after
        // `am kill`, onCreate saw `saved=true, flagInBundle=true`, while a rotation is the only
        // path where `isChangingConfigurations` is true here. The vault's contents were never at
        // risk (layer 2 still holds the PBKDF2 key), but the device-owner check was.
        //
        // The flag must therefore live exactly as long as the process: this activity's own field
        // covers a foregrounded app, and the bundle covers only the teardown that keeps the process
        // alive. If the guess is ever wrong the cost is one extra biometric prompt, not a skipped
        // one.
        if (isChangingConfigurations) {
            outState.putBoolean(STATE_PASSWORD_SHOWN, passwordFragmentShown)
        }
    }

    override fun onStart() {
        super.onStart()
        if (passwordFragmentShown) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            showNoScreenLockWarning()
        } else {
            promptForAuth()
        }
    }

    private fun showNoScreenLockWarning() {
        AlertDialog.Builder(this)
            .setTitle(R.string.no_screen_lock_title)
            .setMessage(R.string.no_screen_lock_body)
            .setCancelable(false)
            .setPositiveButton(R.string.action_continue_anyway) { _, _ -> swapInPasswordFragment() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> finishAffinity() }
            .setNeutralButton(android.R.string.ok) { _, _ ->
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                finishAffinity()
            }
            .show()
    }

    private fun promptForAuth() {
        val manager = BiometricManager.from(this)
        val strongOrCredential = Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        val weakOrCredential = Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL

        val allowedAuthenticators = when {
            manager.canAuthenticate(strongOrCredential) == BiometricManager.BIOMETRIC_SUCCESS ->
                strongOrCredential
            manager.canAuthenticate(weakOrCredential) == BiometricManager.BIOMETRIC_SUCCESS ->
                weakOrCredential
            else ->
                Authenticators.DEVICE_CREDENTIAL
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                swapInPasswordFragment()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    // User's active refusal (§1.1) — close silently as intended.
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> finishAffinity()
                    // System-side failure (LOCKOUT, LOCKOUT_PERMANENT, HW_UNAVAILABLE,
                    // NO_BIOMETRICS, …). Surface the platform's localized reason so the user
                    // knows why the app is closing rather than seeing it vanish silently.
                    else -> showAuthErrorAndFinish(errString)
                }
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setAllowedAuthenticators(allowedAuthenticators)
            // Only prompts with BIOMETRIC-only allow a negative button; DEVICE_CREDENTIAL prompts do not.
            .apply {
                if (allowedAuthenticators and Authenticators.DEVICE_CREDENTIAL == 0) {
                    setNegativeButtonText(getString(R.string.biometric_prompt_cancel))
                }
            }
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info)
    }

    private fun showAuthErrorAndFinish(errString: CharSequence) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.biometric_error_title)
            .setMessage(errString)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finishAffinity() }
            .show()
    }

    private fun swapInPasswordFragment() {
        if (passwordFragmentShown) return
        passwordFragmentShown = true
        revealPasswordContainer()
        supportFragmentManager.beginTransaction()
            .replace(R.id.password_container, AppPasswordFragment())
            .commit()
    }

    /** Layout-level half of showing layer 2, without the fragment transaction. */
    private fun revealPasswordContainer() {
        findViewById<View>(R.id.lockTitle).visibility = View.GONE
        findViewById<View>(R.id.password_container).visibility = View.VISIBLE
    }

    private companion object {
        const val STATE_PASSWORD_SHOWN = "password_fragment_shown"
    }
}

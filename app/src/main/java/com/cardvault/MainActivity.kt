package com.cardvault

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.cardvault.data.prefs.SecurePreferences
import com.cardvault.security.SessionManager
import com.cardvault.ui.auth.BiometricLockActivity
import com.cardvault.ui.onboarding.OnboardingActivity

/**
 * The vault's front door. Applies FLAG_SECURE, then routes:
 *   - not onboarded → [OnboardingActivity]
 *   - onboarded but locked → [BiometricLockActivity]
 *   - unlocked → hosts the main nav graph
 *
 * onStop is where the "lock immediately on background" policy is enforced (§1.4, §8).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SecurePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        super.onCreate(savedInstanceState)
        prefs = SecurePreferences(this)

        when {
            !prefs.isOnboardingDone() -> {
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
            }
            !SessionManager.isUnlocked() -> {
                startActivity(Intent(this, BiometricLockActivity::class.java))
                finish()
            }
            else -> setContentView(R.layout.activity_main)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) return
        // Lock-on-background policy: default ON. When ON, we drop the key + Keystore alias so
        // returning from background forces the full biometric + password flow.
        if (prefs.getLockOnBackground()) {
            SessionManager.lock()
        }
    }

    override fun onResume() {
        super.onResume()
        // If we lost the key while backgrounded (either because the policy is ON, or because
        // the OS killed the process), route back through the lock screen.
        if (prefs.isOnboardingDone() && !SessionManager.isUnlocked()) {
            startActivity(Intent(this, BiometricLockActivity::class.java))
            finish()
        }
    }
}

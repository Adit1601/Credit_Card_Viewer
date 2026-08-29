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
 * onUserLeaveHint + onStop are where the "lock immediately on background" policy is enforced
 * (§1.4, §8).
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

    /**
     * Set for the one hop where the app itself puts a system activity in front of the user as part
     * of an in-app flow — currently only the CAMERA permission request (§3.5). See [onUserLeaveHint].
     */
    private var inAppExcursion = false

    /**
     * Called by a fragment immediately before it launches a system activity that is part of the
     * flow the user is already in, so the next [onUserLeaveHint] is not mistaken for the user
     * leaving. Deliberately one-shot and fail-secure: forgetting to call it locks the vault, which
     * is merely inconvenient, while a flag that stuck would keep it unlocked.
     */
    fun beginInAppExcursion() {
        inAppExcursion = true
    }

    /**
     * Called when that excursion has resolved — the permission callback fires whether or not a
     * dialog was ever shown, so this is what guarantees the flag cannot outlive the one hop it was
     * armed for and absorb an unrelated departure later.
     */
    fun endInAppExcursion() {
        inAppExcursion = false
    }

    /**
     * Called before onPause when the user leaves the app — Home, Recents, or an activity start.
     *
     * onStop alone left a sub-second window: the system does not stop an activity the instant Home
     * is pressed, and returning inside that window meant the app went onPause → onResume without
     * ever stopping, so "lock immediately on background" did not lock at all. Measured on a Pixel 9
     * Pro emulator: away for 1 s did not lock, 2 s did. Locking here closes that window.
     *
     * The hook also fires when the app itself starts an activity, and the camera permission dialog
     * does not stop this activity — so locking unconditionally here locked the vault the first time
     * anyone opened the scanner, mid-grant. [beginInAppExcursion] marks that one hop. Leaving for
     * real from on top of such a dialog still stops this activity, so onStop below catches it.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (inAppExcursion) {
            inAppExcursion = false
            return
        }
        lockIfPolicyOn()
    }

    override fun onStop() {
        super.onStop()
        // A configuration change tears this activity down and rebuilds it without the app ever
        // leaving the foreground, so it must not count as backgrounding. `isFinishing` is false
        // during one — `isChangingConfigurations` is the flag that distinguishes it — so guarding
        // on isFinishing alone locked the vault on every rotation and ejected the user to the lock
        // screen, discarding whatever they were part-way through typing.
        if (isChangingConfigurations) return
        // Everything else is the app going away, finishing included. Backing out of the vault used
        // to hit the old `isFinishing` early return and skip the lock entirely, so the key stayed
        // in memory and the next launch walked straight in past both auth layers.
        lockIfPolicyOn()
    }

    /**
     * Lock-on-background policy: default ON. When ON, we drop the key + Keystore alias so
     * returning from background forces the full biometric + password flow. When the user has
     * turned it OFF they have asked for the session to outlive backgrounding, which includes
     * backing out of the app.
     */
    private fun lockIfPolicyOn() {
        if (prefs.getLockOnBackground()) {
            SessionManager.lock()
        }
    }

    override fun onResume() {
        super.onResume()
        // onCreate already called finish() when routing to onboarding or lock. Android still
        // runs onStart → onResume on the way out, so without this guard we would start
        // BiometricLockActivity a second time and stack two lock screens on top of each other.
        if (isFinishing) return
        // Clears a flag whose excursion never started (a launch that threw, say) so it cannot
        // silently absorb a later, real departure.
        inAppExcursion = false
        // If we lost the key while backgrounded (either because the policy is ON, or because
        // the OS killed the process), route back through the lock screen.
        if (prefs.isOnboardingDone() && !SessionManager.isUnlocked()) {
            startActivity(Intent(this, BiometricLockActivity::class.java))
            finish()
        }
    }
}

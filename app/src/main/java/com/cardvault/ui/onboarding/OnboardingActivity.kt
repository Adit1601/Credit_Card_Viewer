package com.cardvault.ui.onboarding

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.cardvault.R

/**
 * Hosts the two-fragment onboarding flow (Welcome → SetPassword). Shown only on the very first
 * launch and again after "Reset app" / "Forgot password" wipe the vault.
 * FLAG_SECURE is applied so password entry can't be screenshotted or seen in Recents.
 */
class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
    }
}

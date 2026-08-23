package com.cardvault.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Small typed wrapper around EncryptedSharedPreferences. Holds:
 *  - onboarding_done flag
 *  - password salt (Base64)
 *  - password-verification canary (Base64, iv||ciphertext of a known plaintext)
 *  - lock_on_background toggle
 *  - cvv_reauth_mode toggle (PER_SESSION | PER_ACTION)
 *
 * Everything here is small and secret-adjacent; the store itself is AES-256 wrapped by a
 * MasterKey backed by Android Keystore.
 */
class SecurePreferences(context: Context) {

    private val prefs: SharedPreferences = build(context.applicationContext)

    fun isOnboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
    fun setOnboardingDone(value: Boolean) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    fun getSalt(): String? = prefs.getString(KEY_SALT, null)
    fun setSalt(base64: String) = prefs.edit().putString(KEY_SALT, base64).apply()

    fun getCanary(): String? = prefs.getString(KEY_CANARY, null)
    fun setCanary(base64: String) = prefs.edit().putString(KEY_CANARY, base64).apply()

    fun getLockOnBackground(): Boolean = prefs.getBoolean(KEY_LOCK_ON_BG, true)
    fun setLockOnBackground(value: Boolean) = prefs.edit().putBoolean(KEY_LOCK_ON_BG, value).apply()

    fun getCvvReauthMode(): CvvReauthMode {
        val name = prefs.getString(KEY_CVV_REAUTH, CvvReauthMode.PER_ACTION.name)
        return runCatching { CvvReauthMode.valueOf(name!!) }.getOrDefault(CvvReauthMode.PER_ACTION)
    }
    fun setCvvReauthMode(mode: CvvReauthMode) =
        prefs.edit().putString(KEY_CVV_REAUTH, mode.name).apply()

    fun clear() = prefs.edit().clear().commit()

    companion object {
        private const val PREFS_NAME = "cardvault_secure_prefs"

        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_SALT = "salt"
        private const val KEY_CANARY = "canary"
        private const val KEY_LOCK_ON_BG = "lock_on_background"
        private const val KEY_CVV_REAUTH = "cvv_reauth_mode"

        private fun build(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}

enum class CvvReauthMode { PER_SESSION, PER_ACTION }

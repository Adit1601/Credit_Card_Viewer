package com.cardvault.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Small typed wrapper around EncryptedSharedPreferences. Holds:
 *  - onboarding_done flag
 *  - lock_on_background toggle
 *  - cvv_reauth_mode toggle (PER_SESSION | PER_ACTION)
 *
 * Salt + password-verification canary used to live here but were moved into the Room
 * `metadata` table (schema v4) so a password change can commit card ciphertext AND the
 * salt/canary rotation atomically in one Room transaction. The [readSaltForSeed] /
 * [readCanaryForSeed] / [clearSeededSecrets] accessors below are the one-shot upgrade
 * path used by VaultMetadataRepository to migrate v3 installs — nothing else should
 * touch them.
 *
 * Everything here is small and secret-adjacent; the store itself is AES-256 wrapped by a
 * MasterKey backed by Android Keystore.
 */
class SecurePreferences(context: Context) {

    private val prefs: SharedPreferences = build(context.applicationContext)

    fun isOnboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
    fun setOnboardingDone(value: Boolean) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    /** v3 → v4 seed only. Callers outside the metadata repo should use `VaultMetadataRepository`. */
    internal fun readSaltForSeed(): String? = prefs.getString(KEY_SALT, null)

    /** v3 → v4 seed only. Callers outside the metadata repo should use `VaultMetadataRepository`. */
    internal fun readCanaryForSeed(): String? = prefs.getString(KEY_CANARY, null)

    /**
     * v3 → v4 seed only. Runs synchronously (`commit()`) so the wipe is durable before the
     * seeder returns — a crash between the DB insert and this call leaves the two stores
     * temporarily double-holding the values, which is fine (the DB is authoritative and the
     * next read will re-seed idempotently or, after this returns, skip prefs entirely).
     */
    internal fun clearSeededSecrets() {
        prefs.edit().remove(KEY_SALT).remove(KEY_CANARY).commit()
    }

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

package com.cardvault.crypto

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * All cryptography for the vault lives here.
 *
 * Key model:
 *   - The user's app password derives a 256-bit AES key via PBKDF2WithHmacSHA256, 100k iterations,
 *     using the salt from [com.cardvault.data.prefs.SecurePreferences].
 *   - That key is *transiently* imported into the Android Keystore under [MASTER_ALIAS] for the
 *     duration of the unlocked session. Cipher operations run through the Keystore-backed key.
 *   - On lock (background, forgot-password, reset), the Keystore alias is deleted.
 *   - Password verification is done via a "canary": at onboarding a fixed plaintext is encrypted
 *     with the derived key and the ciphertext stored in SecurePreferences. To verify a password,
 *     we re-derive the key and try to decrypt the canary — GCM auth-tag failure means wrong password.
 */
object CryptoManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val MASTER_ALIAS = "cardvault_master_key"

    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_SIZE_BITS = 256
    private const val SALT_BYTES = 16

    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private const val CANARY_PLAINTEXT = "cardvault-canary-v1"

    private val random = SecureRandom()

    // ---- Random / derivation ----

    fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    /**
     * Derive an in-memory AES key from a password + salt. Caller is responsible for zeroing
     * the incoming char array afterwards.
     */
    fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val raw = factory.generateSecret(spec).encoded
            try {
                return SecretKeySpec(raw, "AES")
            } finally {
                raw.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    // ---- Keystore ----

    /**
     * Import a raw derived key into the Android Keystore under [MASTER_ALIAS] and return the
     * Keystore-backed handle. Any previous alias is overwritten.
     */
    fun installMasterKey(rawKey: SecretKey): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val protection = KeyProtection.Builder(
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        ks.setEntry(MASTER_ALIAS, KeyStore.SecretKeyEntry(rawKey), protection)
        return (ks.getEntry(MASTER_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun getMasterKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = ks.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null
        return entry.secretKey
    }

    fun removeMasterKey() {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(MASTER_ALIAS)) ks.deleteEntry(MASTER_ALIAS)
    }

    // ---- Encrypt / decrypt (used for card fields AND the canary) ----

    fun encrypt(plainText: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(cipherTextBase64: String, key: SecretKey): String {
        val combined = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
        require(combined.size > GCM_IV_BYTES) { "ciphertext too short" }
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val ct = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    // ---- Canary (password verification) ----

    /** Encrypt the fixed canary plaintext with [key]. Store the result at onboarding. */
    fun createCanary(key: SecretKey): String = encrypt(CANARY_PLAINTEXT, key)

    /** Returns true iff [blob] decrypts under [key] to the canonical canary plaintext. */
    fun verifyCanary(key: SecretKey, blob: String): Boolean =
        runCatching { decrypt(blob, key) == CANARY_PLAINTEXT }.getOrDefault(false)

    // ---- Base64 helpers ----

    fun base64Encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun base64Decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}

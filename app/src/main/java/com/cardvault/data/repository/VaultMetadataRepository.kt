package com.cardvault.data.repository

import androidx.room.withTransaction
import com.cardvault.data.db.AppDatabase
import com.cardvault.data.db.MetadataDao
import com.cardvault.data.db.MetadataEntry
import com.cardvault.data.prefs.SecurePreferences

/**
 * Typed access to the vault's crypto metadata — the password salt and the canary blob used
 * to verify the app password. Both live in the Room `metadata` table (schema v4) so the
 * change-password flow can rotate them atomically alongside card ciphertext.
 *
 * v3 → v4 upgrade path: a v3 install still has salt + canary in [SecurePreferences]. On
 * first read post-upgrade, [readOrSeed] copies them into the DB inside a transaction and
 * clears them from prefs. Idempotent — subsequent reads hit the DB directly.
 */
class VaultMetadataRepository(
    private val db: AppDatabase,
    private val dao: MetadataDao,
    private val prefs: SecurePreferences,
) {

    suspend fun getSalt(): String? = readOrSeed(KEY_SALT)
    suspend fun getCanary(): String? = readOrSeed(KEY_CANARY)

    /**
     * Write both keys. Callers should invoke this inside a `db.withTransaction { ... }` block
     * (composed with other writes — e.g. rotating card ciphertext) so the whole password swap
     * is one atomic step.
     */
    suspend fun putSaltAndCanary(saltB64: String, canaryB64: String) {
        dao.put(MetadataEntry(KEY_SALT, saltB64))
        dao.put(MetadataEntry(KEY_CANARY, canaryB64))
    }

    private suspend fun readOrSeed(key: String): String? {
        dao.get(key)?.let { return it }
        val prefsSalt = prefs.readSaltForSeed()
        val prefsCanary = prefs.readCanaryForSeed()
        if (prefsSalt == null || prefsCanary == null) return null
        db.withTransaction {
            dao.put(MetadataEntry(KEY_SALT, prefsSalt))
            dao.put(MetadataEntry(KEY_CANARY, prefsCanary))
        }
        prefs.clearSeededSecrets()
        return if (key == KEY_SALT) prefsSalt else prefsCanary
    }

    companion object {
        const val KEY_SALT = "salt"
        const val KEY_CANARY = "canary"
    }
}

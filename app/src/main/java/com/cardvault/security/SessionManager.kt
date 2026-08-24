package com.cardvault.security

import com.cardvault.crypto.CryptoManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.SecretKey

/**
 * Process-scoped session state for the unlocked vault.
 *
 * Holds the Keystore-backed master [SecretKey] while the app is unlocked. Any Activity/Fragment
 * that needs to encrypt or decrypt calls [requireKey]; the crypto boundary asserts the vault is
 * currently unlocked. On lock, the Keystore alias is removed as well so nothing usable remains.
 *
 *   needsReauth  — true means the next foreground has to route through BiometricLock again.
 *   cvvAuthorizedThisSession — true iff the user has already entered the app password to view a
 *                              CVV during this session (used only in PER_SESSION cvv-reauth mode).
 */
object SessionManager {

    @Volatile private var masterKey: SecretKey? = null

    @Volatile var needsReauth: Boolean = true
        private set

    @Volatile var cvvAuthorizedThisSession: Boolean = false
        @Synchronized set

    @Synchronized
    fun setMasterKey(key: SecretKey) {
        masterKey = key
        needsReauth = false
    }

    fun getMasterKey(): SecretKey? = masterKey

    fun requireKey(): SecretKey =
        masterKey ?: error("Vault is locked; requireKey() called without an active session")

    fun isUnlocked(): Boolean = masterKey != null

    /** Called on background / logout / reset. Clears in-memory key and Keystore alias. */
    @Synchronized
    fun lock() {
        masterKey = null
        cvvAuthorizedThisSession = false
        needsReauth = true
        runCatching { CryptoManager.removeMasterKey() }
    }

    /** Set by MainActivity.onStop when the user backgrounds the app. */
    @Synchronized
    fun markNeedsReauth() {
        needsReauth = true
    }

    private val writeLock = Mutex()

    /**
     * Serialise encrypt-then-persist operations against a password rotation. Change-password
     * takes this lock around the DB rewrap transaction plus the Keystore install and
     * [setMasterKey]; any encrypter that would otherwise fetch [getMasterKey] and land in
     * the post-commit / pre-setMasterKey gap queues instead and picks up the new key.
     *
     * Read paths (list/detail decryption) do NOT take this lock — a decrypt that races
     * rotation returns empty for the affected row and self-heals on the next Room emission.
     */
    suspend fun <T> withWriteLock(block: suspend () -> T): T = writeLock.withLock { block() }
}

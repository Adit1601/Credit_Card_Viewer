package com.cardvault.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.cardvault.crypto.CryptoManager
import com.cardvault.data.db.AppDatabase
import com.cardvault.data.prefs.SecurePreferences
import com.cardvault.data.repository.CardRepository
import com.cardvault.data.repository.VaultMetadataRepository
import com.cardvault.security.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val cardDao = db.cardDao()
    private val repo = CardRepository(cardDao)
    private val prefs = SecurePreferences(app)
    private val metadata = VaultMetadataRepository(db, db.metadataDao(), prefs)

    val busy = MutableLiveData(false)
    val changePasswordResult = MutableLiveData<ChangePasswordOutcome?>(null)
    val resetCompleted = MutableLiveData(false)

    /**
     * Verify old password, then re-encrypt every stored card with a key derived from the new
     * password AND rotate the salt/canary — all inside one Room transaction. Either every
     * write commits or none do, so a mid-run process kill can never leave the DB and the
     * salt/canary describing different keys.
     *
     * Keystore alias + in-memory SessionManager are updated after the transaction commits.
     * They are non-durable state (the alias is re-installed on every unlock from the
     * persisted salt), so a crash between commit and those two calls self-heals on next
     * unlock.
     */
    fun changePassword(current: CharArray, new: CharArray) {
        viewModelScope.launch {
            busy.value = true
            try {
                val outcome = withContext(Dispatchers.Default) { runChange(current, new) }
                changePasswordResult.value = outcome
            } catch (t: Throwable) {
                // Preserve cooperative cancellation; every other throw is surfaced as a
                // generic Error so the fragment's Snackbar fires and the spinner comes down.
                // Without this catch, a Room/Keystore exception would kill the launched
                // coroutine and leave `busy` stuck true, freezing the Settings screen.
                if (t is CancellationException) throw t
                changePasswordResult.value = ChangePasswordOutcome.Error
            } finally {
                // Zero the caller-supplied CharArrays even if runChange itself threw before
                // its own finally could scrub them — belt-and-braces since we can't tell here
                // whether execution reached PBKDF2.
                current.fill(' ')
                new.fill(' ')
                busy.value = false
            }
        }
    }

    private suspend fun runChange(current: CharArray, new: CharArray): ChangePasswordOutcome {
        val saltB64 = metadata.getSalt() ?: return ChangePasswordOutcome.Error
        val canary = metadata.getCanary() ?: return ChangePasswordOutcome.Error
        val oldSalt = CryptoManager.base64Decode(saltB64)

        val oldKey = CryptoManager.deriveKey(current, oldSalt)
        if (!CryptoManager.verifyCanary(oldKey, canary)) return ChangePasswordOutcome.WrongOldPassword

        val newSalt = CryptoManager.randomSalt()
        val newRawKey = CryptoManager.deriveKey(new, newSalt)
        // Canary from the raw derived key, not the Keystore-wrapped key. Same key material,
        // and verifyCanary in every read path already uses the raw derived key — so this
        // breaks the ordering dependency on installMasterKey and lets the whole rotation
        // fit inside one Room transaction.
        val newCanary = CryptoManager.createCanary(newRawKey)

        val cards = repo.getAll()
        val rewrapped = cards.map { row ->
            val pan = CryptoManager.decrypt(row.encryptedCardNumber, oldKey)
            val exp = CryptoManager.decrypt(row.encryptedExpiry, oldKey)
            val cvv = CryptoManager.decrypt(row.encryptedCvv, oldKey)
            row.copy(
                encryptedCardNumber = CryptoManager.encrypt(pan, newRawKey),
                encryptedExpiry = CryptoManager.encrypt(exp, newRawKey),
                encryptedCvv = CryptoManager.encrypt(cvv, newRawKey)
            )
        }

        db.withTransaction {
            cardDao.updateAll(rewrapped)
            metadata.putSaltAndCanary(CryptoManager.base64Encode(newSalt), newCanary)
        }

        val newKeystoreKey = CryptoManager.installMasterKey(newRawKey)
        SessionManager.setMasterKey(newKeystoreKey)

        return ChangePasswordOutcome.Success
    }

    fun resetAllData() {
        if (resetCompleted.value == true) return
        viewModelScope.launch {
            busy.value = true
            withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                AppDatabase.wipe(ctx)
                SecurePreferences(ctx).clear()
                SessionManager.lock()
            }
            busy.value = false
            resetCompleted.value = true
        }
    }

    fun setLockOnBackground(value: Boolean) = prefs.setLockOnBackground(value)

    fun setCvvReauthMode(mode: com.cardvault.data.prefs.CvvReauthMode) =
        prefs.setCvvReauthMode(mode)
}

enum class ChangePasswordOutcome { Success, WrongOldPassword, Error }

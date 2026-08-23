package com.cardvault.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.cardvault.crypto.CryptoManager
import com.cardvault.data.db.AppDatabase
import com.cardvault.data.prefs.SecurePreferences
import com.cardvault.data.repository.CardRepository
import com.cardvault.security.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CardRepository(AppDatabase.get(app).cardDao())
    private val prefs = SecurePreferences(app)

    val busy = MutableLiveData(false)
    val changePasswordResult = MutableLiveData<ChangePasswordOutcome?>(null)
    val resetCompleted = MutableLiveData(false)

    /**
     * Verify old password, then re-encrypt every stored card with a key derived from the new
     * password, in a single Room transaction. Salt/canary/Keystore alias are rotated only after
     * the transaction commits, so a mid-run process kill either rolls back the DB entirely (old
     * password still works) or reaches the tiny window between commit and prefs update.
     */
    fun changePassword(current: String, new: String) {
        viewModelScope.launch {
            busy.value = true
            val outcome = withContext(Dispatchers.Default) { runChange(current, new) }
            busy.value = false
            changePasswordResult.value = outcome
        }
    }

    private suspend fun runChange(current: String, new: String): ChangePasswordOutcome {
        val saltB64 = prefs.getSalt() ?: return ChangePasswordOutcome.Error
        val canary = prefs.getCanary() ?: return ChangePasswordOutcome.Error
        val oldSalt = CryptoManager.base64Decode(saltB64)

        val oldKey = CryptoManager.deriveKey(current.toCharArray(), oldSalt)
        if (!CryptoManager.verifyCanary(oldKey, canary)) return ChangePasswordOutcome.WrongOldPassword

        val newSalt = CryptoManager.randomSalt()
        val newRawKey = CryptoManager.deriveKey(new.toCharArray(), newSalt)

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

        repo.updateAll(rewrapped)

        val newKeystoreKey = CryptoManager.installMasterKey(newRawKey)
        val newCanary = CryptoManager.createCanary(newKeystoreKey)
        prefs.setSalt(CryptoManager.base64Encode(newSalt))
        prefs.setCanary(newCanary)
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

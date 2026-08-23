package com.cardvault.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.cardvault.crypto.CryptoManager
import com.cardvault.data.db.AppDatabase
import com.cardvault.data.repository.CardRepository
import com.cardvault.security.CardNetwork
import com.cardvault.security.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads a single card, decrypts its sensitive fields, and exposes the result as [state].
 * Sensitive strings never leave this ViewModel encrypted, but they also never persist here
 * across process death; they are re-derived from ciphertext on each cold load.
 */
class CardDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CardRepository(AppDatabase.get(app).cardDao())

    val state = MutableLiveData<CardDetailState?>(null)
    val deleted = MutableLiveData<Unit?>(null)
    val error = MutableLiveData<String?>(null)

    fun load(cardId: String) {
        viewModelScope.launch {
            val entity = withContext(Dispatchers.IO) { repo.getById(cardId) } ?: run {
                error.value = "Card not found"; return@launch
            }
            val key = SessionManager.getMasterKey() ?: run {
                error.value = "Vault is locked"; return@launch
            }
            val decrypted = withContext(Dispatchers.Default) {
                runCatching {
                    CardDetailState(
                        id = entity.id,
                        nickname = entity.nickname,
                        nameOnCard = entity.nameOnCard,
                        cardNumberDigits = CryptoManager.decrypt(entity.encryptedCardNumber, key)
                            .filter(Char::isDigit),
                        expiryDigits = CryptoManager.decrypt(entity.encryptedExpiry, key)
                            .filter(Char::isDigit),
                        cvvDigits = CryptoManager.decrypt(entity.encryptedCvv, key),
                        colorHex = entity.colorHex,
                        network = entity.cardNetwork
                    )
                }.getOrNull()
            }
            if (decrypted == null) {
                error.value = "Could not read card"
            } else {
                state.value = decrypted
            }
        }
    }

    fun deleteCurrent() {
        val id = state.value?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.getById(id)?.let { repo.delete(it) }
            deleted.postValue(Unit)
        }
    }
}

data class CardDetailState(
    val id: String,
    val nickname: String,
    val nameOnCard: String,
    val cardNumberDigits: String,
    val expiryDigits: String,
    val cvvDigits: String,
    val colorHex: String,
    val network: CardNetwork
)

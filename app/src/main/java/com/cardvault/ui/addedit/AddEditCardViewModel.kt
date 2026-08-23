package com.cardvault.ui.addedit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.cardvault.crypto.CryptoManager
import com.cardvault.data.db.AppDatabase
import com.cardvault.data.db.CardEntity
import com.cardvault.data.db.CardType
import com.cardvault.data.repository.CardRepository
import com.cardvault.security.CardNetwork
import com.cardvault.security.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddEditCardViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CardRepository(AppDatabase.get(app).cardDao())

    val initial = MutableLiveData<EditableCard?>(null)
    val savedEvent = MutableLiveData<Unit?>(null)
    val fatalError = MutableLiveData<String?>(null)

    fun loadIfEditing(cardId: String?) {
        if (cardId == null) return
        viewModelScope.launch {
            val entity = withContext(Dispatchers.IO) { repo.getById(cardId) } ?: return@launch
            val key = SessionManager.getMasterKey() ?: run {
                fatalError.value = "Vault is locked"; return@launch
            }
            val decrypted = withContext(Dispatchers.Default) {
                runCatching {
                    EditableCard(
                        id = entity.id,
                        nickname = entity.nickname,
                        nameOnCard = entity.nameOnCard,
                        cardNumberDigits = CryptoManager.decrypt(entity.encryptedCardNumber, key)
                            .filter(Char::isDigit),
                        expiryDigits = CryptoManager.decrypt(entity.encryptedExpiry, key)
                            .filter(Char::isDigit),
                        cvvDigits = CryptoManager.decrypt(entity.encryptedCvv, key),
                        colorHex = entity.colorHex,
                        cardType = entity.cardType,
                        sortOrder = entity.sortOrder,
                        createdAt = entity.createdAt
                    )
                }.getOrNull()
            }
            if (decrypted == null) {
                fatalError.value = "Could not read card"
            } else {
                initial.value = decrypted
            }
        }
    }

    fun save(form: FormInput, editingCardId: String?, detectedNetwork: CardNetwork) {
        viewModelScope.launch {
            val key = SessionManager.getMasterKey() ?: run {
                fatalError.value = "Vault is locked"; return@launch
            }
            withContext(Dispatchers.Default) {
                val encPan = CryptoManager.encrypt(form.cardNumberDigits, key)
                val encExp = CryptoManager.encrypt(form.expiryDigits, key)
                val encCvv = CryptoManager.encrypt(form.cvvDigits, key)

                if (editingCardId == null) {
                    val nextOrder = repo.nextSortOrder()
                    repo.insert(
                        CardEntity(
                            nickname = form.nickname.trim(),
                            nameOnCard = form.nameOnCard.trim(),
                            encryptedCardNumber = encPan,
                            encryptedExpiry = encExp,
                            encryptedCvv = encCvv,
                            colorHex = form.colorHex,
                            cardNetwork = detectedNetwork,
                            cardType = form.cardType,
                            sortOrder = nextOrder,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    val existing = repo.getById(editingCardId) ?: return@withContext
                    repo.update(
                        existing.copy(
                            nickname = form.nickname.trim(),
                            nameOnCard = form.nameOnCard.trim(),
                            encryptedCardNumber = encPan,
                            encryptedExpiry = encExp,
                            encryptedCvv = encCvv,
                            colorHex = form.colorHex,
                            cardNetwork = detectedNetwork,
                            cardType = form.cardType
                        )
                    )
                }
            }
            savedEvent.value = Unit
        }
    }
}

data class EditableCard(
    val id: String,
    val nickname: String,
    val nameOnCard: String,
    val cardNumberDigits: String,
    val expiryDigits: String,
    val cvvDigits: String,
    val colorHex: String,
    val cardType: CardType,
    val sortOrder: Int,
    val createdAt: Long
)

data class FormInput(
    val nickname: String,
    val nameOnCard: String,
    val cardNumberDigits: String,
    val expiryDigits: String,
    val cvvDigits: String,
    val colorHex: String,
    val cardType: CardType
)

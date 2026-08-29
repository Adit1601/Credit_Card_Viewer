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

    /**
     * True once a scan result has been written into the form.
     *
     * Lives here rather than in the fragment because it must survive a rotation: both delivery
     * channels (a back-stack `SavedStateHandle` LiveData and the fragment's own `arguments`)
     * replay their value to the rebuilt fragment, and re-applying would silently overwrite
     * corrections the user typed after the scan. Reset by the fragment immediately *before* it
     * navigates to the scanner, so a deliberate re-scan still lands.
     */
    var scanApplied: Boolean = false

    val initial = MutableLiveData<EditableCard?>(null)
    val savedEvent = MutableLiveData<Unit?>(null)
    val fatalError = MutableLiveData<String?>(null)

    // ---- form state that outlives the view ----------------------------------------------------
    //
    // Saved view state covers the text fields and the card-type toggle, and the framework restores
    // those on its own. It does not cover any of the four below: `dirty` and `selectedColor` are
    // plain fragment state, the palette's selection lives in an adapter that is rebuilt from
    // scratch, and `detectedNetwork` is derived. They used to be fragment fields, which meant a
    // rotation reset them and `applyInitial` had to re-run in full to put them back — overwriting
    // the user's restored input with the stored row in the process. Holding them here is what lets
    // `applyInitial` stay a one-shot.

    /** True once the stored row has been written into the form. See [loadStarted]. */
    var initialApplied: Boolean = false

    /**
     * Whether the form has unsaved changes, i.e. whether backing out should warn.
     *
     * Rotation used to clear this, so a rotated form could be abandoned without a prompt.
     */
    var dirty: Boolean = false

    var selectedColor: String = ColorPalette.default()
    var detectedNetwork: CardNetwork = CardNetwork.UNKNOWN
    var selectedCardType: CardType = CardType.UNKNOWN

    /**
     * True once the edited row has been read, set **synchronously** before the coroutine launches.
     *
     * A `initial.value != null` check would not close this: `initial` only fills in when the
     * decrypt lands, so a view recreated before then — which is exactly what returning from the
     * scanner is — would slip a second load through. Its late `initial.value = decrypted` then
     * dispatches [initial] again *after* the scan has already filled the form, and `applyInitial`
     * silently restores the stored card over the scanned one with `dirty` back to false. Rotating
     * mid-edit did the same to typed input.
     *
     * Never reset. It is scoped to the ViewModel, which is scoped to one editing session, and
     * neither failure path wants a retry: `fatalError` pops the form, and a row that has vanished
     * is not going to reappear.
     */
    private var loadStarted: Boolean = false

    fun loadIfEditing(cardId: String?) {
        if (cardId == null || loadStarted) return
        loadStarted = true
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
                        issuingBank = entity.issuingBank,
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

    /** Distinct non-empty issuing banks already stored — used to seed the autocomplete. */
    suspend fun distinctBanks(): List<String> =
        withContext(Dispatchers.IO) { repo.distinctIssuingBanks() }

    fun save(form: FormInput, editingCardId: String?, detectedNetwork: CardNetwork) {
        viewModelScope.launch {
            // Guarded by SessionManager.withWriteLock: serialises against password rotation so
            // getMasterKey() and the subsequent DB write cannot straddle a rotation and produce
            // ciphertext with the doomed old key.
            val outcome: SaveOutcome = SessionManager.withWriteLock {
                val key = SessionManager.getMasterKey()
                    ?: return@withWriteLock SaveOutcome.Locked
                // encrypt/write can fail if the user backgrounds the app mid-save with
                // lock-on-background ON: SessionManager.lock() removes the Keystore alias, and
                // Cipher.init on the still-held SecretKey handle throws. Without this catch, the
                // exception would kill the launched coroutine, savedEvent never fires, and the
                // fragment sits with its Save button disabled forever.
                withContext(Dispatchers.Default) {
                    runCatching {
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
                                    issuingBank = form.issuingBank.trim(),
                                    sortOrder = nextOrder,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            SaveOutcome.Saved
                        } else {
                            // Update path: the row we're editing may have been deleted from another
                            // path since load. Signal that back to the caller instead of silently
                            // dropping the edit and letting the fragment pop as if the save succeeded.
                            val existing = repo.getById(editingCardId)
                                ?: return@runCatching SaveOutcome.NotFound
                            repo.update(
                                existing.copy(
                                    nickname = form.nickname.trim(),
                                    nameOnCard = form.nameOnCard.trim(),
                                    encryptedCardNumber = encPan,
                                    encryptedExpiry = encExp,
                                    encryptedCvv = encCvv,
                                    colorHex = form.colorHex,
                                    cardNetwork = detectedNetwork,
                                    cardType = form.cardType,
                                    issuingBank = form.issuingBank.trim()
                                )
                            )
                            SaveOutcome.Saved
                        }
                    }.getOrElse {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        // Re-check the master key: if it's gone, the vault locked mid-save. That's
                        // a distinct terminal state from a generic encrypt failure.
                        if (SessionManager.getMasterKey() == null) SaveOutcome.Locked
                        else SaveOutcome.EncryptFailed
                    }
                }
            }
            when (outcome) {
                SaveOutcome.Saved -> savedEvent.value = Unit
                SaveOutcome.NotFound -> fatalError.value = "Card no longer exists"
                SaveOutcome.Locked -> fatalError.value = "Vault is locked"
                SaveOutcome.EncryptFailed -> fatalError.value = "Could not save card"
            }
        }
    }

    private enum class SaveOutcome { Saved, NotFound, Locked, EncryptFailed }
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
    val issuingBank: String,
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
    val cardType: CardType,
    val issuingBank: String
)

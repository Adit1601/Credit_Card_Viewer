package com.cardvault.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.cardvault.crypto.CryptoManager
import com.cardvault.data.db.AppDatabase
import com.cardvault.data.db.CardEntity
import com.cardvault.data.repository.CardRepository
import com.cardvault.security.SessionManager
import com.cardvault.util.CardFormatting
import com.cardvault.util.ExpiryUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Provides the home screen with a decrypted, display-ready list of cards.
 *
 * The raw [CardEntity] rows arriving from Room have ciphertext PAN and expiry. We decrypt
 * on Dispatchers.Default using the [SessionManager] key, mask the PAN for the tile, and
 * emit [CardDisplay] rows. If the session becomes locked mid-flight, decryption is skipped
 * and empty placeholders are surfaced — the caller shouldn't render a stale list.
 */
class CardViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CardRepository(AppDatabase.get(app).cardDao())

    /**
     * Decrypted-full list of cards, without any search filter applied. Drives:
     *   - the "X / 30" toolbar subtitle (always reflects the true total, even mid-search)
     *   - the 30-card cap check when tapping Add
     *   - the reorder path (uses adapter.currentIds() which is fed by [cards], so we
     *     disable reorder when a search query is active — see HomeFragment).
     */
    // Cancel the in-flight decrypt when a fresh Room emission arrives. Otherwise a slow
    // coroutine started for list A can complete AFTER a coroutine started for list B and
    // overwrite `value` with stale data — any subsequent filter/search then runs on the
    // wrong list until Room next emits.
    private var decryptJob: Job? = null

    val allCards: LiveData<List<CardDisplay>> = MediatorLiveData<List<CardDisplay>>().apply {
        addSource(repo.observeCards()) { entities ->
            decryptJob?.cancel()
            decryptJob = viewModelScope.launch {
                val display = withContext(Dispatchers.Default) { entities.map { it.toDisplay() } }
                value = display
            }
        }
    }

    val searchQuery = MutableLiveData("")

    /**
     * Bank filter state, driven by the chip row on Home:
     *   - null → All cards
     *   - ""   → cards with no bank set (the "Unknown" chip)
     *   - any other → cards whose issuingBank matches (case-insensitive)
     */
    val bankFilter = MutableLiveData<String?>(null)

    /** Chip-row source of truth: distinct banks present + whether any card has no bank. */
    val bankFilterOptions: LiveData<BankFilterOptions> = MediatorLiveData<BankFilterOptions>().apply {
        addSource(allCards) { list ->
            val banks = list.orEmpty()
                .map { it.issuingBank }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
            val hasUnknown = list.orEmpty().any { it.issuingBank.isEmpty() }
            value = BankFilterOptions(banks = banks, hasUnknown = hasUnknown)
        }
    }

    /**
     * Filtered list for the RecyclerView. Recomputes on any source change; text filter is
     * case-insensitive on the plaintext nickname and on the plaintext last-4 already present
     * at the tail of [CardDisplay.maskedNumber] (no extra decryption pass). Bank filter is a
     * plain equality check on the plaintext `issuingBank`.
     */
    val cards: LiveData<List<CardDisplay>> = MediatorLiveData<List<CardDisplay>>().apply {
        val recompute = {
            val list = allCards.value.orEmpty()
            val q = searchQuery.value.orEmpty().trim()
            val bank = bankFilter.value
            value = list
                .let { l -> if (q.isEmpty()) l else l.filter { it.matches(q) } }
                .let { l -> if (bank == null) l else l.filter { it.matchesBank(bank) } }
        }
        addSource(allCards) { recompute() }
        addSource(searchQuery) { recompute() }
        addSource(bankFilter) { recompute() }
    }

    private fun CardDisplay.matches(q: String): Boolean {
        if (nickname.contains(q, ignoreCase = true)) return true
        val last4 = maskedNumber.takeLast(4)
        return last4.all(Char::isDigit) && last4.contains(q)
    }

    private fun CardDisplay.matchesBank(filter: String): Boolean =
        if (filter.isEmpty()) issuingBank.isEmpty()
        else issuingBank.equals(filter, ignoreCase = true)

    fun applyReorder(orderedIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) { repo.applyOrder(orderedIds) }
    }

    fun deleteById(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.getById(id)?.let { repo.delete(it) }
        }
    }

    private fun CardEntity.toDisplay(): CardDisplay {
        val key = SessionManager.getMasterKey()
        val pan = key?.let { runCatching { CryptoManager.decrypt(encryptedCardNumber, it) }.getOrNull() }.orEmpty()
        val exp = key?.let { runCatching { CryptoManager.decrypt(encryptedExpiry, it) }.getOrNull() }.orEmpty()
        return CardDisplay(
            id = id,
            nickname = nickname,
            nameOnCard = nameOnCard,
            maskedNumber = CardFormatting.maskPan(pan),
            expiry = CardFormatting.formatExpiry(exp),
            colorHex = colorHex,
            network = cardNetwork,
            cardType = cardType,
            issuingBank = issuingBank,
            isExpiringSoon = ExpiryUtil.isExpiringSoon(exp)
        )
    }
}

data class BankFilterOptions(
    val banks: List<String>,
    val hasUnknown: Boolean
)

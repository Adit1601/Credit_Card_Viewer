package com.cardvault.data.repository

import androidx.lifecycle.LiveData
import com.cardvault.data.db.CardDao
import com.cardvault.data.db.CardEntity

/**
 * Storage boundary: opaque to encryption. It hands out `CardEntity` rows whose sensitive
 * fields are still ciphertext. Encryption/decryption happens in the ViewModel layer,
 * using the key held by [com.cardvault.security.SessionManager].
 */
class CardRepository(private val dao: CardDao) {

    fun observeCards(): LiveData<List<CardEntity>> = dao.observeAllCards()

    suspend fun getAll(): List<CardEntity> = dao.getAllCards()
    suspend fun getById(id: String): CardEntity? = dao.getCardById(id)
    suspend fun nextSortOrder(): Int = dao.maxSortOrder() + 1
    suspend fun distinctIssuingBanks(): List<String> = dao.distinctIssuingBanks()

    suspend fun insert(card: CardEntity) = dao.insert(card)
    suspend fun update(card: CardEntity) = dao.update(card)
    suspend fun updateAll(cards: List<CardEntity>) = dao.updateAll(cards)
    suspend fun delete(card: CardEntity) = dao.delete(card)

    suspend fun applyOrder(orderedIds: List<String>) = dao.applySortOrders(orderedIds)

    suspend fun deleteAll() = dao.deleteAll()
}

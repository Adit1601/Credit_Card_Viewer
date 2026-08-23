package com.cardvault.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface CardDao {

    @Query("SELECT * FROM cards ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAllCards(): LiveData<List<CardEntity>>

    @Query("SELECT * FROM cards ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllCards(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun getCardById(id: String): CardEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM cards")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(card: CardEntity)

    @Update
    suspend fun update(card: CardEntity)

    @Transaction
    suspend fun updateAll(cards: List<CardEntity>) {
        cards.forEach { update(it) }
    }

    @Delete
    suspend fun delete(card: CardEntity)

    @Query("UPDATE cards SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)

    @Transaction
    suspend fun applySortOrders(ordered: List<String>) {
        ordered.forEachIndexed { index, id -> updateSortOrder(id, index) }
    }

    @Query("DELETE FROM cards")
    suspend fun deleteAll()
}

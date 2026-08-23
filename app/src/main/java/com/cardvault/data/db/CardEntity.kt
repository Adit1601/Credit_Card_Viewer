package com.cardvault.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cardvault.security.CardNetwork
import java.util.UUID

/**
 * A single card record. Sensitive fields are stored as base64(iv || ciphertext) strings —
 * this entity never sees plaintext PAN, expiry, or CVV.
 * Field list matches §2.3 of REQUIREMENTS.md.
 */
@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val nickname: String,
    val nameOnCard: String,
    val encryptedCardNumber: String,
    val encryptedExpiry: String,
    val encryptedCvv: String,
    val colorHex: String,
    val cardNetwork: CardNetwork,
    val cardType: CardType = CardType.UNKNOWN,
    val sortOrder: Int,
    val createdAt: Long
)

package com.cardvault.ui.home

import com.cardvault.data.db.CardType
import com.cardvault.security.CardNetwork

/**
 * Row model for the home list. Sensitive fields are already decrypted-and-masked here
 * so the adapter doesn't need access to the key.
 */
data class CardDisplay(
    val id: String,
    val nickname: String,
    val nameOnCard: String,
    val maskedNumber: String,
    val expiry: String,
    val colorHex: String,
    val network: CardNetwork,
    val cardType: CardType,
    val isExpiringSoon: Boolean
)

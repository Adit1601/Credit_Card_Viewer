package com.cardvault.data.db

import androidx.room.TypeConverter
import com.cardvault.security.CardNetwork

class Converters {
    @TypeConverter
    fun networkToString(network: CardNetwork): String = network.name

    @TypeConverter
    fun stringToNetwork(value: String): CardNetwork =
        runCatching { CardNetwork.valueOf(value) }.getOrDefault(CardNetwork.UNKNOWN)

    @TypeConverter
    fun cardTypeToString(type: CardType): String = type.name

    @TypeConverter
    fun stringToCardType(value: String): CardType =
        runCatching { CardType.valueOf(value) }.getOrDefault(CardType.UNKNOWN)
}

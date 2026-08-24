package com.cardvault.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "metadata")
data class MetadataEntry(
    @PrimaryKey val key: String,
    val value: String
)

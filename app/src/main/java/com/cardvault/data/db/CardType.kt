package com.cardvault.data.db

import androidx.annotation.StringRes
import com.cardvault.R

/**
 * User-selected tag for a card. UNKNOWN is the default for cards created before this field
 * existed (migrated in v2) and for new cards where the user did not pick a type; only the
 * three concrete values render a badge on the home tile.
 */
enum class CardType(@StringRes val labelRes: Int) {
    UNKNOWN(R.string.tile_type_unknown),
    DEBIT(R.string.tile_type_debit),
    CREDIT(R.string.tile_type_credit),
    PREPAID(R.string.tile_type_prepaid)
}

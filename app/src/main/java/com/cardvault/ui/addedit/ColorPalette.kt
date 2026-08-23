package com.cardvault.ui.addedit

/**
 * The fixed palette of 12 card colors exposed in the color picker (§4.2). Kept as hex
 * strings so the DB stores them verbatim in the `colorHex` column.
 */
object ColorPalette {
    val hexes: List<String> = listOf(
        "#2A3140", // card_slate
        "#1F3A6B", // card_navy
        "#155E6B", // card_teal
        "#1F5E3A", // card_forest
        "#5E5A1F", // card_olive
        "#8A5A1F", // card_amber
        "#8A3B1F", // card_rust
        "#7A1F2B", // card_crimson
        "#5C1F5E", // card_plum
        "#2B1F7A", // card_indigo
        "#3A3F4A", // card_graphite
        "#0F1218"  // card_black
    )

    fun default(): String = hexes.first()
}

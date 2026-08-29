package com.cardvault.util

/**
 * Small pure-function helpers shared between the home tile, the detail screen, and the
 * live preview on the add/edit screen. Everything here operates on strings only — no
 * decryption, no state.
 */
object CardFormatting {

    /** Insert a space every 4 digits. Non-digit chars are stripped first. */
    fun formatPanForDisplay(input: String): String {
        val digits = input.filter(Char::isDigit)
        if (digits.isEmpty()) return ""
        return buildString {
            digits.forEachIndexed { i, c ->
                if (i > 0 && i % 4 == 0) append(' ')
                append(c)
            }
        }
    }

    /**
     * Home-tile mask: last 4 visible, everything else replaced with a bullet.
     * "4111111111111234" → "•••• •••• •••• 1234"
     */
    fun maskPan(input: String): String {
        val digits = input.filter(Char::isDigit)
        if (digits.length <= 4) return digits.padStart(4, '•').chunked(4).joinToString(" ")
        val hidden = "•".repeat(digits.length - 4)
        val combined = hidden + digits.takeLast(4)
        return combined.chunked(4).joinToString(" ")
    }

    /**
     * The visible digits of a string already masked by [maskPan] — i.e. the last 4 of the PAN.
     *
     * Pull them out by filtering for digits, never by position: [maskPan] groups the whole masked
     * string in fours, so the trailing group is short whenever the PAN length is not a multiple of
     * 4. On a 13-, 14- or 15-digit card `takeLast(4)` therefore returned a grouping space plus
     * three characters ("•• 1"), which the search filter rejected as non-numeric — those cards
     * could not be found by their last four digits at all. Only the last 4 characters of a masked
     * number are ever digits, so filtering yields exactly them regardless of grouping.
     *
     * Returns "" for an empty or fully-bulleted input (a row that failed to decrypt).
     */
    fun visibleDigitsOf(maskedNumber: String): String = maskedNumber.filter(Char::isDigit)

    /** MM/YY formatter used by both the entry field and the tile display. */
    fun formatExpiry(input: String): String {
        val digits = input.filter(Char::isDigit).take(4)
        return when {
            digits.length <= 2 -> digits
            else -> digits.substring(0, 2) + "/" + digits.substring(2)
        }
    }
}

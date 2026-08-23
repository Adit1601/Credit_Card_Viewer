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

    /** MM/YY formatter used by both the entry field and the tile display. */
    fun formatExpiry(input: String): String {
        val digits = input.filter(Char::isDigit).take(4)
        return when {
            digits.length <= 2 -> digits
            else -> digits.substring(0, 2) + "/" + digits.substring(2)
        }
    }
}

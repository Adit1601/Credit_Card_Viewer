package com.cardvault.scan

/**
 * Repairs the letter-for-digit substitutions OCR makes on embossed card numbers.
 *
 * The substitution table is one-directional and applied **only inside runs that are already
 * mostly digits**. That restriction is the whole safety argument: an unconditional `S`→`5` would
 * turn `VISA` into `VI5A` and `MASTERCARD` into `MA5TERCARD`, poisoning the alphabetic
 * blocklists that [CardScanParser] relies on to reject network names as cardholder names.
 *
 * A token qualifies when it holds at least two digits and at least as many digits as letters.
 * `4I11` (3 digits, 1 letter) is repaired; `VISA` (0 digits) and `S5` (1 digit) are not.
 */
object NumericNormalizer {

    private val SUBSTITUTIONS: Map<Char, Char> = mapOf(
        'O' to '0', 'o' to '0', 'Q' to '0', 'D' to '0',
        'I' to '1', 'l' to '1', 'i' to '1', '|' to '1', '!' to '1',
        'Z' to '2', 'z' to '2',
        'S' to '5', 's' to '5',
        'G' to '6',
        'T' to '7',
        'B' to '8',
        'g' to '9', 'q' to '9',
    )

    /** Repairs every digit-dominated whitespace-delimited token, leaving the rest untouched. */
    fun normalizeLine(line: String): String =
        line.split(WHITESPACE_KEEPING)
            .joinToString("") { chunk ->
                if (chunk.isBlank()) chunk else normalizeToken(chunk)
            }

    /** Digits of [line] after repair — the form the PAN and expiry scorers consume. */
    fun normalizedDigits(line: String): String =
        normalizeLine(line).filter(Char::isDigit)

    private fun normalizeToken(token: String): String {
        if (!isDigitDominated(token)) return token
        return buildString(token.length) {
            token.forEach { c -> append(SUBSTITUTIONS[c] ?: c) }
        }
    }

    private fun isDigitDominated(token: String): Boolean {
        var digits = 0
        var letters = 0
        token.forEach { c ->
            when {
                c.isDigit() -> digits++
                // Count only characters the table could actually move. A '/' or '-' is
                // structural punctuation, not evidence either way.
                c.isLetter() -> letters++
            }
        }
        return digits >= 2 && digits >= letters
    }

    /** Splits on whitespace but keeps the whitespace as its own chunk, so joining round-trips. */
    private val WHITESPACE_KEEPING = Regex("(?<=\\s)|(?=\\s)")
}

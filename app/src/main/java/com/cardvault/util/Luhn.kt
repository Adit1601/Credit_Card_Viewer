package com.cardvault.util

/**
 * Luhn (mod-10) check-digit validation for a PAN.
 *
 * Used in two unrelated places, which is why this lives in `util` rather than inside either
 * caller: the add/edit form (soft save-time warning) and the scanner's PAN candidate scorer.
 *
 * Deliberately a *soft* signal everywhere it is used. Real cards do fail Luhn — issuer test
 * PANs, some disposable/virtual numbers, and gift or private-label cards — so a failure means
 * "worth a second look", never "reject".
 *
 * Non-digits are stripped before validating, matching the convention in [CardFormatting]; the
 * form field itself holds grouping spaces. Anything that isn't 13–19 digits after stripping
 * returns false, so callers never have to pre-check length.
 */
object Luhn {

    fun isValid(input: String): Boolean {
        val digits = input.filter(Char::isDigit)
        if (digits.length !in 13..19) return false

        // Walk right-to-left, doubling every second digit and casting out nines.
        var sum = 0
        var doubling = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (doubling) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            doubling = !doubling
        }
        return sum % 10 == 0
    }
}

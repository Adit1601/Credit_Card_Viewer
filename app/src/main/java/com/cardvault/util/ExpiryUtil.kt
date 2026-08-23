package com.cardvault.util

import java.time.YearMonth

/**
 * Month-granular "expires soon" check. Stored expiries are `MMYY` strings (4 digits, no
 * separator). A card is treated as expiring soon if the expiry month is on or before
 * today's month plus [withinMonths] — inclusive of already-expired cards, so a lapsed
 * card is still flagged for the user's attention.
 *
 * Two months out from today's month is our approximation of "within 60 days" — the
 * stored data has no day-of-month precision anyway.
 */
object ExpiryUtil {

    fun isExpiringSoon(
        mmyy: String,
        today: YearMonth = YearMonth.now(),
        withinMonths: Int = 2
    ): Boolean {
        val digits = mmyy.filter(Char::isDigit)
        if (digits.length != 4) return false
        val month = digits.substring(0, 2).toIntOrNull() ?: return false
        val yearYY = digits.substring(2, 4).toIntOrNull() ?: return false
        if (month !in 1..12) return false
        val expiry = runCatching { YearMonth.of(2000 + yearYY, month) }.getOrNull() ?: return false
        return !expiry.isAfter(today.plusMonths(withinMonths.toLong()))
    }
}

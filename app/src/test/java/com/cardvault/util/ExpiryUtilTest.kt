package com.cardvault.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class ExpiryUtilTest {

    private val today = YearMonth.of(2026, 8) // pin today to Aug 2026 for deterministic tests

    @Test fun thisMonth_isExpiringSoon() =
        assertTrue(ExpiryUtil.isExpiringSoon("0826", today))

    @Test fun oneMonthOut_isExpiringSoon() =
        assertTrue(ExpiryUtil.isExpiringSoon("0926", today))

    @Test fun twoMonthsOut_isExpiringSoon() =
        assertTrue(ExpiryUtil.isExpiringSoon("1026", today))

    @Test fun threeMonthsOut_isNotExpiringSoon() =
        assertFalse(ExpiryUtil.isExpiringSoon("1126", today))

    @Test fun expiredLastMonth_stillExpiring() =
        assertTrue(ExpiryUtil.isExpiringSoon("0726", today))

    @Test fun expiredLastYear_stillExpiring() =
        assertTrue(ExpiryUtil.isExpiringSoon("1225", today))

    @Test fun farFuture_isNotExpiringSoon() =
        assertFalse(ExpiryUtil.isExpiringSoon("1230", today))

    @Test fun crossYearBoundary_worksForward() {
        // Nov 2026 + 2 months = Jan 2027
        val november = YearMonth.of(2026, 11)
        assertTrue(ExpiryUtil.isExpiringSoon("0127", november))
        assertFalse(ExpiryUtil.isExpiringSoon("0227", november))
    }

    @Test fun malformedInputs_returnFalse() {
        assertFalse(ExpiryUtil.isExpiringSoon("", today))
        assertFalse(ExpiryUtil.isExpiringSoon("826", today))
        assertFalse(ExpiryUtil.isExpiringSoon("12345", today))
        assertFalse(ExpiryUtil.isExpiringSoon("1326", today)) // month 13
        assertFalse(ExpiryUtil.isExpiringSoon("0026", today)) // month 0
        assertFalse(ExpiryUtil.isExpiringSoon("abcd", today))
    }

    @Test fun customWithinMonthsParameter_narrowsWindow() {
        // withinMonths=0 → only current month qualifies
        assertTrue(ExpiryUtil.isExpiringSoon("0826", today, withinMonths = 0))
        assertFalse(ExpiryUtil.isExpiringSoon("0926", today, withinMonths = 0))
    }

    // ================================================================================
    // Input handling and the 2000-offset assumption.
    // ================================================================================

    @Test fun separatorsAreStrippedBeforeParsing() {
        // Stored expiries are bare MMYY, but the detail screen and the scanner both hand around
        // the display form. Accepting both means a caller cannot get it subtly wrong.
        assertTrue(ExpiryUtil.isExpiringSoon("09/26", today))
        assertTrue(ExpiryUtil.isExpiringSoon("09-26", today))
        assertEquals(
            ExpiryUtil.isExpiringSoon("0926", today),
            ExpiryUtil.isExpiringSoon("09/26", today),
        )
    }

    @Test fun sixDigitYearFormIsRejectedRatherThanTruncated() {
        // "092026" is 6 digits. Silently reading the first four would give 09/20 — a card that
        // expired six years ago — so it must be rejected outright.
        assertFalse(ExpiryUtil.isExpiringSoon("092026", today))
        assertFalse(ExpiryUtil.isExpiringSoon("09/2026", today))
    }

    @Test fun twoDigitYearIsAlwaysReadAs20xx() {
        // The stored format carries no century, so "0100" is January 2000, not 2100. A card from
        // the year 2000 is long expired and therefore flagged.
        assertTrue(ExpiryUtil.isExpiringSoon("0100", today))
        assertFalse(ExpiryUtil.isExpiringSoon("0199", today))
    }

    @Test fun aWideWindowFlagsTheFarFuture() {
        assertFalse(ExpiryUtil.isExpiringSoon("0835", today))
        assertTrue(ExpiryUtil.isExpiringSoon("0835", today, withinMonths = 120))
    }

    @Test fun decemberBoundaryLooksIntoTheNextYear() {
        val december = YearMonth.of(2026, 12)
        assertTrue(ExpiryUtil.isExpiringSoon("0127", december))
        assertTrue(ExpiryUtil.isExpiringSoon("0227", december))
        assertFalse(ExpiryUtil.isExpiringSoon("0327", december))
    }

    @Test fun everyMonthOfTheYearParses() {
        // Guards the substring/toInt path against an off-by-one on the month field.
        (1..12).forEach { month ->
            val mmyy = "%02d26".format(month)
            val expected = month <= 10 // today is Aug 2026, window is +2 months
            assertEquals("mmyy=[$mmyy]", expected, ExpiryUtil.isExpiringSoon(mmyy, today))
        }
    }
}

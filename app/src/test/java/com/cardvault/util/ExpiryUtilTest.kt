package com.cardvault.util

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
}

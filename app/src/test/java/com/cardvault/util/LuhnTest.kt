package com.cardvault.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuhnTest {

    // ---- Known-good PANs across networks and lengths ----

    @Test fun visa16_isValid() = assertTrue(Luhn.isValid("4111111111111111"))

    @Test fun visa13_isValid() = assertTrue(Luhn.isValid("4222222222222"))

    @Test fun mastercard_isValid() = assertTrue(Luhn.isValid("5555555555554444"))

    @Test fun amex15_isValid() = assertTrue(Luhn.isValid("378282246310005"))

    @Test fun diners14_isValid() = assertTrue(Luhn.isValid("30569309025904"))

    @Test fun discover_isValid() = assertTrue(Luhn.isValid("6011111111111117"))

    @Test fun jcb_isValid() = assertTrue(Luhn.isValid("3530111333300000"))

    @Test fun rupay16_isValid() = assertTrue(Luhn.isValid("6221260000000000"))

    @Test fun nineteenDigits_isValid() = assertTrue(Luhn.isValid("4917610000000000003"))

    // ---- The failures we actually care about catching ----

    @Test fun lastDigitOffByOne_isInvalid() =
        assertFalse(Luhn.isValid("4111111111111112"))

    @Test fun transposedAdjacentDigits_isInvalid() {
        // 378282... -> 372882..., the classic typo Luhn exists to catch
        assertFalse(Luhn.isValid("372882246310005"))
    }

    // ---- Separators are stripped, matching CardFormatting's convention ----

    @Test fun groupingSpaces_areIgnored() =
        assertTrue(Luhn.isValid("4111 1111 1111 1111"))

    @Test fun hyphens_areIgnored() =
        assertTrue(Luhn.isValid("4111-1111-1111-1111"))

    // ---- Length gate: callers never pre-check, so out-of-range is false regardless ----

    @Test fun tooShort_isInvalidEvenWhenChecksumPasses() {
        // 12 digits whose mod-10 sum is 0 — rejected on length alone
        assertFalse(Luhn.isValid("411111111117"))
    }

    @Test fun tooLong_isInvalidEvenWhenChecksumPasses() {
        // 20 digits whose mod-10 sum is 0 — rejected on length alone
        assertFalse(Luhn.isValid("49176100000000000034"))
    }

    @Test fun partiallyTypedPan_isInvalid() {
        // Why the form checks on focus-loss and not per-keystroke: a PAN in mid-entry
        // is almost always Luhn-invalid, so a live check would flag the field the whole
        // time the user is typing.
        assertFalse(Luhn.isValid("411111111111"))
        assertFalse(Luhn.isValid("41111111111111"))
        assertFalse(Luhn.isValid("411111111111111"))
    }

    @Test fun emptyAndJunk_areInvalid() {
        assertFalse(Luhn.isValid(""))
        assertFalse(Luhn.isValid("    "))
        assertFalse(Luhn.isValid("not a card number"))
    }

    @Test fun allZeros_isValidChecksum_soLengthIsTheOnlyGuard() {
        // Sixteen zeros trivially sum to 0. Luhn is a typo detector, not a validity
        // oracle — the form still relies on network detection and the user's own eyes.
        assertTrue(Luhn.isValid("0000000000000000"))
    }

    // ================================================================================
    // Properties, rather than more examples. Luhn's whole value is the class of errors it
    // is guaranteed to catch, and that is worth asserting directly.
    // ================================================================================

    @Test fun anySingleDigitError_isAlwaysCaught() {
        // The guarantee the amber warning on the add/edit form rests on: mistype exactly one
        // digit of a real card number and the checksum fails, at every position.
        val valid = "4111111111111111"
        assertTrue(Luhn.isValid(valid))
        valid.indices.forEach { i ->
            ('0'..'9').filter { it != valid[i] }.forEach { replacement ->
                val mutated = valid.substring(0, i) + replacement + valid.substring(i + 1)
                assertFalse("mutated=[$mutated] at index $i", Luhn.isValid(mutated))
            }
        }
    }

    @Test fun lengthBoundsAreExactlyThirteenToNineteen() {
        // Just outside the range, checksum notwithstanding. Twelve and twenty zeros both have a
        // trivially valid checksum, so length is the only thing rejecting them.
        assertFalse(Luhn.isValid("0".repeat(12)))
        assertTrue(Luhn.isValid("0".repeat(13)))
        assertTrue(Luhn.isValid("0".repeat(19)))
        assertFalse(Luhn.isValid("0".repeat(20)))
    }

    @Test fun lengthIsCountedAfterStrippingSeparators() {
        // A fully grouped 16-digit PAN is 19 characters. If length were checked before stripping,
        // the form field's own display value would be rejected.
        val grouped = "4111 1111 1111 1111"
        assertEquals(19, grouped.length)
        assertTrue(Luhn.isValid(grouped))
        // And a 12-digit number padded out to 19 characters with separators is still too short.
        assertFalse(Luhn.isValid("0-0-0-0-0-0-0-0-0-0"))
    }

    @Test fun aTruncatedValidPan_isNotItselfValid() {
        // Called on every keystroke, so every prefix of a real PAN gets tested. None of the
        // shorter-but-in-range prefixes of this number happen to check out.
        val valid = "4111111111111111"
        (13..15).forEach { len ->
            assertFalse("prefix len=$len", Luhn.isValid(valid.take(len)))
        }
    }
}

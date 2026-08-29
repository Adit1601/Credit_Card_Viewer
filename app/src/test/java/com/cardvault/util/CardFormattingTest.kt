package com.cardvault.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CardFormatting] is the only thing standing between stored digits and what the user reads on
 * the home tile, the detail screen and the live preview, so its edge cases are user-visible even
 * though the functions are three lines each.
 *
 * Two of the tests below pin *limitations* rather than desired behaviour ([amex15_groupsAsFourFourFourThree],
 * [maskPan_groupingIsRaggedForOddLengths]). They are here so that changing either one is a
 * deliberate act with a failing test attached, not an accident.
 */
class CardFormattingTest {

    // ------------------------------------------------------ formatPanForDisplay

    @Test fun empty_formatsToEmpty() {
        assertEquals("", CardFormatting.formatPanForDisplay(""))
        assertEquals("", CardFormatting.formatPanForDisplay("   "))
        assertEquals("", CardFormatting.formatPanForDisplay("no digits here"))
    }

    @Test fun shortPrefixes_getNoSeparatorUntilTheFifthDigit() {
        // The live preview calls this on every keystroke, so partial input is the common case.
        assertEquals("4", CardFormatting.formatPanForDisplay("4"))
        assertEquals("41", CardFormatting.formatPanForDisplay("41"))
        assertEquals("411", CardFormatting.formatPanForDisplay("411"))
        assertEquals("4111", CardFormatting.formatPanForDisplay("4111"))
        assertEquals("4111 1", CardFormatting.formatPanForDisplay("41111"))
    }

    @Test fun sixteenDigits_groupInFours() {
        assertEquals("4111 1111 1111 1111", CardFormatting.formatPanForDisplay("4111111111111111"))
    }

    @Test fun nineteenDigits_groupInFoursWithAThreeDigitTail() {
        assertEquals(
            "4111 1111 1111 1111 111",
            CardFormatting.formatPanForDisplay("4111111111111111111")
        )
    }

    @Test fun amex15_groupsAsFourFourFourThree() {
        // Documented limitation: Amex is really printed 4-6-5. The formatter is length-agnostic
        // and groups everything in fours, so an Amex renders 4-4-4-3. Pinned deliberately —
        // see the "Limitations" note in the class KDoc.
        assertEquals("3782 8224 6310 005", CardFormatting.formatPanForDisplay("378282246310005"))
    }

    @Test fun nonDigits_areStrippedBeforeGrouping() {
        val expected = "4111 1111 1111 1111"
        assertEquals(expected, CardFormatting.formatPanForDisplay("4111-1111-1111-1111"))
        assertEquals(expected, CardFormatting.formatPanForDisplay("4111 1111 1111 1111"))
        assertEquals(expected, CardFormatting.formatPanForDisplay("4111.1111/1111 1111"))
        assertEquals(expected, CardFormatting.formatPanForDisplay(" 4111a1111b1111c1111 "))
    }

    @Test fun formattingIsIdempotent() {
        // The add/edit TextWatcher feeds its own output back in, so a second pass must be a no-op.
        listOf("", "4", "41111", "4111111111111111", "378282246310005", "4111111111111111111")
            .forEach { raw ->
                val once = CardFormatting.formatPanForDisplay(raw)
                assertEquals("raw=[$raw]", once, CardFormatting.formatPanForDisplay(once))
            }
    }

    // ----------------------------------------------------------------- maskPan

    @Test fun maskPan_sixteenDigits_showsOnlyTheLastFour() {
        assertEquals("•••• •••• •••• 1234", CardFormatting.maskPan("4111111111111234"))
    }

    @Test fun maskPan_isTheDocumentedHomeTileFormat() {
        // Matches the example in CardFormatting's own KDoc.
        assertEquals("•••• •••• •••• 1234", CardFormatting.maskPan("4111 1111 1111 1234"))
    }

    @Test fun maskPan_groupingIsRaggedForOddLengths() {
        // 15 digits leaves 11 bullets, so the third group straddles the bullet/digit boundary.
        // Still leaks nothing beyond the last four — only the grouping is uneven.
        assertEquals("•••• •••• •••0 005", CardFormatting.maskPan("378282246310005"))
    }

    @Test fun maskPan_fourOrFewerDigits_isPaddedNotTruncated() {
        assertEquals("••••", CardFormatting.maskPan(""))
        assertEquals("•••1", CardFormatting.maskPan("1"))
        assertEquals("••12", CardFormatting.maskPan("12"))
        assertEquals("•123", CardFormatting.maskPan("123"))
        assertEquals("1234", CardFormatting.maskPan("1234"))
    }

    @Test fun maskPan_neverRevealsAnythingButTheLastFourDigits() {
        // The invariant the home screen depends on, asserted over every supported PAN length
        // rather than a single example.
        val full = "4111111111111234567"
        (13..19).forEach { len ->
            val pan = full.take(len)
            val masked = CardFormatting.maskPan(pan)
            val visibleDigits = masked.filter(Char::isDigit)
            assertEquals("len=$len", pan.takeLast(4), visibleDigits)
            // Every hidden position is a bullet, and nothing was dropped.
            assertEquals("len=$len", len - 4, masked.count { it == '•' })
        }
    }

    @Test fun maskPan_stripsNonDigitsBeforeCountingLength() {
        assertEquals(
            CardFormatting.maskPan("4111111111111234"),
            CardFormatting.maskPan("4111-1111-1111-1234")
        )
    }

    // -------------------------------------------------------------- formatExpiry

    @Test fun expiry_partialInputIsNotSlashedEarly() {
        assertEquals("", CardFormatting.formatExpiry(""))
        assertEquals("0", CardFormatting.formatExpiry("0"))
        assertEquals("09", CardFormatting.formatExpiry("09"))
    }

    @Test fun expiry_slashAppearsOnTheThirdDigit() {
        assertEquals("09/2", CardFormatting.formatExpiry("092"))
        assertEquals("09/29", CardFormatting.formatExpiry("0929"))
    }

    @Test fun expiry_extraDigitsAreDiscarded() {
        assertEquals("09/29", CardFormatting.formatExpiry("092912"))
        // A 4-digit year cannot be entered — MM/YY only, matching what is stored.
        assertEquals("09/20", CardFormatting.formatExpiry("092029"))
    }

    @Test fun expiry_nonDigitsAreStrippedAndFormattingIsIdempotent() {
        assertEquals("09/29", CardFormatting.formatExpiry("09/29"))
        assertEquals("09/29", CardFormatting.formatExpiry("09-29"))
        assertEquals("09/29", CardFormatting.formatExpiry("a09b29c"))
        listOf("", "0", "09", "092", "0929", "09/29").forEach { raw ->
            val once = CardFormatting.formatExpiry(raw)
            assertEquals("raw=[$raw]", once, CardFormatting.formatExpiry(once))
        }
    }

    @Test fun expiry_neverEmitsMoreThanFiveCharacters() {
        // The entry field is capped visually; this pins the same guarantee in the formatter.
        listOf("1", "12", "123", "1234", "12345678", "1/2/3/4/5").forEach { raw ->
            assertTrue("raw=[$raw]", CardFormatting.formatExpiry(raw).length <= 5)
        }
    }

    // --- visibleDigitsOf ---------------------------------------------------------------------
    // Regression: home-screen search matched the last 4 by taking the last four *characters* of
    // the masked number, so any PAN length that is not a multiple of 4 handed the filter a
    // grouping space and the card became unfindable by its last four digits.

    @Test fun visibleDigits_areTheLastFourForEveryRealPanLength() {
        // 16 (Visa/MC), 15 (Amex), 14 (Diners), 13 (old Visa) — all must yield exactly the last 4.
        mapOf(
            "4111111111111234" to "1234",
            "378282246310005" to "0005",
            "30569309025904" to "5904",
            "4222222222222" to "2222"
        ).forEach { (pan, expected) ->
            val masked = CardFormatting.maskPan(pan)
            assertEquals("pan=[$pan] masked=[$masked]", expected, CardFormatting.visibleDigitsOf(masked))
        }
    }

    @Test fun visibleDigits_neverPicksUpAGroupingSpace() {
        (1..19).forEach { len ->
            val masked = CardFormatting.maskPan("9".repeat(len))
            val visible = CardFormatting.visibleDigitsOf(masked)
            assertTrue("len=$len masked=[$masked] visible=[$visible]", visible.all(Char::isDigit))
            assertEquals("len=$len masked=[$masked]", minOf(len, 4), visible.length)
        }
    }

    @Test fun visibleDigits_isEmptyWhenNothingWasDecrypted() {
        // A row whose ciphertext could not be decrypted masks to bullets only; search must not
        // treat that as a match for every query.
        assertEquals("", CardFormatting.visibleDigitsOf(CardFormatting.maskPan("")))
        assertEquals("", CardFormatting.visibleDigitsOf("•••• •••• •••• ••••"))
    }
}

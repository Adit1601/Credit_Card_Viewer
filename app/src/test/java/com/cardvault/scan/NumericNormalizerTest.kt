package com.cardvault.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class NumericNormalizerTest {

    @Test fun digitDominatedTokens_areRepaired() {
        assertEquals("4111", NumericNormalizer.normalizeLine("4I11"))
        assertEquals("1510", NumericNormalizer.normalizeLine("1S10"))
        assertEquals("4510", NumericNormalizer.normalizeLine("45IO"))
        assertEquals("8823", NumericNormalizer.normalizeLine("88Z3"))
        assertEquals("6600", NumericNormalizer.normalizeLine("66OO"))
    }

    @Test fun alphabeticTokens_areLeftAlone() {
        // The whole safety argument for the substitution table. If VISA became VI5A, the
        // cardholder-name blocklist in CardScanParser would stop recognising it as a network.
        assertEquals("VISA", NumericNormalizer.normalizeLine("VISA"))
        assertEquals("MASTERCARD", NumericNormalizer.normalizeLine("MASTERCARD"))
        assertEquals("DEBIT", NumericNormalizer.normalizeLine("DEBIT"))
        assertEquals("SIGNATURE", NumericNormalizer.normalizeLine("SIGNATURE"))
        assertEquals("GOOD THRU", NumericNormalizer.normalizeLine("GOOD THRU"))
        assertEquals("ARJUN MEHTA", NumericNormalizer.normalizeLine("ARJUN MEHTA"))
    }

    @Test fun tokenWithOneDigit_isNotRepaired() {
        // Too little evidence that this is meant to be a number at all.
        assertEquals("S5", NumericNormalizer.normalizeLine("S5"))
        assertEquals("1ST", NumericNormalizer.normalizeLine("1ST"))
    }

    @Test fun letterMajorityToken_isNotRepaired() {
        assertEquals("1SIO", NumericNormalizer.normalizeLine("1SIO"))
    }

    @Test fun mixedLine_repairsOnlyTheNumericTokens() {
        assertEquals(
            "VISA 4111 1111",
            NumericNormalizer.normalizeLine("VISA 4I11 1I11")
        )
    }

    @Test fun whitespaceIsPreserved() {
        assertEquals("4111  1111", NumericNormalizer.normalizeLine("4I11  1I11"))
        assertEquals(" 4111 ", NumericNormalizer.normalizeLine(" 4I11 "))
    }

    @Test fun punctuationIsNotEvidenceEitherWay() {
        // '/' is structural, so O5/27 is still digit-dominated and still repaired.
        assertEquals("05/27", NumericNormalizer.normalizeLine("O5/27"))
    }

    @Test fun normalizedDigits_stripsEverythingElse() {
        assertEquals(
            "4510151015101518",
            NumericNormalizer.normalizedDigits("45IO 1S10 151O 1518")
        )
    }

    @Test fun emptyAndBlank_roundTrip() {
        assertEquals("", NumericNormalizer.normalizeLine(""))
        assertEquals("   ", NumericNormalizer.normalizeLine("   "))
    }

    // ================================================================================
    // Substitution-table properties. The table is the thing most likely to be "improved"
    // later, and every one of these is a way that would break something downstream.
    // ================================================================================

    @Test fun substitutionsAreOneDirectional_digitsAreNeverTurnedIntoLetters() {
        // If the table ever became bidirectional, the alphabetic blocklists in CardScanParser
        // (VISA, PLATINUM, VALID THRU) would stop matching and card furniture would be offered
        // as the cardholder's name.
        listOf("4111", "0000", "622126", "09/29", "1234567890").forEach { input ->
            assertEquals(input, NumericNormalizer.normalizeLine(input))
        }
    }

    @Test fun theTableIsCaseSensitiveWhereTheGlyphsDiffer() {
        // 'G' reads as 6 and 'g' as 9 — they are different glyphs, not a casing accident.
        assertEquals("1936", NumericNormalizer.normalizeLine("1g3G"))
    }

    @Test fun exactlyTwoDigitsIsTheThreshold() {
        assertEquals("555", NumericNormalizer.normalizeLine("5S5"))   // 2 digits, 1 letter
        assertEquals("S5", NumericNormalizer.normalizeLine("S5"))     // 1 digit  -> untouched
    }

    @Test fun equalDigitsAndLetters_isRepaired() {
        // The rule is digits >= letters, not digits > letters. "41II" is a real half of a PAN.
        assertEquals("4111", NumericNormalizer.normalizeLine("41II"))
        // One more letter tips it the other way.
        assertEquals("4III", NumericNormalizer.normalizeLine("4III"))
    }

    @Test fun symbolSubstitutionsAreCountedAsNeitherDigitNorLetter() {
        // '|' and '!' are not letters, so they do not count against the digit majority.
        assertEquals("4111", NumericNormalizer.normalizeLine("4|11"))
        assertEquals("4111", NumericNormalizer.normalizeLine("4!11"))
    }

    @Test fun normalizedDigits_recoversTheGarbledFixturePan() {
        // The exact string from ScanFixtures.garbledDigits. Raw digit extraction yields 12
        // characters; repair yields all 16.
        assertEquals(12, "45IO 1S10 151O 1518".filter(Char::isDigit).length)
        assertEquals(
            ScanFixtures.GARBLED_PAN,
            NumericNormalizer.normalizedDigits("45IO 1S10 151O 1518")
        )
    }

    @Test fun networkAndFurnitureWordsSurviveUntouched() {
        // The safety argument for the whole class, stated as a test.
        listOf(
            "VISA", "MASTERCARD", "RuPay", "DISCOVER", "AMERICAN EXPRESS",
            "VALID THRU", "PLATINUM", "AUTHORIZED SIGNATURE", "HDFC BANK", "ARJUN MEHTA",
        ).forEach { word ->
            assertEquals(word, NumericNormalizer.normalizeLine(word))
        }
    }
}

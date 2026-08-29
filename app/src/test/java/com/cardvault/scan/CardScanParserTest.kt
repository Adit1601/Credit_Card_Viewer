package com.cardvault.scan

import com.cardvault.security.CardNetwork
import com.cardvault.security.CardNetworkDetector
import com.cardvault.ui.addedit.BankSuggestions
import com.cardvault.util.Luhn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardScanParserTest {

    private val vocabulary = BankSuggestions.combined(emptyList())

    private fun parse(frame: OcrFrame) = CardScanParser.parse(frame, vocabulary)

    // ---------------------------------------------------------------- happy paths

    @Test fun visaFront_readsEveryField() {
        val result = parse(ScanFixtures.visaFront)
        assertEquals(ScanFixtures.VISA_PAN, result.panDigits)
        assertEquals("0929", result.expiryDigits)
        assertEquals("ARJUN MEHTA", result.nameOnCard)
        assertEquals("HDFC Bank", result.issuingBank)
    }

    @Test fun splitPanAcrossTwoLines_isReassembled() {
        // ML Kit returns the number as two side-by-side lines often enough that failing here
        // would look like "the scanner just doesn't work on my card".
        val result = parse(ScanFixtures.splitPanAcrossLines)
        assertEquals(ScanFixtures.VISA_PAN, result.panDigits)
        assertEquals("0728", result.expiryDigits)
        assertEquals("ICICI Bank", result.issuingBank)
    }

    @Test fun garbledDigits_areReadOnlyAfterNormalization() {
        // Raw digit extraction on the number line yields only 12 characters, below the 13-digit
        // floor, so this case is the normalizer earning its place.
        val panLine = ScanFixtures.garbledDigits.lines.first { it.text.contains("45IO") }
        assertEquals(12, panLine.text.count(Char::isDigit))
        val result = parse(ScanFixtures.garbledDigits)
        assertEquals(ScanFixtures.GARBLED_PAN, result.panDigits)
        assertEquals("0330", result.expiryDigits)
        assertEquals("NEHA GUPTA", result.nameOnCard)
        assertEquals("Axis Bank", result.issuingBank)
    }

    // ------------------------------------------------------------ expiry selection

    @Test fun validFromAndValidThru_theLaterDateWins() {
        // Indian debit cards print both. Taking the first regex match hands the user a card
        // that expired in 2022.
        val result = parse(ScanFixtures.rupayBothDates)
        assertEquals("0527", result.expiryDigits)
        assertEquals(ScanFixtures.RUPAY_PAN, result.panDigits)
        assertEquals("State Bank of India", result.issuingBank)
        assertEquals("PRIYA SHARMA", result.nameOnCard)
    }

    // -------------------------------------------------- CVV suppression (plan §6)

    @Test fun amex_frontCid_isNeverEmitted() {
        // Amex prints a four-digit CID on the front, above the number. A bare four-digit run has
        // no path to any output field: it is too short for a PAN and has no MM/YY separator.
        val result = parse(ScanFixtures.amexWithFrontCid)
        assertEquals(ScanFixtures.AMEX_PAN, result.panDigits)
        assertEquals("1128", result.expiryDigits)
        assertEquals("R K IYER", result.nameOnCard)
        assertEquals("American Express", result.issuingBank)
        assertNoFieldContains(result, "3782", exceptPan = true)
    }

    @Test fun backFaceWithCvvInFrame_readsPanAndNotTheCode() {
        // The reason no "front only" restriction is needed: scanning the reverse is safe by
        // construction, even with the security code sitting inches from the number.
        val result = parse(ScanFixtures.backFaceWithCvv)
        assertEquals(ScanFixtures.VISA_PAN, result.panDigits)
        assertEquals("0929", result.expiryDigits)
        assertNoFieldContains(result, "123", exceptPan = true)
        // "AUTHORIZED SIGNATURE" and the service number are furniture, not a cardholder.
        assertNull(result.nameOnCard)
    }

    // ---------------------------------------------------------------- bank resolution

    @Test fun hdfcAndHsbc_areNotConfused() {
        assertEquals("HDFC Bank", parse(ScanFixtures.hdfcCard).issuingBank)
        assertEquals("HSBC", parse(ScanFixtures.hsbcCard).issuingBank)
    }

    @Test fun mixedCaseBankMark_resolves() {
        assertEquals("Kotak Mahindra Bank", parse(ScanFixtures.mixedCaseBank).issuingBank)
    }

    @Test fun missingBankMark_leavesBankNull() {
        val result = parse(ScanFixtures.noBankMark)
        assertEquals(ScanFixtures.VISA_PAN, result.panDigits)
        assertNull(result.issuingBank)
        // Everything else still comes through, so the user only fills in the one gap.
        assertEquals("1227", result.expiryDigits)
        assertEquals("SUNIL NAIR", result.nameOnCard)
    }

    @Test fun bankMarkIsNotAlsoOfferedAsTheName() {
        val result = parse(ScanFixtures.visaFront)
        assertEquals("HDFC Bank", result.issuingBank)
        assertEquals("ARJUN MEHTA", result.nameOnCard)
    }

    // ---------------------------------------------------------------- negative cases

    @Test fun frameWithNoCard_returnsEverythingNull() {
        val result = parse(ScanFixtures.notACard)
        assertTrue(result.isEmpty)
        // Specifically: "GROCERY STORE" is two uppercase words and is not a cardholder name.
        assertNull(result.nameOnCard)
    }

    @Test fun emptyFrame_returnsEverythingNull() {
        assertTrue(parse(ScanFixtures.emptyFrame).isEmpty)
    }

    @Test fun longNonCardDigitRun_isRejected() {
        // A helpline number is 14 digits of nothing. Without a checksum, a known prefix and card
        // grouping it cannot clear the score floor.
        assertTrue(parse(ScanFixtures.phoneNumberOnly).isEmpty)
    }

    @Test fun nonLuhnNumber_isNeverEmitted() {
        // The score floor is set so that every non-Luhn signal combined (network prefix, length,
        // grouping, glyph height) tops out below it. This asserts that property directly rather
        // than trusting the arithmetic to stay true through future tuning.
        val bad = "4111 1111 1111 1112"
        assertTrue(!Luhn.isValid(bad))
        val frame = OcrFrame(
            listOf(
                OcrLine("HDFC BANK", 60, 40, 420, 82),
                OcrLine(bad, 60, 270, 900, 336),
                OcrLine("VALID THRU", 60, 400, 250, 428),
                OcrLine("09/29", 270, 398, 400, 430),
                OcrLine("ARJUN MEHTA", 60, 500, 560, 542),
            ),
            width = 1000, height = 630,
        )
        val result = parse(frame)
        assertNull(result.panDigits)
        // And because the PAN gates everything, nothing else leaks out of a frame we could not read.
        assertTrue(result.isEmpty)
    }

    @Test fun unrecognisedNetworkPrefix_stillScans() {
        // CardNetworkDetector has known BIN gaps — the 62xxxx space (UnionPay, and part of
        // RuPay's real range) resolves to UNKNOWN. Such a card loses the +50 network bonus, so
        // this asserts the score floor is survivable without it: checksum, length and grouping
        // are enough. Otherwise the gap in the detector would silently become "this scanner
        // does not work on my card".
        val pan = "6221250000000001"
        assertEquals(CardNetwork.UNKNOWN, CardNetworkDetector.detect(pan))
        assertTrue(Luhn.isValid(pan))
        val frame = OcrFrame(
            listOf(
                OcrLine("6221 2500 0000 0001", 60, 270, 900, 336),
                OcrLine("VALID THRU 08/29", 270, 398, 500, 430),
            ),
            width = 1000, height = 630,
        )
        val result = parse(frame)
        assertEquals(pan, result.panDigits)
        assertEquals("0829", result.expiryDigits)
    }

    @Test fun expiryThatIsASliceOfThePan_isRejected() {
        // "11/11" appears inside 4111111111111111. Emitting it would silently set a card to an
        // expiry that came from its own number.
        val frame = OcrFrame(
            listOf(
                OcrLine("4111 1111 1111 1111", 60, 270, 900, 336),
                OcrLine("11/11", 270, 398, 400, 430),
            ),
            width = 1000, height = 630,
        )
        val result = parse(frame)
        assertEquals(ScanFixtures.VISA_PAN, result.panDigits)
        assertNull(result.expiryDigits)
    }

    @Test fun labelledExpiryWhoseDigitsOccurInThePan_isKept() {
        // The other side of the guard above, and the reason it is conditional. "1234" appears in
        // 4532 1234 5678 9014, so a 12/34 expiry is a substring of this card's own number — which
        // by the pigeonhole count happens to roughly one card in 800. Rejecting on that alone
        // meant the expiry never locked, the accumulator never settled, and the card timed out on
        // every attempt with no way for the user to get past it. `VALID THRU` is what makes it
        // real: a misread fragment of the number does not arrive labelled.
        val pan = "4532123456789014"
        assertTrue(Luhn.isValid(pan))
        assertTrue(pan.contains("1234"))
        val frame = OcrFrame(
            listOf(
                OcrLine("4532 1234 5678 9014", 60, 270, 900, 336),
                OcrLine("VALID THRU 12/34", 270, 398, 500, 430),
            ),
            width = 1000, height = 630,
        )
        val result = parse(frame)
        assertEquals(pan, result.panDigits)
        assertEquals("1234", result.expiryDigits)
    }

    @Test fun impossibleMonth_isNotAnExpiry() {
        val frame = OcrFrame(
            listOf(
                OcrLine("6521 1234 5678 9012", 60, 270, 900, 336),
                OcrLine("13/29", 270, 398, 400, 430),
                OcrLine("00/29", 270, 440, 400, 472),
            ),
            width = 1000, height = 630,
        )
        assertNotNull(parse(frame).panDigits)
        assertNull(parse(frame).expiryDigits)
    }

    @Test fun fourDigitYear_isTruncatedToTwo() {
        val frame = OcrFrame(
            listOf(
                OcrLine("4111 1111 1111 1111", 60, 270, 900, 336),
                OcrLine("VALID THRU 09/2029", 270, 398, 500, 430),
            ),
            width = 1000, height = 630,
        )
        assertEquals("0929", parse(frame).expiryDigits)
    }

    // ------------------------------------------------------------------------ helper

    /** Asserts [needle] appears in no output field, optionally allowing it inside the PAN. */
    private fun assertNoFieldContains(result: ScanCandidate, needle: String, exceptPan: Boolean) {
        if (!exceptPan) assertTrue(result.panDigits?.contains(needle) != true)
        assertTrue(result.expiryDigits?.contains(needle) != true)
        assertTrue(result.nameOnCard?.contains(needle) != true)
        assertTrue(result.issuingBank?.contains(needle) != true)
    }

    // ================================================================================
    // Scoring precedence and name rejection. These are the paths where the parser returns
    // something plausible-but-wrong rather than nothing, which is the failure mode that
    // actually costs the user money.
    // ================================================================================

    @Test fun letterHeavyLine_losesToACleanPanLine() {
        // Both numbers are Luhn-valid 16-digit PANs with card-like grouping. The -40 letter
        // penalty is the only thing separating them, so this asserts the penalty decides.
        val frame = OcrFrame(
            listOf(
                OcrLine("4111 1111 1111 1111", 60, 270, 900, 336),
                OcrLine("MEMBER SINCE 5555 5555 5555 4444", 60, 400, 900, 460),
            ),
            width = 1000, height = 630,
        )
        assertEquals(ScanFixtures.VISA_PAN, parse(frame).panDigits)
    }

    @Test fun cardFurnitureIsNeverReturnedAsTheName() {
        // A frame whose only name-shaped line is blocklisted must yield no name at all rather
        // than printing "PLATINUM SIGNATURE" as the cardholder.
        val frame = OcrFrame(
            listOf(
                OcrLine("4111 1111 1111 1111", 60, 270, 900, 336),
                OcrLine("PLATINUM SIGNATURE", 60, 500, 560, 542),
            ),
            width = 1000, height = 630,
        )
        val result = parse(frame)
        assertEquals(ScanFixtures.VISA_PAN, result.panDigits)
        assertNull(result.nameOnCard)
    }

    @Test fun sentenceCaseMarketingCopyIsNotAName() {
        // Card faces print the holder in caps; the 60% uppercase floor is what rejects straplines.
        val frame = OcrFrame(
            listOf(
                OcrLine("4111 1111 1111 1111", 60, 270, 900, 336),
                OcrLine("Your money your way", 60, 500, 560, 542),
            ),
            width = 1000, height = 630,
        )
        assertNull(parse(frame).nameOnCard)
    }

    @Test fun labelledExpiryBeatsALaterUnlabelledDate() {
        // The inverse of validFromAndValidThru: recency is the *tie-breaker*, not the primary
        // signal. A stray later date elsewhere on the card must not outrank "VALID THRU".
        val frame = OcrFrame(
            listOf(
                OcrLine("6521 1234 5678 9012", 60, 270, 900, 336),
                OcrLine("VALID THRU 05/27", 60, 398, 400, 430),
                OcrLine("05/30", 600, 398, 720, 430),
            ),
            width = 1000, height = 630,
        )
        assertEquals("0527", parse(frame).expiryDigits)
    }

    @Test fun dashAndEnDashSeparatorsAreAcceptedInAnExpiry() {
        // OCR renders the embossed slash as any of these depending on the card.
        listOf("09-29", "09\u201329", "09 / 29").forEach { rendering ->
            val frame = OcrFrame(
                listOf(
                    OcrLine("4111 1111 1111 1111", 60, 270, 900, 336),
                    OcrLine("VALID THRU $rendering", 60, 398, 500, 430),
                ),
                width = 1000, height = 630,
            )
            assertEquals("rendering=[$rendering]", "0929", parse(frame).expiryDigits)
        }
    }

    @Test fun degenerateFrameDimensions_doNotCrashOrBlockThePan() {
        // ImageProxy dimensions have been reported as 0 on some devices during teardown. The
        // bottom-third bonus is guarded; the PAN must still come through.
        val frame = OcrFrame(
            listOf(
                OcrLine("4111 1111 1111 1111", 60, 270, 900, 336),
                OcrLine("ARJUN MEHTA", 60, 500, 560, 542),
            ),
            width = 0, height = 0,
        )
        val result = parse(frame)
        assertEquals(ScanFixtures.VISA_PAN, result.panDigits)
        assertEquals("ARJUN MEHTA", result.nameOnCard)
    }

    @Test fun duplicateIdenticalPanLines_stillYieldOneNumber() {
        // ML Kit occasionally returns the same text block twice. The merged-row candidate for the
        // pair is 32 digits and must be discarded rather than winning on grouping structure.
        val frame = OcrFrame(
            listOf(
                OcrLine("4111 1111 1111 1111", 60, 270, 900, 336),
                OcrLine("4111 1111 1111 1111", 62, 271, 902, 337),
            ),
            width = 1000, height = 630,
        )
        assertEquals(ScanFixtures.VISA_PAN, parse(frame).panDigits)
    }

    @Test fun aBareThreeOrFourDigitRunHasNoPathToAnyField() {
        // §6 mechanisms 2-4 restated as a test on the shortest possible input: a security code
        // on its own in frame produces nothing, because there is no field it could occupy.
        listOf("123", "4567", "000").forEach { code ->
            val frame = OcrFrame(
                listOf(OcrLine(code, 820, 348, 900, 384)),
                width = 1000, height = 630,
            )
            assertTrue("code=[$code]", parse(frame).isEmpty)
        }
    }

    @Test fun panGatesEveryOtherField() {
        // A frame with a perfectly readable bank, expiry and name but no number returns nothing.
        // This is what stops a receipt or a laptop lid producing a plausible half-card.
        val frame = OcrFrame(
            listOf(
                OcrLine("HDFC BANK", 60, 40, 420, 82),
                OcrLine("VALID THRU 09/29", 60, 398, 500, 430),
                OcrLine("ARJUN MEHTA", 60, 500, 560, 542),
            ),
            width = 1000, height = 630,
        )
        assertTrue(parse(frame).isEmpty)
    }
}

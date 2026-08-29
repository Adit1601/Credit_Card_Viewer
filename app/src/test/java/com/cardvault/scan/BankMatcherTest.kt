package com.cardvault.scan

import com.cardvault.ui.addedit.BankSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankMatcherTest {

    private val vocabulary = BankSuggestions.combined(emptyList())

    @Test fun exactUppercaseMark_resolvesToCanonicalSpelling() {
        // The point of the whole class: a scanned card must land on the same bank filter chip
        // as a hand-typed one, so the vocabulary's spelling is returned, not the OCR text.
        assertEquals("HDFC Bank", BankMatcher.match(listOf("HDFC BANK"), vocabulary))
        assertEquals("Axis Bank", BankMatcher.match(listOf("AXIS BANK"), vocabulary))
    }

    @Test fun mixedCaseMark_resolves() {
        assertEquals("Kotak Mahindra Bank", BankMatcher.match(listOf("Kotak Mahindra Bank"), vocabulary))
        assertEquals("State Bank of India", BankMatcher.match(listOf("State Bank of India"), vocabulary))
    }

    @Test fun noiseSuffixesAreIgnored() {
        assertEquals("ICICI Bank", BankMatcher.match(listOf("ICICI BANK LTD"), vocabulary))
        assertEquals("ICICI Bank", BankMatcher.match(listOf("ICICI Bank Limited"), vocabulary))
        assertEquals("Yes Bank", BankMatcher.match(listOf("YES BANK LTD."), vocabulary))
    }

    @Test fun hdfcIsNotMistakenForHsbc() {
        // The reason fuzzyThreshold is length-sensitive. HDFC and HSBC are both four characters
        // and exactly two edits apart, so a flat "within 2 edits" rule would report HSBC for
        // every HDFC card — on an India-first app, the most likely wrong answer this feature
        // could give. With HDFC absent from the vocabulary the right answer is "I don't know".
        val withoutHdfc = vocabulary.filterNot { it.startsWith("HDFC") }
        assertNull(BankMatcher.match(listOf("HDFC BANK"), withoutHdfc))

        val withoutHsbc = vocabulary.filterNot { it == "HSBC" }
        assertNull(BankMatcher.match(listOf("HSBC"), withoutHsbc))
    }

    @Test fun bothPresent_eachResolvesToItself() {
        assertEquals("HDFC Bank", BankMatcher.match(listOf("HDFC BANK"), vocabulary))
        assertEquals("HSBC", BankMatcher.match(listOf("HSBC"), vocabulary))
    }

    @Test fun amexIsNotReadAsBankOfAmerica() {
        // "Bank of America" slugs to AMERICA, a substring of AMERICANEXPRESS. Containment alone
        // would answer Bank of America here; trying exact equality across every line first is
        // what keeps this correct.
        assertEquals("American Express", BankMatcher.match(listOf("AMERICAN EXPRESS"), vocabulary))
    }

    @Test fun exactMatchOnALaterLineBeatsFuzzyMatchOnAnEarlierOne() {
        val lines = listOf("AXIS BANKK", "HDFC BANK")
        assertEquals("HDFC Bank", BankMatcher.match(lines, vocabulary))
    }

    @Test fun singleTypoInALongerNameIsForgiven() {
        // 6+ characters get a two-edit budget, which is where OCR noise actually lands.
        assertEquals("Barclays", BankMatcher.match(listOf("BARCLAYSS"), vocabulary))
        assertEquals("Standard Chartered", BankMatcher.match(listOf("STANDARD CHARTEREO"), vocabulary))
    }

    @Test fun shortUnrelatedTokensDoNotMatch() {
        // A cardholder named YESHWANT must not become Yes Bank via containment of the
        // three-character slug YES.
        assertNull(BankMatcher.match(listOf("YESHWANT KUMAR"), vocabulary))
        assertNull(BankMatcher.match(listOf("VALID THRU"), vocabulary))
        assertNull(BankMatcher.match(listOf("AUTHORIZED SIGNATURE"), vocabulary))
        assertNull(BankMatcher.match(listOf("4111 1111 1111 1111"), vocabulary))
    }

    @Test fun emptyInputsReturnNull() {
        assertNull(BankMatcher.match(emptyList(), vocabulary))
        assertNull(BankMatcher.match(listOf("", "  ", "AB"), vocabulary))
        assertNull(BankMatcher.match(listOf("HDFC BANK"), emptyList()))
    }

    @Test fun userEnteredBankJoinsTheVocabulary() {
        // distinctIssuingBanks() feeds this, so a bank the user typed once is recognised on the
        // next scan.
        val withUserBank = BankSuggestions.combined(listOf("Federal Bank"))
        assertEquals("Federal Bank", BankMatcher.match(listOf("FEDERAL BANK"), withUserBank))
    }

    @Test fun detailedMatchReportsTheSourceLine() {
        // CardScanParser needs the index so the bank mark cannot also be offered as the name.
        val match = BankMatcher.matchDetailed(listOf("VISA", "HDFC BANK", "ARJUN MEHTA"), vocabulary)
        assertEquals("HDFC Bank", match?.canonical)
        assertEquals(1, match?.lineIndex)
    }

    // ================================================================================
    // Stage guards and slug normalisation. The three-stage order and the two length
    // constants are the whole correctness story; each of these pins one of them.
    // ================================================================================

    @Test fun vocabularyEntriesTooShortToIdentifyAnythingAreDropped() {
        // MIN_SLUG_LENGTH. A two-character bank name would fuzzy-match half the alphabet.
        assertNull(BankMatcher.match(listOf("AB BANK"), listOf("AB")))
        assertNull(BankMatcher.match(listOf("HDFC BANK"), listOf("XY", "Z")))
    }

    @Test fun aLineOfNothingButNoiseTokensCannotMatch() {
        // "BANK LIMITED" slugs to the empty string once noise is stripped, so it must not be
        // offered as a candidate at all — otherwise it would fuzzy-match the shortest slug.
        assertNull(BankMatcher.match(listOf("BANK LIMITED"), vocabulary))
        assertNull(BankMatcher.match(listOf("THE BANK OF"), vocabulary))
        assertNull(BankMatcher.match(listOf("PVT LTD"), vocabulary))
    }

    @Test fun containmentIsNotTrustedForShortSlugs() {
        // MIN_CONTAINMENT_LENGTH. HSBC slugs to four characters, so it must not be found inside
        // a longer unrelated word — and the fuzzy stage's length check cannot rescue it either.
        assertNull(BankMatcher.match(listOf("HSBCARDHOLDER"), vocabulary))
    }

    @Test fun containmentResolvesALongerLineToTheBankInsideIt() {
        // The positive side of stage 2: real cards print the bank alongside product wording.
        assertEquals("ICICI Bank", BankMatcher.match(listOf("ICICI BANK CREDIT CARD"), vocabulary))
    }

    @Test fun slugIgnoresPunctuationAndSpacing() {
        assertEquals("ICICI Bank", BankMatcher.match(listOf("I.C.I.C.I. BANK"), vocabulary))
        assertEquals("HDFC Bank", BankMatcher.match(listOf("hdfc-bank"), vocabulary))
        assertEquals("HDFC Bank", BankMatcher.match(listOf("  HDFC   BANK  "), vocabulary))
    }

    @Test fun noiseSuffixesAreStrippedFromTheVocabularySideToo() {
        // The canonical spelling returned is whatever the vocabulary holds, warts and all — the
        // point is that it is *stable*, so the bank filter chip does not fork.
        val withUserBank = BankSuggestions.combined(listOf("Federal Bank Ltd"))
        assertEquals("Federal Bank Ltd", BankMatcher.match(listOf("FEDERAL BANK"), withUserBank))
        assertEquals("Federal Bank Ltd", BankMatcher.match(listOf("Federal"), withUserBank))
    }

    @Test fun detailedMatchReportsTheLineIndexFromTheFuzzyStage() {
        // CardScanParser excludes the bank's line from name candidates, so the index has to be
        // right on every stage, not just on an exact hit.
        val match = BankMatcher.matchDetailed(
            listOf("VISA", "STANDARD CHARTEREO", "ARJUN MEHTA"),
            vocabulary,
        )
        assertEquals("Standard Chartered", match?.canonical)
        assertEquals(1, match?.lineIndex)
    }

    @Test fun aPanLineIsNeverReadAsABank() {
        assertNull(BankMatcher.match(listOf("4111 1111 1111 1111"), vocabulary))
        assertNull(BankMatcher.match(listOf("09/29", "123"), vocabulary))
        assertNull(BankMatcher.match(listOf("622126 0000 0000"), vocabulary))
    }
}

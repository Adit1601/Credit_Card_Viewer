package com.cardvault.security

import org.junit.Assert.assertEquals
import org.junit.Test

class CardNetworkDetectorTest {

    // ----- Visa -----
    @Test fun visa_singleDigitPrefix() = expect("4", CardNetwork.VISA)
    @Test fun visa_fullNumber() = expect("4111 1111 1111 1111", CardNetwork.VISA)
    @Test fun visa_dashesTolerated() = expect("4-539-1488-0343-6467", CardNetwork.VISA)

    // ----- Mastercard -----
    @Test fun mastercard_51() = expect("5100 0000 0000 0000", CardNetwork.MASTERCARD)
    @Test fun mastercard_55() = expect("5555 5555 5555 4444", CardNetwork.MASTERCARD)
    @Test fun mastercard_2221_lowBound() = expect("2221 0000 0000 0009", CardNetwork.MASTERCARD)
    @Test fun mastercard_2720_highBound() = expect("2720 0000 0000 0000", CardNetwork.MASTERCARD)
    @Test fun mastercard_2500() = expect("2500 0000 0000 0000", CardNetwork.MASTERCARD)
    @Test fun mastercard_2220_belowBoundIsUnknown() = expect("2220 0000 0000", CardNetwork.UNKNOWN)
    @Test fun mastercard_2721_aboveBoundIsUnknown() = expect("2721 0000", CardNetwork.UNKNOWN)

    // ----- Amex -----
    @Test fun amex_34() = expect("3400 000000 00009", CardNetwork.AMEX)
    @Test fun amex_37() = expect("3712 345678 90123", CardNetwork.AMEX)

    // ----- RuPay (must beat Discover on 6521 / 6522) -----
    @Test fun rupay_60() = expect("6000 0000 0000", CardNetwork.RUPAY)
    @Test fun rupay_6521_beatsDiscover65() = expect("6521 0000 0000 0000", CardNetwork.RUPAY)
    @Test fun rupay_6522_beatsDiscover65() = expect("6522 9999 9999 9999", CardNetwork.RUPAY)

    // ----- Discover -----
    @Test fun discover_6011_beatsRupay60() = expect("6011 1111 1111 1117", CardNetwork.DISCOVER)
    @Test fun discover_622126_lowBound() = expect("6221 2600 0000 0000", CardNetwork.DISCOVER)
    @Test fun discover_622925_highBound() = expect("6229 2500 0000 0000", CardNetwork.DISCOVER)
    // Just outside the 622126..622925 range there is no other rule that can catch a `62`
    // prefix: RuPay is `60`, Mastercard's numeric range is 2221..2720, and no rule matches a
    // bare `6`. So falling off either end of the Discover range lands on UNKNOWN, not RuPay.
    @Test fun discover_622125_belowBoundIsUnknown() = expect("6221 2500 0000", CardNetwork.UNKNOWN)
    @Test fun discover_622926_aboveBoundIsUnknown() = expect("6229 2600 0000", CardNetwork.UNKNOWN)
    @Test fun discover_644() = expect("6440 0000 0000", CardNetwork.DISCOVER)
    @Test fun discover_649() = expect("6490 0000 0000", CardNetwork.DISCOVER)
    @Test fun discover_65_notOverriddenByLonger() = expect("6530 0000 0000", CardNetwork.DISCOVER)

    // ----- Diners -----
    @Test fun diners_300() = expect("3000 000000 0004", CardNetwork.DINERS)
    @Test fun diners_305() = expect("3050 000000 0000", CardNetwork.DINERS)
    @Test fun diners_306_isUnknown() = expect("3060 000000 0000", CardNetwork.UNKNOWN)
    @Test fun diners_36() = expect("3600 000000 0000", CardNetwork.DINERS)
    @Test fun diners_38() = expect("3800 000000 0000", CardNetwork.DINERS)

    // ----- Progressive typing (real-time UX) -----
    @Test fun progressive_65_thenRupayOn6521() {
        assertEquals(CardNetwork.DISCOVER, CardNetworkDetector.detect("65"))
        assertEquals(CardNetwork.DISCOVER, CardNetworkDetector.detect("652"))
        // 6521 must flip to RuPay per the longest-prefix rule.
        assertEquals(CardNetwork.RUPAY, CardNetworkDetector.detect("6521"))
    }

    @Test fun progressive_60_thenDiscoverOn6011() {
        // A bare "6" is genuinely ambiguous (RuPay 60/6521/6522, Discover 6011/644-649/65,
        // and the 622126..622925 range all live under it), so there is no 1-digit rule for it
        // and the preview stays blank until the second digit arrives.
        assertEquals(CardNetwork.UNKNOWN, CardNetworkDetector.detect("6"))
        assertEquals(CardNetwork.RUPAY, CardNetworkDetector.detect("60"))
        assertEquals(CardNetwork.RUPAY, CardNetworkDetector.detect("601"))
        assertEquals(CardNetwork.DISCOVER, CardNetworkDetector.detect("6011"))
    }

    @Test fun empty_isUnknown() = expect("", CardNetwork.UNKNOWN)
    @Test fun spacesOnly_isUnknown() = expect("   ", CardNetwork.UNKNOWN)
    @Test fun sevenIsUnknown() = expect("7000 0000 0000", CardNetwork.UNKNOWN)

    // ================================================================================
    // Range and prefix boundaries. Every rule in RULES is a range or a prefix set, and the
    // digit *just outside* each one is where a detector goes wrong silently — it shows the
    // wrong logo rather than crashing, so only a test catches it.
    // ================================================================================

    // ----- Mastercard 51-55 and the 2221-2720 range -----
    @Test fun mastercard_50_isUnknown() = expect("5000 0000 0000 0000", CardNetwork.UNKNOWN)
    @Test fun mastercard_56_isUnknown() = expect("5600 0000 0000 0000", CardNetwork.UNKNOWN)
    @Test fun mastercard_2221_prefixAloneIsEnough() = expect("2221", CardNetwork.MASTERCARD)
    @Test fun mastercard_2720_prefixAloneIsEnough() = expect("2720", CardNetwork.MASTERCARD)

    // ----- Diners 300-305 -----
    @Test fun diners_299_isUnknown() = expect("2990 000000 0000", CardNetwork.UNKNOWN)
    @Test fun diners_306_prefixAloneIsUnknown() = expect("306", CardNetwork.UNKNOWN)
    @Test fun diners_37_isAmexNotDiners() = expect("37", CardNetwork.AMEX)
    @Test fun diners_35_isUnknown_jcbNotSupported() = expect("3528 0000 0000 0000", CardNetwork.UNKNOWN)

    // ----- Discover 644-649 -----
    @Test fun discover_643_isUnknown() = expect("6430 0000 0000", CardNetwork.UNKNOWN)
    @Test fun discover_650_fallsBackToThe65Prefix() = expect("6500 0000 0000", CardNetwork.DISCOVER)

    // ----- Discover 622126-622925: a 6-digit range needs 6 digits -----
    @Test fun discover_622126_exactLowBoundWithSixDigitsOnly() = expect("622126", CardNetwork.DISCOVER)
    @Test fun discover_622925_exactHighBoundWithSixDigitsOnly() = expect("622925", CardNetwork.DISCOVER)
    @Test fun discover_fiveDigitsIntoTheRange_cannotDecideYet() {
        // "62212" could still become either 622126 (Discover) or 622125 (nothing), and no
        // shorter rule covers a 62 prefix, so the preview stays blank rather than guessing.
        expect("62212", CardNetwork.UNKNOWN)
    }

    // ----- Longest-prefix precedence, both directions -----
    @Test fun progressive_65_thenBackToDiscoverOn6523() {
        // 6521/6522 are the only RuPay carve-outs under 65; 6523 must fall back to Discover
        // rather than sticking on RuPay.
        assertEquals(CardNetwork.DISCOVER, CardNetworkDetector.detect("65"))
        assertEquals(CardNetwork.RUPAY, CardNetworkDetector.detect("6521"))
        assertEquals(CardNetwork.DISCOVER, CardNetworkDetector.detect("6523"))
    }

    @Test fun progressive_6011_isDiscoverEvenThough60IsRupay() {
        assertEquals(CardNetwork.RUPAY, CardNetworkDetector.detect("60"))
        assertEquals(CardNetwork.DISCOVER, CardNetworkDetector.detect("6011 1111"))
        // 6012 has no 4-digit rule, so it falls back to RuPay's 60.
        assertEquals(CardNetwork.RUPAY, CardNetworkDetector.detect("6012 1111"))
    }

    // ----- Single ambiguous leading digits -----
    @Test fun ambiguousLeadingDigits_areUnknownUntilTheSecond() {
        // Only "4" is unambiguous at one digit. Everything else needs more input.
        assertEquals(CardNetwork.VISA, CardNetworkDetector.detect("4"))
        listOf("2", "3", "5", "6", "0", "1", "8", "9").forEach { d ->
            assertEquals("digit=[$d]", CardNetwork.UNKNOWN, CardNetworkDetector.detect(d))
        }
    }

    @Test fun twoAndThreeDigitPrefixesInsideNoRule_areUnknown() {
        expect("22", CardNetwork.UNKNOWN)
        expect("222", CardNetwork.UNKNOWN)
        expect("62", CardNetwork.UNKNOWN)
        expect("622", CardNetwork.UNKNOWN)
    }

    // ----- Input hygiene: detect() runs on every keystroke of a live field -----
    @Test fun separatorsAndStrayCharactersAreStripped() {
        expect("(4111) 1111-1111 1111", CardNetwork.VISA)
        expect("4a1b1c1", CardNetwork.VISA)
        expect("\t4111\n", CardNetwork.VISA)
    }

    @Test fun noDigitsAtAll_isUnknown() {
        expect("abcd", CardNetwork.UNKNOWN)
        expect("----", CardNetwork.UNKNOWN)
        expect("/", CardNetwork.UNKNOWN)
    }

    @Test fun overlongInput_isStillClassifiedByItsPrefix() {
        // detect() deliberately does no length validation — it is called mid-typing and must not
        // regress to UNKNOWN if the user pastes something too long. Luhn is the length guard.
        expect("4111111111111111111111111", CardNetwork.VISA)
        expect("6011111111111111111111111", CardNetwork.DISCOVER)
    }

    @Test fun everyNetworkExceptUnknownIsReachable() {
        // Guards against a rule being deleted or shadowed such that a network becomes dead code
        // and its logo can never appear.
        val reached = listOf(
            "4111111111111111", "5555555555554444", "378282246310005",
            "6000000000000000", "6011111111111117", "3600000000000000",
        ).map { CardNetworkDetector.detect(it) }.toSet()
        assertEquals(CardNetwork.entries.toSet() - CardNetwork.UNKNOWN, reached)
    }

    private fun expect(input: String, network: CardNetwork) {
        assertEquals("input=[$input]", network, CardNetworkDetector.detect(input))
    }
}

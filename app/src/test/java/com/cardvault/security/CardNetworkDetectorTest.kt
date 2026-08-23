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
    @Test fun discover_622125_belowBoundIsRupay60Prefix() = expect("6221 2500 0000", CardNetwork.RUPAY)
    @Test fun discover_622926_aboveBoundIsRupay60Prefix() = expect("6229 2600 0000", CardNetwork.RUPAY)
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
        assertEquals(CardNetwork.RUPAY, CardNetworkDetector.detect("6"))
        assertEquals(CardNetwork.RUPAY, CardNetworkDetector.detect("60"))
        assertEquals(CardNetwork.RUPAY, CardNetworkDetector.detect("601"))
        assertEquals(CardNetwork.DISCOVER, CardNetworkDetector.detect("6011"))
    }

    @Test fun empty_isUnknown() = expect("", CardNetwork.UNKNOWN)
    @Test fun spacesOnly_isUnknown() = expect("   ", CardNetwork.UNKNOWN)
    @Test fun sevenIsUnknown() = expect("7000 0000 0000", CardNetwork.UNKNOWN)

    private fun expect(input: String, network: CardNetwork) {
        assertEquals("input=[$input]", network, CardNetworkDetector.detect(input))
    }
}

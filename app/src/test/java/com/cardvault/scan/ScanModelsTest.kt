package com.cardvault.scan

import com.cardvault.util.Luhn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The value types themselves, plus the two invariants that are currently only asserted in prose.
 *
 * [scanCandidateHasNoFieldThatCouldCarryASecurityCode] is the important one. §6 mechanism 2 of
 * REQUIREMENTS.md claims the CVV is unrepresentable in the scanner's output type — that claim is
 * load-bearing for the whole "scanning the back of a card is safe by construction" argument, and
 * until now nothing failed if someone added a fifth field. Now something does.
 */
class ScanModelsTest {

    // ----------------------------------------------------------------- OcrLine

    @Test fun height_isTheBoxHeight() {
        assertEquals(66, OcrLine("x", left = 60, top = 270, right = 900, bottom = 336).height)
    }

    @Test fun invertedBox_clampsHeightToZeroRatherThanGoingNegative() {
        // A negative glyph height would invert CardScanParser's top-quartile comparison and make
        // a malformed box the *tallest* candidate on the frame.
        assertEquals(0, OcrLine("x", left = 0, top = 100, right = 10, bottom = 40).height)
    }

    @Test fun verticalCenter_isTheMidpoint() {
        assertEquals(303, OcrLine("x", left = 60, top = 270, right = 900, bottom = 336).verticalCenter)
        assertEquals(0, OcrLine("x", left = 0, top = 0, right = 0, bottom = 0).verticalCenter)
    }

    // ----------------------------------------------------------- ScanCandidate

    @Test fun emptyCandidate_isEmpty() {
        assertTrue(ScanCandidate.EMPTY.isEmpty)
        assertTrue(ScanCandidate().isEmpty)
        assertEquals(ScanCandidate(), ScanCandidate.EMPTY)
    }

    @Test fun anySingleFieldMakesACandidateNonEmpty() {
        assertFalse(ScanCandidate(panDigits = "4111111111111111").isEmpty)
        assertFalse(ScanCandidate(expiryDigits = "0929").isEmpty)
        assertFalse(ScanCandidate(nameOnCard = "ARJUN MEHTA").isEmpty)
        assertFalse(ScanCandidate(issuingBank = "HDFC Bank").isEmpty)
    }

    @Test fun scanCandidateHasNoFieldThatCouldCarryASecurityCode() {
        val fields = ScanCandidate::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            "ScanCandidate gained or lost a field. If a security-code field was added, " +
                "REQUIREMENTS.md §6 mechanism 2 is no longer true.",
            setOf("panDigits", "expiryDigits", "nameOnCard", "issuingBank"),
            fields,
        )

        // Also catch a security code smuggled in as a computed property with no backing field.
        // Standard data-class members are excluded by name — `hashCode` would otherwise trip
        // the "code" needle on every run.
        val standardMembers = setOf(
            "hashCode", "equals", "toString", "copy", "copy\$default",
            "component1", "component2", "component3", "component4",
        )
        val forbidden = listOf("cvv", "cvc", "cid", "csc", "security", "code", "pin")
        val members = fields + ScanCandidate::class.java.declaredMethods
            .map { it.name }
            .filterNot { it in standardMembers }
        members.forEach { member ->
            val lower = member.lowercase()
            forbidden.forEach { needle ->
                assertFalse("member [$member] looks like a security code", lower.contains(needle))
            }
        }
    }

    // -------------------------------------------------------------- OcrFrame

    @Test fun frameKeepsItsLinesAndDimensions() {
        val line = OcrLine("4111 1111 1111 1111", 60, 270, 900, 336)
        val frame = OcrFrame(listOf(line), width = 1000, height = 630)
        assertEquals(1, frame.lines.size)
        assertEquals(1000, frame.width)
        assertEquals(630, frame.height)
    }

    // -------------------------------------------------------- fixture integrity

    @Test fun fixturePansAreLuhnValid() {
        // Several CardScanParserTest cases only prove anything if these clear the score floor,
        // which requires the checksum. A typo in a fixture constant would otherwise turn those
        // tests into vacuous "returns null" assertions that still pass.
        listOf(
            "VISA_PAN" to ScanFixtures.VISA_PAN,
            "RUPAY_PAN" to ScanFixtures.RUPAY_PAN,
            "AMEX_PAN" to ScanFixtures.AMEX_PAN,
            "GARBLED_PAN" to ScanFixtures.GARBLED_PAN,
        ).forEach { (name, pan) ->
            assertTrue("$name=[$pan] is not Luhn-valid", Luhn.isValid(pan))
        }
    }
}

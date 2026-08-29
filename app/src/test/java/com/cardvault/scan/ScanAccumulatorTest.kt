package com.cardvault.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAccumulatorTest {

    private val pan = ScanFixtures.VISA_PAN
    private val full = ScanCandidate(
        panDigits = pan,
        expiryDigits = "0929",
        nameOnCard = "ARJUN MEHTA",
        issuingBank = "HDFC Bank",
    )

    @Test fun locksOnlyAfterThreeAgreeingFrames() {
        val acc = ScanAccumulator()
        acc.offer(full)
        assertFalse(acc.isPanLocked)
        acc.offer(full)
        assertFalse(acc.isPanLocked)
        acc.offer(full)
        assertTrue(acc.isPanLocked)
        assertEquals(pan, acc.panDigits)
    }

    @Test fun disagreeingFramesDoNotLock() {
        val acc = ScanAccumulator()
        acc.offer(ScanCandidate(panDigits = pan))
        acc.offer(ScanCandidate(panDigits = "4242424242424242"))
        acc.offer(ScanCandidate(panDigits = pan))
        assertFalse(acc.isPanLocked)
        // The third agreeing frame for one value is what settles it, not the frame count.
        acc.offer(ScanCandidate(panDigits = pan))
        assertTrue(acc.isPanLocked)
        assertEquals(pan, acc.panDigits)
    }

    @Test fun fieldsLockIndependently() {
        val acc = ScanAccumulator()
        // Expiry reads cleanly from the first frame; the number takes longer, as on a real card
        // where glare sits across the embossing.
        repeat(3) { acc.offer(ScanCandidate(expiryDigits = "0929")) }
        assertTrue(acc.isExpiryLocked)
        assertFalse(acc.isPanLocked)
        assertFalse(acc.isSettled())

        repeat(3) { acc.offer(ScanCandidate(panDigits = pan)) }
        assertTrue(acc.isSettled())
    }

    @Test fun lockedValueIsNeverRevised() {
        val acc = ScanAccumulator()
        repeat(3) { acc.offer(ScanCandidate(nameOnCard = "ARJUN MEHTA")) }
        assertEquals("ARJUN MEHTA", acc.nameOnCard)
        repeat(10) { acc.offer(ScanCandidate(nameOnCard = "ARJUN MEHTA JR")) }
        assertEquals("ARJUN MEHTA", acc.nameOnCard)
    }

    @Test fun nonLuhnPan_neverLocks() {
        val acc = ScanAccumulator()
        repeat(10) { acc.offer(ScanCandidate(panDigits = "4111111111111112")) }
        assertFalse(acc.isPanLocked)
        assertNull(acc.panDigits)
    }

    @Test fun settledRequiresPanAndExpiryOnly() {
        val acc = ScanAccumulator()
        repeat(3) { acc.offer(ScanCandidate(panDigits = pan, expiryDigits = "0929")) }
        assertTrue(acc.isSettled())
        // Name and bank never hold the scan open.
        assertNull(acc.nameOnCard)
        assertNull(acc.issuingBank)
    }

    @Test fun emptyCandidatesAreHarmless() {
        val acc = ScanAccumulator()
        repeat(20) { acc.offer(ScanCandidate.EMPTY) }
        assertFalse(acc.isSettled())
        assertTrue(acc.snapshot().isEmpty)
    }

    @Test fun snapshotReturnsWhatHasLockedSoFar() {
        val acc = ScanAccumulator()
        repeat(3) { acc.offer(ScanCandidate(panDigits = pan)) }
        acc.offer(ScanCandidate(expiryDigits = "0929"))
        // "Use what you have" sends the PAN alone; the half-voted expiry is not smuggled out.
        assertEquals(ScanCandidate(panDigits = pan), acc.snapshot())
    }

    @Test fun clearDropsVotesAndLockedValues() {
        val acc = ScanAccumulator()
        repeat(3) { acc.offer(full) }
        assertTrue(acc.isPanLocked)
        acc.clear()
        assertTrue(acc.snapshot().isEmpty)
        assertFalse(acc.isSettled())
        // Votes are gone too, so the next frame starts the count from one.
        acc.offer(full)
        assertFalse(acc.isPanLocked)
    }

    @Test fun voteThresholdIsConfigurable() {
        val acc = ScanAccumulator(votesToLock = 1)
        acc.offer(full)
        assertTrue(acc.isSettled())
    }

    // ================================================================================
    // Voting mechanics that the overlay's tick behaviour depends on.
    // ================================================================================

    @Test fun nullFieldsConsumeNoVotes() {
        // Frames that read nothing are the majority of frames. If they counted as votes, a field
        // could never accumulate three agreeing reads on a card held slightly off-centre.
        val acc = ScanAccumulator()
        val panOnly = ScanCandidate(panDigits = pan)
        acc.offer(panOnly)
        acc.offer(panOnly)
        repeat(5) { acc.offer(ScanCandidate.EMPTY) }
        assertFalse(acc.isPanLocked)
        acc.offer(panOnly)
        assertTrue(acc.isPanLocked)
        assertEquals(pan, acc.panDigits)
    }

    @Test fun competingValuesTallySeparatelyAndTheFirstToThreeWins() {
        // Interleaved misreads are exactly what the vote threshold exists for.
        val a = ScanCandidate(panDigits = "4111111111111111")
        val b = ScanCandidate(panDigits = "5555555555554444")
        val acc = ScanAccumulator()
        acc.offer(a); acc.offer(b); acc.offer(a); acc.offer(b)
        assertFalse(acc.isPanLocked)
        acc.offer(a)
        assertEquals("4111111111111111", acc.panDigits)
    }

    @Test fun votesAreExactStringMatches() {
        // The tally is keyed on the value as delivered, so two spellings of the same number are
        // two candidates. CardScanParser always emits bare digits, which is what makes locking
        // possible at all — this pins that contract from the accumulator's side.
        val acc = ScanAccumulator()
        acc.offer(ScanCandidate(panDigits = pan))
        acc.offer(ScanCandidate(panDigits = pan))
        acc.offer(ScanCandidate(panDigits = "4111 1111 1111 1111"))
        assertFalse(acc.isPanLocked)
    }

    @Test fun onlyThePanIsChecksumGated() {
        // Expiry, name and bank are taken on the vote alone; there is nothing to validate them
        // against, and the user can see and edit them on the form.
        val acc = ScanAccumulator()
        val odd = ScanCandidate(expiryDigits = "0929", nameOnCard = "Z", issuingBank = "?")
        repeat(3) { acc.offer(odd) }
        assertTrue(acc.isExpiryLocked)
        assertTrue(acc.isNameLocked)
        assertTrue(acc.isBankLocked)
        assertNull(acc.panDigits)
        assertFalse(acc.isSettled())
    }

    @Test fun nameAndBankAloneNeverSettleTheScan() {
        val acc = ScanAccumulator()
        repeat(3) { acc.offer(ScanCandidate(nameOnCard = "ARJUN MEHTA", issuingBank = "HDFC Bank")) }
        assertFalse(acc.isSettled())
        repeat(3) { acc.offer(ScanCandidate(panDigits = pan)) }
        assertFalse(acc.isSettled())
        repeat(3) { acc.offer(ScanCandidate(expiryDigits = "0929")) }
        assertTrue(acc.isSettled())
    }

    @Test fun clearThenAFullSecondCycleLocksAgain() {
        val acc = ScanAccumulator()
        repeat(3) { acc.offer(full) }
        assertTrue(acc.isSettled())
        acc.clear()
        assertTrue(acc.snapshot().isEmpty)
        repeat(3) { acc.offer(full) }
        assertTrue(acc.isSettled())
        assertEquals(pan, acc.panDigits)
    }

    @Test fun snapshotIsAValueAndDoesNotAliasTheAccumulator() {
        val acc = ScanAccumulator()
        repeat(3) { acc.offer(ScanCandidate(panDigits = pan)) }
        val early = acc.snapshot()
        repeat(3) { acc.offer(ScanCandidate(expiryDigits = "0929")) }
        // "Use what you have" hands the form a snapshot; later frames must not mutate it.
        assertNull(early.expiryDigits)
        assertEquals("0929", acc.snapshot().expiryDigits)
    }
}

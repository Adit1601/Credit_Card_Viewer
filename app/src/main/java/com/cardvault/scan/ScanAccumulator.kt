package com.cardvault.scan

import com.cardvault.util.Luhn

/**
 * Votes each field across frames and locks it once enough frames agree (§5.5).
 *
 * Single-frame OCR of an embossed card is unreliable in a way that no amount of parser
 * cleverness fixes — glare moves, focus breathes, a digit reads as `8` in one frame and `B` in
 * the next. Requiring [votesToLock] frames to produce the *identical* value is what turns that
 * into something usable, and it is why the overlay can tick fields off one at a time.
 *
 * Fields lock **independently and permanently**. A locked value is never revised by a later
 * frame: the user is watching ticks appear, and a field that un-ticked or silently changed under
 * them would be worse than one that took another second to settle. Anything they disagree with
 * is editable on the form afterwards.
 *
 * Not thread-safe by design — every call arrives on the single CameraX analyzer executor.
 */
class ScanAccumulator(private val votesToLock: Int = DEFAULT_VOTES_TO_LOCK) {

    private val panVotes = HashMap<String, Int>()
    private val expiryVotes = HashMap<String, Int>()
    private val nameVotes = HashMap<String, Int>()
    private val bankVotes = HashMap<String, Int>()

    var panDigits: String? = null
        private set
    var expiryDigits: String? = null
        private set
    var nameOnCard: String? = null
        private set
    var issuingBank: String? = null
        private set

    val isPanLocked: Boolean get() = panDigits != null
    val isExpiryLocked: Boolean get() = expiryDigits != null
    val isNameLocked: Boolean get() = nameOnCard != null
    val isBankLocked: Boolean get() = issuingBank != null

    fun offer(candidate: ScanCandidate) {
        // The PAN carries an extra condition. CardScanParser's score floor already makes a
        // non-Luhn PAN unreachable, so this is redundant today — kept because it is the one
        // field where a future scoring tweak could quietly widen what gets accepted, and this
        // is where that would need to be a deliberate choice rather than an accident.
        if (panDigits == null) {
            candidate.panDigits
                ?.takeIf { Luhn.isValid(it) }
                ?.let { value -> if (tally(panVotes, value)) panDigits = value }
        }
        if (expiryDigits == null) {
            candidate.expiryDigits?.let { value -> if (tally(expiryVotes, value)) expiryDigits = value }
        }
        if (nameOnCard == null) {
            candidate.nameOnCard?.let { value -> if (tally(nameVotes, value)) nameOnCard = value }
        }
        if (issuingBank == null) {
            candidate.issuingBank?.let { value -> if (tally(bankVotes, value)) issuingBank = value }
        }
    }

    /** Records a vote and reports whether it just reached the lock threshold. */
    private fun tally(votes: MutableMap<String, Int>, value: String): Boolean {
        val count = (votes[value] ?: 0) + 1
        votes[value] = count
        return count >= votesToLock
    }

    /**
     * True when the scan can close on its own. The PAN and expiry are the two fields the user
     * cannot reasonably supply from memory, so they define "done"; name and bank are
     * conveniences and never hold the scan open.
     */
    fun isSettled(): Boolean = isPanLocked && isExpiryLocked

    /** Whatever has locked so far. Safe to call at any point — that is what "Use what you have" sends. */
    fun snapshot(): ScanCandidate = ScanCandidate(
        panDigits = panDigits,
        expiryDigits = expiryDigits,
        nameOnCard = nameOnCard,
        issuingBank = issuingBank,
    )

    /** Drops every vote and every locked value. Called from `onDestroyView` (§6, mechanism 5). */
    fun clear() {
        panVotes.clear()
        expiryVotes.clear()
        nameVotes.clear()
        bankVotes.clear()
        panDigits = null
        expiryDigits = null
        nameOnCard = null
        issuingBank = null
    }

    companion object {
        const val DEFAULT_VOTES_TO_LOCK = 3
    }
}

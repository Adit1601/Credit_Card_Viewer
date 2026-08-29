package com.cardvault.scan

import com.cardvault.security.CardNetwork
import com.cardvault.security.CardNetworkDetector
import com.cardvault.util.Luhn

/**
 * Turns one OCR'd frame into a [ScanCandidate]. Pure function, no Android, no state — §5.
 *
 * Extraction order matters and is not rearrangeable: the PAN is resolved first because the
 * expiry ranking needs it (to reject digits that are really part of the card number) and the
 * name ranking needs its baseline (names sit below the number). The bank is resolved before the
 * name so the line consumed as a bank cannot also be offered as a cardholder.
 *
 * ### Why no CVV can escape (§6, mechanisms 2-4)
 * A digit run reaches the output only as a PAN — 13-19 digits scoring above [PAN_SCORE_FLOOR] —
 * or as an expiry, which requires an `MM/YY` separator *and* a month in 01-12. A bare `123`,
 * `4567`, or Amex's 4-digit front CID satisfies neither, so it has no path to any field. That
 * also makes scanning the back of a card safe by construction, with no "front only" rule needed.
 */
object CardScanParser {

    /**
     * Minimum PAN score. Chosen so that **Luhn alone is not sufficient** (100 < 120): a winning
     * candidate must also carry a recognised network prefix or a plausible length.
     *
     * The converse falls out of the same number and is the stronger guarantee: every other
     * signal combined tops out at 110, so nothing that fails Luhn can ever clear the floor.
     * `CardScanParserTest.nonLuhnNumber_isNeverEmitted` pins that.
     */
    private const val PAN_SCORE_FLOOR = 120

    fun parse(frame: OcrFrame, bankVocabulary: List<String>): ScanCandidate {
        if (frame.lines.isEmpty()) return ScanCandidate.EMPTY

        val rows = buildRows(frame.lines)
        val pan = selectPan(frame, rows)
        // No number, no output. Every other field is only meaningful as part of a card, and a
        // frame with no card in it — a receipt, a laptop lid, the inside of a pocket — will
        // still happily yield two uppercase words that look like a name. Gating on the PAN is
        // what makes a miss return nothing rather than something plausible and wrong.
        if (pan == null) return ScanCandidate.EMPTY

        val bank = BankMatcher.matchDetailed(frame.lines.map { it.text }, bankVocabulary)
        val expiry = selectExpiry(frame.lines, pan.digits)
        val name = selectName(frame, pan.sourceBottom, bank?.lineIndex)

        return ScanCandidate(
            panDigits = pan.digits,
            expiryDigits = expiry,
            nameOnCard = name,
            issuingBank = bank?.canonical,
        )
    }

    // ------------------------------------------------------------------ PAN (§5.1)

    private data class PanPick(val digits: String, val score: Int, val sourceBottom: Int)

    private data class PanCandidate(
        val digits: String,
        val sourceText: String,
        val glyphHeight: Int,
        val sourceBottom: Int,
    )

    private fun selectPan(frame: OcrFrame, rows: List<Row>): PanPick? {
        val quartile = topQuartileHeight(frame.lines)
        val seen = HashSet<String>()
        val candidates = ArrayList<PanCandidate>()

        fun consider(digits: String, sourceText: String, glyphHeight: Int, sourceBottom: Int) {
            if (digits.length !in 13..19) return
            // Same number from the same text twice (raw == normalized) adds nothing.
            if (!seen.add("$digits@$sourceText")) return
            candidates += PanCandidate(digits, sourceText, glyphHeight, sourceBottom)
        }

        frame.lines.forEach { line ->
            consider(line.text.filter(Char::isDigit), line.text, line.height, line.bottom)
            val repaired = NumericNormalizer.normalizeLine(line.text)
            consider(repaired.filter(Char::isDigit), repaired, line.height, line.bottom)
        }
        // ML Kit sometimes breaks a PAN into two lines side by side ("4111 1111" / "1111 1234"),
        // so every horizontal row is also offered as one merged candidate.
        rows.filter { it.lines.size > 1 }.forEach { row ->
            consider(row.text.filter(Char::isDigit), row.text, row.maxHeight, row.bottom)
            val repaired = NumericNormalizer.normalizeLine(row.text)
            consider(repaired.filter(Char::isDigit), repaired, row.maxHeight, row.bottom)
        }

        return candidates
            .map { PanPick(it.digits, scorePan(it, quartile), it.sourceBottom) }
            .filter { it.score >= PAN_SCORE_FLOOR }
            .maxByOrNull { it.score }
    }

    private fun scorePan(candidate: PanCandidate, topQuartileHeight: Int): Int {
        var score = 0
        if (Luhn.isValid(candidate.digits)) score += 100
        if (CardNetworkDetector.detect(candidate.digits) != CardNetwork.UNKNOWN) score += 50
        if (candidate.digits.length == 15 || candidate.digits.length == 16) score += 25
        if (hasPanGroupStructure(candidate.sourceText)) score += 20
        if (candidate.glyphHeight >= topQuartileHeight) score += 15
        if (candidate.sourceText.count(Char::isLetter) >= 3) score -= 40
        return score
    }

    /** 3-4 groups of 4-6 digits, i.e. what a card number looks like and a phone number doesn't. */
    private fun hasPanGroupStructure(sourceText: String): Boolean {
        val groups = DIGIT_RUN.findAll(sourceText).map { it.value.length }.toList()
        return groups.size in 3..4 && groups.all { it in 4..6 }
    }

    /** The PAN is usually the largest text on the card, so height is real evidence. */
    private fun topQuartileHeight(lines: List<OcrLine>): Int {
        val heights = lines.map { it.height }.sorted()
        if (heights.isEmpty()) return 0
        return heights[((heights.size - 1) * 3) / 4]
    }

    // --------------------------------------------------------------- Expiry (§5.2)

    private data class ExpiryHit(val mmyy: String, val score: Int, val ordinal: Int)

    private fun selectExpiry(lines: List<OcrLine>, panDigits: String?): String? {
        val hits = ArrayList<ExpiryHit>()

        lines.forEach { line ->
            // Both forms, because "O5/27" is a routine misread of an embossed expiry.
            sequenceOf(line.text, NumericNormalizer.normalizeLine(line.text))
                .distinct()
                .forEach { text -> collectExpiries(text, panDigits, hits) }
        }

        // Rank by label evidence, then by the later date. Indian debit cards routinely print
        // VALID FROM alongside VALID THRU, and taking the first match hands the user a card
        // that expired years ago.
        return hits
            .distinctBy { it.mmyy }
            .maxWithOrNull(compareBy({ it.score }, { it.ordinal }))
            ?.mmyy
    }

    private fun collectExpiries(text: String, panDigits: String?, into: MutableList<ExpiryHit>) {
        val upper = text.uppercase()
        val labelled = THRU_HINTS.any { upper.contains(it) }
        var score = 0
        if (labelled) score += 30
        if (FROM_HINTS.any { upper.contains(it) }) score -= 30

        EXPIRY_PATTERN.findAll(text).forEach { m ->
            val month = m.groupValues[1]
            val yearRaw = m.groupValues[2]
            val year = if (yearRaw.length == 4) yearRaw.substring(2) else yearRaw
            val mmyy = month + year
            // A slice of the card number is not an expiry date — unless the card has labelled it
            // as one.
            //
            // Without the label exemption this rejection had no upper bound on what it could
            // discard. It tests the *whole* PAN, so any card whose number happens to contain its
            // own MMYY as four consecutive digits — 4532 1234 5678 9012 expiring 12/34, and about
            // one card in 800 by the pigeonhole count — had its correctly-read, clearly-labelled
            // `VALID THRU` thrown away on every frame. The expiry could then never lock, the
            // accumulator could never settle, and that card burned the full timeout and landed on
            // the "couldn't read" panel every single time, permanently.
            //
            // Restricting the test to hits on the PAN's own source line would not do it: the case
            // that motivated the rejection, pinned by `expiryThatIsASliceOfThePan_isRejected`, is
            // a fragment of the number that OCR returned as its own separate line. The label is
            // what actually separates the two — a stray run of PAN digits does not come with
            // `VALID THRU` attached to it.
            //
            // An unlabelled expiry whose digits do occur in the PAN is still dropped, and stays
            // dropped: with no label there is nothing left to tell it apart from a misread slice
            // of the number, and inventing an expiry from the card's own digits is the worse of
            // the two failures. The user retypes four digits.
            if (!labelled && panDigits != null && panDigits.contains(mmyy)) return@forEach
            into += ExpiryHit(
                mmyy = mmyy,
                score = score,
                ordinal = (2000 + year.toInt()) * 12 + month.toInt(),
            )
        }
    }

    // ----------------------------------------------------------------- Name (§5.3)

    private fun selectName(frame: OcrFrame, panBottom: Int?, bankLineIndex: Int?): String? {
        val bottomThird = frame.height * 2 / 3
        var best: String? = null
        var bestScore = Int.MIN_VALUE

        frame.lines.forEachIndexed { index, line ->
            if (index == bankLineIndex) return@forEachIndexed
            val text = line.text.trim()
            if (!looksLikeAName(text)) return@forEachIndexed

            var score = 0
            // Names sit below the number and low on the card face.
            if (panBottom != null && line.top >= panBottom) score += 30
            if (frame.height > 0 && line.top >= bottomThird) score += 15
            if (score > bestScore) {
                bestScore = score
                best = text
            }
        }
        return best
    }

    private fun looksLikeAName(text: String): Boolean {
        if (text.length !in 5..26) return false
        if (text.any { !it.isLetter() && it !in NAME_PUNCTUATION }) return false

        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty()) return false
        // Card faces print the holder's name in caps; sentence-case text is almost always
        // marketing copy or a bank strapline.
        if (letters.count { it.isUpperCase() } * 100 < letters.length * 60) return false

        val tokens = text.split(' ', '\t').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        if (tokens.size == 1 && tokens[0].length < 5) return false

        val upperTokens = tokens.map { token -> token.filter(Char::isLetter).uppercase() }
        if (upperTokens.any { it in NAME_TOKEN_BLOCKLIST }) return false

        val squashed = letters.uppercase()
        if (NAME_PHRASE_BLOCKLIST.any { squashed.contains(it) }) return false

        return true
    }

    // ------------------------------------------------------------------ row grouping

    private class Row(val lines: List<OcrLine>) {
        val text: String = lines.sortedBy { it.left }.joinToString(" ") { it.text }
        val maxHeight: Int = lines.maxOf { it.height }
        val bottom: Int = lines.maxOf { it.bottom }
        val center: Int = lines.sumOf { it.verticalCenter } / lines.size
    }

    /**
     * Groups lines whose vertical centres are close relative to their own glyph height, which is
     * how two halves of a split card number end up back together regardless of read order.
     */
    private fun buildRows(lines: List<OcrLine>): List<Row> {
        val sorted = lines.sortedBy { it.verticalCenter }
        val rows = ArrayList<Row>()
        var current = ArrayList<OcrLine>()

        sorted.forEach { line ->
            if (current.isEmpty()) {
                current.add(line)
                return@forEach
            }
            val currentCenter = current.sumOf { it.verticalCenter } / current.size
            val tolerance = (maxOf(current.maxOf { it.height }, line.height) * 6) / 10
            if (kotlin.math.abs(line.verticalCenter - currentCenter) <= tolerance) {
                current.add(line)
            } else {
                rows.add(Row(current))
                current = arrayListOf(line)
            }
        }
        if (current.isNotEmpty()) rows.add(Row(current))
        return rows
    }

    // ---------------------------------------------------------------------- tables

    private val DIGIT_RUN = Regex("\\d+")

    /** Month 01-12, a real separator, then a 2- or 4-digit year, not glued to other digits. */
    private val EXPIRY_PATTERN =
        Regex("(?<!\\d)(0[1-9]|1[0-2])\\s*[/\\-–—]\\s*(\\d{2}|\\d{4})(?!\\d)")

    private val THRU_HINTS = listOf("THRU", "THROUGH", "EXPIRES", "EXP", "GOOD")
    private val FROM_HINTS = listOf("FROM", "SINCE", "MEMBER")

    private val NAME_PUNCTUATION = charArrayOf(' ', '.', '-', '\'')

    private val NAME_TOKEN_BLOCKLIST: Set<String> = buildSet {
        // Networks
        addAll(listOf("VISA", "MASTERCARD", "MAESTRO", "RUPAY", "AMERICAN", "EXPRESS", "AMEX", "DISCOVER", "DINERS", "CLUB", "JCB", "UNIONPAY"))
        // Tiers and product names
        addAll(listOf("DEBIT", "CREDIT", "PREPAID", "PLATINUM", "GOLD", "SILVER", "TITANIUM", "SIGNATURE", "INFINITE", "WORLD", "SELECT", "CLASSIC", "BUSINESS", "CORPORATE", "INTERNATIONAL", "CONTACTLESS", "REWARDS", "CASHBACK"))
        // Card furniture
        addAll(listOf("VALID", "THRU", "THROUGH", "FROM", "EXPIRES", "EXPIRY", "GOOD", "MEMBER", "SINCE", "AUTHORIZED", "AUTHORISED", "CUSTOMER", "SERVICE", "CARDHOLDER", "HOLDER", "NAME", "BANK", "LTD", "LIMITED", "PVT", "PRIVATE", "WWW", "COM", "TOLL", "FREE", "HELPLINE", "CVV", "CVC", "CID"))
        // Month names, long and short
        addAll(listOf("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"))
        addAll(listOf("JAN", "FEB", "MAR", "APR", "JUN", "JUL", "AUG", "SEP", "SEPT", "OCT", "NOV", "DEC"))
    }

    /**
     * Phrases only, for wording that survives tokenisation. Kept short on purpose: matching
     * squashed substrings can false-reject a real name (a holder called GOLDIE contains GOLD),
     * and the token list above already carries the single words. Losing a name the user then
     * types by hand is a far cheaper mistake than printing "PLATINUM GOLD" as their name.
     */
    private val NAME_PHRASE_BLOCKLIST = listOf(
        "AMERICANEXPRESS", "AUTHORIZEDSIGNATURE", "AUTHORISEDSIGNATURE",
        "CUSTOMERSERVICE", "GOODTHRU", "VALIDTHRU", "TOLLFREE", "STATEBANK",
    )
}

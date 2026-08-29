package com.cardvault.scan

/**
 * Resolves an OCR'd bank name to the **canonical spelling from the vocabulary** (§5.4).
 *
 * Returning the canonical form rather than the raw OCR text is the point: `issuingBank` is a
 * plaintext Room column that also drives the home screen's bank filter chips, so a scanned
 * "HDFC BANK" must land on the same chip as a hand-typed "HDFC Bank" rather than creating a
 * near-duplicate.
 *
 * Matching runs in three stages, each applied across *all* candidate lines before the next is
 * tried, so an exact hit on the last line always beats a fuzzy hit on the first:
 *
 *  1. exact slug equality
 *  2. slug containment in either direction (guarded — see [MIN_CONTAINMENT_LENGTH])
 *  3. Levenshtein with a **length-sensitive** threshold
 *
 * Stage ordering is not cosmetic. `Bank of America` slugs to `AMERICA`, which is a substring of
 * `AMERICANEXPRESS` — so on an Amex card, containment alone would answer "Bank of America".
 * Trying exact equality across every line first means `AMERICAN EXPRESS` resolves correctly.
 */
object BankMatcher {

    /** Where a match came from, so [CardScanParser] can exclude that line from name candidates. */
    data class Match(val canonical: String, val lineIndex: Int)

    /**
     * Tokens that carry no identifying information. Dropped from both sides before comparison so
     * `ICICI BANK LTD` and `ICICI Bank` reduce to the same slug.
     */
    private val NOISE_TOKENS = setOf(
        "BANK", "BANKS", "LIMITED", "LTD", "PVT", "PRIVATE", "PLC", "INC",
        "CORP", "CORPORATION", "CO", "THE", "OF", "AND", "NA", "GROUP",
    )

    /**
     * Containment is only trusted when the shorter slug is at least this long. `Yes Bank` slugs
     * to the 3-character `YES`, which appears inside plenty of unrelated uppercase text — a
     * cardholder named `YESHWANT` would otherwise be read as a bank.
     */
    private const val MIN_CONTAINMENT_LENGTH = 5

    /** Slugs shorter than this are ignored entirely as too weak to identify anything. */
    private const val MIN_SLUG_LENGTH = 3

    /** As specified in §5.4. Returns the canonical vocabulary spelling, or null. */
    fun match(lines: List<String>, vocabulary: List<String>): String? =
        matchDetailed(lines, vocabulary)?.canonical

    fun matchDetailed(lines: List<String>, vocabulary: List<String>): Match? {
        val vocab = vocabulary
            .map { it to slug(it) }
            .filter { (_, s) -> s.length >= MIN_SLUG_LENGTH }
        if (vocab.isEmpty()) return null

        val candidates = lines
            .mapIndexed { index, line -> index to slug(line) }
            .filter { (_, s) -> s.length >= MIN_SLUG_LENGTH }
        if (candidates.isEmpty()) return null

        // ---- Stage 1: exact ----
        candidates.forEach { (index, cand) ->
            vocab.filter { (_, v) -> v == cand }
                // Longest canonical wins if two vocabulary entries share a slug — deterministic.
                .maxByOrNull { (_, v) -> v.length }
                ?.let { (canonical, _) -> return Match(canonical, index) }
        }

        // ---- Stage 2: containment, most specific first ----
        candidates.forEach { (index, cand) ->
            val hit = vocab
                .filter { (_, v) ->
                    val shorter = minOf(v.length, cand.length)
                    shorter >= MIN_CONTAINMENT_LENGTH && (cand.contains(v) || v.contains(cand))
                }
                .maxByOrNull { (_, v) -> v.length }
            if (hit != null) return Match(hit.first, index)
        }

        // ---- Stage 3: fuzzy ----
        var best: Match? = null
        var bestDistance = Int.MAX_VALUE
        var bestSlugLength = -1
        candidates.forEach { (index, cand) ->
            vocab.forEach { (canonical, v) ->
                val threshold = fuzzyThreshold(v, cand)
                val distance = levenshtein(cand, v, threshold)
                if (distance <= threshold &&
                    (distance < bestDistance || (distance == bestDistance && v.length > bestSlugLength))
                ) {
                    best = Match(canonical, index)
                    bestDistance = distance
                    bestSlugLength = v.length
                }
            }
        }
        return best
    }

    /**
     * Length-sensitive edit budget — the single most consequential constant in this file.
     *
     * `HDFC` and `HSBC` are both four characters and exactly **2** edits apart. A flat `<= 2`
     * threshold would silently report HSBC for every HDFC card, which on an India-first app is
     * the most likely wrong answer this feature could possibly give. Short slugs therefore get
     * one edit, not two. `BankMatcherTest` pins this with a named test.
     */
    private fun fuzzyThreshold(a: String, b: String): Int =
        if (minOf(a.length, b.length) < 6) 1 else 2

    /** Uppercase, split on anything non-alphanumeric, drop noise tokens, concatenate. */
    private fun slug(raw: String): String =
        raw.uppercase()
            .split(NON_ALPHANUMERIC)
            .filter { it.isNotEmpty() && it !in NOISE_TOKENS }
            .joinToString("")

    /**
     * Standard two-row Levenshtein, abandoned early once every cell in a row exceeds [cutoff].
     * The cutoff is an optimisation only — the returned value is exact whenever it is <= cutoff,
     * which is the only range callers compare against.
     */
    private fun levenshtein(a: String, b: String, cutoff: Int): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > cutoff) return cutoff + 1

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
                if (current[j] < rowMin) rowMin = current[j]
            }
            if (rowMin > cutoff) return cutoff + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private val NON_ALPHANUMERIC = Regex("[^A-Z0-9]+")
}

package com.cardvault.security

/**
 * BIN-prefix detector per §3.1 of REQUIREMENTS.md.
 *
 * Priority rule (user decision): **longest matching prefix wins**. For example, "6521" and
 * "6522" resolve to RuPay even though "65" would otherwise resolve to Discover. Numeric
 * ranges (e.g. 622126–622925) also participate, using the length of the range's digit width.
 *
 * The detector accepts card numbers with spaces/dashes — non-digits are stripped first —
 * and works on any prefix, so it is safe to call on every keystroke while the user is typing.
 */
object CardNetworkDetector {

    /** A single rule: at least [len] digits must be present, and [match] must return true. */
    private data class Rule(val len: Int, val network: CardNetwork, val match: (String) -> Boolean)

    // Rules ordered by longest prefix first. `firstN(...).toInt()` is safe because [len]
    // guarantees enough digits are present at the call site.
    private val RULES: List<Rule> = listOf(
        // ---- 6-digit prefixes ----
        Rule(6, CardNetwork.DISCOVER) { it.take(6).toInt() in 622126..622925 },

        // ---- 4-digit prefixes ----
        Rule(4, CardNetwork.RUPAY) { it.startsWith("6521") },
        Rule(4, CardNetwork.RUPAY) { it.startsWith("6522") },
        Rule(4, CardNetwork.DISCOVER) { it.startsWith("6011") },
        Rule(4, CardNetwork.MASTERCARD) { it.take(4).toInt() in 2221..2720 },

        // ---- 3-digit prefixes ----
        Rule(3, CardNetwork.DINERS) { it.take(3).toInt() in 300..305 },
        Rule(3, CardNetwork.DISCOVER) { it.take(3).toInt() in 644..649 },

        // ---- 2-digit prefixes ----
        Rule(2, CardNetwork.AMEX) { it.startsWith("34") || it.startsWith("37") },
        Rule(2, CardNetwork.MASTERCARD) { it.take(2).toInt() in 51..55 },
        Rule(2, CardNetwork.RUPAY) { it.startsWith("60") },
        Rule(2, CardNetwork.DISCOVER) { it.startsWith("65") },
        Rule(2, CardNetwork.DINERS) { it.startsWith("36") || it.startsWith("38") },

        // ---- 1-digit prefixes ----
        Rule(1, CardNetwork.VISA) { it.startsWith("4") }
    )

    fun detect(cardNumber: String): CardNetwork {
        val digits = cardNumber.filter(Char::isDigit)
        if (digits.isEmpty()) return CardNetwork.UNKNOWN
        // RULES is authored in longest-first order, so the first match wins.
        return RULES.firstOrNull { rule -> digits.length >= rule.len && rule.match(digits) }
            ?.network
            ?: CardNetwork.UNKNOWN
    }
}

package com.cardvault.ui.addedit

/**
 * Autocomplete suggestions for the "Issuing bank" field. Kept as a plain string list
 * (not an enum) because banks are open-ended — the user can type anything, and any
 * previously-entered bank is folded into the suggestion set via [combined].
 *
 * India-first ordering to match the app's default audience; international majors follow.
 */
object BankSuggestions {

    val seed: List<String> = listOf(
        "HDFC Bank",
        "State Bank of India",
        "ICICI Bank",
        "Axis Bank",
        "Kotak Mahindra Bank",
        "Yes Bank",
        "IndusInd Bank",
        "IDFC First Bank",
        "Punjab National Bank",
        "Bank of Baroda",
        "American Express",
        "Citibank",
        "HSBC",
        "Standard Chartered",
        "Bank of America",
        "Chase",
        "Capital One",
        "Barclays"
    )

    /**
     * Union of the seed list and the user's own previously-entered banks, deduped
     * case-insensitively and sorted alphabetically. Blank entries are dropped.
     */
    fun combined(userEntered: List<String>): List<String> {
        val bySlug = LinkedHashMap<String, String>()
        (seed + userEntered).forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEach
            val slug = trimmed.lowercase()
            if (!bySlug.containsKey(slug)) bySlug[slug] = trimmed
        }
        return bySlug.values.sortedBy { it.lowercase() }
    }
}

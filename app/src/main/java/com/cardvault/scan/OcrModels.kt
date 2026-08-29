package com.cardvault.scan

/**
 * Framework-free OCR value types.
 *
 * Deliberately plain data classes with `Int` bounds rather than `android.graphics.Rect`, so the
 * whole parsing layer ([NumericNormalizer], [BankMatcher], [CardScanParser], [ScanAccumulator])
 * is testable as pure Kotlin on the JVM with no Robolectric and no ML Kit on the test classpath.
 * `MlKitTextSource` is the only place that knows what a `Text.Line` is.
 */

/** One recognised line of text and its bounding box in frame pixel coordinates. */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /** Glyph height proxy. The PAN is typically the tallest text on a card face (§5.1). */
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    val verticalCenter: Int get() = (top + bottom) / 2
}

/** One analysed camera frame. */
data class OcrFrame(
    val lines: List<OcrLine>,
    val width: Int,
    val height: Int,
)

/**
 * What a single frame yielded. Every field is independently nullable — a frame that only
 * resolved the PAN is normal and useful.
 *
 * **There is no CVV field, and there must never be one.** This is the first and strongest of
 * the five CVV-suppression mechanisms (§6): a security code is not representable in the
 * scanner's output type, so no amount of downstream carelessness can carry one to the form.
 */
data class ScanCandidate(
    val panDigits: String? = null,
    val expiryDigits: String? = null,
    val nameOnCard: String? = null,
    val issuingBank: String? = null,
) {
    val isEmpty: Boolean
        get() = panDigits == null && expiryDigits == null && nameOnCard == null && issuingBank == null

    companion object {
        val EMPTY = ScanCandidate()
    }
}

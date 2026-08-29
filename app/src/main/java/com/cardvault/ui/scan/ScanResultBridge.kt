package com.cardvault.ui.scan

import android.os.Bundle
import com.cardvault.scan.ScanCandidate

/**
 * Carries a [ScanCandidate] from the scanner to the add/edit form through Navigation's
 * `SavedStateHandle`.
 *
 * A [Bundle] of four optional strings, rather than making [ScanCandidate] `Parcelable`, for two
 * reasons — and both are the point of this file existing at all:
 *
 *  1. `ScanCandidate` lives in `com.cardvault.scan`, which is Android-free so the whole parsing
 *     layer stays JVM-unit-testable. `Parcelable` is an `android.os` type; implementing it there
 *     would break that invariant for the sake of plumbing.
 *  2. `@Parcelize` would mean adding the `kotlin-parcelize` Gradle plugin to the build for one
 *     four-field class.
 *
 * The shape also keeps the CVV guarantee visible: there are exactly four keys here, none of them
 * a security code, so the transport itself cannot carry one even if a future change tried to
 * (§6, mechanism 1 — extended to the boundary).
 */
object ScanResultBridge {

    /** Key under which the result bundle is stored on the *previous* back-stack entry. */
    const val KEY_SCAN_RESULT = "scan_result"

    private const val KEY_PAN = "pan"
    private const val KEY_EXPIRY = "expiry"
    private const val KEY_NAME = "name"
    private const val KEY_BANK = "bank"

    fun toBundle(candidate: ScanCandidate): Bundle = Bundle(4).apply {
        candidate.panDigits?.let { putString(KEY_PAN, it) }
        candidate.expiryDigits?.let { putString(KEY_EXPIRY, it) }
        candidate.nameOnCard?.let { putString(KEY_NAME, it) }
        candidate.issuingBank?.let { putString(KEY_BANK, it) }
    }

    fun fromBundle(bundle: Bundle): ScanCandidate = ScanCandidate(
        panDigits = bundle.getString(KEY_PAN),
        expiryDigits = bundle.getString(KEY_EXPIRY),
        nameOnCard = bundle.getString(KEY_NAME),
        issuingBank = bundle.getString(KEY_BANK),
    )
}

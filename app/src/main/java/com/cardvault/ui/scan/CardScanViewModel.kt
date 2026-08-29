package com.cardvault.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.cardvault.data.db.AppDatabase
import com.cardvault.data.repository.CardRepository
import com.cardvault.scan.CardScanParser
import com.cardvault.scan.OcrFrame
import com.cardvault.scan.ScanAccumulator
import com.cardvault.scan.ScanCandidate
import com.cardvault.ui.addedit.BankSuggestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which fields have locked, for the overlay checklist. */
data class ScanProgress(
    val panLocked: Boolean = false,
    val expiryLocked: Boolean = false,
    val nameLocked: Boolean = false,
    val bankLocked: Boolean = false,
) {
    /**
     * The PAN is the one field the user genuinely cannot retype from memory, so it alone decides
     * whether a partial scan is worth accepting.
     */
    val hasSomethingWorthKeeping: Boolean get() = panLocked
}

/**
 * Owns the scan's accumulated state so it survives rotation.
 *
 * **Where the accumulator is cleared, and why it isn't `onDestroyView`.** §6's mechanism 5 says
 * the accumulator is cleared in `onDestroyView`; taken literally that would wipe every vote on
 * every rotation, which is the exact thing holding it in a ViewModel is meant to prevent. So it
 * is cleared in [onCleared] instead — that fires when the fragment is genuinely finished (popped,
 * or the graph torn down), not when its view is merely rebuilt. The guarantee §6 is actually
 * after — *no scan data outlives the scan* — is kept, and rotation mid-scan no longer costs the
 * user their progress. The recognizer's `close()` stays on the view's lifecycle, since it is tied
 * to the camera rather than to the data.
 *
 * The vault's master key is never needed here: `issuingBank` is a plaintext Room column, so the
 * bank vocabulary loads whether or not the vault happens to be unlocked (§2.3).
 */
class CardScanViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CardRepository(AppDatabase.get(app).cardDao())
    private val accumulator = ScanAccumulator()

    /**
     * Written once on the main thread, read on the analyzer thread. `@Volatile` publishes the new
     * (immutable) list safely; until it arrives the seed list is already a usable vocabulary, so
     * an early frame is matched against the built-in banks rather than against nothing.
     */
    @Volatile
    private var vocabulary: List<String> = BankSuggestions.seed

    /**
     * The accumulator's locked values, republished on the analyzer thread after every `offer`.
     *
     * [ScanAccumulator] is documented as single-threaded and only ever touched from the analyzer
     * thread, but "Use what you have" is a main-thread tap that needs to read the same values.
     * Reading the accumulator's fields directly from main would be an unsynchronised cross-thread
     * read; mirroring them behind one `@Volatile` reference gives the button a safely-published
     * value without loosening the accumulator's own contract.
     */
    @Volatile
    private var lastSnapshot: ScanCandidate = ScanCandidate.EMPTY

    /** Guards against posting the auto-finish more than once as further frames arrive. */
    @Volatile
    private var finishPosted: Boolean = false

    val progress = MutableLiveData(ScanProgress())

    /** Non-null exactly once, when PAN and expiry have both locked. */
    val autoFinished = MutableLiveData<ScanCandidate?>(null)

    /**
     * True once the scan has run out its time budget and given up.
     *
     * Here rather than on the fragment because it has to outlive the view. The fragment decides
     * whether to open the camera in `onViewCreated`, and a rotation rebuilds the fragment with
     * every field back to its default — so a flag held there would be gone exactly when that
     * decision is made, and the "Couldn't read the card" panel would come back as a live camera.
     * The same flag is what stops `onResume` doing the equivalent after a trip to the background:
     * releasing the camera on timeout necessarily clears `previewBound`, which is otherwise
     * indistinguishable from "the camera was never started".
     *
     * Cleared only by an explicit "Try again" — having given up is a state the user was told
     * about, so only the user gets to leave it.
     */
    var timedOut: Boolean = false

    init {
        viewModelScope.launch {
            val userBanks = runCatching {
                withContext(Dispatchers.IO) { repo.distinctIssuingBanks() }
            }.getOrDefault(emptyList())
            if (userBanks.isNotEmpty()) vocabulary = BankSuggestions.combined(userBanks)
        }
    }

    /**
     * Feeds one recognised frame through the parser and the vote accumulator.
     *
     * Called on ML Kit's callback thread — never from the main thread — which is what keeps
     * [ScanAccumulator]'s single-threaded contract true.
     */
    fun onFrame(frame: OcrFrame) {
        if (finishPosted) return

        accumulator.offer(CardScanParser.parse(frame, vocabulary))
        lastSnapshot = accumulator.snapshot()

        progress.postValue(
            ScanProgress(
                panLocked = accumulator.isPanLocked,
                expiryLocked = accumulator.isExpiryLocked,
                nameLocked = accumulator.isNameLocked,
                bankLocked = accumulator.isBankLocked,
            )
        )

        if (accumulator.isSettled()) {
            finishPosted = true
            autoFinished.postValue(lastSnapshot)
        }
    }

    /** Whatever has locked so far — what "Use what you have" and the timeout path send. */
    fun currentResult(): ScanCandidate = lastSnapshot

    /** Stops further frames from being counted once the user has committed to a result. */
    fun stopAccepting() {
        finishPosted = true
    }

    override fun onCleared() {
        accumulator.clear()
        lastSnapshot = ScanCandidate.EMPTY
        super.onCleared()
    }
}

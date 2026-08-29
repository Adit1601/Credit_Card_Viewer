package com.cardvault.ui.addedit

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cardvault.R
import com.cardvault.data.db.CardType
import com.cardvault.scan.ScanCandidate
import com.cardvault.security.CardNetwork
import com.cardvault.security.CardNetworkDetector
import com.cardvault.ui.scan.ScanResultBridge
import com.cardvault.util.CardFormatting
import com.cardvault.util.Luhn
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Calendar

class AddEditCardFragment : Fragment(R.layout.fragment_add_edit_card) {

    private val viewModel: AddEditCardViewModel by viewModels()

    private var editingCardId: String? = null
    private var suppressWatchers: Boolean = false

    /**
     * Whether the form has unsaved changes.
     *
     * Backed by the ViewModel so it outlives the view — see the state block in
     * [AddEditCardViewModel] for why this and the three below had to move there.
     */
    private var dirty: Boolean
        get() = viewModel.dirty
        set(value) { viewModel.dirty = value }
    /** Set once the user has dismissed the Luhn warning, so it never nags twice per PAN. */
    private var luhnAcknowledged: Boolean = false
    /**
     * The form as it was immediately before a scan overwrote it, for the Undo action.
     *
     * Not persisted across rotation on purpose: the Snackbar that offers Undo does not survive a
     * rotation either, so keeping the snapshot alive would only leave a dangling undo with no way
     * to trigger it.
     */
    private var preScanSnapshot: FormSnapshot? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var nicknameLayout: TextInputLayout
    private lateinit var nicknameInput: TextInputEditText
    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText
    private lateinit var panLayout: TextInputLayout
    private lateinit var panInput: TextInputEditText
    private lateinit var expiryLayout: TextInputLayout
    private lateinit var expiryInput: TextInputEditText
    private lateinit var cvvLayout: TextInputLayout
    private lateinit var cvvInput: TextInputEditText
    private lateinit var bankLayout: TextInputLayout
    private lateinit var bankInput: MaterialAutoCompleteTextView
    private lateinit var colorPicker: RecyclerView
    private lateinit var cardTypeGroup: MaterialButtonToggleGroup
    private lateinit var saveButton: MaterialButton

    private lateinit var previewCard: MaterialCardView
    private lateinit var previewNickname: TextView
    private lateinit var previewPan: TextView
    private lateinit var previewName: TextView
    private lateinit var previewExpiry: TextView
    private lateinit var previewNetwork: ImageView

    private var selectedColor: String
        get() = viewModel.selectedColor
        set(value) { viewModel.selectedColor = value }
    private var detectedNetwork: CardNetwork
        get() = viewModel.detectedNetwork
        set(value) { viewModel.detectedNetwork = value }
    private var selectedCardType: CardType
        get() = viewModel.selectedCardType
        set(value) { viewModel.selectedCardType = value }
    private lateinit var paletteAdapter: ColorPaletteAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        editingCardId = arguments?.getString("cardId")

        bindViews(view)
        toolbar.setTitle(if (editingCardId == null) R.string.addedit_add_title else R.string.addedit_edit_title)
        toolbar.setNavigationOnClickListener { attemptExit() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { attemptExit() }
            }
        )

        wirePreview()
        wireWatchers()
        wireLuhnWarning()
        wireColorPicker()
        wireCardTypePicker()
        wireBankSuggestions()
        wireSave()

        viewModel.initial.observe(viewLifecycleOwner) { editable ->
            if (editable != null) applyInitial(editable)
        }
        viewModel.savedEvent.observe(viewLifecycleOwner) { evt ->
            if (evt != null) {
                viewModel.savedEvent.value = null
                findNavController().popBackStack()
            }
        }
        // The ViewModel raises fatalError when the vault has locked mid-flight or the
        // edited row was deleted. Without this observer the save button stays disabled
        // forever and the user sees no feedback. Re-enable the button, tell the user,
        // and drop out of the form for the terminal cases.
        viewModel.fatalError.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                viewModel.fatalError.value = null
                saveButton.isEnabled = true
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG).show()
                findNavController().popBackStack()
            }
        }

        viewModel.loadIfEditing(editingCardId)
        wireScan()

        // Opens the window that [onViewStateRestored] closes, because restoring saved view state is
        // not the user typing and must not be mistaken for it. Every restored field notifies the
        // watchers wired above, and `MaterialButton` restores its own checked state so the toggle
        // group's listener fires too — left alone they set `dirty` on a rotation the user did
        // nothing in, and needlessly re-run the PAN reformat.
        //
        // It has to be set *here*: `Fragment.restoreViewState` calls `mView.restoreHierarchyState`
        // and only then `onViewStateRestored`, so a flag raised inside that callback would already
        // be too late.
        suppressWatchers = true
    }

    /**
     * Closes the suppression window opened at the end of [onViewCreated] and repaints what saved
     * view state cannot carry.
     *
     * The live preview is plain `TextView`s, which save nothing, so it has to be redrawn from the
     * just-restored fields rather than left to the watchers that were deliberately suppressed
     * through the restore. Runs before `onStart`, so a genuinely first [applyInitial] still arrives
     * afterwards and wins.
     */
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        suppressWatchers = false
        refreshPreview()
    }

    private fun bindViews(v: View) {
        toolbar = v.findViewById(R.id.toolbar)
        nicknameLayout = v.findViewById(R.id.nicknameLayout)
        nicknameInput = v.findViewById(R.id.nicknameInput)
        nameLayout = v.findViewById(R.id.nameLayout)
        nameInput = v.findViewById(R.id.nameInput)
        panLayout = v.findViewById(R.id.panLayout)
        panInput = v.findViewById(R.id.panInput)
        expiryLayout = v.findViewById(R.id.expiryLayout)
        expiryInput = v.findViewById(R.id.expiryInput)
        cvvLayout = v.findViewById(R.id.cvvLayout)
        cvvInput = v.findViewById(R.id.cvvInput)
        bankLayout = v.findViewById(R.id.bankLayout)
        bankInput = v.findViewById(R.id.bankInput)
        colorPicker = v.findViewById(R.id.colorPicker)
        cardTypeGroup = v.findViewById(R.id.cardTypeGroup)
        saveButton = v.findViewById(R.id.saveButton)

        // The <include android:id="@+id/livePreview"> overrides the root id, so livePreview IS
        // the MaterialCardView (originally cardTile in item_card_tile.xml).
        previewCard = v.findViewById(R.id.livePreview)
        previewNickname = previewCard.findViewById(R.id.nickname)
        previewPan = previewCard.findViewById(R.id.cardNumber)
        previewName = previewCard.findViewById(R.id.nameOnCard)
        previewExpiry = previewCard.findViewById(R.id.expiry)
        previewNetwork = previewCard.findViewById(R.id.networkLogo)
    }

    private fun wirePreview() {
        previewCard.setCardBackgroundColor(parseHex(selectedColor))
        previewNickname.text = ""
        previewPan.text = CardFormatting.maskPan("")
        previewName.text = ""
        previewExpiry.text = ""
        previewNetwork.setImageResource(CardNetwork.UNKNOWN.logoRes)
    }

    private fun wireWatchers() {
        nicknameInput.doAfterChanged {
            if (suppressWatchers) return@doAfterChanged
            dirty = true
            previewNickname.text = it
        }
        nameInput.doAfterChanged {
            if (suppressWatchers) return@doAfterChanged
            dirty = true
            previewName.text = it.uppercase()
        }
        panInput.doAfterChanged { raw ->
            if (suppressWatchers) return@doAfterChanged
            dirty = true
            val digits = raw.filter(Char::isDigit).take(19)
            val formatted = CardFormatting.formatPanForDisplay(digits)
            if (formatted != raw) {
                suppressWatchers = true
                panInput.setText(formatted)
                panInput.setSelection(formatted.length)
                suppressWatchers = false
            }
            detectedNetwork = CardNetworkDetector.detect(digits)
            previewNetwork.setImageResource(detectedNetwork.logoRes)
            previewPan.text = CardFormatting.maskPan(digits)
            // Editing the number retracts both the standing warning and any earlier
            // acknowledgement — the new value has not been judged yet.
            panLayout.helperText = null
            luhnAcknowledged = false
        }
        expiryInput.doAfterChanged { raw ->
            if (suppressWatchers) return@doAfterChanged
            dirty = true
            val digits = raw.filter(Char::isDigit).take(4)
            val formatted = CardFormatting.formatExpiry(digits)
            if (formatted != raw) {
                suppressWatchers = true
                expiryInput.setText(formatted)
                expiryInput.setSelection(formatted.length)
                suppressWatchers = false
            }
            previewExpiry.text = formatted
        }
        cvvInput.doAfterChanged {
            if (suppressWatchers) return@doAfterChanged
            dirty = true
        }
        bankInput.doAfterChanged {
            if (suppressWatchers) return@doAfterChanged
            dirty = true
        }
    }

    /**
     * Soft Luhn feedback on the card number, evaluated when focus *leaves* the field.
     *
     * Not per-keystroke: a PAN in mid-entry almost always fails Luhn, so a live check would
     * keep the field flagged for the entire time the user is typing. Focus-loss is the first
     * honest moment to judge it.
     *
     * Uses helperText rather than error. `error` belongs to the hard 13-19 digit block in
     * [attemptSave], and TextInputLayout hides helper text while an error is showing, so the
     * soft warning and the hard block can never collide on screen.
     *
     * Nothing fires on load: [applyInitial] runs with watchers suppressed and never moves
     * focus, so opening an existing card whose number fails Luhn stays silent until the user
     * actually touches the field. We don't nag about stored data they didn't just type.
     */
    private fun wireLuhnWarning() {
        panLayout.setHelperTextColor(
            ColorStateList.valueOf(requireContext().getColor(R.color.warning_amber))
        )
        panInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val digits = panInput.text?.toString().orEmpty().filter(Char::isDigit)
            // Out-of-range lengths are the hard validator's business, not ours.
            panLayout.helperText =
                if (digits.length in 13..19 && !Luhn.isValid(digits)) {
                    getString(R.string.warning_pan_checksum)
                } else {
                    null
                }
        }
    }

    private fun wireBankSuggestions() {
        // Seed with the static list immediately so the dropdown is populated on first focus;
        // then fold in banks the user has already entered in other cards.
        bankInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, BankSuggestions.combined(emptyList()))
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val userBanks = runCatching { viewModel.distinctBanks() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
            if (userBanks.isNotEmpty() && isAdded) {
                bankInput.setAdapter(
                    ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_list_item_1,
                        BankSuggestions.combined(userBanks)
                    )
                )
            }
        }
    }

    private fun wireColorPicker() {
        paletteAdapter = ColorPaletteAdapter(ColorPalette.hexes, selectedColor) { hex ->
            selectedColor = hex
            dirty = true
            paletteAdapter.setSelected(hex)
            previewCard.setCardBackgroundColor(parseHex(hex))
        }
        colorPicker.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        colorPicker.adapter = paletteAdapter
    }

    private fun wireCardTypePicker() {
        cardTypeGroup.addOnButtonCheckedListener { group, _, _ ->
            if (suppressWatchers) return@addOnButtonCheckedListener
            dirty = true
            val checkedId = group.checkedButtonId
            selectedCardType = if (checkedId == View.NO_ID) CardType.UNKNOWN else typeForButtonId(checkedId)
        }
    }

    private fun typeForButtonId(id: Int): CardType = when (id) {
        R.id.typeDebit -> CardType.DEBIT
        R.id.typeCredit -> CardType.CREDIT
        R.id.typePrepaid -> CardType.PREPAID
        else -> CardType.UNKNOWN
    }

    private fun buttonIdForType(type: CardType): Int = when (type) {
        CardType.DEBIT -> R.id.typeDebit
        CardType.CREDIT -> R.id.typeCredit
        CardType.PREPAID -> R.id.typePrepaid
        CardType.UNKNOWN -> View.NO_ID
    }

    private fun wireSave() {
        saveButton.setOnClickListener { attemptSave() }
    }

    private fun applyInitial(e: EditableCard) {
        // Second or later delivery for the same editing session — a rotation, or the pop back from
        // the scanner. [AddEditCardViewModel.initial] is retained LiveData and replays its value to
        // every new observer, so writing the stored row again here would silently discard whatever
        // the user has typed since, and reset `dirty` along with it.
        //
        // Nothing needs re-applying on that path: the text fields and the type toggle come back from
        // saved view state, [onViewStateRestored] repaints the preview, and the state that is in
        // neither now lives on the ViewModel. A form that is genuinely empty means the ViewModel was
        // rebuilt too, which resets this flag with it.
        if (viewModel.initialApplied) return
        viewModel.initialApplied = true

        suppressWatchers = true
        nicknameInput.setText(e.nickname)
        nameInput.setText(e.nameOnCard)
        panInput.setText(CardFormatting.formatPanForDisplay(e.cardNumberDigits))
        expiryInput.setText(CardFormatting.formatExpiry(e.expiryDigits))
        cvvInput.setText(e.cvvDigits)
        // Second arg `filter=false` — otherwise setText triggers the dropdown filter and pops
        // the suggestion list right after opening the edit screen.
        bankInput.setText(e.issuingBank, false)
        selectedColor = e.colorHex
        paletteAdapter.setSelected(e.colorHex)
        detectedNetwork = CardNetworkDetector.detect(e.cardNumberDigits)
        selectedCardType = e.cardType
        val typeButtonId = buttonIdForType(e.cardType)
        if (typeButtonId != View.NO_ID) cardTypeGroup.check(typeButtonId) else cardTypeGroup.clearChecked()
        suppressWatchers = false

        refreshPreview()
        dirty = false
    }

    /**
     * Repaints the live preview from the current field values.
     *
     * Every path that writes the fields with [suppressWatchers] on needs this: the watchers are
     * what normally keep the preview in step, so suppressing them also suppresses the preview.
     */
    private fun refreshPreview() {
        previewNickname.text = nicknameInput.text?.toString().orEmpty()
        previewName.text = nameInput.text?.toString().orEmpty().uppercase()
        previewPan.text = CardFormatting.maskPan(currentPanDigits())
        previewExpiry.text = CardFormatting.formatExpiry(
            expiryInput.text?.toString().orEmpty().filter(Char::isDigit)
        )
        previewNetwork.setImageResource(detectedNetwork.logoRes)
        previewCard.setCardBackgroundColor(parseHex(selectedColor))
    }

    // ---- scanning ----------------------------------------------------------------------------

    private fun wireScan() {
        // The camera lives inside the number field's end icon rather than as a separate button:
        // it sits next to the field it mostly fills in, costs no vertical space, and the edit
        // screen gets it for free (re-scanning a mistyped number is a legitimate correction).
        panLayout.setEndIconOnClickListener {
            // Cleared on the way *out*, not on the way back in. The flag exists only to stop a
            // rotation re-applying an already-applied result; clearing it here means a second,
            // deliberate scan still lands.
            viewModel.scanApplied = false
            findNavController().navigate(R.id.action_add_to_scan)
        }

        // Route A — the scanner popped back to a form that was already on the stack, so the
        // user's partially typed input is still here and must survive.
        val entry = findNavController().currentBackStackEntry
        entry?.savedStateHandle
            ?.getLiveData<Bundle?>(ScanResultBridge.KEY_SCAN_RESULT)
            ?.observe(viewLifecycleOwner) { bundle ->
                if (bundle == null) return@observe
                // Null out rather than remove(): SavedStateHandle.remove() also drops the
                // LiveData, which would leave this observer permanently disconnected and
                // silently swallow the *second* scan from this form. Setting null keeps the
                // channel open and still stops the rotation replay from re-applying.
                entry.savedStateHandle[ScanResultBridge.KEY_SCAN_RESULT] = null
                applyScan(ScanResultBridge.fromBundle(bundle))
            }

        // Route B — Home sent the user straight to the scanner, which navigated *here* with the
        // result as arguments. There was no form on the stack to pop back to in that flow.
        val fromArgs = arguments?.getBundle(ScanResultBridge.KEY_SCAN_RESULT)
        if (fromArgs != null) {
            // arguments is the same Bundle instance the framework saves and restores, so the
            // removal survives rotation. viewModel.scanApplied backs it up either way.
            arguments?.remove(ScanResultBridge.KEY_SCAN_RESULT)
            applyScan(ScanResultBridge.fromBundle(fromArgs))
        }
    }

    /**
     * Fills the form from a scan.
     *
     * Modelled on [applyInitial] — suppress the watchers, run the setters, refresh the preview
     * explicitly — with three deliberate differences:
     *
     *  - **CVV is never written.** The scanner does not read security codes, [ScanCandidate] has
     *    no field for one, and this method has no line that touches [cvvInput]. The field is left
     *    empty and given focus instead, because it is the one thing the user must still type.
     *  - Only fields the scan actually resolved are written. `null` means "could not read", not
     *    "empty", so blanking on null would destroy input the user had already typed.
     *  - `dirty` is left **true**. A scanned-but-unsaved form genuinely has unsaved changes and
     *    backing out of it should warn.
     */
    private fun applyScan(scan: ScanCandidate) {
        if (viewModel.scanApplied) return
        viewModel.scanApplied = true

        if (scan.isEmpty) {
            Snackbar.make(requireView(), R.string.scan_applied_nothing, Snackbar.LENGTH_LONG).show()
            return
        }

        preScanSnapshot = takeSnapshot()

        suppressWatchers = true
        scan.nameOnCard?.let { nameInput.setText(it) }
        scan.panDigits?.let {
            panInput.setText(CardFormatting.formatPanForDisplay(it))
            detectedNetwork = CardNetworkDetector.detect(it)
        }
        scan.expiryDigits?.let { expiryInput.setText(CardFormatting.formatExpiry(it)) }
        // filter=false, same reason as applyInitial: setText would otherwise pop the dropdown.
        scan.issuingBank?.let { bankInput.setText(it, false) }
        suppressWatchers = false

        refreshPreview()
        dirty = true

        // A single misread digit is the likeliest way a scan goes wrong, and Luhn is the only
        // check that can catch it. Evaluate it now rather than waiting for the focus-loss check,
        // which may never fire if the user goes straight to CVV and saves.
        val panDigits = currentPanDigits()
        luhnAcknowledged = false
        panLayout.helperText =
            if (panDigits.length in 13..19 && !Luhn.isValid(panDigits)) {
                getString(R.string.warning_pan_checksum)
            } else {
                null
            }

        cvvInput.requestFocus()

        val complete = scan.panDigits != null && scan.expiryDigits != null &&
            scan.nameOnCard != null && scan.issuingBank != null
        Snackbar.make(
            requireView(),
            if (complete) R.string.scan_applied_full else R.string.scan_applied_partial,
            Snackbar.LENGTH_LONG
        ).setAction(R.string.scan_action_undo) { undoScan() }.show()
    }

    /**
     * Puts back exactly what the scan overwrote.
     *
     * The escape hatch for the case OCR handles worst: the user had already typed the number
     * correctly, scanned to fill in the rest, and the scan replaced good input with a misread.
     */
    private fun undoScan() {
        val before = preScanSnapshot ?: return
        preScanSnapshot = null

        suppressWatchers = true
        nameInput.setText(before.nameOnCard)
        panInput.setText(before.panText)
        expiryInput.setText(before.expiryText)
        bankInput.setText(before.issuingBank, false)
        detectedNetwork = before.network
        suppressWatchers = false

        refreshPreview()
        // Restored, not forced: if the form was untouched before the scan it is untouched again,
        // so backing out should not warn about changes that no longer exist.
        dirty = before.dirty
        panLayout.helperText = null
        luhnAcknowledged = false
        Snackbar.make(requireView(), R.string.scan_undone, Snackbar.LENGTH_SHORT).show()
    }

    private fun takeSnapshot(): FormSnapshot = FormSnapshot(
        nameOnCard = nameInput.text?.toString().orEmpty(),
        panText = panInput.text?.toString().orEmpty(),
        expiryText = expiryInput.text?.toString().orEmpty(),
        issuingBank = bankInput.text?.toString().orEmpty(),
        network = detectedNetwork,
        dirty = dirty
    )

    private fun currentPanDigits(): String =
        panInput.text?.toString().orEmpty().filter(Char::isDigit)

    private fun attemptSave() {
        clearErrors()
        val nickname = nicknameInput.text?.toString().orEmpty().trim()
        val name = nameInput.text?.toString().orEmpty().trim()
        val panDigits = panInput.text?.toString().orEmpty().filter(Char::isDigit)
        val expiryDigits = expiryInput.text?.toString().orEmpty().filter(Char::isDigit)
        val cvvDigits = cvvInput.text?.toString().orEmpty().filter(Char::isDigit)
        val issuingBank = bankInput.text?.toString().orEmpty().trim()

        var ok = true
        if (nickname.isEmpty()) { nicknameLayout.error = getString(R.string.error_required); ok = false }
        if (name.isEmpty()) { nameLayout.error = getString(R.string.error_required); ok = false }
        if (panDigits.length !in 13..19) { panLayout.error = getString(R.string.error_card_number_length); ok = false }
        if (!isExpiryValidShape(expiryDigits)) { expiryLayout.error = getString(R.string.error_expiry_format); ok = false }
        else if (isExpiryInPast(expiryDigits)) { expiryLayout.error = getString(R.string.error_expiry_past); ok = false }
        if (cvvDigits.length !in 3..4) { cvvLayout.error = getString(R.string.error_cvv_length); ok = false }
        if (!ok) return

        // Soft checksum gate, deliberately after hard validation so the user never gets a
        // checksum dialog stacked on top of "nickname required".
        if (!luhnAcknowledged && !Luhn.isValid(panDigits)) {
            confirmLuhnMismatch()
            return
        }

        saveButton.isEnabled = false
        viewModel.save(
            FormInput(
                nickname = nickname,
                nameOnCard = name,
                cardNumberDigits = panDigits,
                expiryDigits = expiryDigits,
                cvvDigits = cvvDigits,
                colorHex = selectedColor,
                cardType = selectedCardType,
                issuingBank = issuingBank
            ),
            editingCardId = editingCardId,
            detectedNetwork = detectedNetwork
        )
    }

    /**
     * A failed checksum is a warning, never a block. Real numbers do fail Luhn — issuer test
     * PANs, some virtual/disposable numbers, gift and private-label cards — and being unable
     * to store a card you actually hold is a worse outcome than a mistyped digit.
     */
    private fun confirmLuhnMismatch() {
        panLayout.helperText = getString(R.string.warning_pan_checksum)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.luhn_warning_title)
            .setMessage(R.string.luhn_warning_body)
            .setPositiveButton(R.string.action_save_anyway) { _, _ ->
                luhnAcknowledged = true
                attemptSave() // re-enters with the flag set, so it cannot loop
            }
            .setNegativeButton(R.string.action_let_me_check) { _, _ ->
                panInput.requestFocus()
                panInput.setSelection(panInput.text?.length ?: 0)
            }
            .show()
    }

    private fun clearErrors() {
        nicknameLayout.error = null
        nameLayout.error = null
        panLayout.error = null
        expiryLayout.error = null
        cvvLayout.error = null
    }

    private fun isExpiryValidShape(digits: String): Boolean {
        if (digits.length != 4) return false
        val month = digits.substring(0, 2).toIntOrNull() ?: return false
        return month in 1..12
    }

    private fun isExpiryInPast(digits: String): Boolean {
        val month = digits.substring(0, 2).toInt()
        val year = 2000 + digits.substring(2, 4).toInt()
        val now = Calendar.getInstance()
        val thisMonth = now.get(Calendar.MONTH) + 1
        val thisYear = now.get(Calendar.YEAR)
        return year < thisYear || (year == thisYear && month < thisMonth)
    }

    private fun attemptExit() {
        if (!dirty) {
            findNavController().popBackStack(); return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.unsaved_changes_title)
            .setMessage(R.string.unsaved_changes_body)
            .setPositiveButton(R.string.action_discard) { _, _ -> findNavController().popBackStack() }
            .setNegativeButton(R.string.action_keep_editing, null)
            .show()
    }

    private fun parseHex(hex: String): Int = runCatching { Color.parseColor(hex) }
        .getOrDefault(requireContext().getColor(R.color.card_slate))
}

/**
 * The slice of the form a scan can overwrite, captured for Undo.
 *
 * CVV is deliberately absent: nothing in the scan path writes it, so nothing needs restoring.
 * Nickname and colour are absent for the same reason.
 */
private data class FormSnapshot(
    val nameOnCard: String,
    val panText: String,
    val expiryText: String,
    val issuingBank: String,
    val network: CardNetwork,
    val dirty: Boolean
)

/** EditText helper — lambda gets called after every text change with the current value. */
private inline fun android.widget.EditText.doAfterChanged(crossinline block: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { block(s?.toString().orEmpty()) }
    })
}

package com.cardvault.ui.addedit

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cardvault.R
import com.cardvault.data.db.CardType
import com.cardvault.security.CardNetwork
import com.cardvault.security.CardNetworkDetector
import com.cardvault.util.CardFormatting
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar

class AddEditCardFragment : Fragment(R.layout.fragment_add_edit_card) {

    private val viewModel: AddEditCardViewModel by viewModels()

    private var editingCardId: String? = null
    private var suppressWatchers: Boolean = false
    private var dirty: Boolean = false

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
    private lateinit var colorPicker: RecyclerView
    private lateinit var cardTypeGroup: MaterialButtonToggleGroup
    private lateinit var saveButton: MaterialButton

    private lateinit var previewCard: MaterialCardView
    private lateinit var previewNickname: TextView
    private lateinit var previewPan: TextView
    private lateinit var previewName: TextView
    private lateinit var previewExpiry: TextView
    private lateinit var previewNetwork: ImageView

    private var selectedColor: String = ColorPalette.default()
    private var detectedNetwork: CardNetwork = CardNetwork.UNKNOWN
    private var selectedCardType: CardType = CardType.UNKNOWN
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
        wireColorPicker()
        wireCardTypePicker()
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

        viewModel.loadIfEditing(editingCardId)
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
        suppressWatchers = true
        nicknameInput.setText(e.nickname)
        nameInput.setText(e.nameOnCard)
        panInput.setText(CardFormatting.formatPanForDisplay(e.cardNumberDigits))
        expiryInput.setText(CardFormatting.formatExpiry(e.expiryDigits))
        cvvInput.setText(e.cvvDigits)
        selectedColor = e.colorHex
        paletteAdapter.setSelected(e.colorHex)
        detectedNetwork = CardNetworkDetector.detect(e.cardNumberDigits)
        selectedCardType = e.cardType
        val typeButtonId = buttonIdForType(e.cardType)
        if (typeButtonId != View.NO_ID) cardTypeGroup.check(typeButtonId) else cardTypeGroup.clearChecked()
        suppressWatchers = false

        previewNickname.text = e.nickname
        previewName.text = e.nameOnCard.uppercase()
        previewPan.text = CardFormatting.maskPan(e.cardNumberDigits)
        previewExpiry.text = CardFormatting.formatExpiry(e.expiryDigits)
        previewNetwork.setImageResource(detectedNetwork.logoRes)
        previewCard.setCardBackgroundColor(parseHex(e.colorHex))

        dirty = false
    }

    private fun attemptSave() {
        clearErrors()
        val nickname = nicknameInput.text?.toString().orEmpty().trim()
        val name = nameInput.text?.toString().orEmpty().trim()
        val panDigits = panInput.text?.toString().orEmpty().filter(Char::isDigit)
        val expiryDigits = expiryInput.text?.toString().orEmpty().filter(Char::isDigit)
        val cvvDigits = cvvInput.text?.toString().orEmpty().filter(Char::isDigit)

        var ok = true
        if (nickname.isEmpty()) { nicknameLayout.error = getString(R.string.error_required); ok = false }
        if (name.isEmpty()) { nameLayout.error = getString(R.string.error_required); ok = false }
        if (panDigits.length !in 13..19) { panLayout.error = getString(R.string.error_card_number_length); ok = false }
        if (!isExpiryValidShape(expiryDigits)) { expiryLayout.error = getString(R.string.error_expiry_format); ok = false }
        else if (isExpiryInPast(expiryDigits)) { expiryLayout.error = getString(R.string.error_expiry_past); ok = false }
        if (cvvDigits.length !in 3..4) { cvvLayout.error = getString(R.string.error_cvv_length); ok = false }
        if (!ok) return

        saveButton.isEnabled = false
        viewModel.save(
            FormInput(
                nickname = nickname,
                nameOnCard = name,
                cardNumberDigits = panDigits,
                expiryDigits = expiryDigits,
                cvvDigits = cvvDigits,
                colorHex = selectedColor,
                cardType = selectedCardType
            ),
            editingCardId = editingCardId,
            detectedNetwork = detectedNetwork
        )
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

/** TextInputEditText helper — lambda gets called after every text change with the current value. */
private inline fun TextInputEditText.doAfterChanged(crossinline block: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { block(s?.toString().orEmpty()) }
    })
}

package com.cardvault.ui.detail

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.cardvault.R
import com.cardvault.data.prefs.CvvReauthMode
import com.cardvault.data.prefs.SecurePreferences
import com.cardvault.security.SessionManager
import com.cardvault.ui.auth.AppPasswordDialogFragment
import com.cardvault.ui.auth.CvvBiometricPrompt
import com.cardvault.util.CardFormatting
import com.cardvault.util.ClipboardUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar

/**
 * Card Detail screen (§6). Renders decrypted fields, gates CVV behind the app-password
 * dialog per the configured [CvvReauthMode], and copies sensitive fields with 30 s
 * clipboard auto-clear.
 */
class CardDetailFragment : Fragment(R.layout.fragment_card_detail) {

    private val viewModel: CardDetailViewModel by viewModels()

    private lateinit var prefs: SecurePreferences
    private var cardId: String? = null
    private var pending: PendingCvvAction? = null
    private var isNumberRevealed: Boolean = false
    private var isCvvRevealed: Boolean = false

    private lateinit var toolbar: MaterialToolbar
    private lateinit var previewCard: MaterialCardView
    private lateinit var previewNickname: TextView
    private lateinit var previewPan: TextView
    private lateinit var previewName: TextView
    private lateinit var previewExpiry: TextView
    private lateinit var previewNetwork: ImageView

    private lateinit var rowNickname: TextView
    private lateinit var rowName: TextView
    private lateinit var rowNumber: TextView
    private lateinit var rowExpiry: TextView
    private lateinit var rowCvv: TextView
    private lateinit var rowNetwork: TextView
    private lateinit var toggleNumber: ImageButton
    private lateinit var copyNumber: ImageButton
    private lateinit var copyExpiry: ImageButton
    private lateinit var copyCvv: ImageButton
    private lateinit var copyNickname: ImageButton
    private lateinit var copyName: ImageButton
    private lateinit var showCvvButton: MaterialButton
    private lateinit var editButton: MaterialButton
    private lateinit var deleteButton: MaterialButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SecurePreferences(requireContext())
        // Bind views first — popBackStack() below is async, so the fragment can still
        // reach RESUMED and then onPause before the pop lands. onPause touches rowCvv,
        // and it must be initialized by then even in the missing-arg edge case.
        bindViews(view)
        val id = arguments?.getString("cardId")
        if (id == null) {
            findNavController().popBackStack()
            return
        }
        cardId = id

        parentFragmentManager.setFragmentResultListener(
            AppPasswordDialogFragment.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val ok = bundle.getBoolean(AppPasswordDialogFragment.RESULT_OK, false)
            val action = pending
            pending = null
            if (ok && action != null) {
                if (prefs.getCvvReauthMode() == CvvReauthMode.PER_SESSION) {
                    SessionManager.cvvAuthorizedThisSession = true
                }
                when (action) {
                    PendingCvvAction.REVEAL -> revealCvv()
                    PendingCvvAction.COPY -> copyCvvToClipboard()
                }
            }
        }

        viewModel.state.observe(viewLifecycleOwner) { s ->
            if (s != null) renderState(s)
        }
        viewModel.deleted.observe(viewLifecycleOwner) { evt ->
            if (evt != null) {
                viewModel.deleted.value = null
                findNavController().popBackStack()
            }
        }
        // Load can fail three ways: row missing, vault locked, decrypt failed. All three
        // are terminal for this screen — surface the message and get out, rather than
        // leaving the fragment mounted with lateinit fields never populated.
        viewModel.error.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                viewModel.error.value = null
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG).show()
                findNavController().popBackStack()
            }
        }

        viewModel.load(cardId!!)
    }

    override fun onPause() {
        super.onPause()
        // §6.3 — CVV hides when the user leaves the screen or backgrounds the app.
        hideCvv()
        // Same policy for the revealed PAN. Otherwise a brief background→foreground with
        // lock-on-background=false would return the user to a screen that still shows the
        // full card number.
        if (isNumberRevealed) {
            isNumberRevealed = false
            val digits = viewModel.state.value?.cardNumberDigits
            rowNumber.text = if (digits != null) CardFormatting.maskPan(digits) else ""
            toggleNumber.setImageResource(R.drawable.ic_visibility)
            toggleNumber.contentDescription = getString(R.string.detail_reveal_number)
        }
    }

    private fun bindViews(v: View) {
        toolbar = v.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // The <include android:id="@+id/detailPreview"> overrides the root id, so detailPreview IS
        // the MaterialCardView (originally cardTile in item_card_tile.xml).
        previewCard = v.findViewById(R.id.detailPreview)
        previewNickname = previewCard.findViewById(R.id.nickname)
        previewPan = previewCard.findViewById(R.id.cardNumber)
        previewName = previewCard.findViewById(R.id.nameOnCard)
        previewExpiry = previewCard.findViewById(R.id.expiry)
        previewNetwork = previewCard.findViewById(R.id.networkLogo)

        rowNickname = v.findViewById(R.id.rowNickname)
        rowName = v.findViewById(R.id.rowName)
        rowNumber = v.findViewById(R.id.rowNumber)
        rowExpiry = v.findViewById(R.id.rowExpiry)
        rowCvv = v.findViewById(R.id.rowCvv)
        rowNetwork = v.findViewById(R.id.rowNetwork)

        toggleNumber = v.findViewById(R.id.toggleNumber)
        copyNumber = v.findViewById(R.id.copyNumber)
        copyExpiry = v.findViewById(R.id.copyExpiry)
        copyCvv = v.findViewById(R.id.copyCvv)
        copyNickname = v.findViewById(R.id.copyNickname)
        copyName = v.findViewById(R.id.copyName)
        showCvvButton = v.findViewById(R.id.showCvvButton)
        editButton = v.findViewById(R.id.editButton)
        deleteButton = v.findViewById(R.id.deleteButton)
    }

    private fun renderState(s: CardDetailState) {
        toolbar.title = s.nickname
        rowNickname.text = s.nickname
        rowName.text = s.nameOnCard
        // Re-mask the number and reset the toggle in lock-step, so a re-render after an edit
        // never leaves the "hide" icon showing above a masked number.
        isNumberRevealed = false
        rowNumber.text = CardFormatting.maskPan(s.cardNumberDigits)
        toggleNumber.setImageResource(R.drawable.ic_visibility)
        toggleNumber.contentDescription = getString(R.string.detail_reveal_number)
        rowExpiry.text = CardFormatting.formatExpiry(s.expiryDigits)
        hideCvv()
        rowNetwork.text = getString(s.network.contentDescRes)

        previewCard.setCardBackgroundColor(parseHex(s.colorHex))
        previewNickname.text = s.nickname
        previewName.text = s.nameOnCard.uppercase()
        previewPan.text = CardFormatting.maskPan(s.cardNumberDigits)
        previewExpiry.text = CardFormatting.formatExpiry(s.expiryDigits)
        previewNetwork.setImageResource(s.network.logoRes)

        copyNickname.setOnClickListener { ClipboardUtil.copyPlain(requireContext(), s.nickname, it) }
        copyName.setOnClickListener { ClipboardUtil.copyPlain(requireContext(), s.nameOnCard, it) }
        copyExpiry.setOnClickListener {
            ClipboardUtil.copyPlain(requireContext(), CardFormatting.formatExpiry(s.expiryDigits), it)
        }

        toggleNumber.setOnClickListener {
            isNumberRevealed = !isNumberRevealed
            rowNumber.text = if (isNumberRevealed)
                CardFormatting.formatPanForDisplay(s.cardNumberDigits)
            else
                CardFormatting.maskPan(s.cardNumberDigits)
            toggleNumber.setImageResource(
                if (isNumberRevealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            )
            toggleNumber.contentDescription = getString(
                if (isNumberRevealed) R.string.detail_hide_number else R.string.detail_reveal_number
            )
        }
        copyNumber.setOnClickListener {
            ClipboardUtil.copySensitive(
                requireContext(),
                s.cardNumberDigits,
                requireView()
            )
        }

        showCvvButton.setOnClickListener {
            // Hiding again is not a privileged action, so it never re-prompts — only revealing
            // does. Without this the button sat there reading "Show CVV" over an already-visible
            // CVV, and tapping it re-ran the whole biometric/password prompt to no visible effect.
            when {
                isCvvRevealed -> hideCvv()
                canSkipCvvReauth() -> revealCvv()
                else -> request(PendingCvvAction.REVEAL)
            }
        }
        copyCvv.setOnClickListener {
            if (canSkipCvvReauth()) copyCvvToClipboard() else request(PendingCvvAction.COPY)
        }

        editButton.setOnClickListener {
            findNavController().navigate(
                R.id.action_detail_to_edit,
                bundleOf("cardId" to s.id)
            )
        }
        deleteButton.setOnClickListener { confirmDelete() }
    }

    private fun canSkipCvvReauth(): Boolean =
        prefs.getCvvReauthMode() == CvvReauthMode.PER_SESSION &&
            SessionManager.cvvAuthorizedThisSession

    private fun request(action: PendingCvvAction) {
        pending = action
        val messageRes = when (action) {
            PendingCvvAction.REVEAL -> R.string.password_dialog_reveal_cvv
            PendingCvvAction.COPY -> R.string.password_dialog_copy_cvv
        }
        CvvBiometricPrompt.request(this, messageRes)
    }

    private fun revealCvv() {
        rowCvv.text = viewModel.state.value?.cvvDigits ?: return
        isCvvRevealed = true
        showCvvButton.setText(R.string.detail_hide_cvv)
    }

    /** Re-masks the CVV and puts the button back to "Show CVV", in lock-step. */
    private fun hideCvv() {
        isCvvRevealed = false
        rowCvv.text = getString(R.string.detail_cvv_hidden)
        showCvvButton.setText(R.string.detail_show_cvv)
    }

    private fun copyCvvToClipboard() {
        val cvv = viewModel.state.value?.cvvDigits ?: return
        ClipboardUtil.copySensitive(requireContext(), cvv, requireView())
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.detail_delete_confirm_title)
            .setMessage(R.string.detail_delete_confirm_body)
            .setPositiveButton(R.string.detail_delete) { _, _ -> viewModel.deleteCurrent() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun parseHex(hex: String): Int = runCatching { Color.parseColor(hex) }
        .getOrDefault(requireContext().getColor(R.color.card_slate))

    private enum class PendingCvvAction { REVEAL, COPY }
}

package com.cardvault.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.cardvault.R
import com.cardvault.data.prefs.CvvReauthMode
import com.cardvault.data.prefs.SecurePreferences
import com.cardvault.ui.onboarding.OnboardingActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var prefs: SecurePreferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SecurePreferences(requireContext())

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val rowChangePassword = view.findViewById<View>(R.id.rowChangePassword)
        val switchLockOnBg = view.findViewById<MaterialSwitch>(R.id.switchLockOnBg)
        val radioPerAction = view.findViewById<RadioButton>(R.id.cvvReauthPerAction)
        val radioPerSession = view.findViewById<RadioButton>(R.id.cvvReauthPerSession)
        val rowResetApp = view.findViewById<View>(R.id.rowResetApp)
        val versionText = view.findViewById<TextView>(R.id.versionText)
        val progress = view.findViewById<ProgressBar>(R.id.blockingProgress)

        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        switchLockOnBg.isChecked = prefs.getLockOnBackground()
        switchLockOnBg.setOnCheckedChangeListener { _, checked -> viewModel.setLockOnBackground(checked) }

        when (prefs.getCvvReauthMode()) {
            CvvReauthMode.PER_ACTION -> radioPerAction.isChecked = true
            CvvReauthMode.PER_SESSION -> radioPerSession.isChecked = true
        }
        radioPerAction.setOnCheckedChangeListener { _, c -> if (c) viewModel.setCvvReauthMode(CvvReauthMode.PER_ACTION) }
        radioPerSession.setOnCheckedChangeListener { _, c -> if (c) viewModel.setCvvReauthMode(CvvReauthMode.PER_SESSION) }

        rowChangePassword.setOnClickListener { showChangePasswordDialog() }
        rowResetApp.setOnClickListener { showResetConfirmDialog() }

        versionText.text = readVersionName()

        viewModel.busy.observe(viewLifecycleOwner) { progress.visibility = if (it == true) View.VISIBLE else View.GONE }
        viewModel.resetCompleted.observe(viewLifecycleOwner) { done ->
            if (done == true) {
                startActivity(
                    Intent(requireContext(), OnboardingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                requireActivity().finish()
            }
        }
        viewModel.changePasswordResult.observe(viewLifecycleOwner) { outcome ->
            if (outcome == null) return@observe
            viewModel.changePasswordResult.value = null
            when (outcome) {
                ChangePasswordOutcome.Success ->
                    Snackbar.make(requireView(), R.string.change_password_success, Snackbar.LENGTH_SHORT).show()
                ChangePasswordOutcome.WrongOldPassword ->
                    Snackbar.make(requireView(), R.string.error_wrong_password, Snackbar.LENGTH_SHORT).show()
                ChangePasswordOutcome.Error ->
                    Snackbar.make(requireView(), R.string.change_password_error_generic, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showChangePasswordDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_change_password, null, false)
        val currentLayout = view.findViewById<TextInputLayout>(R.id.currentLayout)
        val newLayout = view.findViewById<TextInputLayout>(R.id.newLayout)
        val newConfirmLayout = view.findViewById<TextInputLayout>(R.id.newConfirmLayout)
        val currentInput = view.findViewById<TextInputEditText>(R.id.currentInput)
        val newInput = view.findViewById<TextInputEditText>(R.id.newInput)
        val newConfirmInput = view.findViewById<TextInputEditText>(R.id.newConfirmInput)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_change_password)
            .setView(view)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                currentLayout.error = null
                newLayout.error = null
                newConfirmLayout.error = null

                val current = currentInput.text?.toString().orEmpty()
                val newPw = newInput.text?.toString().orEmpty()
                val newConfirm = newConfirmInput.text?.toString().orEmpty()

                if (current.isEmpty()) {
                    currentLayout.error = getString(R.string.error_required); return@setOnClickListener
                }
                if (newPw.length < 4) {
                    newLayout.error = getString(R.string.error_password_min_length); return@setOnClickListener
                }
                if (newPw != newConfirm) {
                    newConfirmLayout.error = getString(R.string.error_password_mismatch); return@setOnClickListener
                }
                dialog.dismiss()
                viewModel.changePassword(current, newPw)
            }
        }
        dialog.show()
    }

    private fun showResetConfirmDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_reset_app_confirm_title)
            .setMessage(R.string.settings_reset_app_confirm_body)
            .setPositiveButton(R.string.action_reset_app) { _, _ -> showResetFinalConfirm() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showResetFinalConfirm() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_reset_app_confirm_title)
            .setMessage(R.string.settings_reset_app_final_body)
            .setPositiveButton(R.string.action_reset_app) { _, _ -> performReset() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun performReset() {
        // Navigation happens when resetCompleted flips true (observed in onViewCreated). That
        // path survives rotation — the observer re-attaches on the new viewLifecycleOwner and
        // LiveData replays the current value — which the in-callback observer did not.
        viewModel.resetAllData()
    }

    private fun readVersionName(): String {
        val pm = requireContext().packageManager
        val name = pm.getPackageInfo(requireContext().packageName, 0).versionName ?: "?"
        return name
    }
}

package com.cardvault.ui.onboarding

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.cardvault.R
import com.google.android.material.button.MaterialButton

class WelcomeFragment : Fragment(R.layout.fragment_welcome) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.getStartedButton).setOnClickListener {
            findNavController().navigate(R.id.action_welcome_to_set_password)
        }
    }
}

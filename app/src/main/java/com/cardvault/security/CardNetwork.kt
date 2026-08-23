package com.cardvault.security

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.cardvault.R

enum class CardNetwork(
    @DrawableRes val logoRes: Int,
    @StringRes val contentDescRes: Int
) {
    VISA(R.drawable.ic_network_visa, R.string.cd_network_visa),
    MASTERCARD(R.drawable.ic_network_mastercard, R.string.cd_network_mastercard),
    AMEX(R.drawable.ic_network_amex, R.string.cd_network_amex),
    RUPAY(R.drawable.ic_network_rupay, R.string.cd_network_rupay),
    DISCOVER(R.drawable.ic_network_discover, R.string.cd_network_discover),
    DINERS(R.drawable.ic_network_diners, R.string.cd_network_diners),
    UNKNOWN(R.drawable.ic_network_generic, R.string.cd_network_unknown)
}

package com.cardvault

import android.app.Application

class CardVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CardVaultApplication
            private set
    }
}

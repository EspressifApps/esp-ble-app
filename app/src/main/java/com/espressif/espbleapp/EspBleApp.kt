package com.espressif.espbleapp

import android.app.Application
import com.espressif.espbleapp.settings.AppSettingsRepository

class EspBleApp : Application() {
    lateinit var settings: AppSettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettingsRepository(this)
        settings.applyStoredLanguageBlocking()
    }
}

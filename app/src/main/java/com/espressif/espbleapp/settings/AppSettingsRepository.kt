package com.espressif.espbleapp.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

class AppSettingsRepository(private val context: Context) {
    private val languageKey = stringPreferencesKey("app_language")

    val language: Flow<AppLanguage> = context.settingsDataStore.data.map { prefs ->
        AppLanguage.fromStorageTag(prefs[languageKey])
    }

    fun applyStoredLanguageBlocking() {
        runBlocking {
            applyLanguage(language.first())
        }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { prefs ->
            prefs[languageKey] = language.storageTag
        }
        applyLanguage(language)
    }

    private fun applyLanguage(language: AppLanguage) {
        val locales = when (language) {
            AppLanguage.System -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.English -> LocaleListCompat.forLanguageTags("en")
            AppLanguage.Chinese -> LocaleListCompat.forLanguageTags("zh-CN")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

package com.westly.neribovault.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.neriboSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "neribo_settings")

/** Small app settings kept in DataStore. */
class SettingsStore(private val context: Context) {
    private val themeModeKey = stringPreferencesKey("theme_mode")

    /** "system" (default), "light" or "dark". */
    val themeMode: Flow<String> = context.neriboSettingsDataStore.data.map { prefs ->
        prefs[themeModeKey] ?: "system"
    }

    suspend fun setThemeMode(mode: String) {
        context.neriboSettingsDataStore.edit { prefs -> prefs[themeModeKey] = mode }
    }
}

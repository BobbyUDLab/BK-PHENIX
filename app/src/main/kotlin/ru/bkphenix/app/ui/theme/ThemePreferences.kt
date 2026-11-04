package ru.bkphenix.app.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Выбор темы пользователем. */
enum class ThemeMode(val displayName: String) { LIGHT("Светлая"), OLED("Тёмная OLED"), SYSTEM("Системная") }

private val Context.settings by preferencesDataStore(name = "settings")

/** Хранит выбранную тему в DataStore; по умолчанию — системная. */
class ThemePreferences(private val context: Context) {
    val mode: Flow<ThemeMode> = context.settings.data.map { prefs ->
        prefs[KEY]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM
    }

    suspend fun set(mode: ThemeMode) {
        context.settings.edit { it[KEY] = mode.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("theme_mode")
    }
}

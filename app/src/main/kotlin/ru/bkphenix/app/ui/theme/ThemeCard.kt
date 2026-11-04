package ru.bkphenix.app.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.bkphenix.app.PhenixApplication
import ru.bkphenix.app.ui.components.SectionCard

/** Переключатель темы: Светлая / Тёмная OLED / Системная. Выбор сохраняется в DataStore. */
@Composable
fun ThemeCard() {
    val prefs = (LocalContext.current.applicationContext as PhenixApplication).container.themePreferences
    val mode by prefs.mode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val scope = rememberCoroutineScope()
    SectionCard("Оформление") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m ->
                FilterChip(
                    selected = m == mode,
                    onClick = { scope.launch { prefs.set(m) } },
                    label = { Text(m.displayName) },
                )
            }
        }
    }
}

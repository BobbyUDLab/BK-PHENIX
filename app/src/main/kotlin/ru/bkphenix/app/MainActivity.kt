package ru.bkphenix.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bkphenix.app.ui.PhenixApp
import ru.bkphenix.app.ui.theme.BkPhenixTheme
import ru.bkphenix.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = (application as PhenixApplication).container.themePreferences
        setContent {
            val mode by prefs.mode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            BkPhenixTheme(mode) {
                PhenixApp()
            }
        }
    }
}

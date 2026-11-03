package ru.bkphenix.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PhenixNavy = Color(0xFF00163B)
private val PhenixOrange = Color(0xFFFF7A1A)
private val PhenixCyan = Color(0xFF1EC8FF)

private val DarkColors = darkColorScheme(
    primary = PhenixOrange,
    secondary = PhenixCyan,
    background = PhenixNavy,
    surface = Color(0xFF0A2350),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFD9570A),
    secondary = Color(0xFF0078A8),
)

@Composable
fun BkPhenixTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

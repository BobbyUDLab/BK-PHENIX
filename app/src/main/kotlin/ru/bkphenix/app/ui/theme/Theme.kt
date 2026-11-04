package ru.bkphenix.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private fun c(argb: Long) = Color(argb)

private val OledColors = with(PhenixPalette.Oled) {
    darkColorScheme(
        primary = c(PRIMARY),
        onPrimary = c(BACKGROUND),
        secondary = c(SECONDARY),
        onSecondary = c(BACKGROUND),
        tertiary = c(TERTIARY),
        onTertiary = c(BACKGROUND),
        background = c(BACKGROUND),
        onBackground = c(ON_SURFACE),
        surface = c(BACKGROUND),
        onSurface = c(ON_SURFACE),
        surfaceVariant = c(SURFACE_CONTAINER),
        onSurfaceVariant = c(ON_SURFACE_VARIANT),
        surfaceContainerLowest = c(BACKGROUND),
        surfaceContainerLow = c(SURFACE_CONTAINER),
        surfaceContainer = c(SURFACE_CONTAINER),
        surfaceContainerHigh = c(0xFF101A2EL),
        surfaceContainerHighest = c(0xFF16223AL),
        outline = c(PhenixPalette.ORBIT),
        outlineVariant = c(0xFF1E2E4AL),
        error = c(STATUS_BAD),
        onError = c(BACKGROUND),
    )
}

private val LightColors = with(PhenixPalette.Light) {
    lightColorScheme(
        primary = c(PRIMARY),
        secondary = c(SECONDARY),
        tertiary = c(TERTIARY),
        background = c(BACKGROUND),
        onBackground = c(ON_SURFACE),
        surface = c(BACKGROUND),
        onSurface = c(ON_SURFACE),
        surfaceVariant = c(SURFACE_CONTAINER),
        onSurfaceVariant = c(ON_SURFACE_VARIANT),
        surfaceContainer = c(SURFACE_CONTAINER),
        error = c(STATUS_BAD),
    )
}

/** Цвета статусов доверия: «ок», «подозрение», «отвергнут». Подобраны под AA на фоне темы. */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val bad: Color)

val LocalStatusColors = staticCompositionLocalOf {
    with(PhenixPalette.Light) { StatusColors(c(STATUS_OK), c(STATUS_WARN), c(STATUS_BAD)) }
}

/** `true`, если активна тёмная тема OLED (для стиля карты). */
val LocalIsOled = staticCompositionLocalOf { false }

/** Тема приложения. Dynamic color намеренно не используется: палитра бренда фиксирована. */
@Composable
fun BkPhenixTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.OLED -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val status = if (dark) {
        with(PhenixPalette.Oled) { StatusColors(c(STATUS_OK), c(STATUS_WARN), c(STATUS_BAD)) }
    } else {
        with(PhenixPalette.Light) { StatusColors(c(STATUS_OK), c(STATUS_WARN), c(STATUS_BAD)) }
    }
    CompositionLocalProvider(LocalStatusColors provides status, LocalIsOled provides dark) {
        MaterialTheme(colorScheme = if (dark) OledColors else LightColors, content = content)
    }
}

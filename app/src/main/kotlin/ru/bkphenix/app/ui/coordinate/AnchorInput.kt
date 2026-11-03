package ru.bkphenix.app.ui.coordinate

import ru.bkphenix.core.geo.GeoPoint

/** Результат разбора ручного ввода якоря. */
sealed interface AnchorInputResult {
    data class Valid(val point: GeoPoint, val accuracyM: Double) : AnchorInputResult
    data class Invalid(val message: String) : AnchorInputResult
}

/** Разбор координат, введённых человеком: допускаются запятая и точка, пробелы. */
object AnchorInput {
    const val DEFAULT_ACCURACY_M: Double = 10.0

    fun parse(latitude: String, longitude: String, accuracy: String): AnchorInputResult {
        val lat = latitude.toNumber() ?: return AnchorInputResult.Invalid("Широта не число")
        val lon = longitude.toNumber() ?: return AnchorInputResult.Invalid("Долгота не число")
        if (lat !in -90.0..90.0) return AnchorInputResult.Invalid("Широта должна быть от −90 до 90")
        if (lon !in -180.0..180.0) return AnchorInputResult.Invalid("Долгота должна быть от −180 до 180")
        val acc = if (accuracy.isBlank()) DEFAULT_ACCURACY_M else accuracy.toNumber()
            ?: return AnchorInputResult.Invalid("Погрешность не число")
        if (acc <= 0.0) return AnchorInputResult.Invalid("Погрешность должна быть больше нуля")
        return AnchorInputResult.Valid(GeoPoint(lat, lon), acc)
    }

    private fun String.toNumber(): Double? =
        trim().replace(" ", "").replace(',', '.').replace('−', '-').toDoubleOrNull()?.takeIf { it.isFinite() }
}

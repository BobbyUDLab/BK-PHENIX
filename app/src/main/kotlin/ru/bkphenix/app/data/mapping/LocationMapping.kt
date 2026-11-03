package ru.bkphenix.app.data.mapping

import ru.bkphenix.app.data.model.RawLocation
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

object LocationMapping {
    /**
     * Android сообщает радиус 68 % круговой погрешности. Для 2D-нормального распределения
     * это ≈1,515σ, поэтому σ = r₆₈ / 1,515.
     */
    const val R68_TO_SIGMA: Double = 1.515

    /** Минимальная σ: защита от нереалистично «точных» значений, которые выдают некоторые чипы. */
    const val MIN_SIGMA_M: Double = 1.0

    fun source(provider: String): PositionSource? = when (provider) {
        "gps" -> PositionSource.GNSS
        "network" -> PositionSource.NETWORK
        else -> null // passive/fused дублируют уже учтённые провайдеры
    }

    /** Наблюдение для ядра или `null`, если данные непригодны (нет погрешности, mock, неизвестный провайдер). */
    fun toObservation(location: RawLocation): PositionObservation? {
        if (location.isMock) return null
        val source = source(location.provider) ?: return null
        val accuracy = location.accuracy68M?.toDouble()?.takeIf { it > 0.0 && it.isFinite() } ?: return null
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return null
        return PositionObservation(
            source = source,
            point = GeoPoint(location.latitude, location.longitude, location.altitude),
            accuracyM = (accuracy / R68_TO_SIGMA).coerceAtLeast(MIN_SIGMA_M),
            timestampMillis = location.timeMillis,
        )
    }
}

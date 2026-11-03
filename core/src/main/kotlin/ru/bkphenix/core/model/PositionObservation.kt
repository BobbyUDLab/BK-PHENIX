package ru.bkphenix.core.model

import ru.bkphenix.core.geo.GeoPoint

/**
 * Одно наблюдение координаты от конкретного источника.
 *
 * @property accuracyM горизонтальная погрешность (1σ), метры.
 * @property timestampMillis время наблюдения, мс UTC.
 */
public data class PositionObservation(
    val source: PositionSource,
    val point: GeoPoint,
    val accuracyM: Double,
    val timestampMillis: Long,
) {
    init {
        require(accuracyM > 0.0 && accuracyM.isFinite()) { "Погрешность должна быть положительной: $accuracyM" }
    }

    /** Вес наблюдения при совмещении: обратная дисперсия. */
    val weight: Double get() = 1.0 / (accuracyM * accuracyM)
}

package ru.bkphenix.core.model

import ru.bkphenix.core.geo.GeoPoint

/**
 * Одно наблюдение координаты от конкретного источника.
 *
 * @property accuracyM горизонтальная погрешность (1σ), метры.
 * @property timestampMillis время наблюдения, мс UTC.
 * @property origin группы зависимости, на сигналах которых построена позиция (по умолчанию — из источника).
 *   Например, сетевая позиция, опирающаяся на GNSS, получает ещё и [SourceGroup.GNSS_SIGNAL].
 */
public data class PositionObservation(
    val source: PositionSource,
    val point: GeoPoint,
    val accuracyM: Double,
    val timestampMillis: Long,
    val origin: Set<SourceGroup> = source.defaultOrigin,
) {
    init {
        require(accuracyM > 0.0 && accuracyM.isFinite()) { "Погрешность должна быть положительной: $accuracyM" }
        require(origin.isNotEmpty()) { "У наблюдения должно быть происхождение" }
    }

    /** Вес наблюдения при совмещении: обратная дисперсия. */
    val weight: Double get() = 1.0 / (accuracyM * accuracyM)
}

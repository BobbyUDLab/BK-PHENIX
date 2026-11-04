package ru.bkphenix.core.model

import ru.bkphenix.core.geo.GeoPoint

/**
 * Одно наблюдение координаты от конкретного источника.
 *
 * @property accuracyM горизонтальная погрешность (1σ), метры.
 * @property timestampMillis время наблюдения, мс UTC.
 * @property origin группы зависимости, на сигналах которых построена позиция (по умолчанию — из источника).
 *   Например, сетевая позиция, опирающаяся на GNSS, получает ещё и [SourceGroup.GNSS_SIGNAL].
 * @property gnssDerived позиция выведена из прошлого решения GNSS без независимого подтверждения
 *   (например, счисление пути, привязанное к фиксу GNSS). Такой источник повторяет GNSS и не может
 *   её подтвердить: при подмене он уведён вместе с ней.
 */
public data class PositionObservation(
    val source: PositionSource,
    val point: GeoPoint,
    val accuracyM: Double,
    val timestampMillis: Long,
    val origin: Set<SourceGroup> = source.defaultOrigin,
    val gnssDerived: Boolean = false,
) {
    init {
        require(accuracyM > 0.0 && accuracyM.isFinite()) { "Погрешность должна быть положительной: $accuracyM" }
        require(origin.isNotEmpty()) { "У наблюдения должно быть происхождение" }
    }

    /** Вес наблюдения при совмещении: обратная дисперсия. */
    val weight: Double get() = 1.0 / (accuracyM * accuracyM)

    /** Источник зависит от GNSS: сам сигнал GNSS, возможное подмешивание (сеть, Fused) или привязка к фиксу GNSS. */
    val dependsOnGnss: Boolean get() = SourceGroup.GNSS_SIGNAL in origin || source.mayUseGnss || gnssDerived

    /**
     * Наблюдение — самостоятельное свидетельство: голос с ним засчитывается в кворум. Сам GNSS-сигнал —
     * свидетельство (один голос); источники, которые лишь повторяют GNSS (сеть/Fused платформы,
     * счисление от фикса GNSS), — нет.
     */
    val isIndependentEvidence: Boolean get() = !source.mayUseGnss && !gnssDerived

    /** Свидетель против GNSS: самостоятельный и не опирается на GNSS ни прямо, ни косвенно. */
    val isGnssIndependent: Boolean get() = !dependsOnGnss
}

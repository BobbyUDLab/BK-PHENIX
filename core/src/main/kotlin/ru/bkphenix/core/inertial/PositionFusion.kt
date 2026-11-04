package ru.bkphenix.core.inertial

import ru.bkphenix.core.track.HeldPosition

/**
 * Выбор позиции для карты: свежее решение — как есть; без свежего решения инерциальная оценка
 * предпочтительнее экстраполяции по последней скорости, если её погрешность не больше.
 */
public object PositionFusion {
    public fun choose(held: HeldPosition?, inertial: InertialEstimate?): HeldPosition? {
        if (held != null && !held.extrapolated) return held
        val dr = inertial ?: return held
        if (held != null && held.accuracyM < dr.sigmaM) return held
        return HeldPosition(dr.point, dr.sigmaM, dr.speedMps, dr.bearingDeg, extrapolated = true, deadReckoning = true)
    }
}

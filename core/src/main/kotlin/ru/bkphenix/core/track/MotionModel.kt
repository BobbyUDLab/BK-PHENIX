package ru.bkphenix.core.track

import ru.bkphenix.core.geo.GeoPoint

/** Режим движения: от него зависят удержание позиции при пропусках решения и поведение карты. */
public enum class MotionMode(
    public val displayName: String,
    /** Сколько удерживать позицию по последней скорости, когда решения нет, мс. */
    public val holdMillis: Long,
    /** Максимальная правдоподобная скорость, м/с: быстрее — скачок, скорость не обновляем. */
    public val maxSpeedMps: Double,
) {
    WALK("Пешком", 5_000, 4.0),
    CAR("Авто", 15_000, 70.0),
    NONE("Без привязки", 0, Double.MAX_VALUE),
}

/** Оценка позиции на момент отображения. */
public data class HeldPosition(
    val point: GeoPoint,
    val accuracyM: Double,
    val speedMps: Double,
    /** Курс, градусы от севера, или null, если стоим. */
    val bearingDeg: Double?,
    /** true — позиция экстраполирована по скорости, свежего решения нет. */
    val extrapolated: Boolean,
)

/**
 * Удерживает позицию на скорости: сглаживает скорость и курс по последовательным решениям и,
 * когда решение пропадает (тоннель, эстакада, отказ компаратора), ведёт точку по последней скорости
 * не дольше [MotionMode.holdMillis], наращивая погрешность.
 */
public class MotionModel(public var mode: MotionMode = MotionMode.CAR) {
    private var last: TrackPoint? = null
    private var speed = 0.0
    private var bearing: Double? = null

    public fun update(p: TrackPoint) {
        val prev = last
        if (prev != null) {
            val dt = (p.timeMillis - prev.timeMillis) / 1000.0
            if (dt <= 0) return
            val d = prev.point.distanceTo(p.point)
            val v = d / dt
            if (v <= mode.maxSpeedMps) {
                // Сдвиг в пределах шума не считаем движением.
                val noise = (p.accuracyM ?: 0.0) * 0.5
                val measured = if (d > noise) v else 0.0
                speed = if (speed == 0.0) measured else speed + SMOOTHING * (measured - speed)
                if (measured > MIN_MOVING_MPS) bearing = prev.point.bearingTo(p.point)
                if (speed < MIN_MOVING_MPS) bearing = null
            }
        }
        last = p
    }

    public fun position(nowMillis: Long): HeldPosition? {
        val p = last ?: return null
        val age = nowMillis - p.timeMillis
        val acc = p.accuracyM ?: DEFAULT_ACCURACY_M
        if (age <= FRESH_MILLIS) return HeldPosition(p.point, acc, speed, bearing, extrapolated = false)
        if (age > mode.holdMillis) return null
        val b = bearing
        val ageS = age / 1000.0
        val point = if (b != null && speed > MIN_MOVING_MPS) p.point.offset(speed * ageS, b) else p.point
        return HeldPosition(point, acc + speed * ageS * DRIFT_FRACTION + ageS, speed, b, extrapolated = true)
    }

    public fun reset() {
        last = null
        speed = 0.0
        bearing = null
    }

    private companion object {
        const val SMOOTHING = 0.5
        const val MIN_MOVING_MPS = 1.0
        const val FRESH_MILLIS = 2_000L
        const val DRIFT_FRACTION = 0.3
        const val DEFAULT_ACCURACY_M = 20.0
    }
}

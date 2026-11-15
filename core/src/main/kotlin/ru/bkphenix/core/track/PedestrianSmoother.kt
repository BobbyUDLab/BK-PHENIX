package ru.bkphenix.core.track

import ru.bkphenix.core.geo.GeoPoint
import kotlin.math.max
import kotlin.math.min

/**
 * Ограничение скорости итоговой точки пешком, когда она держится на грубых источниках (сеть, соты, счисление
 * с большой σ). Сетевая позиция с σ ≈ 40 м обновляется раз в несколько секунд и прыгает на 40–190 м; без
 * ограничения точка пешехода скакала вслед за ней (прогулка 14.11.2026, первые 3 минуты). Точка движется к
 * новой оценке не быстрее [maxSpeedMps]; расстояние до неё входит в погрешность. Точный источник (σ ≤
 * [preciseSigmaM]) принимается сразу.
 */
public class PedestrianSmoother(
    private val maxSpeedMps: Double = 2.5,
    private val preciseSigmaM: Double = 15.0,
    private val resetAfterMillis: Long = 30_000,
) {
    private var last: GeoPoint? = null
    private var lastMillis = 0L

    public data class Output(val point: GeoPoint, val accuracyM: Double, val limited: Boolean)

    public fun apply(point: GeoPoint, accuracyM: Double, timeMillis: Long, mode: MotionMode): Output {
        val prev = last
        val dt = (timeMillis - lastMillis) / 1000.0
        if (mode != MotionMode.WALK || prev == null || accuracyM <= preciseSigmaM || dt <= 0 || timeMillis - lastMillis > resetAfterMillis) {
            last = point
            lastMillis = timeMillis
            return Output(point, accuracyM, limited = false)
        }
        val d = prev.distanceTo(point)
        val step = maxSpeedMps * dt
        if (d <= step) {
            last = point
            lastMillis = timeMillis
            return Output(point, accuracyM, limited = false)
        }
        val moved = prev.offset(min(step, d), prev.bearingTo(point))
        last = moved
        lastMillis = timeMillis
        return Output(moved, max(accuracyM, moved.distanceTo(point)), limited = true)
    }

    public fun reset() {
        last = null
    }
}

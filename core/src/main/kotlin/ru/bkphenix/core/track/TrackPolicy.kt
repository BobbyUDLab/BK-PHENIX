package ru.bkphenix.core.track

import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.SourceGroup
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Как точка итога попадает в трек. */
public enum class TrackQuality {
    /** Обычная точка: линия трека. */
    GOOD,

    /** Грубая точка (сеть, счисление, якорь во время подмены или σ больше порога): пунктир. */
    COARSE,

    /** Не записывать и оборвать линию. */
    SKIP,
}

/**
 * Что писать в трек. В 0.6.3 писались только эпохи со статусом OK, а в Москве их почти нет
 * (поездка 04.11.2026 20:23 — ни одной из 3592): трек получался пустым. Теперь пропускается только
 * то, чему нельзя верить как позиции: итог на подозрительной GNSS и испорченные данные.
 */
public object TrackPolicy {
    /** Погрешность (1σ), выше которой точка считается грубой, м. */
    public const val COARSE_SIGMA_M: Double = 25.0

    public val SKIP_STATES: Set<IntegrityState> = setOf(IntegrityState.NO_DATA, IntegrityState.NAV_DATA_CORRUPTED)

    public fun classify(solution: PhenixSolution): TrackQuality {
        solution.point ?: return TrackQuality.SKIP
        val state = solution.integrity.state
        if (state in SKIP_STATES) return TrackQuality.SKIP
        val usesGnss = solution.comparison.accepted.any { SourceGroup.GNSS_SIGNAL in it.origin }
        if (state == IntegrityState.SPOOFING_SUSPECTED) return if (usesGnss) TrackQuality.SKIP else TrackQuality.COARSE
        val sigma = solution.accuracyM ?: return TrackQuality.COARSE
        return if (sigma > COARSE_SIGMA_M) TrackQuality.COARSE else TrackQuality.GOOD
    }

    /** Грубая ли сохранённая точка (по записанному состоянию и погрешности). */
    public fun isCoarse(p: TrackPoint): Boolean =
        p.integrity == IntegrityState.SPOOFING_SUSPECTED.name || (p.accuracyM ?: Double.MAX_VALUE) > COARSE_SIGMA_M
}

/**
 * Сглаживание трека: фильтр Калмана «постоянная скорость» по осям восток/север с погрешностью
 * каждой точки. Переключение итога между GNSS (σ 3 м) и сетью (σ 30 м) больше не рисует зигзаг:
 * грубая точка почти не сдвигает линию. Выброс (χ² > [gateChi2]) пропускается, после [maxOutliers]
 * выбросов подряд или перерыва [maxGapMillis] фильтр начинает заново — новый отрезок.
 */
public class TrackSmoother(
    private val accelPsd: Double = 3.0,
    private val gateChi2: Double = 16.0,
    private val maxOutliers: Int = 3,
    private val maxGapMillis: Long = 30_000,
) {
    private var origin: GeoPoint? = null
    private val x = DoubleArray(4) // e, n, ve, vn
    private val p = Array(2) { DoubleArray(3) } // по оси: Ppp, Ppv, Pvv
    private var lastMillis = 0L
    private var outliers = 0

    /** Был ли последний [smooth] началом нового отрезка. */
    public var restarted: Boolean = false
        private set

    public fun reset() {
        origin = null
        outliers = 0
    }

    /** Сглаженная точка или null, если [point] — выброс. */
    public fun smooth(point: TrackPoint): TrackPoint? {
        val sigma = (point.accuracyM ?: DEFAULT_SIGMA_M).coerceAtLeast(MIN_SIGMA_M)
        val o = origin
        restarted = false
        if (o == null || point.timeMillis - lastMillis > maxGapMillis || point.timeMillis <= lastMillis) {
            init(point.point, sigma, point.timeMillis)
            restarted = true
            return point
        }
        val dt = (point.timeMillis - lastMillis) / 1000.0
        predict(dt)
        val d = o.distanceTo(point.point)
        val b = Math.toRadians(o.bearingTo(point.point))
        val de = d * sin(b) - x[0]
        val dn = d * cos(b) - x[1]
        val r = sigma * sigma
        val chi2 = de * de / (p[0][0] + r) + dn * dn / (p[1][0] + r)
        if (chi2 > gateChi2) {
            if (++outliers >= maxOutliers) {
                init(point.point, sigma, point.timeMillis)
                restarted = true
                return point
            }
            lastMillis = point.timeMillis
            return null
        }
        outliers = 0
        update(0, de, r)
        update(1, dn, r)
        lastMillis = point.timeMillis
        val e = x[0]
        val n = x[1]
        val smoothed = o.offset(hypot(e, n), Math.toDegrees(atan2(e, n)))
        val ps = sqrt((p[0][0] + p[1][0]) / 2)
        return point.copy(point = GeoPoint(smoothed.latitude, smoothed.longitude, point.point.altitude), accuracyM = minOf(sigma, ps))
    }

    private fun init(point: GeoPoint, sigma: Double, timeMillis: Long) {
        origin = point
        x.fill(0.0)
        for (a in p) {
            a[0] = sigma * sigma; a[1] = 0.0; a[2] = INITIAL_SPEED_SIGMA * INITIAL_SPEED_SIGMA
        }
        lastMillis = timeMillis
        outliers = 0
    }

    private fun predict(dt: Double) {
        val q = accelPsd
        for (axis in 0..1) {
            x[axis] += x[axis + 2] * dt
            val a = p[axis]
            val pp = a[0] + 2 * dt * a[1] + dt * dt * a[2] + q * dt * dt * dt / 3
            val pv = a[1] + dt * a[2] + q * dt * dt / 2
            val vv = a[2] + q * dt
            a[0] = pp; a[1] = pv; a[2] = vv
        }
    }

    private fun update(axis: Int, d: Double, r: Double) {
        val a = p[axis]
        val s = a[0] + r
        val kp = a[0] / s
        val kv = a[1] / s
        x[axis] += kp * d
        x[axis + 2] += kv * d
        val pp = (1 - kp) * a[0]
        val pv = (1 - kp) * a[1]
        val vv = a[2] - kv * a[1]
        a[0] = pp; a[1] = pv; a[2] = vv
    }

    private companion object {
        const val DEFAULT_SIGMA_M = 20.0
        const val MIN_SIGMA_M = 2.0
        const val INITIAL_SPEED_SIGMA = 20.0
    }
}

/** Отрезок трека для отрисовки и экспорта. */
public data class TrackSegment(val points: List<TrackPoint>, val coarse: Boolean)

/**
 * Деление трека на отрезки: линия не тянется через перерыв больше [maxGapMillis] и через
 * невозможный скачок; подряд идущие грубые точки — отдельный (пунктирный) отрезок,
 * стыкующийся с соседними, чтобы линия не рвалась на смене качества.
 */
public object TrackSegments {
    public fun split(
        points: List<TrackPoint>,
        maxGapMillis: Long = 60_000,
        maxSpeedMps: Double = TrackThinner.MAX_REASONABLE_SPEED_MPS,
        /** Минимум точек в отрезке: для линии на карте — 2, для GPX одиночные точки тоже сохраняются. */
        minPoints: Int = 2,
    ): List<TrackSegment> {
        val result = mutableListOf<TrackSegment>()
        var current = mutableListOf<TrackPoint>()
        var coarse = false
        fun flush() {
            if (current.size >= minPoints) result += TrackSegment(current, coarse)
            current = mutableListOf()
        }
        for (p in points) {
            val prev = current.lastOrNull()
            val pCoarse = TrackPolicy.isCoarse(p)
            if (prev != null) {
                val dtMillis = p.timeMillis - prev.timeMillis
                val broken = dtMillis <= 0 || dtMillis > maxGapMillis ||
                    prev.point.distanceTo(p.point) / (dtMillis / 1000.0) > maxSpeedMps
                if (broken) {
                    flush()
                } else if (pCoarse != coarse) {
                    // Смена качества: отрезок закрывается этой точкой, следующий начинается с неё же.
                    current += p
                    flush()
                    current += p
                    coarse = pCoarse
                    continue
                }
            }
            if (current.isEmpty()) coarse = pCoarse
            current += p
        }
        flush()
        return result
    }
}

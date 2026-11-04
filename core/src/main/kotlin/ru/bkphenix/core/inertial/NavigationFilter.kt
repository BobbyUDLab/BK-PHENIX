package ru.bkphenix.core.inertial

import ru.bkphenix.core.geo.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Фильтр Калмана положения и скорости в локальной плоскости (восток, север) вокруг первой точки.
 *
 * Состояние — [e, n, v_e, v_n]. Прогноз — модель постоянной скорости с шумом ускорения, поэтому без
 * измерений σ растёт (∝ t^{3/2}). Измерения: позиция ГНСС, нулевая скорость (ZUPT), скорость из шага
 * (PDR), нулевая боковая скорость автомобиля; поворот вектора скорости по гироскопу (авто). Все
 * обновления скалярные (последовательные), матрицы обращать не нужно.
 */
public class NavigationFilter(
    /** СКО шума ускорения в прогнозе, м/с². */
    public var accelNoise: Double = 1.0,
) {
    private var origin: GeoPoint? = null
    private var timeMillis = 0L
    private val x = DoubleArray(4)
    private val p = Array(4) { DoubleArray(4) }
    private var rejected = 0

    public val isInitialized: Boolean get() = origin != null
    public val lastTimeMillis: Long get() = timeMillis

    /** Сбрасывает фильтр в точку [point] с погрешностью [sigmaM] и неизвестной скоростью. */
    public fun reset(point: GeoPoint, sigmaM: Double, timeMillis: Long, speedSigma: Double = 5.0) {
        origin = point
        this.timeMillis = timeMillis
        x.fill(0.0)
        for (r in p) r.fill(0.0)
        p[0][0] = sigmaM * sigmaM
        p[1][1] = sigmaM * sigmaM
        p[2][2] = speedSigma * speedSigma
        p[3][3] = speedSigma * speedSigma
        rejected = 0
    }

    /** Прогноз до [toMillis]; назад во времени не идёт. */
    public fun predict(toMillis: Long) {
        val dt = (toMillis - timeMillis) / 1000.0
        if (dt <= 0 || origin == null) return
        x[0] += x[2] * dt
        x[1] += x[3] * dt
        // P ← F P Fᵀ + Q, F = [[I, dt·I], [0, I]].
        for (axis in 0..1) {
            val pp = p[axis][axis]; val pv = p[axis][axis + 2]; val vv = p[axis + 2][axis + 2]
            p[axis][axis] = pp + 2 * dt * pv + dt * dt * vv
            p[axis][axis + 2] = pv + dt * vv
            p[axis + 2][axis] = p[axis][axis + 2]
        }
        // Перекрёстные члены осей.
        val en = p[0][1] + dt * (p[2][1] + p[0][3]) + dt * dt * p[2][3]
        val ev = p[0][3] + dt * p[2][3]
        val nv = p[1][2] + dt * p[3][2]
        p[0][1] = en; p[1][0] = en
        p[0][3] = ev; p[3][0] = ev
        p[1][2] = nv; p[2][1] = nv
        val q = accelNoise * accelNoise
        for (axis in 0..1) {
            p[axis][axis] += q * dt * dt * dt * dt / 4
            p[axis][axis + 2] += q * dt * dt * dt / 2
            p[axis + 2][axis] += q * dt * dt * dt / 2
            p[axis + 2][axis + 2] += q * dt * dt
        }
        timeMillis = toMillis
    }

    /**
     * Позиция ГНСС с СКО [sigmaM]. Выброс (χ² с 2 степенями свободы > 13.8, p = 0.999) отклоняется;
     * после трёх отклонений подряд фильтр сбрасывается в новую точку. Возвращает true, если принято.
     */
    public fun updatePosition(point: GeoPoint, sigmaM: Double, timeMillis: Long): Boolean {
        val o = origin ?: run { reset(point, sigmaM, timeMillis); return true }
        predict(timeMillis)
        val (e, n) = local(o, point)
        val r = sigmaM * sigmaM
        val de = e - x[0]
        val dn = n - x[1]
        val a = p[0][0] + r; val b = p[0][1]; val d = p[1][1] + r
        val det = a * d - b * b
        val m2 = (d * de * de - 2 * b * de * dn + a * dn * dn) / det
        if (m2 > GATE_CHI2_2DOF) {
            if (++rejected >= MAX_REJECTED) reset(point, sigmaM, timeMillis)
            return rejected == 0
        }
        rejected = 0
        scalarUpdate(doubleArrayOf(1.0, 0.0, 0.0, 0.0), e, r)
        scalarUpdate(doubleArrayOf(0.0, 1.0, 0.0, 0.0), n, r)
        return true
    }

    /** ZUPT: скорость равна нулю с СКО [sigmaMps]. */
    public fun zeroVelocity(sigmaMps: Double = 0.05) {
        if (origin == null) return
        scalarUpdate(doubleArrayOf(0.0, 0.0, 1.0, 0.0), 0.0, sigmaMps * sigmaMps)
        scalarUpdate(doubleArrayOf(0.0, 0.0, 0.0, 1.0), 0.0, sigmaMps * sigmaMps)
    }

    /** Скорость [speedMps] по курсу [bearingDeg] (шаг PDR) с СКО по каждой оси [sigmaMps]. */
    public fun updateVelocity(speedMps: Double, bearingDeg: Double, sigmaMps: Double) {
        if (origin == null) return
        val b = Math.toRadians(bearingDeg)
        val r = sigmaMps * sigmaMps
        scalarUpdate(doubleArrayOf(0.0, 0.0, 1.0, 0.0), speedMps * sin(b), r)
        scalarUpdate(doubleArrayOf(0.0, 0.0, 0.0, 1.0), speedMps * cos(b), r)
    }

    /** Автомобиль не скользит вбок: проекция скорости на поперечную к курсу [bearingDeg] ось равна 0. */
    public fun lateralConstraint(bearingDeg: Double, sigmaMps: Double = 0.3) {
        if (origin == null) return
        val b = Math.toRadians(bearingDeg)
        scalarUpdate(doubleArrayOf(0.0, 0.0, cos(b), -sin(b)), 0.0, sigmaMps * sigmaMps)
    }

    /** Поворот вектора скорости на [angleRad] против часовой стрелки (рысканье автомобиля по гироскопу). */
    public fun rotateVelocity(angleRad: Double) {
        if (origin == null || angleRad == 0.0) return
        val c = cos(angleRad); val s = sin(angleRad)
        // v_e' = c·v_e − s·v_n, v_n' = s·v_e + c·v_n (против часовой в осях восток-север).
        val j = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, c, -s), doubleArrayOf(0.0, 0.0, s, c),
        )
        val ve = x[2]; val vn = x[3]
        x[2] = c * ve - s * vn
        x[3] = s * ve + c * vn
        val jp = Array(4) { r -> DoubleArray(4) { k -> (0 until 4).sumOf { j[r][it] * p[it][k] } } }
        for (r in 0 until 4) for (k in 0 until 4) p[r][k] = (0 until 4).sumOf { jp[r][it] * j[k][it] }
    }

    public val point: GeoPoint?
        get() = origin?.let { o -> o.offset(hypot(x[0], x[1]), Math.toDegrees(atan2(x[0], x[1]))) }

    /** Горизонтальная погрешность (1σ, √(σ_e² + σ_n²)), метры. */
    public val sigmaM: Double get() = sqrt(max(0.0, p[0][0] + p[1][1]))

    public val speedMps: Double get() = hypot(x[2], x[3])

    public val speedSigmaMps: Double get() = sqrt(max(0.0, p[2][2] + p[3][3]))

    /** Курс скорости, градусы от севера, 0…360. */
    public val bearingDeg: Double get() = (Math.toDegrees(atan2(x[2], x[3])) + 360) % 360

    private fun scalarUpdate(h: DoubleArray, z: Double, r: Double) {
        val ph = DoubleArray(4) { i -> (0 until 4).sumOf { p[i][it] * h[it] } }
        val s = (0 until 4).sumOf { h[it] * ph[it] } + r
        val k = DoubleArray(4) { ph[it] / s }
        val innov = z - (0 until 4).sumOf { h[it] * x[it] }
        for (i in 0 until 4) x[i] += k[i] * innov
        for (i in 0 until 4) for (j in 0 until 4) p[i][j] -= k[i] * ph[j]
    }

    private fun local(o: GeoPoint, q: GeoPoint): Pair<Double, Double> {
        val d = o.distanceTo(q)
        val b = Math.toRadians(o.bearingTo(q))
        return d * sin(b) to d * cos(b)
    }

    private companion object {
        const val GATE_CHI2_2DOF = 13.8
        const val MAX_REJECTED = 3
    }
}

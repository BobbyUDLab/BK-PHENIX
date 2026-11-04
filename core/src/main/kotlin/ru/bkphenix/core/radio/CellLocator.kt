package ru.bkphenix.core.radio

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Позиция по сотовым вышкам из офлайн-базы: собственная трилатерация взвешенным МНК (Гаусс — Ньютон)
 * в локальной плоскости.
 *
 * Наблюдения:
 * - **дальность по Timing Advance** обслуживающей соты (LTE шаг 78 м, GSM 554 м) — самое точное;
 * - **дальность по мощности сигнала** (log-distance, n = 3, разброс 8 дБ ⇒ σ ≈ 0.6·d);
 * - **близость к вышке**, если мощности нет, с σ = половина радиуса соты из базы;
 * - слабый априор: центр масс найденных вышек с σ = средний радиус сот (не даёт решению уйти
 *   в зеркальную точку и делает задачу определённой при одной дальности).
 *
 * Координаты вышек в краудсорсинговых базах — центры наблюдений, а не антенны, поэтому к σ каждой
 * дальности добавляется погрешность положения вышки (четверть радиуса соты, 50–1000 м).
 * Итоговая σ — корень наибольшего собственного числа ковариации, умноженный на √(χ²/ст.св.), если
 * невязки больше ожидаемых.
 */
public class CellLocator(
    private val database: CellTowerDatabase,
    private val minAccuracyM: Double = 50.0,
) {
    public fun locate(measurements: List<CellMeasurement>, timestampMillis: Long): PositionObservation? {
        val found = measurements.mapNotNull { m -> database.find(m)?.let { m to it } }
            .distinctBy { (_, t) -> Triple(t.technology, t.areaCode, t.cellId) }
        if (found.isEmpty()) return null
        val solution = solve(found) ?: return null
        return PositionObservation(
            source = PositionSource.CELL,
            point = solution.first,
            accuracyM = max(minAccuracyM, solution.second),
            timestampMillis = timestampMillis,
        )
    }

    private class Obs(val e: Double, val n: Double, val range: Double?, val sigma: Double)

    /** Решение и его σ, метры. */
    internal fun solve(found: List<Pair<CellMeasurement, CellTowerRecord>>): Pair<GeoPoint, Double>? {
        val lat0 = found.sumOf { it.second.position.latitude } / found.size
        val lon0 = found.sumOf { it.second.position.longitude } / found.size
        val mPerLat = EARTH_M_PER_DEG
        val mPerLon = EARTH_M_PER_DEG * cos(lat0 * PI / 180)
        fun east(p: GeoPoint) = (p.longitude - lon0) * mPerLon
        fun north(p: GeoPoint) = (p.latitude - lat0) * mPerLat

        val obs = ArrayList<Obs>()
        for ((m, t) in found) {
            val e = east(t.position); val n = north(t.position)
            val coverage = t.rangeM.coerceIn(MIN_RANGE_M, MAX_RANGE_M)
            val towerSigma = (coverage / 4).coerceIn(50.0, 1_000.0)
            val ta = if (m.isServing) m.timingAdvance?.let { taRange(m.technology, it) } else null
            val rssi = m.rsrpDbm
            when {
                ta != null -> obs += Obs(e, n, ta.first, hypot(ta.second, towerSigma))
                rssi != null -> {
                    val d = pathLossDistanceM(rssi).coerceIn(MIN_PATH_M, 1.5 * coverage)
                    obs += Obs(e, n, d, hypot(PATH_LOSS_SIGMA_FRACTION * d, towerSigma))
                }
                else -> obs += Obs(e, n, null, coverage / 2)
            }
        }
        val meanCoverage = found.sumOf { it.second.rangeM.coerceIn(MIN_RANGE_M, MAX_RANGE_M) } / found.size
        obs += Obs(0.0, 0.0, null, meanCoverage)

        // Начальное приближение: центр, взвешенный по 1/σ² (тяготеет к ближним и точным).
        var x = obs.sumOf { it.e / (it.sigma * it.sigma) } / obs.sumOf { 1 / (it.sigma * it.sigma) }
        var y = obs.sumOf { it.n / (it.sigma * it.sigma) } / obs.sumOf { 1 / (it.sigma * it.sigma) }
        var cov = DoubleArray(3)
        var chi2 = 0.0
        repeat(MAX_ITERATIONS) {
            var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var b1 = 0.0; var b2 = 0.0
            chi2 = 0.0
            for (o in obs) {
                val w = 1 / (o.sigma * o.sigma)
                val dx = x - o.e; val dy = y - o.n
                if (o.range == null) {
                    // Близость: две независимые компоненты положения.
                    a11 += w; a22 += w; b1 -= w * dx; b2 -= w * dy
                    chi2 += w * (dx * dx + dy * dy)
                } else {
                    val d = max(hypot(dx, dy), 1.0)
                    val hx = dx / d; val hy = dy / d
                    val r = o.range - d
                    a11 += w * hx * hx; a12 += w * hx * hy; a22 += w * hy * hy
                    b1 += w * hx * r; b2 += w * hy * r
                    chi2 += w * r * r
                }
            }
            val det = a11 * a22 - a12 * a12
            if (det <= 0) return null
            cov = doubleArrayOf(a22 / det, -a12 / det, a11 / det)
            val sx = (a22 * b1 - a12 * b2) / det
            val sy = (a11 * b2 - a12 * b1) / det
            x += sx; y += sy
            if (hypot(sx, sy) < 0.5) return@repeat
        }
        val dof = obs.sumOf { if (it.range == null) 2 else 1 as Int } - 2
        val scale = if (dof > 0) max(1.0, chi2 / dof) else 1.0
        val tr = cov[0] + cov[2]
        val maxEig = tr / 2 + sqrt(((cov[0] - cov[2]) / 2).pow(2) + cov[1] * cov[1])
        val sigma = sqrt(maxEig * scale)
        return GeoPoint(lat0 + y / mPerLat, lon0 + x / mPerLon) to sigma
    }

    internal companion object {
        const val EARTH_M_PER_DEG = 111_320.0
        const val MAX_ITERATIONS = 10
        const val MIN_RANGE_M = 200.0
        const val MAX_RANGE_M = 30_000.0
        const val MIN_PATH_M = 30.0
        const val PATH_LOSS_SIGMA_FRACTION = 0.6

        /** Опорная мощность −70 дБм на 100 м, показатель затухания 3 (город). */
        const val REF_DBM = -70.0
        const val REF_DISTANCE_M = 100.0
        const val PATH_LOSS_EXPONENT = 3.0

        fun pathLossDistanceM(dbm: Int): Double = REF_DISTANCE_M * 10.0.pow((REF_DBM - dbm) / (10 * PATH_LOSS_EXPONENT))

        /** Дальность по TA (середина шага) и её σ: квантование и многолучёвость. */
        fun taRange(technology: CellTechnology, ta: Int): Pair<Double, Double>? {
            val step = when (technology) {
                CellTechnology.LTE -> 78.12
                CellTechnology.GSM -> 553.85
                else -> return null
            }
            return (ta + 0.5) * step to hypot(step / sqrt(12.0), TA_MULTIPATH_M)
        }

        const val TA_MULTIPATH_M = 60.0
    }
}

/** Пустая база: используется, пока офлайн-база вышек не загружена. */
public object EmptyCellTowerDatabase : CellTowerDatabase {
    override fun find(measurement: CellMeasurement): CellTowerRecord? = null
}

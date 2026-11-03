package ru.bkphenix.core.gnss

import ru.bkphenix.core.geo.EcefCoordinate
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.math.Matrix
import ru.bkphenix.core.model.GnssBand
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Измерение, подготовленное к решению: положение спутника и исправленная за его часы дальность. */
public data class RangeInput(
    val sat: SatId,
    /** Положение спутника в ECEF на момент излучения (без поправки за вращение Земли). */
    val satellitePosition: EcefCoordinate,
    /** ρ + c·Δt_sv, метры. */
    val correctedRangeM: Double,
    val sigmaM: Double,
    val cn0DbHz: Double,
)

/** Решение взвешенным МНК. */
public data class WlsSolution(
    val position: EcefCoordinate,
    val point: GeoPoint,
    /** Смещение часов приёмника, метры. */
    val clockBiasM: Double,
    val used: List<SatId>,
    /** Невязки после решения, метры (наблюдённое − вычисленное). */
    val residualsM: Map<SatId, Double>,
    val sigmasM: Map<SatId, Double>,
    /** Матрица наблюдений в ENU (n×4) и ковариация (HᵀWH)⁻¹ в ENU + часы. */
    val geometryEnu: Matrix,
    val covarianceEnu: Matrix,
    val elevationsDeg: Map<SatId, Double>,
    val iterations: Int,
) {
    /** Сумма квадратов нормированных невязок — статистика χ² с n−4 степенями свободы. */
    val chiSquare: Double get() = used.sumOf { val r = residualsM.getValue(it) / sigmasM.getValue(it); r * r }

    val degreesOfFreedom: Int get() = used.size - 4

    /** Горизонтальная погрешность (1σ, СКО по осям E и N), метры. */
    val horizontalSigmaM: Double get() = sqrt(max(0.0, covarianceEnu[0, 0] + covarianceEnu[1, 1]))

    val verticalSigmaM: Double get() = sqrt(max(0.0, covarianceEnu[2, 2]))
}

/** Подготовка измерений и решение позиции по псевдодальностям (только GPS L1 C/A с эфемеридами LNAV). */
public class WlsSolver(
    private val elevationMaskDeg: Double = 10.0,
    private val maxIterations: Int = 10,
) {
    /**
     * Сопоставляет псевдодальности с эфемеридами: по одному измерению L1 на спутник,
     * только здоровые спутники с действующими эфемеридами.
     */
    public fun prepare(ranges: List<Pseudorange>, ephemerides: EphemerisStore): List<RangeInput> =
        ranges.filter { it.measurement.band == GnssBand.L1 || it.measurement.band == GnssBand.UNKNOWN }
            .groupBy { it.measurement.sat }
            .mapNotNull { (sat, list) ->
                val pr = list.maxBy { it.measurement.cn0DbHz }
                if (sat.constellation != ru.bkphenix.core.model.GnssConstellation.GPS) return@mapNotNull null
                val eph = ephemerides[sat.svid]?.takeIf { it.isHealthy } ?: return@mapNotNull null
                var tTx = pr.receiveTow - pr.rangeM / GpsEphemeris.C
                if (!eph.isValidAt(tTx)) return@mapNotNull null
                // t = t_sv − Δt_sv; Δt_sv зависит от t, достаточно двух приближений.
                var state = eph.stateAt(tTx)
                tTx -= state.clockBiasS
                state = eph.stateAt(tTx)
                RangeInput(sat, state.position, pr.rangeM + GpsEphemeris.C * state.clockBiasS, pr.sigmaM, pr.measurement.cn0DbHz)
            }

    /** Решение по Гауссу–Ньютону; `null`, если спутников меньше 4 или геометрия вырождена. */
    public fun solve(inputs: List<RangeInput>, initial: EcefCoordinate? = null): WlsSolution? {
        if (inputs.size < MIN_SATELLITES) return null
        var x = initial ?: EcefCoordinate(0.0, 0.0, 0.0)
        var b = 0.0
        var active = inputs
        for (iteration in 1..maxIterations) {
            val known = x.norm > EARTH_SURFACE_MIN_M
            val rows = active.mapNotNull { inp -> row(inp, x, b, known) }
            if (rows.size < MIN_SATELLITES) return null
            val h = Matrix(rows.size, 4)
            val w = DoubleArray(rows.size)
            val dz = DoubleArray(rows.size)
            rows.forEachIndexed { i, r ->
                for (k in 0 until 3) h[i, k] = -r.los[k]
                h[i, 3] = 1.0
                w[i] = 1.0 / (r.sigma * r.sigma)
                dz[i] = r.residual
            }
            val ht = h.transpose()
            val htw = Matrix(4, rows.size).also { for (r in 0 until 4) for (c in rows.indices) it[r, c] = ht[r, c] * w[c] }
            val n = (htw * h).inverse() ?: return null
            val delta = n * (htw * Matrix.column(dz))
            x = EcefCoordinate(x.x + delta[0, 0], x.y + delta[1, 0], x.z + delta[2, 0])
            b += delta[3, 0]
            val step = sqrt(delta[0, 0] * delta[0, 0] + delta[1, 0] * delta[1, 0] + delta[2, 0] * delta[2, 0])
            if (known && step < CONVERGENCE_M) {
                return finish(rows.map { it.input }, x, b, iteration)
            }
            if (known) active = rows.map { it.input }
        }
        return null
    }

    private class Row(val input: RangeInput, val los: DoubleArray, val residual: Double, val sigma: Double, val elevationDeg: Double?)

    private fun row(inp: RangeInput, x: EcefCoordinate, b: Double, known: Boolean): Row? {
        val s = earthRotationCorrected(inp.satellitePosition, x)
        val d = s - x
        val rho = d.norm
        var elevation: Double? = null
        var tropo = 0.0
        var sigma = inp.sigmaM
        if (known) {
            val el = x.enuTo(s).elevationDeg
            if (el < elevationMaskDeg) return null
            elevation = el
            tropo = Troposphere.delayM(el)
            sigma = inp.sigmaM / sin(Math.toRadians(max(el, 5.0)))
        }
        val los = doubleArrayOf(d.x / rho, d.y / rho, d.z / rho)
        return Row(inp, los, inp.correctedRangeM - (rho + b + tropo), sigma, elevation)
    }

    private fun finish(inputs: List<RangeInput>, x: EcefCoordinate, b: Double, iterations: Int): WlsSolution? {
        val rows = inputs.mapNotNull { row(it, x, b, true) }
        if (rows.size < MIN_SATELLITES) return null
        val g = x.toGeodetic()
        val phi = Math.toRadians(g.latitude)
        val lambda = Math.toRadians(g.longitude)
        val h = Matrix(rows.size, 4)
        rows.forEachIndexed { i, r ->
            val e = ru.bkphenix.core.geo.Enu.rotate(EcefCoordinate(r.los[0], r.los[1], r.los[2]), phi, lambda)
            h[i, 0] = -e.east
            h[i, 1] = -e.north
            h[i, 2] = -e.up
            h[i, 3] = 1.0
        }
        val ht = h.transpose()
        val htw = Matrix(4, rows.size).also { for (r in 0 until 4) for (c in rows.indices) it[r, c] = ht[r, c] / (rows[c].sigma * rows[c].sigma) }
        val cov = (htw * h).inverse() ?: return null
        return WlsSolution(
            position = x,
            point = g,
            clockBiasM = b,
            used = rows.map { it.input.sat },
            residualsM = rows.associate { it.input.sat to it.residual },
            sigmasM = rows.associate { it.input.sat to it.sigma },
            geometryEnu = h,
            covarianceEnu = cov,
            elevationsDeg = rows.associate { it.input.sat to (it.elevationDeg ?: 0.0) },
            iterations = iterations,
        )
    }

    public companion object {
        public const val MIN_SATELLITES: Int = 4
        private const val CONVERGENCE_M = 1e-3
        private const val EARTH_SURFACE_MIN_M = 6.0e6

        /** Поправка Саньяка: поворот положения спутника на угол вращения Земли за время распространения. */
        public fun earthRotationCorrected(sat: EcefCoordinate, receiver: EcefCoordinate): EcefCoordinate {
            val theta = GpsEphemeris.OMEGA_E * (sat - receiver).norm / GpsEphemeris.C
            return EcefCoordinate(
                sat.x * cos(theta) + sat.y * sin(theta),
                -sat.x * sin(theta) + sat.y * cos(theta),
                sat.z,
            )
        }
    }
}

/** Упрощённая модель тропосферной задержки (стандартная атмосфера, уровень моря). */
public object Troposphere {
    public fun delayM(elevationDeg: Double): Double =
        if (elevationDeg <= 0.0) 0.0 else 2.47 / (sin(Math.toRadians(elevationDeg)) + 0.0121)
}

package ru.bkphenix.core.gnss

import ru.bkphenix.core.geo.EcefCoordinate
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.math.Matrix
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation
import kotlin.math.atan2
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
    /** Несущая частота для масштабирования ионосферной поправки, Гц. */
    val frequencyHz: Double = Klobuchar.L1_HZ,
    /** Время приёма GPST, секунды недели — для модели Klobuchar. */
    val gpsTow: Double = 0.0,
    /** Коэффициенты Klobuchar из навигационного файла; `null` — без ионосферной поправки. */
    val ionosphere: KlobucharCoefficients? = null,
)

/** Решение взвешенным МНК. */
public data class WlsSolution(
    val position: EcefCoordinate,
    val point: GeoPoint,
    /** Смещение часов приёмника в шкале опорной системы (GPS, если есть), метры. */
    val clockBiasM: Double,
    /** Межсистемные смещения часов относительно опорной системы, метры. */
    val interSystemBiasesM: Map<GnssConstellation, Double> = emptyMap(),
    val used: List<SatId>,
    /** Невязки после решения, метры (наблюдённое − вычисленное). */
    val residualsM: Map<SatId, Double>,
    val sigmasM: Map<SatId, Double>,
    /** Матрица наблюдений в ENU (n×(4+k)) и ковариация (HᵀWH)⁻¹ в ENU + часы + k межсистемных смещений. */
    val geometryEnu: Matrix,
    val covarianceEnu: Matrix,
    val elevationsDeg: Map<SatId, Double>,
    val iterations: Int,
) {
    /** Сумма квадратов нормированных невязок — статистика χ² с n−(4+k) степенями свободы. */
    val chiSquare: Double get() = used.sumOf { val r = residualsM.getValue(it) / sigmasM.getValue(it); r * r }

    val degreesOfFreedom: Int get() = used.size - geometryEnu.cols

    /** Горизонтальная погрешность (1σ, СКО по осям E и N), метры. */
    val horizontalSigmaM: Double get() = sqrt(max(0.0, covarianceEnu[0, 0] + covarianceEnu[1, 1]))

    val verticalSigmaM: Double get() = sqrt(max(0.0, covarianceEnu[2, 2]))
}

/**
 * Подготовка измерений и решение позиции по псевдодальностям GPS, ГЛОНАСС, Galileo, BeiDou и QZSS.
 * Часы приёмника — одно неизвестное в шкале опорной системы и по одному межсистемному смещению
 * на каждую другую систему (QZSS считается вместе с GPS: общая шкала времени и сигнал L1 C/A).
 */
public class WlsSolver(
    private val elevationMaskDeg: Double = 10.0,
    private val maxIterations: Int = 10,
    /**
     * Маска C/N0: ниже ~15 дБ·Гц код не отслеживается надёжно (Kirin отдаёт 9 дБ·Гц с ошибками в десятки км,
     * журнал 2026-11-04).
     */
    private val minCn0DbHz: Double = 15.0,
) {
    /**
     * Сопоставляет псевдодальности с эфемеридами: по одному измерению L1 на спутник,
     * только здоровые спутники с действующими эфемеридами.
     */
    public fun prepare(ranges: List<Pseudorange>, ephemerides: EphemerisStore): List<RangeInput> =
        ranges.filter { it.measurement.band in SINGLE_FREQUENCY_BANDS && it.measurement.cn0DbHz >= minCn0DbHz }
            .groupBy { it.measurement.sat }
            .mapNotNull { (sat, list) ->
                // BeiDou: B1I (TGD1 из D1/D2), иначе лучший по C/N0.
                val pr = list.filter { it.measurement.band == GnssBand.B1I }.maxByOrNull { it.measurement.cn0DbHz }
                    ?: list.maxBy { it.measurement.cn0DbHz }
                var tTx = pr.receiveTow - pr.rangeM / GpsEphemeris.C
                val eph = ephemerides.select(sat, pr.gpsWeek, tTx)?.takeIf { it.isHealthy } ?: return@mapNotNull null
                // t = t_sv − Δt_sv; Δt_sv зависит от t, достаточно двух приближений.
                var state = eph.stateAt(tTx)
                tTx -= state.clockBiasS
                state = eph.stateAt(tTx)
                val frequency = when {
                    eph is GlonassEphemeris -> eph.g1FrequencyHz
                    pr.measurement.band == GnssBand.B1I -> GnssBand.B1I.centerFrequencyMhz * 1e6
                    else -> Klobuchar.L1_HZ
                }
                RangeInput(
                    sat, state.position, pr.rangeM + GpsEphemeris.C * state.clockBiasS, pr.sigmaM, pr.measurement.cn0DbHz,
                    frequency, pr.receiveTow, ephemerides.ionosphere,
                )
            }

    /** Решение по Гауссу–Ньютону; `null`, если измерений меньше числа неизвестных или геометрия вырождена. */
    public fun solve(inputs: List<RangeInput>, initial: EcefCoordinate? = null): WlsSolution? {
        if (inputs.size < MIN_SATELLITES) return null
        var x = initial ?: EcefCoordinate(0.0, 0.0, 0.0)
        val clocks = HashMap<GnssConstellation, Double>()
        var active = inputs
        for (iteration in 1..maxIterations) {
            val known = x.norm > EARTH_SURFACE_MIN_M
            val systems = clockSystems(active)
            val rows = active.mapNotNull { inp -> row(inp, x, clocks[clockSystem(inp.sat)] ?: 0.0, known) }
            val unknowns = 3 + systems.size
            if (rows.size < unknowns) return null
            val h = Matrix(rows.size, unknowns)
            val w = DoubleArray(rows.size)
            val dz = DoubleArray(rows.size)
            rows.forEachIndexed { i, r ->
                for (k in 0 until 3) h[i, k] = -r.los[k]
                h[i, 3 + systems.indexOf(clockSystem(r.input.sat))] = 1.0
                w[i] = 1.0 / (r.sigma * r.sigma)
                dz[i] = r.residual
            }
            val ht = h.transpose()
            val htw = Matrix(unknowns, rows.size).also { for (r in 0 until unknowns) for (c in rows.indices) it[r, c] = ht[r, c] * w[c] }
            val n = (htw * h).inverse() ?: return null
            val delta = n * (htw * Matrix.column(dz))
            x = EcefCoordinate(x.x + delta[0, 0], x.y + delta[1, 0], x.z + delta[2, 0])
            systems.forEachIndexed { k, sys -> clocks[sys] = (clocks[sys] ?: 0.0) + delta[3 + k, 0] }
            val step = sqrt(delta[0, 0] * delta[0, 0] + delta[1, 0] * delta[1, 0] + delta[2, 0] * delta[2, 0])
            val next = rows.map { it.input }
            // Маска по углу места может убрать систему целиком — тогда пересчитываем с новым набором.
            if (known && step < CONVERGENCE_M && clockSystems(next) == systems) {
                return finish(next, x, systems.associateWith { clocks.getValue(it) }, iteration)
            }
            if (known) active = next
        }
        return null
    }

    private class Row(val input: RangeInput, val los: DoubleArray, val residual: Double, val sigma: Double, val elevationDeg: Double?)

    private fun row(inp: RangeInput, x: EcefCoordinate, b: Double, known: Boolean): Row? {
        val s = earthRotationCorrected(inp.satellitePosition, x)
        val d = s - x
        val rho = d.norm
        var elevation: Double? = null
        var delay = 0.0
        var sigma = inp.sigmaM
        if (known) {
            val enu = x.enuTo(s)
            val el = enu.elevationDeg
            if (el < elevationMaskDeg) return null
            elevation = el
            delay = Troposphere.delayM(el)
            inp.ionosphere?.let { k ->
                val g = x.toGeodetic()
                val az = Math.toDegrees(atan2(enu.east, enu.north))
                delay += Klobuchar.delayM(k, g.latitude, g.longitude, el, az, inp.gpsTow, inp.frequencyHz)
            }
            sigma = inp.sigmaM / sin(Math.toRadians(max(el, 5.0)))
        }
        val los = doubleArrayOf(d.x / rho, d.y / rho, d.z / rho)
        return Row(inp, los, inp.correctedRangeM - (rho + b + delay), sigma, elevation)
    }

    private fun finish(inputs: List<RangeInput>, x: EcefCoordinate, clocks: Map<GnssConstellation, Double>, iterations: Int): WlsSolution? {
        val systems = clocks.keys.toList()
        val rows = inputs.mapNotNull { row(it, x, clocks.getValue(clockSystem(it.sat)), true) }
        val unknowns = 3 + systems.size
        if (rows.size < unknowns) return null
        val g = x.toGeodetic()
        val phi = Math.toRadians(g.latitude)
        val lambda = Math.toRadians(g.longitude)
        val h = Matrix(rows.size, unknowns)
        rows.forEachIndexed { i, r ->
            val e = ru.bkphenix.core.geo.Enu.rotate(EcefCoordinate(r.los[0], r.los[1], r.los[2]), phi, lambda)
            h[i, 0] = -e.east
            h[i, 1] = -e.north
            h[i, 2] = -e.up
            h[i, 3 + systems.indexOf(clockSystem(r.input.sat))] = 1.0
        }
        val ht = h.transpose()
        val htw = Matrix(unknowns, rows.size).also { for (r in 0 until unknowns) for (c in rows.indices) it[r, c] = ht[r, c] / (rows[c].sigma * rows[c].sigma) }
        val cov = (htw * h).inverse() ?: return null
        val reference = clocks.getValue(systems.first())
        return WlsSolution(
            position = x,
            point = g,
            clockBiasM = reference,
            interSystemBiasesM = systems.drop(1).associateWith { clocks.getValue(it) - reference },
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

        private val SINGLE_FREQUENCY_BANDS = setOf(GnssBand.L1, GnssBand.G1, GnssBand.B1I, GnssBand.UNKNOWN)

        /** Система, к шкале которой относятся часы спутника: QZSS — общая с GPS. */
        public fun clockSystem(sat: SatId): GnssConstellation =
            if (sat.constellation == GnssConstellation.QZSS) GnssConstellation.GPS else sat.constellation

        /** Системы часов в порядке: GPS (опорная, если есть), ГЛОНАСС, Galileo, BeiDou, прочие. */
        public fun clockSystems(inputs: List<RangeInput>): List<GnssConstellation> =
            inputs.map { clockSystem(it.sat) }.distinct().sortedBy { it.ordinal }
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

/**
 * Одночастотная модель ионосферы Klobuchar (IS-GPS-200, 20.3.3.5.2.5). Задержка считается для L1
 * и масштабируется на частоту сигнала как (f_L1 / f)².
 */
public object Klobuchar {
    public const val L1_HZ: Double = 1_575.42e6

    public fun delayM(
        k: KlobucharCoefficients,
        latitudeDeg: Double,
        longitudeDeg: Double,
        elevationDeg: Double,
        azimuthDeg: Double,
        gpsTow: Double,
        frequencyHz: Double = L1_HZ,
    ): Double {
        // Углы в полуокружностях.
        val e = elevationDeg / 180.0
        val a = Math.toRadians(azimuthDeg)
        val psi = 0.0137 / (e + 0.11) - 0.022
        val phiI = (latitudeDeg / 180.0 + psi * cos(a)).coerceIn(-0.416, 0.416)
        val lambdaI = longitudeDeg / 180.0 + psi * sin(a) / cos(phiI * Math.PI)
        val phiM = phiI + 0.064 * cos((lambdaI - 1.617) * Math.PI)
        val t = (43_200.0 * lambdaI + gpsTow).mod(86_400.0)
        val f = 1.0 + 16.0 * (0.53 - e).let { it * it * it }
        val amp = max(0.0, k.alpha.indices.sumOf { k.alpha[it] * Math.pow(phiM, it.toDouble()) })
        val per = max(72_000.0, k.beta.indices.sumOf { k.beta[it] * Math.pow(phiM, it.toDouble()) })
        val x = 2 * Math.PI * (t - 50_400.0) / per
        val delayS = if (kotlin.math.abs(x) < 1.57) f * (5e-9 + amp * (1 - x * x / 2 + x * x * x * x / 24)) else f * 5e-9
        val scale = (L1_HZ / frequencyHz).let { it * it }
        return delayS * GpsEphemeris.C * scale
    }
}

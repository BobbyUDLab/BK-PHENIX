package ru.bkphenix.core.gnss

import ru.bkphenix.core.geo.EcefCoordinate
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Эфемериды и поправки часов одного спутника GPS из подкадров 1–3 LNAV (IS-GPS-200, разд. 20.3.3).
 * Углы — в радианах, время — в секундах GPS-недели.
 */
public data class GpsEphemeris(
    val svid: Int,
    /** Неделя GPS по модулю 1024, как в подкадре 1. */
    val weekMod1024: Int,
    val health: Int,
    val uraIndex: Int,
    val iodc: Int,
    val iode: Int,
    val tgd: Double,
    val toc: Double,
    val af0: Double,
    val af1: Double,
    val af2: Double,
    val toe: Double,
    val sqrtA: Double,
    val eccentricity: Double,
    val i0: Double,
    val omega0: Double,
    val omega: Double,
    val m0: Double,
    val deltaN: Double,
    val omegaDot: Double,
    val iDot: Double,
    val cuc: Double,
    val cus: Double,
    val crc: Double,
    val crs: Double,
    val cic: Double,
    val cis: Double,
) : NavEphemeris {
    override val sat: SatId get() = SatId(ru.bkphenix.core.model.GnssConstellation.GPS, svid)
    override val isHealthy: Boolean get() = health == 0

    /** Неделя по модулю 1024: [ageSeconds] учитывает перенос. */
    override val referenceWeek: Int get() = weekMod1024
    override val referenceTow: Double get() = toe
    override val fitHalfWidthS: Double get() = FIT_INTERVAL_S

    /** Положение спутника (ECEF на момент [tow]) и поправка часов. */
    override fun stateAt(gpsTow: Double): SatelliteState {
        val tow = gpsTow
        val a = sqrtA * sqrtA
        val tk = wrapWeek(tow - toe)
        val n = sqrt(MU / (a * a * a)) + deltaN
        val m = m0 + n * tk
        val e = eccentricAnomaly(m)
        val nu = atan2(sqrt(1 - eccentricity * eccentricity) * sin(e), cos(e) - eccentricity)
        val phi = nu + omega
        val s2 = sin(2 * phi)
        val c2 = cos(2 * phi)
        val u = phi + cus * s2 + cuc * c2
        val r = a * (1 - eccentricity * cos(e)) + crs * s2 + crc * c2
        val inc = i0 + cis * s2 + cic * c2 + iDot * tk
        val xp = r * cos(u)
        val yp = r * sin(u)
        val node = omega0 + (omegaDot - OMEGA_E) * tk - OMEGA_E * toe
        val position = EcefCoordinate(
            x = xp * cos(node) - yp * cos(inc) * sin(node),
            y = xp * sin(node) + yp * cos(inc) * cos(node),
            z = yp * sin(inc),
        )
        val dt = wrapWeek(tow - toc)
        val relativistic = F_REL * eccentricity * sqrtA * sin(e)
        val clock = af0 + af1 * dt + af2 * dt * dt + relativistic - tgd
        return SatelliteState(position, clock)
    }

    /** Эфемериды пригодны в окне ±[FIT_INTERVAL_S] от toe. */
    /** Возраст эфемерид в момент (неделя GPS, секунды недели) с учётом номера недели, секунды. */
    override fun ageSeconds(gpsWeek: Int, gpsTow: Double): Double {
        val tow = gpsTow
        var weeks = (gpsWeek - weekMod1024).mod(WEEK_ROLLOVER)
        if (weeks > WEEK_ROLLOVER / 2) weeks -= WEEK_ROLLOVER
        return weeks * 2 * HALF_WEEK + tow - toe
    }

    public fun isValidAt(tow: Double): Boolean = abs(wrapWeek(tow - toe)) <= FIT_INTERVAL_S

    private fun eccentricAnomaly(m: Double): Double {
        var e = m
        repeat(KEPLER_ITERATIONS) {
            val next = e - (e - eccentricity * sin(e) - m) / (1 - eccentricity * cos(e))
            if (abs(next - e) < 1e-13) return next
            e = next
        }
        return e
    }

    public companion object {
        /** Гравитационный параметр Земли WGS-84 для GPS, м³/с². */
        public const val MU: Double = 3.986005e14

        /** Угловая скорость вращения Земли, рад/с. */
        public const val OMEGA_E: Double = 7.2921151467e-5

        /** Скорость света, м/с. */
        public const val C: Double = 299_792_458.0

        private const val F_REL = -4.442807633e-10
        private const val HALF_WEEK = 302_400.0
        private const val WEEK_ROLLOVER = 1024
        private const val KEPLER_ITERATIONS = 20

        /** Стандартный интервал аппроксимации 4 ч → ±2 ч. */
        public const val FIT_INTERVAL_S: Double = 7_200.0

        /** Приведение разности времён к ±полнедели (переход через границу недели). */
        public fun wrapWeek(dt: Double): Double = when {
            dt > HALF_WEEK -> dt - 2 * HALF_WEEK
            dt < -HALF_WEEK -> dt + 2 * HALF_WEEK
            else -> dt
        }
    }
}

/** Положение спутника и смещение его часов относительно времени GPS, секунды. */
public data class SatelliteState(val position: EcefCoordinate, val clockBiasS: Double)

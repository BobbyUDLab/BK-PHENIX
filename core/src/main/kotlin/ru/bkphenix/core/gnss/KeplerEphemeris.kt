package ru.bkphenix.core.gnss

import ru.bkphenix.core.geo.EcefCoordinate
import ru.bkphenix.core.model.GnssConstellation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Кеплеровы эфемериды Galileo (OS SIS ICD, 5.1.1), BeiDou D1/D2 (ICD B1I, 5.2.4) и QZSS LNAV.
 *
 * [toe] и [toc] — в шкале системы (GST совпадает с GPST, BDT = GPST − 14 с), [weekGps] — неделя
 * GPS, к которой относится toe. Для геостационарных BeiDou (C01–C05, C59–C63) положение
 * считается в инерциальной системе с последующим поворотом на −5° и на угол вращения Земли.
 */
public data class KeplerEphemeris(
    override val sat: SatId,
    val weekGps: Int,
    val health: Int,
    val toc: Double,
    val af0: Double,
    val af1: Double,
    val af2: Double,
    /** Групповая задержка для одночастотного измерения (BGD E1/E5a, TGD1 B1I, TGD L1), секунды. */
    val groupDelayS: Double,
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
    /** Источник данных (поле RINEX «data sources» для Galileo), для выбора I/NAV. */
    val dataSource: Int = 0,
) : NavEphemeris {
    private val constants: Constants = Constants.of(sat.constellation)

    /** Сдвиг шкалы системы относительно GPST, секунды. */
    private val systemMinusGps: Double =
        if (sat.constellation == GnssConstellation.BEIDOU) GpsTime.BDT_MINUS_GPST_S else 0.0

    override val isHealthy: Boolean get() = health == 0
    override val referenceWeek: Int get() = weekGps
    override val referenceTow: Double get() = (toe - systemMinusGps).mod(GpsTime.SECONDS_PER_WEEK)

    override val fitHalfWidthS: Double
        get() = when (sat.constellation) {
            GnssConstellation.GALILEO -> 4 * 3600.0
            GnssConstellation.BEIDOU -> 2 * 3600.0
            else -> GpsEphemeris.FIT_INTERVAL_S
        }

    /** Геостационарный спутник BeiDou. */
    public val isBeidouGeo: Boolean
        get() = sat.constellation == GnssConstellation.BEIDOU && (sat.svid <= 5 || sat.svid in 59..63)

    override fun stateAt(gpsTow: Double): SatelliteState {
        val t = gpsTow + systemMinusGps
        val a = sqrtA * sqrtA
        val tk = GpsTime.wrapWeek(t - toe)
        val n = sqrt(constants.mu / (a * a * a)) + deltaN
        val e = eccentricAnomaly(m0 + n * tk)
        val nu = atan2(sqrt(1 - eccentricity * eccentricity) * sin(e), cos(e) - eccentricity)
        val phi = nu + omega
        val s2 = sin(2 * phi)
        val c2 = cos(2 * phi)
        val u = phi + cus * s2 + cuc * c2
        val r = a * (1 - eccentricity * cos(e)) + crs * s2 + crc * c2
        val inc = i0 + cis * s2 + cic * c2 + iDot * tk
        val xp = r * cos(u)
        val yp = r * sin(u)
        val we = constants.omegaE
        val position = if (isBeidouGeo) {
            val node = omega0 + omegaDot * tk - we * toe
            val xg = xp * cos(node) - yp * cos(inc) * sin(node)
            val yg = xp * sin(node) + yp * cos(inc) * cos(node)
            val zg = yp * sin(inc)
            val f = Math.toRadians(-5.0)
            val z = we * tk
            // R_Z(ωₑ·tk) · R_X(−5°)
            val y1 = yg * cos(f) + zg * sin(f)
            val z1 = -yg * sin(f) + zg * cos(f)
            EcefCoordinate(xg * cos(z) + y1 * sin(z), -xg * sin(z) + y1 * cos(z), z1)
        } else {
            val node = omega0 + (omegaDot - we) * tk - we * toe
            EcefCoordinate(
                x = xp * cos(node) - yp * cos(inc) * sin(node),
                y = xp * sin(node) + yp * cos(inc) * cos(node),
                z = yp * sin(inc),
            )
        }
        val dt = GpsTime.wrapWeek(t - toc)
        val relativistic = constants.fRel * eccentricity * sqrtA * sin(e)
        return SatelliteState(position, af0 + af1 * dt + af2 * dt * dt + relativistic - groupDelayS)
    }

    private fun eccentricAnomaly(m: Double): Double {
        var e = m
        repeat(KEPLER_ITERATIONS) {
            val next = e - (e - eccentricity * sin(e) - m) / (1 - eccentricity * cos(e))
            if (abs(next - e) < 1e-13) return next
            e = next
        }
        return e
    }

    private class Constants(val mu: Double, val omegaE: Double, val fRel: Double) {
        companion object {
            private val GPS = Constants(GpsEphemeris.MU, GpsEphemeris.OMEGA_E, -4.442807633e-10)
            private val GALILEO = Constants(3.986004418e14, 7.2921151467e-5, -4.442807309e-10)
            private val BEIDOU = Constants(3.986004418e14, 7.2921150e-5, -4.442807309e-10)

            fun of(c: GnssConstellation): Constants = when (c) {
                GnssConstellation.GALILEO -> GALILEO
                GnssConstellation.BEIDOU -> BEIDOU
                else -> GPS
            }
        }
    }

    private companion object {
        const val KEPLER_ITERATIONS = 20
    }
}

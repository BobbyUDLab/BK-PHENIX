package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.EcefCoordinate
import ru.bkphenix.core.model.GnssConstellation
import kotlin.math.abs

class KeplerEphemerisTest {
    private val gps = SyntheticConstellation.all(259_200.0).first().copy(eccentricity = 0.01, omega = 0.7, crs = 30.0, cuc = 1e-6)

    private fun kepler(sat: SatId, e: GpsEphemeris, week: Int = 2443) = KeplerEphemeris(
        sat = sat, weekGps = week, health = e.health, toc = e.toc, af0 = e.af0, af1 = e.af1, af2 = e.af2,
        groupDelayS = e.tgd, toe = e.toe, sqrtA = e.sqrtA, eccentricity = e.eccentricity, i0 = e.i0,
        omega0 = e.omega0, omega = e.omega, m0 = e.m0, deltaN = e.deltaN, omegaDot = e.omegaDot, iDot = e.iDot,
        cuc = e.cuc, cus = e.cus, crc = e.crc, crs = e.crs, cic = e.cic, cis = e.cis,
    )

    @Test
    fun `для QZSS с константами GPS совпадает с эфемеридами GPS`() {
        val k = kepler(SatId(GnssConstellation.QZSS, 1), gps)
        for (dt in listOf(-3600.0, 0.0, 1800.0)) {
            val a = gps.stateAt(gps.toe + dt)
            val b = k.stateAt(gps.toe + dt)
            assertEquals(0.0, a.position.distanceTo(b.position), 1e-6)
            assertEquals(a.clockBiasS, b.clockBiasS, 1e-15)
        }
    }

    @Test
    fun `BeiDou считается в BDT со сдвигом 14 секунд`() {
        val bds = kepler(SatId(GnssConstellation.BEIDOU, 30), gps)
        // Опорный момент в GPST на 14 с позже toe в BDT.
        assertEquals(gps.toe + 14.0, bds.referenceTow)
        val r = bds.stateAt(gps.toe + 14.0).position.norm
        val a = gps.sqrtA * gps.sqrtA
        assertTrue(abs(r - a) < a * gps.eccentricity + 1_000.0)
    }

    /** C01 из BRDC BKG за 02.11.2026 (D2, геостационарный, точка стояния 140° в. д.). */
    private val c01 = KeplerEphemeris(
        sat = SatId(GnssConstellation.BEIDOU, 1), weekGps = 1087 + GpsTime.BDT_WEEK_OFFSET, health = 0,
        toc = 86_400.0, af0 = -7.682247087359e-07, af1 = -2.220446049250e-14, af2 = 0.0, groupDelayS = 4.5e-9,
        toe = 86_400.0, sqrtA = 6.493421545029e+03, eccentricity = 5.523725412786e-04, i0 = 8.056051992045e-02,
        omega0 = -3.074227196851, omega = 4.788539173877e-02, m0 = -7.946005881325e-01, deltaN = 8.850368653379e-09,
        omegaDot = -7.956760002420e-09, iDot = -1.246480492344e-10,
        cuc = -1.114793121815e-06, cus = -1.460639759898e-05, crc = 4.474531250000e+02, crs = -4.165625000000e+01,
        cic = -3.194436430931e-07, cis = -1.429580152035e-07,
    )

    @Test
    fun `геостационарный BeiDou стоит над 140 градусами восточной долготы`() {
        assertTrue(c01.isBeidouGeo)
        for (dt in listOf(-3600.0, 0.0, 3600.0)) {
            val p = c01.stateAt(c01.referenceTow + dt).position
            val g = p.toGeodetic()
            assertEquals(42_164_000.0, p.norm, 50_000.0)
            assertEquals(140.0, g.longitude, 1.0, "долгота при dt=$dt")
            assertTrue(abs(g.latitude) < 5.0, "широта ${g.latitude}")
        }
    }

    @Test
    fun `без особого поворота геостационарный спутник ушёл бы с точки стояния`() {
        val asMeo = c01.copy(sat = SatId(GnssConstellation.BEIDOU, 6))
        val p = asMeo.stateAt(asMeo.referenceTow).position
        // Формула MEO/IGSO для GEO даёт другую точку: проверяем, что ветка GEO действительно нужна.
        assertTrue(p.distanceTo(c01.stateAt(c01.referenceTow).position) > 1_000.0)
        assertEquals(EcefCoordinate(0.0, 0.0, 0.0).distanceTo(p), p.norm, 1e-6)
    }
}

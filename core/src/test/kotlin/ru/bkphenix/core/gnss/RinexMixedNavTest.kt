package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.model.GnssConstellation

class RinexMixedNavTest {
    // Фрагмент BRDM00DLR_S_20263060000_01D_MN.rnx (IGS/BKG), 02.11.2026.
    private val nav = RinexNavParser.parse(javaClass.getResource("/rinex/brdm_dlr_excerpt.rnx")!!.readText())

    @Test
    fun `читаются все системы кроме SBAS`() {
        assertEquals(listOf("G05", "R19", "R19", "E11", "C01", "C30", "J194"), nav.ephemerides.map { it.sat.label })
        assertEquals(18, nav.leapSeconds)
    }

    @Test
    fun `коэффициенты Клобушара из заголовка`() {
        val iono = nav.ionosphere!!
        assertEquals(1.6764e-08, iono.alpha[0], 1e-20)
        assertEquals(3.2768e+05, iono.beta[3], 1e-6)
    }

    @Test
    fun `ГЛОНАСС переводится из UTC в шкалу GPS`() {
        val r = nav.ephemerides.filterIsInstance<GlonassEphemeris>().first()
        assertEquals(2443, r.tbWeek)
        // Понедельник 00:15 UTC + 18 с.
        assertEquals(86_400.0 + 900.0 + 18.0, r.tbTow)
        assertEquals(3, r.frequencyChannel)
        assertEquals(11_115_773.92578, r.position.x, 1e-3)
    }

    @Test
    fun `BeiDou получает неделю GPS и сдвиг BDT`() {
        val c30 = nav.ephemerides.first { it.sat.label == "C30" } as KeplerEphemeris
        assertEquals(1087 + 1356, c30.weekGps)
        assertEquals(c30.toe + 14.0, c30.referenceTow)
        assertTrue(!c30.isBeidouGeo)
        assertTrue((nav.ephemerides.first { it.sat.label == "C01" } as KeplerEphemeris).isBeidouGeo)
    }

    @Test
    fun `орбиты всех систем на правдоподобной высоте`() {
        val expected = mapOf(
            GnssConstellation.GPS to 26_560e3, GnssConstellation.GLONASS to 25_510e3,
            GnssConstellation.GALILEO to 29_600e3, GnssConstellation.QZSS to 42_164e3,
        )
        for (e in nav.ephemerides) {
            val r = e.stateAt(e.referenceTow + 300).position.norm
            val nominal = expected[e.sat.constellation] ?: if ((e as KeplerEphemeris).isBeidouGeo) 42_164e3 else 27_906e3
            // QZS-2 на наклонной орбите с e ≈ 0,075: радиус гуляет ±3 тыс. км.
            val tolerance = if (e.sat.constellation == GnssConstellation.QZSS) 0.1 else 0.05
            assertEquals(nominal, r, tolerance * nominal, e.sat.label)
            assertTrue(e.isValidAt(e.referenceWeek, e.referenceTow + 300), e.sat.label)
        }
    }

    @Test
    fun `Galileo I NAV берёт BGD E5b E1`() {
        val e11 = nav.ephemerides.first { it.sat.label == "E11" } as KeplerEphemeris
        assertEquals(517, e11.dataSource)
        assertEquals(-1.699663698673e-08, e11.groupDelayS, 1e-20)
    }
}

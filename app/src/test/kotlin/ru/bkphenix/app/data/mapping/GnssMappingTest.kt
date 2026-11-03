package ru.bkphenix.app.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation

class GnssMappingTest {
    @Test
    fun `константы созвездий Android отображаются на модель ядра`() {
        assertEquals(GnssConstellation.GPS, GnssMapping.constellation(1))
        assertEquals(GnssConstellation.GLONASS, GnssMapping.constellation(3))
        assertEquals(GnssConstellation.BEIDOU, GnssMapping.constellation(5))
        assertEquals(GnssConstellation.GALILEO, GnssMapping.constellation(6))
        assertEquals(GnssConstellation.IRNSS, GnssMapping.constellation(7))
        assertEquals(GnssConstellation.UNKNOWN, GnssMapping.constellation(42))
    }

    @Test
    fun `частоты L1 и L5 распознаются`() {
        assertEquals(GnssBand.L1, GnssMapping.band(1_575.42e6))
        assertEquals(GnssBand.L5, GnssMapping.band(1_176.45e6))
        assertEquals(GnssBand.E5B, GnssMapping.band(1_207.14e6))
        assertEquals(GnssBand.B1I, GnssMapping.band(1_561.098e6))
    }

    @Test
    fun `FDMA-каналы ГЛОНАСС попадают в G1`() {
        assertEquals(GnssBand.G1, GnssMapping.band(1_602e6 + 7 * 0.5625e6))
        assertEquals(GnssBand.G1, GnssMapping.band(1_602e6 - 7 * 0.5625e6))
    }

    @Test
    fun `неизвестная или отсутствующая частота`() {
        assertEquals(GnssBand.UNKNOWN, GnssMapping.band(null))
        assertEquals(GnssBand.UNKNOWN, GnssMapping.band(0.0))
        assertEquals(GnssBand.UNKNOWN, GnssMapping.band(1_278.75e6)) // E6 пока не моделируется
    }

    @Test
    fun `подпись диапазона зависит от созвездия`() {
        assertEquals("E5a", GnssMapping.bandLabel(GnssBand.L5, GnssConstellation.GALILEO))
        assertEquals("B2a", GnssMapping.bandLabel(GnssBand.L5, GnssConstellation.BEIDOU))
        assertEquals("L5", GnssMapping.bandLabel(GnssBand.L5, GnssConstellation.GPS))
    }
}

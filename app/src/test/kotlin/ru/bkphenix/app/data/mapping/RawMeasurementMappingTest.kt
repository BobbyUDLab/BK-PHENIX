package ru.bkphenix.app.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bkphenix.core.gnss.EphemerisStore
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation

class RawMeasurementMappingTest {
    @Test
    fun `флаги TOW распознаются`() {
        assertTrue(RawMeasurementMapping.isTowKnown(0x8 or 0x1))
        assertTrue(RawMeasurementMapping.isTowKnown(0x4000))
        assertFalse(RawMeasurementMapping.isTowKnown(0x1 or 0x2 or RawMeasurementMapping.STATE_GLO_TOD_DECODED))
    }

    @Test
    fun `измерение переносится в модель ядра`() {
        val m = RawMeasurementMapping.measurement(6, 11, 1_176.45e6, 38.5, 123L, 7L, 0.5, 0x8, -120.0, 3.0)
        assertEquals(GnssConstellation.GALILEO, m.sat.constellation)
        assertEquals("E11", m.sat.label)
        assertEquals(GnssBand.L5, m.band)
        assertTrue(m.towKnown)
        assertEquals(3.0, m.agcDb!!, 0.0)
    }

    @Test
    fun `часы без полного смещения сохраняют null`() {
        val c = RawMeasurementMapping.clock(1L, null, null, null, 2, null)
        assertNull(c.fullBiasNanos)
        assertEquals(0.0, c.biasNanos, 0.0)
        assertEquals(2, c.hardwareClockDiscontinuityCount)
    }

    @Test
    fun `сообщение не GPS LNAV не даёт подкадра, LNAV попадает в хранилище эфемерид без ошибок`() {
        assertNull(NavigationMessageMapping.gpsL1caSubframe(5, 0x0301, ByteArray(40)))
        val sf = NavigationMessageMapping.gpsL1caSubframe(5, NavigationMessageMapping.TYPE_GPS_L1CA, ByteArray(40))
        assertNotNull(sf)
        assertEquals(5, sf!!.svid)
        // Нулевые слова имеют верную чётность, но подкадр 0 — не эфемериды.
        assertFalse(EphemerisStore().add(sf))
    }
}

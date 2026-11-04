package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation

class MultiGnssTimeTest {
    private val week = 2443L
    private val towS = 86_400L + 3_600L // понедельник 01:00 GPST
    private val gpsNanos = (week * 604_800L + towS) * 1_000_000_000L
    private val clock = RawGnssClock(timeNanos = gpsNanos + 5_000L, fullBiasNanos = 5_000L)
    private val travelNanos = 72_000_000L

    private fun m(c: GnssConstellation, svid: Int, received: Long, known: Boolean = true) = RawGnssMeasurement(
        sat = SatId(c, svid), band = GnssBand.L1, cn0DbHz = 40.0, receivedSvTimeNanos = received,
        receivedSvTimeUncertaintyNanos = 10, towKnown = known,
    )

    @Test
    fun `дальности BeiDou и ГЛОНАСС считаются в своих шкалах`() {
        val towNanos = towS * 1_000_000_000L
        val bdt = towNanos - 14_000_000_000L - travelNanos
        val gloTod = (towNanos - 18_000_000_000L + 10_800_000_000_000L) % 86_400_000_000_000L - travelNanos
        val ranges = Pseudoranges.compute(
            RawGnssEpoch(clock, listOf(m(GnssConstellation.GPS, 1, towNanos - travelNanos), m(GnssConstellation.BEIDOU, 30, bdt), m(GnssConstellation.GLONASS, 19, gloTod))),
        )
        assertEquals(3, ranges.size)
        for (r in ranges) {
            assertEquals(travelNanos * 1e-9 * GpsEphemeris.C, r.rangeM, 1e-3, r.measurement.sat.label)
            assertEquals(towS.toDouble(), r.receiveTow, 1e-9)
        }
    }

    @Test
    fun `ГЛОНАСС через полночь UTC плюс 3 часа`() {
        // 21:00:18 GPST = 00:00:00 по времени ГЛОНАСС: излучение было в конце прошлых суток.
        val tow = 86_400L + 21 * 3600L + 18L
        val c = RawGnssClock(timeNanos = (week * 604_800L + tow) * 1_000_000_000L + 5_000L, fullBiasNanos = 5_000L)
        val r = Pseudoranges.compute(RawGnssEpoch(c, listOf(m(GnssConstellation.GLONASS, 3, 86_400_000_000_000L - travelNanos)))).single()
        assertEquals(travelNanos * 1e-9 * GpsEphemeris.C, r.rangeM, 1e-3)
    }

    @Test
    fun `время ГЛОНАСС известно по битам TOD, а не TOW`() {
        assertTrue(MeasurementState.isTimeKnown(GnssConstellation.GLONASS, MeasurementState.GLO_TOD_DECODED))
        assertFalse(MeasurementState.isTimeKnown(GnssConstellation.GLONASS, MeasurementState.TOW_DECODED))
        assertTrue(MeasurementState.isTimeKnown(GnssConstellation.BEIDOU, MeasurementState.TOW_KNOWN))
        assertFalse(MeasurementState.isTimeKnown(GnssConstellation.GALILEO, MeasurementState.GLO_TOD_KNOWN))
    }

    private val nav = RinexNavParser.parse(javaClass.getResource("/rinex/brdm_dlr_excerpt.rnx")!!.readText())
    private val store = EphemerisStore().apply { replaceExternalNav(nav.ephemerides) }

    @Test
    fun `хранилище выбирает ближайшую запись ГЛОНАСС и ищет по литере`() {
        val r19 = nav.ephemerides.filterIsInstance<GlonassEphemeris>()
        // 00:40 UTC ближе к закладке 00:45.
        val t = 86_400.0 + 2_400.0 + 18
        assertSame(r19[1], store.select(SatId(GnssConstellation.GLONASS, 19), 2443, t))
        // Android без номера слота: 100 + литера 3.
        assertSame(r19[1], store.select(SatId(GnssConstellation.GLONASS, 103), 2443, t))
        assertNull(store.select(SatId(GnssConstellation.GLONASS, 104), 2443, t))
        // Вне окна применимости — ничего.
        assertNull(store.select(SatId(GnssConstellation.GLONASS, 19), 2443, t + 7_200))
    }

    @Test
    fun `хранилище отдаёт Galileo BeiDou и QZSS`() {
        for (label in listOf("E11", "C01", "C30", "J194")) {
            val e = nav.ephemerides.first { it.sat.label == label }
            assertSame(e, store.select(e.sat, e.referenceWeek, e.referenceTow + 600), label)
        }
        assertEquals(6, store.availableSats.size)
    }
}

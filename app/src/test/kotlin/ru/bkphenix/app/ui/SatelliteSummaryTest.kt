package ru.bkphenix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bkphenix.app.ui.satellites.SatelliteSummary
import ru.bkphenix.core.gnss.RawGnssClock
import ru.bkphenix.core.gnss.RawGnssEpoch
import ru.bkphenix.core.gnss.RawGnssMeasurement
import ru.bkphenix.core.gnss.SatId
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation
import ru.bkphenix.core.model.SatelliteStatus

class SatelliteSummaryTest {
    private fun sat(c: GnssConstellation, svid: Int, band: GnssBand, cn0: Double, used: Boolean) =
        SatelliteStatus(c, svid, band, cn0, 45.0, 90.0, used)

    @Test
    fun `двухчастотный спутник считается один раз, диапазоны подписаны по созвездию`() {
        val list = listOf(
            sat(GnssConstellation.GALILEO, 11, GnssBand.L1, 40.0, true),
            sat(GnssConstellation.GALILEO, 11, GnssBand.L5, 36.0, true),
            sat(GnssConstellation.GALILEO, 12, GnssBand.L1, 0.0, false),
            sat(GnssConstellation.GPS, 5, GnssBand.L1, 30.0, false),
        )
        val galileo = SatelliteSummary.byConstellation(list).first { it.constellation == GnssConstellation.GALILEO }
        assertEquals(2, galileo.visible)
        assertEquals(1, galileo.used)
        assertEquals(listOf("E1", "E5a"), galileo.bandLabels)
        assertEquals(38.0, galileo.meanCn0DbHz!!, 1e-9)
        assertEquals(true, SatelliteSummary.isDualFrequency(list, null))
        assertEquals(false, SatelliteSummary.isDualFrequency(list.filter { it.band == GnssBand.L1 }, null))
    }

    @Test
    fun `L5 определяется по сырым измерениям, если статуса спутников нет`() {
        assertNull(SatelliteSummary.isDualFrequency(emptyList(), null))
        assertNull(SatelliteSummary.isDualFrequency(emptyList(), setOf(GnssBand.UNKNOWN)))
        assertEquals(true, SatelliteSummary.isDualFrequency(emptyList(), setOf(GnssBand.L1, GnssBand.L5)))
        assertEquals(false, SatelliteSummary.isDualFrequency(emptyList(), setOf(GnssBand.L1)))
    }

    @Test
    fun `список спутников строится по сырым измерениям без дублей`() {
        fun m(c: GnssConstellation, svid: Int, band: GnssBand) =
            RawGnssMeasurement(SatId(c, svid), band, 35.0, 0, 10, 0.0, true, 0.0, null)
        val epoch = RawGnssEpoch(
            RawGnssClock(timeNanos = 0, fullBiasNanos = 0),
            listOf(
                m(GnssConstellation.GPS, 3, GnssBand.L1),
                m(GnssConstellation.GPS, 3, GnssBand.L1),
                m(GnssConstellation.GPS, 3, GnssBand.L5),
                m(GnssConstellation.GLONASS, 7, GnssBand.G1),
            ),
        )
        val sats = SatelliteSummary.fromMeasurements(epoch)
        assertEquals(3, sats.size)
        assertTrue(sats.all { it.elevationDeg.isNaN() && !it.usedInFix })
        val gps = SatelliteSummary.byConstellation(sats).first { it.constellation == GnssConstellation.GPS }
        assertEquals(1, gps.visible)
    }
}

package ru.bkphenix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bkphenix.app.ui.satellites.SatelliteSummary
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
        assertTrue(SatelliteSummary.isDualFrequency(list))
        assertFalse(SatelliteSummary.isDualFrequency(list.filter { it.band == GnssBand.L1 }))
    }
}

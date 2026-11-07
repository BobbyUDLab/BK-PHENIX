package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation

/** Детектор «GPS против других систем» на синтетических эпохах по образцу полевых записей 05–07.11.2026. */
class ConstellationAsymmetryTest {
    private fun m(c: GnssConstellation, svid: Int, cn0: Double, tow: Boolean = true) = RawGnssMeasurement(
        sat = SatId(c, svid),
        band = when (c) { GnssConstellation.GLONASS -> GnssBand.G1; GnssConstellation.BEIDOU -> GnssBand.B1I; else -> GnssBand.L1 },
        cn0DbHz = cn0, receivedSvTimeNanos = 0, receivedSvTimeUncertaintyNanos = 10, towKnown = tow,
    )

    private fun input(ms: List<RawGnssMeasurement>) =
        SpoofingInput(RawGnssEpoch(RawGnssClock(0, 0), ms), emptyList(), null, null, null, emptyList())

    private fun gps(cn0: Double) = (1..7).map { m(GnssConstellation.GPS, it, cn0 + it % 3) }

    /** Честное небо Mate 70: все системы декодируются, C/N0 около 20–25. */
    private fun honest() = gps(20.0) +
        (1..6).map { m(GnssConstellation.GLONASS, it, 23.0 + it % 4) } +
        (1..5).map { m(GnssConstellation.GALILEO, it, 19.0 + it % 3) } +
        (1..8).map { m(GnssConstellation.BEIDOU, it, 24.0 + it % 5) }

    private fun run(epochs: Int, make: (Int) -> List<RawGnssMeasurement>): List<DetectorVerdict> {
        val mon = SpoofingMonitor()
        return (0 until epochs).map { mon.constellationAsymmetry(input(make(it))).verdict }
    }

    @Test
    fun `честное небо — тревог нет`() {
        assertEquals(0, run(120) { honest() }.count { it == DetectorVerdict.ALERT })
    }

    @Test
    fun `Mate 70 01_22 — GPS есть, остальные системы видны с нулевым C_N0 — тревога с пятой эпохи`() {
        val v = run(20) {
            gps(24.0) + (1..6).map { m(GnssConstellation.GLONASS, it, 0.0, tow = false) } +
                (1..5).map { m(GnssConstellation.GALILEO, it, 0.0, tow = false) } +
                (1..8).map { m(GnssConstellation.BEIDOU, it, 0.0, tow = false) }
        }
        assertEquals(DetectorVerdict.PASS, v[3])
        assertEquals(DetectorVerdict.ALERT, v[4])
        assertEquals(16, v.count { it == DetectorVerdict.ALERT })
    }

    @Test
    fun `P30 lite — GPS на 8 дБ сильнее остальных, они частично декодируются — тревога по превышению`() {
        val v = run(30) {
            gps(34.0) + (1..6).map { m(GnssConstellation.GLONASS, it, 26.0, tow = it % 3 == 0) } +
                (1..5).map { m(GnssConstellation.BEIDOU, it, 24.0, tow = it % 4 == 0) }
        }
        assertEquals(DetectorVerdict.PASS, v[8])
        assertEquals(DetectorVerdict.ALERT, v[9])
    }

    @Test
    fun `только GPS без других систем в измерениях — неприменимо`() {
        val v = run(10) { gps(30.0) }
        assertEquals(10, v.count { it == DetectorVerdict.NOT_APPLICABLE })
    }
}

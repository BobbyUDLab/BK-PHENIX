package ru.bkphenix.core.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation

class GnssLoggerParserTest {
    // Начало pseudoranges_log_2016_08_22_14_45_50.txt из google/gps-measurement-tools (Apache-2.0).
    private val log = GnssLoggerParser.parse(
        javaClass.getResource("/gnsslogger_excerpt.txt")!!.readText().lineSequence(),
    )

    @Test
    fun `строки Raw группируются в эпохи по TimeNanos`() {
        assertEquals(listOf(22, 1), log.epochs.map { it.measurements.size })
        assertEquals(10_084_000_000L, log.epochs[0].clock.timeNanos)
        assertEquals(-1_155_937_562_915_873_645L, log.epochs[0].clock.fullBiasNanos)
    }

    @Test
    fun `поля измерения берутся по именам колонок`() {
        val m = log.epochs[0].measurements[0]
        assertEquals(GnssConstellation.GPS, m.sat.constellation)
        assertEquals(2, m.sat.svid)
        assertEquals(164_772_920_063_716L, m.receivedSvTimeNanos)
        assertEquals(34.9620475769043, m.cn0DbHz, 1e-12)
        // Без CarrierFrequencyHz считается L1; State 39 без TOW_DECODED.
        assertEquals(GnssBand.L1, m.band)
        assertFalse(m.towKnown)
    }

    @Test
    fun `фиксы чипа читаются`() {
        assertEquals(2, log.fixes.size)
        val fix = log.fixes[0]
        assertEquals("gps", fix.provider)
        assertEquals(37.422604, fix.point.latitude, 1e-9)
        assertEquals(4.0, fix.accuracyM)
        assertEquals(1_471_902_355_999L, fix.utcMillis)
    }

    @Test
    fun `фиксы в формате GnssLogger v2 и BK-PHENIX — колонки с единицами`() {
        val v2 = GnssLoggerParser.parse(
            sequenceOf(
                "# Fix,Provider,LatitudeDegrees,LongitudeDegrees,AltitudeMeters,SpeedMps,AccuracyMeters,BearingDegrees,UnixTimeMillis",
                "Fix,GPS,55.75139216855342,37.61833227738487,179.49,,2.7066464,,1793782776000",
            ),
        )
        val fix = v2.fixes.single()
        assertEquals(55.75139216855342, fix.point.latitude, 1e-12)
        assertEquals(179.49, fix.point.altitude!!, 1e-9)
        assertEquals(2.7066464, fix.accuracyM, 1e-9)
        assertEquals(1_793_782_776_000L, fix.utcMillis)
    }
}

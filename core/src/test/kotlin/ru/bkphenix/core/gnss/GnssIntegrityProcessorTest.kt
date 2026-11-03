package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

class GnssIntegrityProcessorTest {
    private val s = SyntheticEpoch(noiseM = 1.0, seed = 21)

    private fun core(): PhenixCore = PhenixCore(gnssProcessor = GnssIntegrityProcessor(ephemerides = s.store))

    private fun chip(distM: Double) = PositionObservation(PositionSource.GNSS, s.truth.offset(distM, 10.0), 5.0, 1_000)

    @Test
    fun `чистая эпоха — собственное решение совпадает с чипом, целостность OK`() {
        val sol = core().solve(listOf(chip(3.0)), 1_000, rawGnss = s.epoch())
        val g = sol.gnss!!
        assertEquals(RaimStatus.OK, g.raim!!.status)
        assertNotNull(g.ownSolution)
        assertEquals(IntegrityState.OK, sol.integrity.state)
        assertEquals(DetectorVerdict.PASS, g.detectors.first { it.kind == DetectorKind.CHIP_VS_OWN }.verdict)
    }

    @Test
    fun `неисправный спутник исключается и попадает в причины`() {
        val bad = s.visible()[1].svid
        val sol = core().solve(listOf(chip(3.0)), 1_000, rawGnss = s.epoch(faultsM = mapOf(bad to 400.0)))
        assertEquals(RaimStatus.EXCLUDED, sol.gnss!!.raim!!.status)
        assertTrue(sol.integrity.reasons.any { "G" + bad.toString().padStart(2, '0') in it })
    }

    @Test
    fun `чип уводит позицию на километр — несогласованность`() {
        val sol = core().solve(listOf(chip(1_000.0)), 1_000, rawGnss = s.epoch())
        assertEquals(IntegrityState.INCONSISTENT, sol.integrity.state)
    }

    @Test
    fun `без эфемерид RAIM недоступен, без сырых данных блок не заполняется`() {
        val sol = PhenixCore().solve(listOf(chip(0.0)), 1_000, rawGnss = s.epoch())
        assertEquals(RaimStatus.UNAVAILABLE, sol.gnss!!.raim!!.status)
        assertNull(sol.gnss!!.ownSolution)
        assertNull(PhenixCore().solve(listOf(chip(0.0)), 1_000).gnss)
    }
}

class NitzDeduplicationTest {
    @Test
    fun `расхождение с NITZ при сырых данных сообщается один раз`() {
        val s = SyntheticEpoch(noiseM = 1.0, seed = 21)
        val core = PhenixCore(gnssProcessor = GnssIntegrityProcessor(ephemerides = s.store))
        val time = listOf(
            ru.bkphenix.core.model.TimeObservation(ru.bkphenix.core.model.TimeSource.GNSS, 1_000_000, 100),
            ru.bkphenix.core.model.TimeObservation(ru.bkphenix.core.model.TimeSource.NITZ, 1_900_000, 100),
        )
        val obs = listOf(PositionObservation(PositionSource.GNSS, s.truth, 5.0, 1_000))
        val withRaw = core.solve(obs, 1_000, timeObservations = time, rawGnss = s.epoch())
        assertEquals(IntegrityState.SPOOFING_SUSPECTED, withRaw.integrity.state)
        assertEquals(1, withRaw.integrity.reasons.count { "NITZ" in it })
        val withoutRaw = PhenixCore().solve(obs, 1_000, timeObservations = time)
        assertEquals(1, withoutRaw.integrity.reasons.count { "NITZ" in it })
    }
}

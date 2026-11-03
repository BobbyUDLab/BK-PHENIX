package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RaimTest {
    private val solver = WlsSolver()
    private val raim = Raim(solver)

    private fun inputs(s: SyntheticEpoch, faults: Map<Int, Double> = emptyMap(), sats: List<GpsEphemeris> = s.visible()) =
        solver.prepare(Pseudoranges.compute(s.epoch(sats, faults)), s.store)

    @Test
    fun `чистые измерения проходят проверку`() {
        val s = SyntheticEpoch(noiseM = 1.0, seed = 3)
        val r = raim.process(inputs(s))
        assertEquals(RaimStatus.OK, r.status)
        assertTrue(r.excluded.isEmpty())
        assertTrue(r.horizontalProtectionLevelM!! > 0)
        assertTrue(r.testStatistic!! < r.threshold!!)
    }

    @Test
    fun `спутник со смещением 300 м исключается, позиция восстанавливается`() {
        val s = SyntheticEpoch(noiseM = 1.0, seed = 5)
        val bad = s.visible()[2].svid
        val r = raim.process(inputs(s, mapOf(bad to 300.0)))
        assertEquals(RaimStatus.EXCLUDED, r.status)
        assertEquals(listOf(bad), r.excluded.map { it.sat.svid })
        assertTrue(r.solution!!.point.distanceTo(s.truth) < 15.0)
    }

    @Test
    fun `два неисправных спутника исключаются оба`() {
        val s = SyntheticEpoch(noiseM = 0.5, seed = 7)
        val v = s.visible()
        val r = raim.process(inputs(s, mapOf(v[0].svid to 250.0, v[3].svid to -400.0)))
        assertEquals(RaimStatus.EXCLUDED, r.status)
        assertEquals(setOf(v[0].svid, v[3].svid), r.excluded.map { it.sat.svid }.toSet())
    }

    @Test
    fun `при пяти спутниках ошибка обнаруживается, но не исключается`() {
        val s = SyntheticEpoch(noiseM = 0.5, seed = 9)
        val five = s.visible().take(5)
        val r = raim.process(inputs(s, mapOf(five[1].svid to 500.0), five))
        assertEquals(RaimStatus.FAULT, r.status)
    }

    @Test
    fun `при четырёх спутниках RAIM недоступен`() {
        val s = SyntheticEpoch()
        assertEquals(RaimStatus.UNAVAILABLE, raim.process(inputs(s, sats = s.visible().take(4))).status)
    }

    @Test
    fun `квантили распределений`() {
        assertEquals(1.645, Raim.normalQuantile(0.95), 1e-3)
        assertEquals(11.07, Raim.chiSquareQuantile(5, 0.95), 0.05)
        assertEquals(20.52, Raim.chiSquareQuantile(5, 0.999), 0.3) // приближение в хвосте ~1 %
    }
}

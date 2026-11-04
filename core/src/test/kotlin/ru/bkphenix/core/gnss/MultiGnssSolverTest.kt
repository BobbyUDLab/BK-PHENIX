package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.EcefCoordinate
import ru.bkphenix.core.model.GnssConstellation

class MultiGnssSolverTest {
    private val s = SyntheticEpoch()
    private val solver = WlsSolver()
    private val receiver = EcefCoordinate.fromGeodetic(s.truth)
    private val clockM = 1_000.0
    private val isb = mapOf(GnssConstellation.GALILEO to 25.0, GnssConstellation.BEIDOU to -40.0)

    /** Видимые синтетические орбиты, поделённые между GPS, Galileo и BeiDou, с межсистемными смещениями. */
    private fun inputs(faults: Map<Int, Double> = emptyMap()): List<RangeInput> =
        s.visible().mapIndexed { i, eph ->
            val sys = listOf(GnssConstellation.GPS, GnssConstellation.GALILEO, GnssConstellation.BEIDOU)[i % 3]
            val p = eph.stateAt(s.tow - 0.075).position
            val rot = WlsSolver.earthRotationCorrected(p, receiver)
            val el = receiver.enuTo(rot).elevationDeg
            val range = (rot - receiver).norm + clockM + (isb[sys] ?: 0.0) + Troposphere.delayM(el) + (faults[i] ?: 0.0)
            RangeInput(SatId(sys, eph.svid), p, range, 3.0, 40.0)
        }

    @Test
    fun `три системы решаются с межсистемными смещениями`() {
        val sol = solver.solve(inputs())!!
        assertTrue((sol.position - receiver).norm < 0.01)
        assertEquals(clockM, sol.clockBiasM, 0.01)
        assertEquals(25.0, sol.interSystemBiasesM.getValue(GnssConstellation.GALILEO), 0.01)
        assertEquals(-40.0, sol.interSystemBiasesM.getValue(GnssConstellation.BEIDOU), 0.01)
        assertEquals(sol.used.size - 6, sol.degreesOfFreedom)
    }

    @Test
    fun `RAIM исключает ошибку на 80 м в многосистемном решении`() {
        val all = inputs()
        val r = Raim(solver).process(inputs(mapOf(1 to 80.0)))
        assertEquals(RaimStatus.EXCLUDED, r.status)
        assertEquals(all[1].sat, r.excluded.single().sat)
        assertEquals(7, Raim.minForDetection(all))
    }

    @Test
    fun `Klobuchar ночью даёт 5 нс и масштабируется по частоте`() {
        val k = KlobucharCoefficients(listOf(1.2e-8, 0.0, 0.0, 0.0), listOf(1.0e5, 0.0, 0.0, 0.0))
        // 02:00 местного времени — ночное значение 5 нс.
        val night = Klobuchar.delayM(k, 0.0, 0.0, 90.0, 0.0, 2 * 3600.0)
        assertEquals(5e-9 * GpsEphemeris.C, night, 0.01)
        // 14:00 местного — максимум 5 нс + α0.
        val day = Klobuchar.delayM(k, 0.0, 0.0, 90.0, 0.0, 14 * 3600.0)
        assertEquals(1.7e-8 * GpsEphemeris.C, day, 0.05)
        val b1i = Klobuchar.delayM(k, 0.0, 0.0, 90.0, 0.0, 14 * 3600.0, 1_561.098e6)
        assertEquals((1_575.42 / 1_561.098).let { it * it }, b1i / day, 1e-9)
    }
}

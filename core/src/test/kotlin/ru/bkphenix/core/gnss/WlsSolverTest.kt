package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.EcefCoordinate
import ru.bkphenix.core.model.GnssConstellation

class WlsSolverTest {
    private val solver = WlsSolver()

    @Test
    fun `псевдодальности по формуле Android совпадают с геометрией`() {
        val s = SyntheticEpoch()
        val ranges = Pseudoranges.compute(s.epoch())
        assertTrue(ranges.size >= 8, "видимых спутников: ${ranges.size}")
        ranges.forEach { assertTrue(it.rangeM in 1.9e7..2.8e7, "ρ = ${it.rangeM}") }
        assertEquals(2440, ranges.first().gpsWeek)
    }

    @Test
    fun `решение без шума совпадает с истиной до сантиметров`() {
        val s = SyntheticEpoch()
        val sol = solver.solve(solver.prepare(Pseudoranges.compute(s.epoch()), s.store))!!
        val err = sol.position.distanceTo(EcefCoordinate.fromGeodetic(s.truth))
        assertTrue(err < 0.5, "ошибка $err м")
        assertEquals(s.clockBiasM, sol.clockBiasM, 0.5)
        assertTrue(sol.horizontalSigmaM > 0)
        assertTrue(sol.chiSquare < 1.0)
    }

    @Test
    fun `с шумом 3 м ошибка в пределах десятков метров`() {
        val s = SyntheticEpoch(noiseM = 3.0, seed = 42)
        val sol = solver.solve(solver.prepare(Pseudoranges.compute(s.epoch()), s.store))!!
        assertTrue(sol.point.distanceTo(s.truth) < 30.0)
    }

    @Test
    fun `без эфемерид, без TOW и меньше четырёх спутников решения нет`() {
        val s = SyntheticEpoch()
        val epoch = s.epoch()
        assertTrue(solver.prepare(Pseudoranges.compute(epoch), EphemerisStore()).isEmpty())
        val noTow = epoch.copy(measurements = epoch.measurements.map { it.copy(towKnown = false) })
        assertTrue(Pseudoranges.compute(noTow).isEmpty())
        val three = solver.prepare(Pseudoranges.compute(epoch), s.store).take(3)
        assertNull(solver.solve(three))
        assertTrue(Pseudoranges.compute(epoch.copy(clock = epoch.clock.copy(fullBiasNanos = null))).isEmpty())
    }

    @Test
    fun `обозначение спутника в стиле RINEX`() {
        assertEquals("G05", SatId(GnssConstellation.GPS, 5).label)
        assertEquals("E12", SatId(GnssConstellation.GALILEO, 12).label)
    }
}

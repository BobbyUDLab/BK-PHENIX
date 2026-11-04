package ru.bkphenix.core.compare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

class QuorumTest {
    private val p = ru.bkphenix.core.geo.GeoPoint(55.75, 37.62)
    private fun o(src: PositionSource, east: Double, acc: Double) = PositionObservation(src, p.offset(east, 90.0), acc, 0)

    @Test
    fun `GNSS и сеть уводят позицию вместе без третьего источника — подозрительно`() {
        // Обе точки смещены на 800 м и согласованы друг с другом: подмена неотличима, но кворума нет.
        val sol = PhenixCore().solve(listOf(o(PositionSource.GNSS, 800.0, 5.0), o(PositionSource.NETWORK, 815.0, 30.0)), 0)
        assertTrue(sol.comparison.isConsistent)
        assertEquals(2, sol.comparison.independentConfirmations)
        assertEquals(1, sol.comparison.strongConfirmations)
        assertFalse(sol.comparison.hasQuorum)
        assertEquals(IntegrityState.SUSPICIOUS, sol.integrity.state)
    }

    @Test
    fun `GNSS и собственная сота — кворум есть`() {
        val sol = PhenixCore().solve(listOf(o(PositionSource.GNSS, 0.0, 5.0), o(PositionSource.CELL, 60.0, 150.0)), 0)
        assertTrue(sol.comparison.hasQuorum)
        assertEquals(IntegrityState.OK, sol.integrity.state)
    }

    @Test
    fun `сеть, опирающаяся на GNSS, весит меньше своей σ`() {
        val r = PositionComparator().compare(listOf(o(PositionSource.NETWORK, 0.0, 10.0), o(PositionSource.WIFI_RTT, 20.0, 10.0)))
        // При равных σ итог сдвинут к Wi-Fi RTT: вес сети ×0,25.
        assertEquals(16.0, p.offset(0.0, 90.0).distanceTo(r.estimate!!), 0.5)
    }
}

class AnchorPriorityTest {
    private val p = ru.bkphenix.core.geo.GeoPoint(55.75, 37.62)

    @Test
    fun `якорь решает спор — согласованная подмена GNSS и сети отсекается`() {
        val core = PhenixCore()
        core.anchorManager.setAnchor(ru.bkphenix.core.anchor.Anchor(p, 10.0, 0))
        val gnss = PositionObservation(PositionSource.GNSS, p.offset(800.0, 90.0), 5.0, 1_000)
        val net = PositionObservation(PositionSource.NETWORK, p.offset(810.0, 90.0), 30.0, 1_000)
        val sol = core.solve(listOf(gnss, net), 1_000)
        assertTrue(gnss in sol.comparison.rejected && net in sol.comparison.rejected)
        assertEquals(PositionSource.LANDMARK, sol.comparison.accepted.single().source)
        assertEquals(IntegrityState.SPOOFING_SUSPECTED, sol.integrity.state)
    }

    @Test
    fun `якорь засчитывается как независимая группа человека`() {
        val core = PhenixCore()
        core.anchorManager.setAnchor(ru.bkphenix.core.anchor.Anchor(p, 10.0, 0))
        val sol = core.solve(listOf(PositionObservation(PositionSource.GNSS, p.offset(5.0, 0.0), 5.0, 1_000)), 1_000)
        assertTrue(sol.comparison.hasQuorum)
        assertEquals(IntegrityState.OK, sol.integrity.state)
    }
}

package ru.bkphenix.core.inertial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.track.HeldPosition

class PositionFusionTest {
    private val p = GeoPoint(55.75, 37.62)
    private val q = GeoPoint(55.76, 37.63)
    private fun held(acc: Double, extrapolated: Boolean) = HeldPosition(p, acc, 1.0, 0.0, extrapolated)
    private fun dr(sigma: Double) = InertialEstimate(q, sigma, 1.0, 90.0, 5_000, false)

    @Test
    fun `свежее решение важнее счисления`() {
        val h = held(5.0, false)
        assertSame(h, PositionFusion.choose(h, dr(3.0)))
    }

    @Test
    fun `без свежего решения берётся более точная оценка`() {
        val r = PositionFusion.choose(held(40.0, true), dr(12.0))!!
        assertTrue(r.deadReckoning)
        assertEquals(q, r.point)
        assertEquals(12.0, r.accuracyM)
        val h = held(8.0, true)
        assertSame(h, PositionFusion.choose(h, dr(12.0)))
    }

    @Test
    fun `нет данных — нет позиции`() {
        assertNull(PositionFusion.choose(null, null))
        assertTrue(PositionFusion.choose(null, dr(10.0))!!.deadReckoning)
    }
}

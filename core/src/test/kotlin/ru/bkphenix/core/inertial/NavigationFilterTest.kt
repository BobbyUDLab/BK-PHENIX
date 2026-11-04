package ru.bkphenix.core.inertial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.GeoPoint

class NavigationFilterTest {
    private val origin = GeoPoint(55.75, 37.62, 150.0)

    @Test
    fun `без измерений погрешность растёт, ZUPT её сдерживает`() {
        val f = NavigationFilter(accelNoise = 1.0).apply { reset(origin, 5.0, 0) }
        val s0 = f.sigmaM
        f.predict(10_000)
        val free = f.sigmaM
        assertTrue(free > s0 * 5, "σ $free")
        val g = NavigationFilter(accelNoise = 1.0).apply { reset(origin, 5.0, 0) }
        for (t in 1..100) { g.predict(t * 100L); g.zeroVelocity() }
        assertTrue(g.sigmaM < s0 * 1.05, "σ с ZUPT ${g.sigmaM}")
    }

    @Test
    fun `позиции ГНСС дают скорость и курс`() {
        val f = NavigationFilter()
        for (t in 0..30) f.updatePosition(origin.offset(1.5 * t, 90.0), 3.0, t * 1000L)
        assertEquals(1.5, f.speedMps, 0.1)
        assertEquals(90.0, f.bearingDeg, 3.0)
        // 10 с без ГНСС: точка ведётся по скорости.
        f.predict(40_000)
        assertEquals(60.0, origin.distanceTo(f.point!!), 3.0)
    }

    @Test
    fun `выброс отклоняется, после трёх подряд — сброс`() {
        val f = NavigationFilter()
        for (t in 0..10) f.updatePosition(origin, 3.0, t * 1000L)
        val far = origin.offset(500.0, 0.0)
        assertFalse(f.updatePosition(far, 3.0, 11_000))
        assertFalse(f.updatePosition(far, 3.0, 12_000))
        f.updatePosition(far, 3.0, 13_000)
        assertEquals(0.0, far.distanceTo(f.point!!), 1.0)
    }

    @Test
    fun `шаг PDR задаёт скорость, боковое ограничение и поворот — авто`() {
        val f = NavigationFilter().apply { reset(origin, 3.0, 0) }
        f.updateVelocity(1.2, 0.0, 0.1)
        assertEquals(1.2, f.speedMps, 0.05)
        assertEquals(0.0, ((f.bearingDeg + 180) % 360) - 180, 2.0)
        val car = NavigationFilter().apply { reset(origin, 3.0, 0) }
        car.updateVelocity(10.0, 45.0, 3.0)
        car.lateralConstraint(0.0, 0.05)
        assertEquals(0.0, ((car.bearingDeg + 180) % 360) - 180, 3.0)
        car.rotateVelocity(-Math.PI / 2) // поворот направо: курс 90°
        assertEquals(90.0, car.bearingDeg, 3.0)
    }
}

package ru.bkphenix.core.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeoPointTest {
    @Test
    fun `один градус широты около 111 км`() {
        assertEquals(111_195.0, GeoPoint(0.0, 0.0).distanceTo(GeoPoint(1.0, 0.0)), 5.0)
    }

    @Test
    fun `смещение на 500 м даёт расстояние 500 м и тот же азимут`() {
        val start = GeoPoint(55.75, 37.62)
        val moved = start.offset(500.0, 45.0)
        assertEquals(500.0, start.distanceTo(moved), 0.01)
        assertEquals(45.0, start.bearingTo(moved), 0.01)
    }

    @Test
    fun `некорректная широта отклоняется`() {
        assertThrows<IllegalArgumentException> { GeoPoint(91.0, 0.0) }
    }

    @Test
    fun `ECEF точки на экваторе и нулевом меридиане равна большой полуоси`() {
        val ecef = EcefCoordinate.fromGeodetic(GeoPoint(0.0, 0.0, 0.0))
        assertEquals(6_378_137.0, ecef.x, 1e-6)
        assertEquals(0.0, ecef.y, 1e-6)
        assertEquals(0.0, ecef.z, 1e-6)
    }
}

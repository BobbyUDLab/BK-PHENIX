package ru.bkphenix.core.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EcefCoordinateTest {
    @Test
    fun `ECEF и обратно сохраняет точку`() {
        val p = GeoPoint(55.75393, 37.620795, 150.0)
        val back = EcefCoordinate.fromGeodetic(p).toGeodetic()
        assertEquals(p.latitude, back.latitude, 1e-9)
        assertEquals(p.longitude, back.longitude, 1e-9)
        assertEquals(150.0, back.altitude!!, 1e-4)
    }

    @Test
    fun `точка над головой имеет угол места 90 градусов`() {
        val p = GeoPoint(10.0, 20.0, 0.0)
        val above = EcefCoordinate.fromGeodetic(p.copy(altitude = 20_000_000.0))
        val enu = EcefCoordinate.fromGeodetic(p).enuTo(above)
        assertEquals(90.0, enu.elevationDeg, 1e-6)
        assertEquals(20_000_000.0, enu.up, 1e-3)
    }

    @Test
    fun `север даёт азимут 0, восток — 90`() {
        val o = GeoPoint(0.0, 0.0, 0.0)
        val origin = EcefCoordinate.fromGeodetic(o)
        assertEquals(0.0, origin.enuTo(EcefCoordinate.fromGeodetic(GeoPoint(0.01, 0.0, 0.0))).azimuthDeg, 1e-6)
        assertEquals(90.0, origin.enuTo(EcefCoordinate.fromGeodetic(GeoPoint(0.0, 0.01, 0.0))).azimuthDeg, 1e-6)
    }
}

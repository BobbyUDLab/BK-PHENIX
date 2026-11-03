package ru.bkphenix.core.geo

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Декартовы координаты ECEF (Earth-Centered, Earth-Fixed), метры. Единый формат для всех ГНСС. */
public data class EcefCoordinate(val x: Double, val y: Double, val z: Double) {

    public fun distanceTo(other: EcefCoordinate): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    public companion object {
        private const val WGS84_A = 6_378_137.0
        private const val WGS84_F = 1.0 / 298.257223563
        private const val WGS84_E2 = WGS84_F * (2 - WGS84_F)

        /** Преобразование геодезических координат WGS-84 в ECEF. */
        public fun fromGeodetic(point: GeoPoint): EcefCoordinate {
            val phi = Math.toRadians(point.latitude)
            val lambda = Math.toRadians(point.longitude)
            val h = point.altitude ?: 0.0
            val n = WGS84_A / sqrt(1 - WGS84_E2 * sin(phi) * sin(phi))
            return EcefCoordinate(
                x = (n + h) * cos(phi) * cos(lambda),
                y = (n + h) * cos(phi) * sin(lambda),
                z = (n * (1 - WGS84_E2) + h) * sin(phi),
            )
        }
    }
}

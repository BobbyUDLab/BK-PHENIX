package ru.bkphenix.core.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
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

    public operator fun minus(other: EcefCoordinate): EcefCoordinate = EcefCoordinate(x - other.x, y - other.y, z - other.z)

    public operator fun plus(other: EcefCoordinate): EcefCoordinate = EcefCoordinate(x + other.x, y + other.y, z + other.z)

    public val norm: Double get() = sqrt(x * x + y * y + z * z)

    /** Геодезические координаты WGS-84 (итерационный метод, сходимость до 1e-12 рад). */
    public fun toGeodetic(): GeoPoint {
        val p = sqrt(x * x + y * y)
        val lambda = atan2(y, x)
        var phi = atan2(z, p * (1 - WGS84_E2))
        var h = 0.0
        repeat(MAX_ITERATIONS) {
            val n = WGS84_A / sqrt(1 - WGS84_E2 * sin(phi) * sin(phi))
            h = if (abs(cos(phi)) > 1e-10) p / cos(phi) - n else abs(z) - n * (1 - WGS84_E2)
            val next = atan2(z, p * (1 - WGS84_E2 * n / (n + h)))
            val done = abs(next - phi) < 1e-12
            phi = next
            if (done) return GeoPoint(Math.toDegrees(phi), Math.toDegrees(lambda), h)
        }
        return GeoPoint(Math.toDegrees(phi), Math.toDegrees(lambda), h)
    }

    /**
     * Вектор [target] − this в локальной системе ENU (восток, север, вверх) в точке this.
     */
    public fun enuTo(target: EcefCoordinate): Enu {
        val g = toGeodetic()
        return Enu.rotate(target - this, Math.toRadians(g.latitude), Math.toRadians(g.longitude))
    }

    public companion object {
        private const val MAX_ITERATIONS = 10

        internal const val WGS84_A = 6_378_137.0
        internal const val WGS84_F = 1.0 / 298.257223563
        internal const val WGS84_E2 = WGS84_F * (2 - WGS84_F)

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

/** Локальные координаты ENU, метры. */
public data class Enu(val east: Double, val north: Double, val up: Double) {
    public val horizontal: Double get() = sqrt(east * east + north * north)

    /** Угол места, градусы. */
    public val elevationDeg: Double get() = Math.toDegrees(asin((up / sqrt(east * east + north * north + up * up)).coerceIn(-1.0, 1.0)))

    /** Азимут, градусы [0; 360). */
    public val azimuthDeg: Double get() = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0

    public companion object {
        /** Поворот вектора ECEF в ENU для широты [phi] и долготы [lambda] (радианы). */
        public fun rotate(d: EcefCoordinate, phi: Double, lambda: Double): Enu {
            val sp = sin(phi)
            val cp = cos(phi)
            val sl = sin(lambda)
            val cl = cos(lambda)
            return Enu(
                east = -sl * d.x + cl * d.y,
                north = -sp * cl * d.x - sp * sl * d.y + cp * d.z,
                up = cp * cl * d.x + cp * sl * d.y + sp * d.z,
            )
        }
    }
}

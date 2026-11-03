package ru.bkphenix.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Географическая точка в WGS-84.
 *
 * @property latitude широта, градусы, [-90; 90].
 * @property longitude долгота, градусы, [-180; 180].
 * @property altitude высота над эллипсоидом, метры, если известна.
 */
public data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "Широта вне диапазона: $latitude" }
        require(longitude in -180.0..180.0) { "Долгота вне диапазона: $longitude" }
    }

    /** Расстояние по большому кругу до [other], метры. */
    public fun distanceTo(other: GeoPoint): Double {
        val phi1 = Math.toRadians(latitude)
        val phi2 = Math.toRadians(other.latitude)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(other.longitude - longitude)
        val h = sin(dPhi / 2).let { it * it } + cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
        return 2 * EARTH_MEAN_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Начальный азимут на [other], градусы [0; 360). */
    public fun bearingTo(other: GeoPoint): Double {
        val phi1 = Math.toRadians(latitude)
        val phi2 = Math.toRadians(other.latitude)
        val dLambda = Math.toRadians(other.longitude - longitude)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Точка, смещённая на [distanceM] метров по азимуту [bearingDeg]. */
    public fun offset(distanceM: Double, bearingDeg: Double): GeoPoint {
        val delta = distanceM / EARTH_MEAN_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(latitude)
        val lambda1 = Math.toRadians(longitude)
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 = lambda1 + atan2(sin(theta) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sin(phi2))
        val lon = (Math.toDegrees(lambda2) + 540.0) % 360.0 - 180.0
        return GeoPoint(Math.toDegrees(phi2), lon, altitude)
    }

    public companion object {
        /** Средний радиус Земли (IUGG), метры. */
        public const val EARTH_MEAN_RADIUS_M: Double = 6_371_008.8
    }
}

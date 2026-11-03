package ru.bkphenix.core

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

internal object Fixtures {
    /** Красная площадь, Москва. */
    val TRUE_POINT = GeoPoint(55.753930, 37.620795)
    const val T0 = 1_790_000_000_000L

    fun obs(source: PositionSource, point: GeoPoint, accuracyM: Double, t: Long = T0) =
        PositionObservation(source, point, accuracyM, t)
}

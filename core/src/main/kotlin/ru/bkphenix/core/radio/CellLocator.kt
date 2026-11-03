package ru.bkphenix.core.radio

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import kotlin.math.max
import kotlin.math.pow

/**
 * Оценка позиции по сотовым вышкам из офлайн-базы: взвешенный по мощности сигнала центр масс
 * найденных вышек. Если у обслуживающей соты есть Timing Advance, погрешность уточняется им.
 */
public class CellLocator(
    private val database: CellTowerDatabase,
    private val minAccuracyM: Double = 100.0,
) {
    public fun locate(measurements: List<CellMeasurement>, timestampMillis: Long): PositionObservation? {
        val found = measurements.mapNotNull { m -> database.find(m)?.let { m to it } }
        if (found.isEmpty()) return null
        val weights = found.map { (m, _) -> 10.0.pow((m.rsrpDbm ?: DEFAULT_DBM) / 10.0) }
        val total = weights.sum()
        val lat = found.indices.sumOf { found[it].second.position.latitude * weights[it] } / total
        val lon = found.indices.sumOf { found[it].second.position.longitude * weights[it] } / total
        val rangeSigma = found.indices.sumOf { found[it].second.rangeM * weights[it] } / total / 2
        val taSigma = found.firstOrNull { it.first.isServing }?.first?.let { m ->
            m.timingAdvance?.let { ta -> timingAdvanceDistanceM(m.technology, ta) }
        }?.let { max(it, minAccuracyM) }
        return PositionObservation(
            source = PositionSource.CELL,
            point = GeoPoint(lat, lon),
            accuracyM = max(minAccuracyM, minOf(rangeSigma, taSigma ?: Double.MAX_VALUE)),
            timestampMillis = timestampMillis,
        )
    }

    private companion object {
        const val DEFAULT_DBM = -110
    }
}

/** Пустая база: используется, пока офлайн-база вышек не загружена. */
public object EmptyCellTowerDatabase : CellTowerDatabase {
    override fun find(measurement: CellMeasurement): CellTowerRecord? = null
}

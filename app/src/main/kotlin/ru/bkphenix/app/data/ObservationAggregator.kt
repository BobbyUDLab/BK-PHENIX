package ru.bkphenix.app.data

import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

/**
 * Хранит последнее наблюдение каждого источника и отбрасывает устаревшие.
 * Потокобезопасен; без зависимостей от Android, чтобы тестироваться на JVM.
 */
class ObservationAggregator(
    private val maxAgeMillis: Map<PositionSource, Long> = DEFAULT_MAX_AGE,
    private val defaultMaxAgeMillis: Long = 30_000,
) {
    private val latest = LinkedHashMap<PositionSource, PositionObservation>()

    @Synchronized
    fun put(observation: PositionObservation) {
        val current = latest[observation.source]
        if (current == null || observation.timestampMillis >= current.timestampMillis) {
            latest[observation.source] = observation
        }
    }

    @Synchronized
    fun fresh(nowMillis: Long): List<PositionObservation> = latest.values.filter {
        nowMillis - it.timestampMillis <= (maxAgeMillis[it.source] ?: defaultMaxAgeMillis)
    }

    companion object {
        val DEFAULT_MAX_AGE: Map<PositionSource, Long> = mapOf(
            PositionSource.GNSS to 5_000L,
            PositionSource.NETWORK to 60_000L,
        )
    }
}

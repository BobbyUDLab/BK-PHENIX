package ru.bkphenix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

class ObservationAggregatorTest {
    private val p = GeoPoint(55.75, 37.62)

    @Test
    fun `хранится последнее наблюдение каждого источника`() {
        val agg = ObservationAggregator()
        agg.put(PositionObservation(PositionSource.GNSS, p, 5.0, 1_000))
        agg.put(PositionObservation(PositionSource.GNSS, p.offset(10.0, 0.0), 5.0, 2_000))
        agg.put(PositionObservation(PositionSource.GNSS, p, 5.0, 1_500)) // запоздавшее
        val fresh = agg.fresh(2_500)
        assertEquals(1, fresh.size)
        assertEquals(2_000, fresh.single().timestampMillis)
    }

    @Test
    fun `устаревшие наблюдения отбрасываются по возрасту источника`() {
        val agg = ObservationAggregator()
        agg.put(PositionObservation(PositionSource.GNSS, p, 5.0, 0))
        agg.put(PositionObservation(PositionSource.NETWORK, p, 30.0, 0))
        val fresh = agg.fresh(10_000)
        assertEquals(listOf(PositionSource.NETWORK), fresh.map { it.source })
        assertTrue(agg.fresh(120_000).isEmpty())
    }
}

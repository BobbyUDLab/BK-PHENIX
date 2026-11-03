package ru.bkphenix.app.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

class TrustMappingTest {
    private val p = GeoPoint(55.75, 37.62)

    @Test
    fun `подменённая ГНСС получает REJECTED, остальные доверие`() {
        val solution = PhenixCore().solve(
            listOf(
                PositionObservation(PositionSource.GNSS, p.offset(500.0, 0.0), 5.0, 0),
                PositionObservation(PositionSource.NETWORK, p, 20.0, 0),
                PositionObservation(PositionSource.CELL, p.offset(50.0, 90.0), 150.0, 0),
            ),
            0,
        )
        val trust = TrustMapping.fromSolution(solution).associateBy { it.source }
        assertEquals(TrustLevel.REJECTED, trust.getValue(PositionSource.GNSS).level)
        assertEquals(TrustLevel.HIGH, trust.getValue(PositionSource.NETWORK).level)
    }
}

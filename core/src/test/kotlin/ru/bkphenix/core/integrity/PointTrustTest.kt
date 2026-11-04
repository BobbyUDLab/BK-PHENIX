package ru.bkphenix.core.integrity

import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.anchor.AnchorEvaluation
import ru.bkphenix.core.anchor.AnchorState
import ru.bkphenix.core.compare.PositionComparator
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class PointTrustTest {
    private val p = GeoPoint(55.75, 37.62)
    private fun obs(src: PositionSource, dLat: Double = 0.0) =
        PositionObservation(src, GeoPoint(p.latitude + dLat, p.longitude), 10.0, 0L)

    private fun solution(obs: List<PositionObservation>, state: IntegrityState = IntegrityState.OK): PhenixSolution {
        val c = PositionComparator().compare(obs)
        return PhenixSolution(c.estimate, c.accuracyM, IntegrityReport(state, emptyList()), AnchorEvaluation(AnchorState.NO_ANCHOR, null, null, null, ""), c)
    }

    @Test
    fun `одна ГНСС при OK не выглядит надёжной`() {
        val t = PointTrust.of(solution(listOf(obs(PositionSource.GNSS))))
        assertEquals(PointTrustLevel.UNRELIABLE, t.level)
        assertTrue("нет кворума" in t.reason)
    }

    @Test
    fun `тревога важнее кворума`() {
        val t = PointTrust.of(solution(listOf(obs(PositionSource.GNSS)), IntegrityState.SPOOFING_SUSPECTED))
        assertEquals(PointTrustLevel.ALARM, t.level)
    }

    @Test
    fun `нет решения`() = assertEquals(PointTrustLevel.NONE, PointTrust.of(null).level)
}

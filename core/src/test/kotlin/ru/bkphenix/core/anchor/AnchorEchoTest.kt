package ru.bkphenix.core.anchor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.integrity.PointTrust
import ru.bkphenix.core.integrity.PointTrustLevel
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.track.MotionMode

/** Аудит 07.11.2026, риск №2: якорь не должен становиться эхом ГНСС. */
class AnchorEchoTest {
    private val home = GeoPoint(55.80, 37.50)
    private val t0 = 1_000_000L

    private fun gnss(p: GeoPoint, t: Long) = PositionObservation(PositionSource.GNSS, p, 4.0, t)

    @Test
    fun `плавный увод одной ГНСС не тянет якорь за собой`() {
        val core = PhenixCore().apply { anchorManager.motionMode = MotionMode.WALK }
        core.anchorManager.setAnchor(Anchor(home, 10.0, t0))
        var last = core.solve(listOf(gnss(home, t0)), t0)
        for (i in 1..600) {
            val t = t0 + i * 1000L
            last = core.solve(listOf(gnss(home.offset(1.5 * i, 90.0), t)), t) // увод 1,5 м/с на восток
        }
        // Положение по якорю осталось дома (σ выросла), а не ушло на 900 м вместе с ГНСС.
        assertTrue(last.anchor.propagatedPoint!!.distanceTo(home) < 1.0, "якорь ушёл на ${last.anchor.propagatedPoint!!.distanceTo(home)} м")
        assertNotEquals(PointTrustLevel.RELIABLE, PointTrust.of(last).level, "одна ГНСС + якорь-область не дают «Надёжно»")
    }

    @Test
    fun `при кворуме независимых источников якорь следует за движением`() {
        val core = PhenixCore().apply { anchorManager.motionMode = MotionMode.CAR }
        core.anchorManager.setAnchor(Anchor(home, 10.0, t0))
        var last = core.solve(emptyList(), t0)
        for (i in 1..300) {
            val t = t0 + i * 1000L
            val truth = home.offset(15.0 * i, 0.0)
            last = core.solve(listOf(gnss(truth, t), PositionObservation(PositionSource.CELL, truth.offset(80.0, 45.0), 300.0, t)), t)
        }
        assertTrue(last.anchor.propagatedPoint!!.distanceTo(home.offset(4500.0, 0.0)) < 100.0)
    }

    @Test
    fun `остановка с одной ГНСС автоякорь не ставит`() {
        val m = AnchorManager().apply { motionMode = MotionMode.WALK }
        m.setAnchor(Anchor(home, 10.0, 0))
        for (i in 1..400) m.evaluate(home, 3.0, i * 1000L, sourcesHaveQuorum = false)
        assertEquals(AnchorMode.MANUAL, m.anchor?.mode)
    }

    @Test
    fun `якорь с большой σ не голосует как независимое подтверждение`() {
        val m = AnchorManager().apply { motionMode = MotionMode.CAR }
        m.setAnchor(Anchor(home, 10.0, 0))
        m.evaluate(home, 5.0, 0)
        assertTrue(m.observation(1_000)!!.isIndependentEvidence, "свежий якорь — голос «Человек»")
        m.evaluate(null, null, 5_000)
        assertTrue(!m.observation(5_000)!!.isIndependentEvidence, "якорь с σ в сотни метров — лишь область")
    }
}

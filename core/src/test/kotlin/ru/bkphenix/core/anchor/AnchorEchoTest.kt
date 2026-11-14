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
    private val home = GeoPoint(55.7512, 37.6184)
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
        // 0.8.1 (требование к продукту): доверенный якорь идёт за точной ГНСС и без кворума, но не дальше 300 м —
        // увод на 900 м не утащил его за собой.
        assertTrue(last.anchor.propagatedPoint!!.distanceTo(home) <= AnchorManager.TRUSTED_FOLLOW_MAX_PATH_M + 1.0, "якорь ушёл на ${last.anchor.propagatedPoint!!.distanceTo(home)} м")
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

    @Test
    fun `ручной якорь голосует, автоякорь без подтверждения — нет`() {
        val m = AnchorManager().apply { motionMode = MotionMode.WALK }
        m.setAnchor(Anchor(home, 10.0, 0))
        m.evaluate(home, 3.0, 1000L)
        assertTrue(m.observation(1000L)!!.isIndependentEvidence, "ручной якорь — доверенный голос")
        m.setAnchor(Anchor(home, 10.0, 2000L, AnchorMode.AUTOMATIC))
        m.evaluate(home, 3.0, 3000L)
        assertTrue(!m.observation(3000L)!!.isIndependentEvidence, "автоякорь без подтверждения — не голос")
    }

    @Test
    fun `доверенный якорь идёт за точной ГНСС без кворума и подтверждает её`() {
        val core = PhenixCore().apply { anchorManager.motionMode = MotionMode.WALK }
        core.anchorManager.setAnchor(Anchor(home, 5.0, t0))
        var last = core.solve(listOf(gnss(home, t0)), t0)
        for (i in 1..120) {
            val t = t0 + i * 1000L
            last = core.solve(listOf(gnss(home.offset(1.2 * i, 0.0), t)), t) // прогулка 1,2 м/с, 144 м
        }
        assertTrue(last.anchor.propagatedPoint!!.distanceTo(home.offset(144.0, 0.0)) < 15.0, "якорь отстал")
        assertEquals(2, last.comparison.strongConfirmations, "ГНСС + человек")
    }

    @Test
    fun `на остановке предлагается подтвердить якорь, подтверждённый — ручной`() {
        val m = AnchorManager().apply { motionMode = MotionMode.WALK }
        m.setAnchor(Anchor(home, 10.0, 0, AnchorMode.AUTOMATIC))
        // Автоякорь без ручного не продлевается, но предложение подтвердить его на остановке есть.
        val stop = home.offset(50.0, 90.0)
        var e = m.evaluate(stop, 3.0, 1000L)
        for (i in 2..20) e = m.evaluate(stop, 3.0, i * 1000L)
        assertTrue(e.suggestion != null, "нет предложения на остановке")
        assertTrue(m.confirmSuggestion(21_000L))
        assertEquals(AnchorMode.MANUAL, m.anchor?.mode)
        assertTrue(m.isTrusted)
        // «Позже» глушит предложения.
        m.setAnchor(Anchor(home, 10.0, 22_000L, AnchorMode.AUTOMATIC))
        m.dismissSuggestion(22_000L)
        for (i in 23..60) e = m.evaluate(stop, 3.0, i * 1000L)
        assertEquals(null, e.suggestion)
    }

    @Test
    fun `при подозрении на подмену предложения нет`() {
        val m = AnchorManager().apply { motionMode = MotionMode.WALK }
        m.setAnchor(Anchor(home, 10.0, 0, AnchorMode.AUTOMATIC))
        var e = m.evaluate(home, 3.0, 1000L, spoofSuspected = true)
        for (i in 2..30) e = m.evaluate(home, 3.0, i * 1000L, spoofSuspected = true)
        assertEquals(null, e.suggestion)
    }
}

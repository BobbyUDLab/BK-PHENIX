package ru.bkphenix.core.anchor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

/**
 * Баг 0.5.1: якорь в машине. σ якоря росла на 3 м/с (пешком), машина ехала 15 м/с, и через пару минут якорь-приоритет
 * отсеивал все источники: позиция замирала на якоре, трек не шёл, повторно якорь не запрашивался.
 */
class AnchorCarDriveTest {
    private val start = GeoPoint(55.7512, 37.6184)
    private val t0 = 1_793_786_912_000L
    private val speed = 15.0

    private fun truth(s: Int) = start.offset(speed * s, 45.0)

    private fun epoch(s: Int, withSources: Boolean = true): List<PositionObservation> {
        if (!withSources) return emptyList()
        val t = truth(s)
        val now = t0 + s * 1000L
        return listOf(
            PositionObservation(PositionSource.GNSS, t.offset(4.0, (s * 37 % 360).toDouble()), 5.0, now),
            PositionObservation(PositionSource.CELL, t.offset(120.0, (s * 53 % 360).toDouble()), 300.0, now),
        )
    }

    @Test
    fun `20 минут на авто с якорем - позиция и трек не пропадают, якорь следует за машиной`() {
        val core = PhenixCore()
        core.anchorManager.setAnchor(Anchor(start, 10.0, t0))
        var maxErr = 0.0
        for (s in 0..1200) {
            val sol = core.solve(epoch(s), t0 + s * 1000L)
            assertNotNull(sol.point, "позиция пропала на $s с")
            val p = sol.point!!
            maxErr = maxOf(maxErr, p.distanceTo(truth(s)))
            if (s == 1200) {
                val a = sol.anchor
                assertEquals(AnchorState.HUMAN_ANCHOR, a.state)
                assertTrue(a.propagatedPoint!!.distanceTo(truth(s)) < 50.0)
                // С 0.8 автоякорь работает во всех режимах: при кворуме (GNSS + соты) якорь продлевается,
                // поэтому через 20 мин он свежий (без продления σ ≈ 540 м и «пора обновить»), а ручной сохранён.
                assertEquals(AnchorMode.AUTOMATIC, a.anchor!!.mode)
                assertEquals(AnchorMode.MANUAL, a.lastHumanAnchor!!.mode)
                assertEquals(AnchorFreshness.ACTUAL, a.freshness)
            }
        }
        assertTrue(maxErr < 50.0, "макс. ошибка $maxErr м")
    }

    @Test
    fun `после перерыва источников 60 с якорь не замораживает позицию`() {
        val core = PhenixCore()
        core.anchorManager.setAnchor(Anchor(start, 10.0, t0))
        var frozenAfterReturn = 0
        for (s in 0..400) {
            val sol = core.solve(epoch(s, withSources = s !in 100..159), t0 + s * 1000L)
            assertNotNull(sol.point, "позиция пропала на $s с")
            if (s >= 160 && sol.point!!.distanceTo(truth(s)) > 100.0) frozenAfterReturn++
        }
        // Конфликт с кворумом источников снимает приоритет якоря не позже чем через CONFLICT_EPOCHS эпох.
        assertTrue(frozenAfterReturn <= AnchorManager.CONFLICT_EPOCHS, "эпох с отставшей позицией: $frozenAfterReturn")
    }
}

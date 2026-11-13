package ru.bkphenix.core.anchor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.track.MotionMode

/**
 * Реальная поездка домой 04.11.2026 (0.6.1, «Авто»): ручной якорь в начале, дальше — автоякоря.
 * Трек (итоговая точка) не должен пропадать ни в одной эпохе, автоякоря должны появляться,
 * последний ручной якорь сохраняется.
 */
class AutoAnchorDrive2Test {
    @Test
    fun `трек идёт всю поездку, автоякорь продлевается`() {
        val epochs = javaClass.getResource("/drive2_20261104.csv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(",") }
            .groupBy { it[0].toLong() }
            .toSortedMap()
        val core = PhenixCore()
        core.anchorManager.motionMode = MotionMode.CAR
        var nulls = 0
        val autos = HashSet<Long>()
        var first = true
        for ((t, rows) in epochs) {
            val obs = rows.map { r ->
                val src = when (r[1]) { "G" -> PositionSource.GNSS; "N" -> PositionSource.NETWORK; else -> PositionSource.DEAD_RECKONING }
                PositionObservation(src, GeoPoint(r[2].toDouble(), r[3].toDouble()), r[4].toDouble().coerceAtLeast(1.0), t)
            }
            if (first) {
                val g = obs.first { it.source == PositionSource.GNSS }
                core.anchorManager.setAnchor(Anchor(g.point, 10.0, t))
                first = false
            }
            val s = core.solve(obs, t)
            if (s.point == null) nulls++
            s.anchor.anchor?.takeIf { it.isAuto }?.let { autos += it.timestampMillis }
            assertEquals(AnchorMode.MANUAL, s.anchor.lastHumanAnchor?.mode)
        }
        println("Эпох ${epochs.size}, без точки $nulls, автоякорей ${autos.size}")
        assertEquals(0, nulls, "трек прерывался")
        assertTrue(autos.size >= 3, "автоякорей: ${autos.size}")
    }

    @Test
    fun `автоякорь работает во всех режимах, но только после ручного`() {
        for (mode in MotionMode.entries) {
            val m = AnchorManager().apply { motionMode = mode }
            val p = GeoPoint(55.75, 37.6)
            for (i in 1..400) m.evaluate(p, 3.0, i * 1000L, sourcesHaveQuorum = true)
            assertEquals(null, m.anchor, "$mode: без ручного якоря автоякоря нет")
            m.setAnchor(Anchor(p, 10.0, 400_000))
            for (i in 401..800) m.evaluate(p, 3.0, i * 1000L, sourcesHaveQuorum = true)
            // 0.8.1: свежий ручной (доверенный) якорь слабым автоякорем не заменяется.
            assertEquals(AnchorMode.MANUAL, m.anchor?.mode, "$mode: свежий ручной якорь")
            // Устаревший окончательно (2 ч) — заменяется.
            for (i in 801..7700) m.evaluate(p, 3.0, i * 1000L, sourcesHaveQuorum = true)
            assertEquals(AnchorMode.AUTOMATIC, m.anchor?.mode, "$mode")
            assertEquals(AnchorMode.MANUAL, m.lastHumanAnchor?.mode, "$mode")
        }
    }

    @Test
    fun `при подозрении на подмену автоякорь не ставится`() {
        val m = AnchorManager().apply { motionMode = MotionMode.CAR }
        val p = GeoPoint(55.75, 37.6)
        m.setAnchor(Anchor(p, 10.0, 0))
        for (i in 1..400) m.evaluate(p, 3.0, i * 1000L, sourcesHaveQuorum = true, spoofSuspected = true)
        assertEquals(AnchorMode.MANUAL, m.anchor?.mode)
        for (i in 401..7700) m.evaluate(p, 3.0, i * 1000L, sourcesHaveQuorum = true)
        assertEquals(AnchorMode.AUTOMATIC, m.anchor?.mode)
    }
}

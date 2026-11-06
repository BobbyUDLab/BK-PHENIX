package ru.bkphenix.core.track

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.GeoPoint
import kotlin.math.abs

class TrackSmoothingTest {
    private val start = GeoPoint(55.70, 37.50)

    /** Отклонение от прямой движения на восток, м (север положителен). */
    private fun lateral(p: GeoPoint): Double {
        val d = start.distanceTo(p)
        return d * kotlin.math.cos(Math.toRadians(start.bearingTo(p)))
    }

    @Test
    fun `переключение между GNSS и сетью не рисует зигзаг`() {
        // Машина едет на восток 15 м/с; каждая третья эпоха — сеть со смещением 60 м поперёк (σ 30 м).
        val s = TrackSmoother()
        var maxLateral = 0.0
        for (i in 0 until 120) {
            val truth = start.offset(15.0 * i, 90.0)
            val raw = if (i % 3 == 2) TrackPoint(i * 1_000L, truth.offset(60.0, if (i % 2 == 0) 0.0 else 180.0), 30.0)
            else TrackPoint(i * 1_000L, truth, 3.0)
            val out = s.smooth(raw) ?: continue
            if (i > 5) maxLateral = maxOf(maxLateral, abs(lateral(out.point)))
        }
        assertTrue(maxLateral < 15.0, "поперечное отклонение $maxLateral м")
    }

    @Test
    fun `одиночный выброс пропускается, серия выбросов начинает новый отрезок`() {
        val s = TrackSmoother()
        for (i in 0 until 10) s.smooth(TrackPoint(i * 1_000L, start.offset(10.0 * i, 90.0), 3.0))
        assertNull(s.smooth(TrackPoint(10_000, start.offset(2_000.0, 0.0), 3.0)))
        s.smooth(TrackPoint(11_000, start.offset(110.0, 90.0), 3.0))
        val far = start.offset(5_000.0, 0.0)
        assertNull(s.smooth(TrackPoint(12_000, far, 3.0)))
        assertNull(s.smooth(TrackPoint(13_000, far, 3.0)))
        val third = s.smooth(TrackPoint(14_000, far, 3.0))!!
        assertTrue(s.restarted)
        assertEquals(far.latitude, third.point.latitude, 1e-9)
    }

    @Test
    fun `отрезки рвутся на перерыве и скачке, грубые участки отдельно`() {
        val pts = buildList {
            for (i in 0 until 5) add(TrackPoint(i * 1_000L, start.offset(10.0 * i, 90.0), 3.0, "OK"))
            for (i in 5 until 10) add(TrackPoint(i * 1_000L, start.offset(10.0 * i, 90.0), 60.0, "SPOOFING_SUSPECTED"))
            for (i in 10 until 13) add(TrackPoint(i * 1_000L, start.offset(10.0 * i, 90.0), 3.0, "OK"))
            add(TrackPoint(200_000, start.offset(130.0, 90.0), 3.0, "OK")) // перерыв 187 с
            add(TrackPoint(201_000, start.offset(140.0, 90.0), 3.0, "OK"))
            add(TrackPoint(202_000, start.offset(9_000.0, 90.0), 3.0, "OK")) // скачок
        }
        val seg = TrackSegments.split(pts)
        assertEquals(listOf(false, true, false, false), seg.map { it.coarse })
        // Стык качества — общая точка, линия не рвётся.
        assertEquals(seg[0].points.last(), seg[1].points.first())
        assertEquals(seg[1].points.last(), seg[2].points.first())
    }

    @Test
    fun `политика трека`() {
        assertTrue(TrackPolicy.isCoarse(TrackPoint(0, start, 40.0, "OK")))
        assertTrue(TrackPolicy.isCoarse(TrackPoint(0, start, 5.0, "SPOOFING_SUSPECTED")))
        assertTrue(!TrackPolicy.isCoarse(TrackPoint(0, start, 5.0, "INCONSISTENT")))
    }
}

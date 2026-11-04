package ru.bkphenix.core.track

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.GeoPoint

class MotionModelTest {
    private val start = GeoPoint(55.75, 37.62)

    private fun drive(m: MotionModel, speedMps: Double, seconds: Int) {
        for (i in 0..seconds) m.update(TrackPoint(i * 1000L, start.offset(speedMps * i, 90.0), 5.0))
    }

    @Test
    fun `в авто позиция ведётся по скорости при пропаже решения`() {
        val m = MotionModel(MotionMode.CAR)
        drive(m, 20.0, 10)
        val held = m.position(15_000)!!
        assertTrue(held.extrapolated)
        assertEquals(20.0, held.speedMps, 0.5)
        assertEquals(300.0, start.distanceTo(held.point), 5.0)
        assertTrue(held.accuracyM > 5.0)
        assertNull(m.position(26_000), "дольше holdMillis не держим")
    }

    @Test
    fun `без привязки не экстраполирует`() {
        val m = MotionModel(MotionMode.NONE)
        drive(m, 20.0, 5)
        assertNull(m.position(8_000))
        assertEquals(false, m.position(5_500)!!.extrapolated)
    }

    @Test
    fun `пешком скачок не портит скорость`() {
        val m = MotionModel(MotionMode.WALK)
        drive(m, 1.4, 5)
        m.update(TrackPoint(6_000, start.offset(500.0, 0.0), 5.0))
        assertTrue(m.position(6_000)!!.speedMps < 2.0)
    }
}

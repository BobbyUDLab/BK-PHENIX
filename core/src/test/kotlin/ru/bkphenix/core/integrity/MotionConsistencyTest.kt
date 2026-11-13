package ru.bkphenix.core.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.inertial.MotionSensing
import ru.bkphenix.core.inertial.MotionSummary
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import kotlin.math.PI
import kotlin.math.sin

class MotionConsistencyTest {
    private val start = GeoPoint(55.80, 37.50)
    private val walking = MotionSummary(steps = 18, cadenceHz = 1.8, stepIntervalCv = 0.08, stationaryS = 0.0, yawChangeDeg = 5.0)
    private val driving = MotionSummary(steps = 0, cadenceHz = 0.0, stepIntervalCv = null, stationaryS = 0.0, yawChangeDeg = 2.0)
    private val still = MotionSummary(steps = 0, cadenceHz = 0.0, stepIntervalCv = null, stationaryS = 60.0, yawChangeDeg = 0.0)

    private fun gnss(p: GeoPoint, t: Long) = PositionObservation(PositionSource.GNSS, p, 5.0, t)

    /** Подменённая точка: круг радиусом 1 км вокруг центра на 100 км/ч (как у Шереметьево). */
    private fun spoofCircle(i: Int): GeoPoint {
        val v = 100 / 3.6
        val angle = Math.toDegrees(v * i / 1000.0)
        return GeoPoint(55.97, 37.415).offset(1000.0, angle)
    }

    private fun run(n: Int, point: (Int) -> GeoPoint, motion: (Int) -> MotionSummary?): List<MotionCheck> {
        val m = MotionConsistencyMonitor()
        return (0 until n).map { i -> m.update(gnss(point(i), i * 1000L), motion(i), i * 1000L) }
    }

    @Test
    fun `прогулка с собакой, а ГНСС едет 100 км_ч по кругу — тревога и запрет ГНСС`() {
        val r = run(60, ::spoofCircle) { walking }
        val firstAlarm = r.indexOfFirst { it.report.state == IntegrityState.SPOOFING_SUSPECTED }
        assertTrue(firstAlarm in 5..15, "первая тревога на $firstAlarm")
        assertTrue(r.last().veto, "ГНСС должна быть запрещена")
        assertTrue(r.last().report.reasons.first().contains("шаги"))
    }

    @Test
    fun `телефон лежит, а ГНСС едет — тревога`() {
        val r = run(60, ::spoofCircle) { still }
        assertEquals(IntegrityState.SPOOFING_SUSPECTED, r.last().report.state)
    }

    @Test
    fun `честная прогулка 1,4 м_с — тревог нет`() {
        val r = run(300, { start.offset(1.4 * it, 30.0 + 20 * sin(it / 40.0)) }) { walking }
        assertTrue(r.none { it.report.state != IntegrityState.OK })
    }

    @Test
    fun `честная машина 60 км_ч с поворотом, который видит гироскоп — тревог нет`() {
        // Поворот на 90° за 20 с — гироскоп видит его же.
        var p = start
        var course = 0.0
        val pts = (0 until 200).map { i -> if (i in 100..119) course += 4.5; p = p.offset(16.7, course); p }
        val r = run(200, { pts[it] }) { i -> if (i in 105..140) driving.copy(yawChangeDeg = 90.0) else driving }
        assertTrue(r.none { it.report.state != IntegrityState.OK }, r.firstOrNull { it.report.state != IntegrityState.OK }?.report?.reasons?.toString())
    }

    @Test
    fun `машина едет прямо по гироскопу, а ГНСС кружит — тревога по повороту без запрета`() {
        val r = run(80, ::spoofCircle) { driving.copy(yawChangeDeg = 1.0) }
        assertTrue(r.any { it.report.reasons.any { s -> s.contains("гироскоп") } })
        assertFalse(r.last().veto)
    }

    @Test
    fun `без датчиков проверка не выполняется`() {
        val r = run(60, ::spoofCircle) { null }
        assertTrue(r.all { it.report.state == IntegrityState.OK })
    }

    @Test
    fun `датчик шагов — регулярная ходьба распознаётся, покой тоже`() {
        val s = MotionSensing()
        // 20 с ходьбы 1,8 шага/с: вертикальное ускорение — синус с амплитудой 2 м/с².
        val hz = 100
        for (k in 0 until 20 * hz) {
            val t = k * (1e9 / hz).toLong()
            val a = 9.81 + 2.0 * sin(2 * PI * 1.8 * k / hz)
            s.onSample(t, doubleArrayOf(0.0, 0.0, a), doubleArrayOf(0.1, 0.0, 0.0), 0.0)
        }
        val w = s.summary(20L * 1_000_000_000)!!
        assertTrue(w.walking, "ходьба не распознана: $w")
        for (k in 20 * hz until 60 * hz) {
            s.onSample(k * (1e9 / hz).toLong(), doubleArrayOf(0.0, 0.0, 9.81), doubleArrayOf(0.0, 0.0, 0.0), 0.0)
        }
        val still = s.summary(60L * 1_000_000_000)!!
        assertFalse(still.walking)
        assertTrue(still.stationaryS > 30, "покой: $still")
    }
}

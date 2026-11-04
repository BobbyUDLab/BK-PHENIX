package ru.bkphenix.core.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import java.util.Random
import kotlin.math.sin

class ContinuityTest {
    private val start = GeoPoint(55.75, 37.62)
    private val rnd = Random(7)

    private fun noisy(p: GeoPoint, sigma: Double): GeoPoint =
        p.offset(kotlin.math.abs(rnd.nextGaussian() * sigma * 1.0), rnd.nextDouble() * 360.0)

    /** Машина 150 км/ч с плавными поворотами: позиция на секунду t. */
    private fun car(t: Int): GeoPoint {
        var p = start
        for (i in 0 until t) p = p.offset(41.7, 90.0 + 40.0 * sin(i / 30.0))
        return p
    }

    @Test
    fun `честная машина 150 км в ч с шумом — без ложных тревог`() {
        val m = ContinuityMonitor()
        var truth = start
        repeat(600) { t ->
            truth = truth.offset(41.7, 90.0 + 40.0 * sin(t / 30.0))
            val r = m.check(listOf(PositionObservation(PositionSource.GNSS, noisy(truth, 5.0), 5.0, 0)), t * 1000L)
            assertEquals(IntegrityState.OK, r.report.state, "t=$t χ²=${r.innovationChi2}")
        }
    }

    @Test
    fun `честная пешая прогулка с сотой — без ложных тревог`() {
        val m = ContinuityMonitor()
        var truth = start
        repeat(900) { t ->
            truth = truth.offset(1.4, (t / 60) * 90.0)
            val obs = listOf(
                PositionObservation(PositionSource.GNSS, noisy(truth, 4.0), 4.0, 0),
                PositionObservation(PositionSource.CELL, noisy(truth, 100.0), 100.0, 0),
            )
            assertEquals(IntegrityState.OK, m.check(obs, t * 1000L).report.state, "t=$t")
        }
    }

    @Test
    fun `скачок на 500 м отсекается кинематикой`() {
        val core = PhenixCore()
        repeat(30) { t -> core.solve(listOf(PositionObservation(PositionSource.GNSS, car(t), 5.0, 0)), t * 1000L) }
        val jumped = PositionObservation(PositionSource.GNSS, car(30).offset(500.0, 0.0), 5.0, 0)
        val sol = core.solve(listOf(jumped), 30_000)
        assertEquals(IntegrityState.SPOOFING_SUSPECTED, sol.integrity.state)
        assertTrue(jumped in sol.comparison.rejected)
    }

    @Test
    fun `плавный увод 2 м в с от соты обнаруживается за 5 минут`() {
        val m = ContinuityMonitor()
        var detectedAt = -1
        for (t in 0 until 600) {
            val truth = start.offset(1.2 * t, 0.0)
            val spoofed = truth.offset(2.0 * t, 90.0)
            val obs = listOf(
                PositionObservation(PositionSource.GNSS, noisy(spoofed, 4.0), 4.0, 0),
                PositionObservation(PositionSource.CELL, noisy(truth, 100.0), 100.0, 0),
            )
            if (m.check(obs, t * 1000L).report.state == IntegrityState.SPOOFING_SUSPECTED) { detectedAt = t; break }
        }
        assertTrue(detectedAt in 1..300, "обнаружено на $detectedAt с")
    }

    @Test
    fun `после тоннеля фильтр стартует заново без тревоги`() {
        val m = ContinuityMonitor()
        repeat(10) { t -> m.check(listOf(PositionObservation(PositionSource.GNSS, car(t), 5.0, 0)), t * 1000L) }
        val r = m.check(listOf(PositionObservation(PositionSource.GNSS, car(70), 5.0, 0)), 70_000)
        assertEquals(IntegrityState.OK, r.report.state)
    }

    @Test
    fun `два решения в одну миллисекунду не дают невозможной скорости`() {
        val m = ContinuityMonitor()
        repeat(10) { t -> m.check(listOf(PositionObservation(PositionSource.GNSS, start, 3.0, 0)), t * 1000L) }
        val near = PositionObservation(PositionSource.GNSS_WLS, start.offset(4.0, 90.0), 3.0, 0)
        val r = m.check(listOf(near), 9_001)
        assertTrue(r.dropped.isEmpty(), "отсеяно: ${r.report.reasons}")
        assertEquals(IntegrityState.OK, r.report.state)
    }

    @Test
    fun `после ложного отсева фильтр восстанавливается при согласии с сетью`() {
        val m = ContinuityMonitor()
        repeat(10) { t -> m.check(listOf(PositionObservation(PositionSource.GNSS, start, 3.0, 0)), t * 1000L) }
        // Истинное положение в 2 км (например, устаревшая точка до рестарта); сеть подтверждает GNSS.
        val truth = start.offset(2_000.0, 45.0)
        var last: ContinuityResult? = null
        for (t in 10 until 20) {
            last = m.check(
                listOf(
                    PositionObservation(PositionSource.GNSS, truth, 3.0, 0),
                    PositionObservation(PositionSource.CELL, truth.offset(50.0, 0.0), 100.0, 0),
                ),
                t * 1000L,
            )
        }
        assertTrue(last!!.dropped.isEmpty(), "GNSS так и не вернулся")
    }

    @Test
    fun `подмена в 2 км против сети не восстанавливается`() {
        val m = ContinuityMonitor()
        repeat(10) { t -> m.check(listOf(PositionObservation(PositionSource.GNSS, start, 3.0, 0)), t * 1000L) }
        val fake = start.offset(2_000.0, 45.0)
        for (t in 10 until 30) {
            val r = m.check(
                listOf(
                    PositionObservation(PositionSource.GNSS, fake, 3.0, 0),
                    PositionObservation(PositionSource.CELL, start, 100.0, 0),
                ),
                t * 1000L,
            )
            assertTrue(r.dropped.isNotEmpty(), "t=$t: поддельный GNSS принят")
        }
    }

    @Test
    fun `дрейф счисления и сети не даёт ложного плавного увода`() {
        val m = ContinuityMonitor()
        var truth = start
        repeat(900) { t ->
            truth = truth.offset(1.2, 30.0)
            val obs = listOf(
                PositionObservation(PositionSource.GNSS, noisy(truth, 3.0), 3.0, 0),
                // Счисление уходит на 0,3 м/с, сеть с постоянным смещением 40 м при заявленных 10 м.
                PositionObservation(PositionSource.DEAD_RECKONING, truth.offset(0.3 * t, 120.0), 5.0, 0),
                PositionObservation(PositionSource.NETWORK, truth.offset(40.0, 200.0), 10.0, 0),
            )
            assertEquals(IntegrityState.OK, m.check(obs, t * 1000L).report.state, "t=$t")
        }
    }
}

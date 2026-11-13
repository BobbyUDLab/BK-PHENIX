package ru.bkphenix.core.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.inertial.DeadReckoningSource
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

/**
 * Холодный старт под подменой и кэшированный первый фикс (обезличенные сценарии по полевым журналам
 * 04–05.11.2026: Mate 70 Pro 02:48 и DOOGEE V Max 16:30). Ложная точка — в 15 км от реальной.
 */
class ColdStartSpoofTest {
    private val real = GeoPoint(55.70, 37.50)
    private val fake = real.offset(15_000.0, 340.0)

    private fun gnss(p: GeoPoint, t: Long) = PositionObservation(PositionSource.GNSS, p, 4.0, t)
    private fun network(p: GeoPoint, t: Long, sigma: Double = 40.0) = PositionObservation(PositionSource.NETWORK, p, sigma, t - 3_000)

    @Test
    fun `подмена с первой эпохи не становится итогом, если сеть показывает другое место`() {
        val core = PhenixCore()
        var nearFake = 0
        for (i in 0 until 60) {
            val t = 1_000_000L + i * 1_000
            val s = core.solve(listOf(gnss(fake.offset(i * 25.0, 180.0), t), network(real, t)), t)
            if (s.point!!.distanceTo(fake) < 5_000) nearFake++
            assertEquals(IntegrityState.SPOOFING_SUSPECTED, s.integrity.state, "эпоха $i")
        }
        assertEquals(0, nearFake, "итог у ложной точки")
    }

    @Test
    fun `после подмены на старте честная GNSS, совпадающая с сетью, принимается`() {
        val core = PhenixCore()
        for (i in 0 until 10) {
            val t = 1_000_000L + i * 1_000
            core.solve(listOf(gnss(fake, t), network(real, t)), t)
        }
        val t = 1_020_000L
        val s = core.solve(listOf(gnss(real, t), network(real, t)), t)
        assertTrue(s.comparison.accepted.any { it.source == PositionSource.GNSS })
        assertTrue(s.point!!.distanceTo(real) < 50)
    }

    @Test
    fun `кэшированный первый фикс не удерживает итог через счисление`() {
        // Контур приложения: прогноз счисления до решения, подтяжка после (PhenixRepository.solveEpoch).
        val core = PhenixCore()
        val dr = DeadReckoningSource()
        var rt = 0L
        var lastNearFake = -1
        for (i in 0 until 90) {
            val t = 1_000_000L + i * 1_000
            rt += 1_000
            val obs = buildList {
                if (i == 0) add(gnss(fake, t)) // кэшированная точка, затем GNSS пропадает
                if (i >= 3) add(network(real, t, sigma = 88.0))
                if (i >= 37) add(gnss(real, t)) // GPS вернулся в реальном месте
                dr.observation(rt, t)?.let(::add)
            }
            val s = core.solve(obs, t)
            dr.onSolution(s, rt)
            if (s.point?.let { it.distanceTo(fake) < 5_000 } == true) lastNearFake = i
        }
        assertTrue(lastNearFake <= 2, "итог у кэшированной точки до эпохи $lastNearFake")
    }

    @Test
    fun `сеть, отстающая в движении на сотни метров, не вытесняет GNSS при тревоге`() {
        // Поездка 04.11.2026 20:23: сеть старше на 6–20 с, расхождение 200–900 м — это не подмена.
        val core = PhenixCore()
        var networkChosen = 0
        for (i in 0 until 120) {
            val t = 1_000_000L + i * 1_000
            val here = real.offset(i * 20.0, 90.0)
            val lagging = real.offset(maxOf(0, i - 15) * 20.0, 90.0)
            val s = core.solve(listOf(gnss(here, t), PositionObservation(PositionSource.NETWORK, lagging, 35.0, t - 15_000)), t)
            if (s.comparison.accepted.none { it.source == PositionSource.GNSS }) networkChosen++
        }
        assertTrue(networkChosen <= 5, "итог уходил на устаревшую сеть $networkChosen раз")
    }
}

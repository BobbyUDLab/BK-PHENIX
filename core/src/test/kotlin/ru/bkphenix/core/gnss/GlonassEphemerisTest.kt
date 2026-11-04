package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.EcefCoordinate
import ru.bkphenix.core.model.GnssConstellation

class GlonassEphemerisTest {
    /** R19 из BRDC BKG за 02.11.2026: записи 00:15 и 00:45 UTC (км → м). */
    private fun r19(tbUtcSod: Double, clock: Double, x: DoubleArray, v: DoubleArray, a: DoubleArray) = GlonassEphemeris(
        sat = SatId(GnssConstellation.GLONASS, 19), frequencyChannel = 3, health = 0, tbWeek = 2443,
        tbTow = 86_400.0 + tbUtcSod + 18, clockBias = clock, relativeFrequencyBias = 1.818989403546e-12,
        position = EcefCoordinate(x[0] * 1e3, x[1] * 1e3, x[2] * 1e3),
        velocity = EcefCoordinate(v[0] * 1e3, v[1] * 1e3, v[2] * 1e3),
        acceleration = EcefCoordinate(a[0] * 1e3, a[1] * 1e3, a[2] * 1e3),
    )

    private val first = r19(
        900.0, 7.261056452990e-05,
        doubleArrayOf(1.111577392578e+04, -4.819082031250e+02, 2.295266162109e+04),
        doubleArrayOf(2.933712005615e-01, 3.127865791321e+00, -8.198261260986e-02),
        doubleArrayOf(0.0, 9.313225746155e-10, -1.862645149231e-09),
    )
    private val second = r19(
        2700.0, 7.261335849762e-05,
        doubleArrayOf(1.202799316406e+04, 5.003103027344e+03, 2.191945800781e+04),
        doubleArrayOf(7.057580947876e-01, 2.917624473572e+00, -1.058738708496e+00),
        doubleArrayOf(0.0, 1.862645149231e-09, -9.313225746155e-10),
    )

    @Test
    fun `на момент tb возвращается исходный вектор состояния`() {
        assertEquals(0.0, first.stateAt(first.tbTow).position.distanceTo(first.position), 1e-9)
    }

    @Test
    fun `прогноз на 30 минут сходится со следующей записью`() {
        val predicted = first.stateAt(second.tbTow).position
        val back = second.stateAt(first.tbTow).position
        // Две независимые закладки, разнесённые на 30 мин: расхождение — единицы метров.
        assertTrue(predicted.distanceTo(second.position) < 10.0, "вперёд ${predicted.distanceTo(second.position)} м")
        assertTrue(back.distanceTo(first.position) < 10.0, "назад ${back.distanceTo(first.position)} м")
        // Без J₂ ошибка была бы километровой: проверяем, что модель работает не случайно.
        assertTrue(first.position.distanceTo(second.position) > 5_000_000.0)
    }

    @Test
    fun `радиус орбиты около 25 500 км и часы идут по гамма`() {
        val s = first.stateAt(first.tbTow + 600)
        assertEquals(25_510_000.0, s.position.norm, 30_000.0)
        assertEquals(first.clockBias + 1.818989403546e-12 * 600, s.clockBiasS, 1e-15)
    }

    @Test
    fun `частота G1 по литере`() {
        assertEquals(1_603.6875e6, first.g1FrequencyHz, 1e-3)
    }
}

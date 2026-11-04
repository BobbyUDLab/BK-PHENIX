package ru.bkphenix.core.inertial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class MotionDetectorsTest {
    @Test
    fun `ZUPT — покой с шумом датчиков неподвижен, ходьба нет`() {
        val z = ZuptDetector()
        val rnd = Random(1)
        var still = false
        repeat(200) { still = z.update(doubleArrayOf(rnd.nextGaussian() * 0.03, 0.0, 9.81 + rnd.nextGaussian() * 0.03), WalkSignal.gyro(0.0, false)) }
        assertTrue(still)
        var moving = true
        for (i in 0 until 200) {
            val t = i / 100.0
            moving = !z.update(WalkSignal.accel(t), WalkSignal.gyro(t, true))
        }
        assertTrue(moving)
    }

    @Test
    fun `шаги — 18 шагов за 10 секунд и разумная длина`() {
        val d = StepDetector()
        val steps = (0 until 1000).mapNotNull { i -> d.update(i * 10_000_000L, WalkSignal.accel(i / 100.0)) }
        assertEquals(18.0, steps.size.toDouble(), 1.0)
        steps.drop(1).forEach { assertEquals(0.6, it.lengthM, 0.15) }
    }

    @Test
    fun `шаги — ошибка счёта не больше 5 % при разном темпе, силе шага и шуме`() {
        val rnd = Random(7)
        for (hz in listOf(1.4, 1.8, 2.3)) for (amp in listOf(1.8, 2.5, 3.5)) {
            val d = StepDetector()
            val seconds = 60
            val count = (0 until seconds * WalkSignal.RATE_HZ).count { i ->
                val a = WalkSignal.accel(i / 100.0, hz, amp).also { it[2] += rnd.nextGaussian() * 0.3 }
                d.update(i * 10_000_000L, a) != null
            }
            val truth = hz * seconds
            val error = kotlin.math.abs(count - truth) / truth
            assertTrue(error <= 0.05, "темп $hz Гц, амплитуда $amp: $count шагов из $truth (${"%.1f".format(error * 100)} %)")
        }
    }

    @Test
    fun `в покое шагов нет`() {
        val d = StepDetector()
        val rnd = Random(2)
        val steps = (0 until 1000).mapNotNull { i -> d.update(i * 10_000_000L, doubleArrayOf(0.0, 0.0, 9.81 + rnd.nextGaussian() * 0.1)) }
        assertFalse(steps.isNotEmpty())
    }
}

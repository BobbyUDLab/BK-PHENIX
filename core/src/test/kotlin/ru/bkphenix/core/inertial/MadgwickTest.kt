package ru.bkphenix.core.inertial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MadgwickTest {
    private val g = doubleArrayOf(0.0, 0.0, 9.81)
    private val zero = doubleArrayOf(0.0, 0.0, 0.0)

    private fun converge(f: MadgwickFilter, accel: DoubleArray, mag: DoubleArray?, seconds: Double = 60.0) {
        repeat((seconds * 100).toInt()) { f.update(zero, accel, mag, 0.01) }
    }

    private fun angleDiff(a: Double, b: Double) = ((a - b + 540) % 360) - 180

    @Test
    fun `курс по магнитометру — верх экрана на север и на запад`() {
        val f = MadgwickFilter(beta = 0.5)
        converge(f, g, doubleArrayOf(0.0, 22.0, -40.0))
        assertEquals(0.0, angleDiff(f.headingDeg, 0.0), 1.0)
        converge(f, g, doubleArrayOf(22.0, 0.0, -40.0))
        assertEquals(0.0, angleDiff(f.headingDeg, 270.0), 1.0)
    }

    @Test
    fun `вертикально стоящий телефон — сила тяжести в земной системе направлена вверх`() {
        val f = MadgwickFilter(beta = 0.5)
        val upright = doubleArrayOf(0.0, 9.81, 0.0)
        converge(f, upright, doubleArrayOf(0.0, -30.0, -30.0))
        val e = f.toEarth(upright)
        assertEquals(0.0, e[0], 0.05)
        assertEquals(0.0, e[1], 0.05)
        assertEquals(9.81, e[2], 0.05)
    }

    @Test
    fun `поворот по гироскопу без магнитометра`() {
        val f = MadgwickFilter(beta = 0.05)
        converge(f, g, doubleArrayOf(0.0, 22.0, -40.0))
        // 90° против часовой стрелки за 2 с: верх экрана смотрит на запад.
        val w = doubleArrayOf(0.0, 0.0, Math.PI / 4)
        repeat(200) { f.update(w, g, null, 0.01) }
        // Без магнитометра коррекция по акселерометру слегка сбивает курс при повороте.
        assertEquals(0.0, angleDiff(f.headingDeg, 270.0), 3.0)
        assertEquals(Math.PI / 4, f.verticalRate(w), 5e-3)
    }
}

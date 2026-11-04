package ru.bkphenix.core.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.random.Random

class ErrorCircleTest {
    @Test
    fun `радиус 95 процентов равен 2_448 сигмы`() {
        assertEquals(2.4477, ErrorCircle.radius(1.0), 1e-4)
        assertEquals(24.477, ErrorCircle.radius(10.0), 1e-3)
        // 39.3 % — «одна σ» для двумерного случая.
        assertEquals(10.0, ErrorCircle.radius(10.0, 1 - exp(-0.5)), 1e-9)
    }

    @Test
    fun `в круг 95 процентов попадает около 95 процентов точек`() {
        val rnd = Random(1)
        val r = ErrorCircle.radius(5.0)
        val n = 20_000
        val inside = (1..n).count {
            val e = rnd.nextGaussian(5.0)
            val nn = rnd.nextGaussian(5.0)
            e * e + nn * nn <= r * r
        }
        assertEquals(0.95, inside.toDouble() / n, 0.01)
    }

    private fun Random.nextGaussian(sigma: Double): Double {
        val u = nextDouble().coerceAtLeast(1e-12)
        return sigma * kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * nextDouble())
    }
}

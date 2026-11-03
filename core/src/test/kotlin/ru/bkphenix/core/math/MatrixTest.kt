package ru.bkphenix.core.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MatrixTest {
    @Test
    fun `обратная матрица даёт единичную`() {
        val m = Matrix(3, 3, doubleArrayOf(4.0, 7.0, 2.0, 3.0, 6.0, 1.0, 2.0, 5.0, 3.0))
        val p = m * m.inverse()!!
        for (r in 0 until 3) for (c in 0 until 3) assertEquals(if (r == c) 1.0 else 0.0, p[r, c], 1e-12)
    }

    @Test
    fun `вырожденная матрица не обращается`() {
        assertNull(Matrix(2, 2, doubleArrayOf(1.0, 2.0, 2.0, 4.0)).inverse())
    }

    @Test
    fun `транспонирование`() {
        val t = Matrix(2, 3, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)).transpose()
        assertEquals(3, t.rows)
        assertEquals(6.0, t[2, 1])
    }
}

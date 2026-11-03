package ru.bkphenix.core.math

import kotlin.math.abs

/** Небольшая плотная матрица для МНК в ГНСС (размеры до десятков). Хранение по строкам. */
public class Matrix(public val rows: Int, public val cols: Int, private val data: DoubleArray = DoubleArray(rows * cols)) {
    init {
        require(rows > 0 && cols > 0 && data.size == rows * cols) { "Неверный размер матрицы" }
    }

    public operator fun get(r: Int, c: Int): Double = data[r * cols + c]

    public operator fun set(r: Int, c: Int, value: Double) {
        data[r * cols + c] = value
    }

    public fun transpose(): Matrix = Matrix(cols, rows).also { t ->
        for (r in 0 until rows) for (c in 0 until cols) t[c, r] = this[r, c]
    }

    public operator fun times(o: Matrix): Matrix {
        require(cols == o.rows) { "Несогласованные размеры: ${rows}x$cols · ${o.rows}x${o.cols}" }
        val out = Matrix(rows, o.cols)
        for (r in 0 until rows) for (k in 0 until cols) {
            val a = this[r, k]
            if (a == 0.0) continue
            for (c in 0 until o.cols) out[r, c] += a * o[k, c]
        }
        return out
    }

    /** Обратная матрица методом Гаусса–Жордана с выбором главного элемента; `null`, если вырождена. */
    public fun inverse(): Matrix? {
        require(rows == cols) { "Обратная только для квадратной матрицы" }
        val n = rows
        val a = Matrix(n, n, data.copyOf())
        val inv = identity(n)
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r, col]) > abs(a[pivot, col])) pivot = r
            if (abs(a[pivot, col]) < SINGULAR_EPS) return null
            if (pivot != col) {
                a.swapRows(pivot, col)
                inv.swapRows(pivot, col)
            }
            val p = a[col, col]
            for (c in 0 until n) {
                a[col, c] /= p
                inv[col, c] /= p
            }
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r, col]
                if (f == 0.0) continue
                for (c in 0 until n) {
                    a[r, c] -= f * a[col, c]
                    inv[r, c] -= f * inv[col, c]
                }
            }
        }
        return inv
    }

    private fun swapRows(i: Int, j: Int) {
        for (c in 0 until cols) {
            val t = this[i, c]
            this[i, c] = this[j, c]
            this[j, c] = t
        }
    }

    public companion object {
        private const val SINGULAR_EPS = 1e-12

        public fun identity(n: Int): Matrix = Matrix(n, n).also { for (i in 0 until n) it[i, i] = 1.0 }

        public fun column(values: DoubleArray): Matrix = Matrix(values.size, 1, values.copyOf())
    }
}

package ru.bkphenix.core.inertial

import kotlin.math.sin

/** Синтетический сигнал ходьбы: вертикальное ускорение с частотой шагов и амплитудой [amplitude]. */
internal object WalkSignal {
    const val RATE_HZ = 100

    fun accel(t: Double, stepHz: Double = 1.8, amplitude: Double = 2.5): DoubleArray =
        doubleArrayOf(0.0, 0.0, 9.80665 + amplitude * sin(2 * Math.PI * stepHz * t))

    fun gyro(t: Double, moving: Boolean): DoubleArray =
        if (moving) doubleArrayOf(0.3 * sin(2 * Math.PI * 1.8 * t), 0.2 * sin(2 * Math.PI * 0.9 * t), 0.0) else doubleArrayOf(0.001, -0.002, 0.001)
}

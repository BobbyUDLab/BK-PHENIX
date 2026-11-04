package ru.bkphenix.core.inertial

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Детектор неподвижности для ZUPT: в скользящем окне СКО модуля ускорения и средняя угловая скорость
 * ниже порогов — устройство стоит, скорость можно считать нулевой.
 */
public class ZuptDetector(
    private val windowSize: Int = 50,
    private val accelStdThreshold: Double = 0.15,
    private val gyroThreshold: Double = 0.05,
) {
    private val accel = ArrayDeque<Double>()
    private val gyro = ArrayDeque<Double>()

    /** Добавляет отсчёт (ускорение м/с², угловая скорость рад/с); true — устройство неподвижно. */
    public fun update(accelMps2: DoubleArray, gyroRadS: DoubleArray): Boolean {
        accel.addLast(norm(accelMps2))
        gyro.addLast(norm(gyroRadS))
        if (accel.size > windowSize) {
            accel.removeFirst()
            gyro.removeFirst()
        }
        if (accel.size < windowSize) return false
        val mean = accel.average()
        val std = sqrt(accel.sumOf { (it - mean) * (it - mean) } / accel.size)
        return std < accelStdThreshold && gyro.average() < gyroThreshold
    }

    public fun reset() {
        accel.clear()
        gyro.clear()
    }
}

/** Шаг пешехода: время пика и оценка длины. */
public data class Step(val timeNanos: Long, val lengthM: Double)

/**
 * Детектор шагов для PDR: пики сглаженного модуля ускорения выше порога, не чаще [minIntervalNanos].
 * Длина шага — по Вайнбергу: K · (a_max − a_min)^¼ на интервале шага.
 */
public class StepDetector(
    private val peakThreshold: Double = 1.2,
    private val minIntervalNanos: Long = 300_000_000L,
    private val weinbergK: Double = 0.48,
    private val smoothing: Double = 0.25,
) {
    private var filtered = Double.NaN
    private var prev = 0.0
    private var rising = false
    private var lastStep: Long? = null
    private var min = Double.MAX_VALUE
    private var max = -Double.MAX_VALUE

    /** Добавляет отсчёт ускорения (м/с², с силой тяжести); возвращает шаг, если пик пройден. */
    public fun update(timeNanos: Long, accelMps2: DoubleArray): Step? {
        val a = norm(accelMps2) - GRAVITY
        filtered = if (filtered.isNaN()) a else filtered + smoothing * (a - filtered)
        min = minOf(min, filtered)
        max = maxOf(max, filtered)
        var step: Step? = null
        if (rising && filtered < prev && prev > peakThreshold && lastStep.let { it == null || timeNanos - it >= minIntervalNanos }) {
            val length = (weinbergK * (max - min).pow(0.25)).coerceIn(MIN_STEP_M, MAX_STEP_M)
            step = Step(timeNanos, length)
            lastStep = timeNanos
            min = filtered
            max = filtered
        }
        rising = filtered > prev
        prev = filtered
        return step
    }

    public fun reset() {
        filtered = Double.NaN
        prev = 0.0
        rising = false
        lastStep = null
        min = Double.MAX_VALUE
        max = -Double.MAX_VALUE
    }

    private companion object {
        const val GRAVITY = 9.80665
        const val MIN_STEP_M = 0.3
        const val MAX_STEP_M = 1.2
    }
}

internal fun norm(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])


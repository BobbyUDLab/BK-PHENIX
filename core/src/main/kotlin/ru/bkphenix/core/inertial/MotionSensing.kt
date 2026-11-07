package ru.bkphenix.core.inertial

import kotlin.math.sqrt

/** Что датчики знают о движении телефона за последние секунды — независимо от ГНСС. */
public data class MotionSummary(
    /** Шагов за последние [MotionSensing.STEP_WINDOW_S] с. */
    val steps: Int,
    /** Частота шагов, Гц (0, если шагов меньше двух). */
    val cadenceHz: Double,
    /** Коэффициент вариации интервалов между шагами: у ходьбы < 0,3, у тряски — больше. */
    val stepIntervalCv: Double?,
    /** Сколько секунд подряд телефон неподвижен (ZUPT), 0 — движется. */
    val stationaryS: Double,
    /** Поворот вокруг вертикали за интервал [yawFromS; yawToS] назад, градусы (по модулю), если есть данные. */
    val yawChangeDeg: Double?,
) {
    /** Регулярная ходьба: шаги с человеческой частотой и ритмом. */
    val walking: Boolean
        get() = steps >= MIN_STEPS && cadenceHz in MIN_CADENCE_HZ..MAX_CADENCE_HZ && (stepIntervalCv ?: 1.0) < MAX_CV

    public companion object {
        public const val MIN_STEPS: Int = 8
        public const val MIN_CADENCE_HZ: Double = 1.2
        public const val MAX_CADENCE_HZ: Double = 2.6
        public const val MAX_CV: Double = 0.3
    }
}

/**
 * Датчики движения для сверки с ГНСС: шаги, покой и поворот по гироскопу. Работает во всех режимах и
 * без инициализации счисления (в отличие от PDR, который считает шаги только «пешком»), потому что
 * режим, выбранный человеком, бывает неверным (прогулка 07.11.2026 в режиме «Авто»).
 * Время — монотонные наносекунды отсчётов ИИБ.
 */
public class MotionSensing {
    private val steps = StepDetector()
    private val zupt = ZuptDetector()
    private val stepTimes = ArrayDeque<Long>()
    private val yaw = ArrayDeque<Pair<Long, Double>>()
    private var cumYawDeg = 0.0
    private var stillSinceNanos: Long? = null
    private var lastNanos: Long? = null

    /** [verticalRateRadS] — угловая скорость вокруг вертикали (из ориентации), рад/с. */
    public fun onSample(timeNanos: Long, accel: DoubleArray, gyro: DoubleArray, verticalRateRadS: Double) {
        val prev = lastNanos
        lastNanos = timeNanos
        steps.update(timeNanos, accel)?.let { stepTimes.addLast(it.timeNanos) }
        val still = zupt.update(accel, gyro)
        stillSinceNanos = if (still) stillSinceNanos ?: timeNanos else null
        if (prev != null && timeNanos > prev) {
            val dt = (timeNanos - prev) / 1e9
            if (dt < MAX_GAP_S) cumYawDeg += Math.toDegrees(verticalRateRadS) * dt
        }
        yaw.addLast(timeNanos to cumYawDeg)
        trim(timeNanos)
    }

    private fun trim(now: Long) {
        while (stepTimes.isNotEmpty() && now - stepTimes.first() > STEP_WINDOW_S * NANOS) stepTimes.removeFirst()
        while (yaw.isNotEmpty() && now - yaw.first().first > YAW_KEEP_S * NANOS) yaw.removeFirst()
    }

    /** Сводка на момент [nowNanos]; null — отсчётов датчиков не было. */
    public fun summary(nowNanos: Long, yawFromS: Double = 25.0, yawToS: Double = 5.0): MotionSummary? {
        lastNanos ?: return null
        trim(nowNanos)
        val n = stepTimes.size
        val intervals = stepTimes.zipWithNext { a, b -> (b - a) / 1e9 }
        val cadence = if (n >= 2) (n - 1) / ((stepTimes.last() - stepTimes.first()) / 1e9).coerceAtLeast(1e-3) else 0.0
        val cv = if (intervals.size >= 3) {
            val m = intervals.average()
            sqrt(intervals.sumOf { (it - m) * (it - m) } / intervals.size) / m
        } else null
        val still = stillSinceNanos?.let { (nowNanos - it) / 1e9 } ?: 0.0
        fun yawAt(sAgo: Double): Double? {
            val target = nowNanos - (sAgo * NANOS).toLong()
            val first = yaw.firstOrNull() ?: return null
            if (first.first > target + TOLERANCE_NANOS) return null
            return yaw.lastOrNull { it.first <= target }?.second ?: first.second
        }
        val from = yawAt(yawFromS)
        val to = yawAt(yawToS)
        val yawChange = if (from != null && to != null) kotlin.math.abs(to - from) else null
        return MotionSummary(n, cadence, cv, still, yawChange)
    }

    public companion object {
        public const val STEP_WINDOW_S: Double = 10.0
        private const val YAW_KEEP_S = 40.0
        private const val MAX_GAP_S = 0.5
        private const val NANOS = 1e9
        private const val TOLERANCE_NANOS = 1_000_000_000L
    }
}

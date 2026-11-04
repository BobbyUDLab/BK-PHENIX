package ru.bkphenix.core.inertial

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.track.MotionMode

/** Отсчёт датчиков: время (нс, часы elapsedRealtime), ускорение м/с², угловая скорость рад/с, поле мкТл. */
public class ImuSample(
    public val timeNanos: Long,
    public val accel: DoubleArray,
    public val gyro: DoubleArray,
    public val mag: DoubleArray? = null,
)

/** Оценка счисления пути. */
public data class InertialEstimate(
    val point: GeoPoint,
    /** Горизонтальная погрешность 1σ, метры; растёт без ГНСС. */
    val sigmaM: Double,
    val speedMps: Double,
    val bearingDeg: Double?,
    /** Сколько прошло с последнего принятого решения ГНСС, мс. */
    val sinceFixMillis: Long,
    val stationary: Boolean,
)

/**
 * Счисление пути между решениями ГНСС: ориентация по Маджвику, ZUPT в покое, PDR (шаги по курсу
 * телефона) пешком, поворот скорости по гироскопу и запрет бокового скольжения в авто. Время всех
 * вызовов — одни часы (elapsedRealtime): ИИС в наносекундах, решения ГНСС и запросы — в миллисекундах.
 *
 * Пешком курс шага — курс верхнего края телефона: телефон держат перед собой. Если держат иначе
 * (в кармане), PDR даст неверное направление, но ГНСС после выхода из тени его исправит, а σ
 * честно растёт.
 */
public class InertialNavigator(mode: MotionMode = MotionMode.WALK) {
    public var mode: MotionMode = mode
        set(value) {
            field = value
            filter.accelNoise = accelNoise(value)
        }

    private val orientation = MadgwickFilter()
    private val zupt = ZuptDetector()
    private val steps = StepDetector()
    private var filter = NavigationFilter(accelNoise(mode))
    private var lastImuNanos: Long? = null
    private var lastStepNanos: Long? = null
    private var lastFixMillis: Long? = null
    private var lastConstraintNanos = 0L
    private var stationary = false
    private var distanceSinceFix = 0.0
    private val gyroBias = DoubleArray(3)
    private var headingOffsetDeg = 0.0
    private var headingOffsetSigmaDeg = INITIAL_HEADING_SIGMA_DEG
    private var alignAnchor: GeoPoint? = null
    private var stepSin = 0.0
    private var stepCos = 0.0
    private var stepCount = 0
    private var magSeen = false
    private var headingAligned = false
    private var stillSamples = 0

    /** Оценка смещения гироскопа, рад/с (обновляется в покое). */
    public val gyroBiasRadS: DoubleArray get() = gyroBias.copyOf()

    /** Откалиброван ли гироскоп: накоплено ≥ 2 с покоя. */
    public val gyroCalibrated: Boolean get() = stillSamples >= GYRO_CALIBRATED_SAMPLES

    /**
     * Магнитное склонение, градусы (восток положителен): истинный курс = магнитный + склонение.
     * Задаётся по позиции из модели WMM (на Android — GeomagneticField); 0 — курс остаётся магнитным.
     */
    public var declinationDeg: Double = 0.0

    /** Истинный курс оси y телефона, градусы от севера. */
    public val headingDeg: Double get() = normalizeDeg(orientation.headingDeg + declinationDeg)

    public fun onImu(s: ImuSample) {
        val prev = lastImuNanos
        lastImuNanos = s.timeNanos
        if (prev == null || s.timeNanos <= prev) return
        val dt = (s.timeNanos - prev) / 1e9
        if (dt > MAX_IMU_GAP_S) return
        if (s.mag != null) magSeen = true
        stationary = zupt.update(s.accel, s.gyro)
        // Калибровка смещения гироскопа в покое (ZUPT): в покое истинная угловая скорость ≈ 0.
        if (stationary) {
            for (k in 0..2) gyroBias[k] += GYRO_BIAS_GAIN * (s.gyro[k] - gyroBias[k])
            stillSamples++
        }
        val gyro = DoubleArray(3) { s.gyro[it] - gyroBias[it] }
        orientation.update(gyro, s.accel, s.mag, dt)
        if (!filter.isInitialized) return
        val ms = s.timeNanos / NANOS_PER_MILLI
        filter.predict(ms)
        distanceSinceFix += filter.speedMps * dt
        if (stationary) {
            filter.zeroVelocity()
            return
        }
        when (mode) {
            MotionMode.WALK -> steps.update(s.timeNanos, s.accel)?.let { step ->
                val period = lastStepNanos?.let { (step.timeNanos - it) / 1e9 }?.coerceIn(MIN_STEP_S, MAX_STEP_S) ?: TYPICAL_STEP_S
                lastStepNanos = step.timeNanos
                val raw = Math.toRadians(headingDeg)
                stepSin += kotlin.math.sin(raw); stepCos += kotlin.math.cos(raw); stepCount++
                filter.updateVelocity(step.lengthM / period, normalizeDeg(headingDeg + headingOffsetDeg), PDR_SPEED_SIGMA)
            }
            MotionMode.CAR -> {
                filter.rotateVelocity(orientation.verticalRate(s.gyro) * dt)
                if (filter.speedMps > MIN_CAR_SPEED && s.timeNanos - lastConstraintNanos >= CONSTRAINT_PERIOD_NANOS) {
                    filter.lateralConstraint(filter.bearingDeg)
                    lastConstraintNanos = s.timeNanos
                }
            }
            MotionMode.NONE -> Unit
        }
    }

    /** Решение ГНСС (или итог компаратора) с СКО [sigmaM]; true — принято фильтром. */
    public fun onFix(point: GeoPoint, sigmaM: Double, timeMillis: Long): Boolean {
        val ok = filter.updatePosition(point, sigmaM, timeMillis)
        if (ok) alignHeading(point, sigmaM)
        if (ok) {
            lastFixMillis = timeMillis
            distanceSinceFix = 0.0
        }
        return ok
    }

    /**
     * Выравнивание курса шагов по курсу ГНСС: телефон держат как угодно (в руке, в кармане), магнитометр
     * у домов искажён, без него курс по гироскопу произволен. Смещение «курс телефона → курс движения»
     * оценивается по участкам между решениями длиной ≥ [ALIGN_MIN_DISTANCE_M]; его σ входит в погрешность.
     */
    private fun alignHeading(point: GeoPoint, sigmaM: Double) {
        val anchor = alignAnchor
        // Без магнитометра курс телефона произволен: смещение неизвестно в пределах ±180°.
        if (!magSeen && !headingAligned) headingOffsetSigmaDeg = NO_MAG_HEADING_SIGMA_DEG
        if (anchor == null || stepCount == 0) {
            alignAnchor = point; stepSin = 0.0; stepCos = 0.0; stepCount = 0
            return
        }
        val d = anchor.distanceTo(point)
        if (d < ALIGN_MIN_DISTANCE_M.coerceAtLeast(4 * sigmaM)) return
        if (stepCount >= ALIGN_MIN_STEPS) {
            val course = anchor.bearingTo(point)
            val phone = Math.toDegrees(kotlin.math.atan2(stepSin, stepCos))
            val innovation = wrap180(course - phone - headingOffsetDeg)
            val courseSigma = Math.toDegrees(kotlin.math.atan2(kotlin.math.sqrt(2.0) * sigmaM, d))
            val k = headingOffsetSigmaDeg * headingOffsetSigmaDeg /
                (headingOffsetSigmaDeg * headingOffsetSigmaDeg + courseSigma * courseSigma)
            headingOffsetDeg = wrap180(headingOffsetDeg + k * innovation)
            headingAligned = true
            headingOffsetSigmaDeg = kotlin.math.sqrt((1 - k) * headingOffsetSigmaDeg * headingOffsetSigmaDeg)
                .coerceAtLeast(MIN_HEADING_SIGMA_DEG)
        }
        alignAnchor = point; stepSin = 0.0; stepCos = 0.0; stepCount = 0
    }

    /** Смещение курса движения относительно курса телефона, градусы, и его σ. */
    public val headingOffset: Pair<Double, Double> get() = headingOffsetDeg to headingOffsetSigmaDeg

    /** Оценка на момент [nowMillis] или null, если не было решений или погрешность выросла выше предела режима. */
    public fun estimate(nowMillis: Long): InertialEstimate? {
        if (!filter.isInitialized || mode == MotionMode.NONE) return null
        filter.predict(nowMillis)
        // Ошибки PDR и гироскопа коррелированы (смещение курса, масштаб шага): фильтр, считающий
        // отсчёты независимыми, их недооценивает, поэтому добавляем дрейф, пропорциональный пути.
        // Плюс ошибка курса: σ смещения курса и дрейф гироскопа без магнитометра.
        val headingSigma = if (mode == MotionMode.WALK) Math.toRadians(headingOffsetSigmaDeg) else 0.0
        val drift = kotlin.math.hypot(DRIFT_PER_METER * distanceSinceFix, distanceSinceFix * kotlin.math.sin(headingSigma.coerceAtMost(Math.PI / 2)))
        val sigma = kotlin.math.sqrt(filter.sigmaM * filter.sigmaM + drift * drift)
        if (sigma > maxSigma(mode)) return null
        val point = filter.point ?: return null
        val moving = filter.speedMps > MIN_MOVING_MPS
        return InertialEstimate(
            point, sigma, filter.speedMps, if (moving) filter.bearingDeg else null,
            nowMillis - (lastFixMillis ?: nowMillis), stationary,
        )
    }

    public fun reset() {
        orientation.reset(); zupt.reset(); steps.reset()
        lastImuNanos = null; lastStepNanos = null; lastFixMillis = null; distanceSinceFix = 0.0
        gyroBias.fill(0.0); stillSamples = 0
        headingOffsetDeg = 0.0; headingOffsetSigmaDeg = INITIAL_HEADING_SIGMA_DEG
        alignAnchor = null; stepSin = 0.0; stepCos = 0.0; stepCount = 0
        magSeen = false; headingAligned = false
        filter = NavigationFilter(accelNoise(mode))
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val GYRO_BIAS_GAIN = 0.01
        const val INITIAL_HEADING_SIGMA_DEG = 30.0
        const val MIN_HEADING_SIGMA_DEG = 3.0
        const val NO_MAG_HEADING_SIGMA_DEG = 90.0
        const val ALIGN_MIN_DISTANCE_M = 15.0
        const val ALIGN_MIN_STEPS = 4
        const val GYRO_CALIBRATED_SAMPLES = 200
        const val MAX_IMU_GAP_S = 0.5
        const val MIN_STEP_S = 0.3
        const val MAX_STEP_S = 1.5
        const val TYPICAL_STEP_S = 0.55
        const val PDR_SPEED_SIGMA = 0.3
        const val MIN_CAR_SPEED = 2.0
        const val MIN_MOVING_MPS = 0.5
        const val CONSTRAINT_PERIOD_NANOS = 100_000_000L

        /** Дрейф счисления: 8 % пройденного без ГНСС пути (1σ). */
        const val DRIFT_PER_METER = 0.08

        fun accelNoise(mode: MotionMode): Double = when (mode) {
            MotionMode.WALK -> 0.5
            MotionMode.CAR -> 2.0
            MotionMode.NONE -> 1.0
        }

        fun maxSigma(mode: MotionMode): Double = when (mode) {
            MotionMode.WALK -> 50.0
            MotionMode.CAR -> 150.0
            MotionMode.NONE -> 0.0
        }
    }
}

private fun wrap180(d: Double): Double = ((d + 540.0) % 360.0) - 180.0

private fun normalizeDeg(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

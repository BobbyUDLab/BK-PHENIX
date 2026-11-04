package ru.bkphenix.core.inertial

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Фильтр ориентации Маджвика (S. Madgwick, 2010): гироскоп интегрируется в кватернион, градиентный
 * шаг по ошибке направления силы тяжести (акселерометр) убирает дрейф наклона, а курс подтягивается
 * к магнитному отдельной комплементарной поправкой [magneticGain] вокруг вертикали (наклон не
 * зависит от магнитных помех, как в варианте Маджвика 2011 г.).
 *
 * Оси датчиков — как в Android (x вправо, y вверх по экрану, z из экрана). Земная система фильтра:
 * x — магнитный север, y — запад, z — вверх. Кватернион [q] поворачивает векторы из осей
 * устройства в земные. Магнитное склонение не учитывается: курс магнитный.
 *
 * @param magneticGain скорость подтягивания курса к магнитному, 1/с.
 * @param beta вес коррекции по акселерометру, рад/с. Больше — быстрее сходится и
 * сильнее шумит; 0.1 — типовое значение для гироскопов смартфонов.
 */
public class MadgwickFilter(public var beta: Double = 0.1, public var magneticGain: Double = 0.5) {
    private var q0 = 1.0
    private var q1 = 0.0
    private var q2 = 0.0
    private var q3 = 0.0

    /** Кватернион ориентации [w, x, y, z]. */
    public val quaternion: DoubleArray get() = doubleArrayOf(q0, q1, q2, q3)

    public fun reset() {
        q0 = 1.0; q1 = 0.0; q2 = 0.0; q3 = 0.0
    }

    /**
     * Шаг фильтра. Гироскоп — рад/с, акселерометр — любые единицы (нормируется), магнитометр — любые
     * единицы или `null`, если его нет (тогда курс держится только по гироскопу).
     */
    public fun update(gyro: DoubleArray, accel: DoubleArray, mag: DoubleArray?, dt: Double) {
        if (dt <= 0) return
        val (gx, gy, gz) = Triple(gyro[0], gyro[1], gyro[2])
        var qDot0 = 0.5 * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot1 = 0.5 * (q0 * gx + q2 * gz - q3 * gy)
        var qDot2 = 0.5 * (q0 * gy - q1 * gz + q3 * gx)
        var qDot3 = 0.5 * (q0 * gz + q1 * gy - q2 * gx)
        val an = norm(accel)
        if (an > 0) {
            val ax = accel[0] / an
            val ay = accel[1] / an
            val az = accel[2] / an
            val s = imuStep(ax, ay, az)
            val sn = sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2] + s[3] * s[3])
            if (sn > 0) {
                qDot0 -= beta * s[0] / sn
                qDot1 -= beta * s[1] / sn
                qDot2 -= beta * s[2] / sn
                qDot3 -= beta * s[3] / sn
            }
        }
        q0 += qDot0 * dt
        q1 += qDot1 * dt
        q2 += qDot2 * dt
        q3 += qDot3 * dt
        normalize()
        if (mag != null && norm(mag) > 0) correctHeading(mag, dt)
    }

    /**
     * Коррекция курса по магнитометру поворотом вокруг земной вертикали: горизонтальная проекция поля
     * должна смотреть на север. Наклон от магнитометра не зависит — магнитные помехи не искажают его.
     */
    private fun correctHeading(mag: DoubleArray, dt: Double) {
        val h = toEarth(mag)
        if (h[0] * h[0] + h[1] * h[1] < 1e-12) return
        val theta = -magneticGain * atan2(h[1], h[0]) * dt
        val c = kotlin.math.cos(theta / 2)
        val s = kotlin.math.sin(theta / 2)
        // q ← q_z(θ) ⊗ q.
        val n0 = c * q0 - s * q3
        val n1 = c * q1 - s * q2
        val n2 = c * q2 + s * q1
        val n3 = c * q3 + s * q0
        q0 = n0; q1 = n1; q2 = n2; q3 = n3
        normalize()
    }

    private fun normalize() {
        val n = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        q0 /= n; q1 /= n; q2 /= n; q3 /= n
    }

    /** Вектор из осей устройства в земные (север, запад, верх). */
    public fun toEarth(v: DoubleArray): DoubleArray {
        val (x, y, z) = Triple(v[0], v[1], v[2])
        return doubleArrayOf(
            (1 - 2 * (q2 * q2 + q3 * q3)) * x + 2 * (q1 * q2 - q0 * q3) * y + 2 * (q1 * q3 + q0 * q2) * z,
            2 * (q1 * q2 + q0 * q3) * x + (1 - 2 * (q1 * q1 + q3 * q3)) * y + 2 * (q2 * q3 - q0 * q1) * z,
            2 * (q1 * q3 - q0 * q2) * x + 2 * (q2 * q3 + q0 * q1) * y + (1 - 2 * (q1 * q1 + q2 * q2)) * z,
        )
    }

    /** Магнитный курс оси y устройства (верх экрана), градусы от севера по часовой стрелке, 0…360. */
    public val headingDeg: Double
        get() {
            val f = toEarth(doubleArrayOf(0.0, 1.0, 0.0))
            return (Math.toDegrees(atan2(-f[1], f[0])) + 360.0) % 360.0
        }

    /** Скорость поворота вокруг земной вертикали, рад/с (положительная — против часовой стрелки). */
    public fun verticalRate(gyro: DoubleArray): Double = toEarth(gyro)[2]

    private fun imuStep(ax: Double, ay: Double, az: Double): DoubleArray {
        val q0q0 = q0 * q0
        val q1q1 = q1 * q1
        val q2q2 = q2 * q2
        val q3q3 = q3 * q3
        return doubleArrayOf(
            4 * q0 * q2q2 + 2 * q2 * ax + 4 * q0 * q1q1 - 2 * q1 * ay,
            4 * q1 * q3q3 - 2 * q3 * ax + 4 * q0q0 * q1 - 2 * q0 * ay - 4 * q1 + 8 * q1 * q1q1 + 8 * q1 * q2q2 + 4 * q1 * az,
            4 * q0q0 * q2 + 2 * q0 * ax + 4 * q2 * q3q3 - 2 * q3 * ay - 4 * q2 + 8 * q2 * q1q1 + 8 * q2 * q2q2 + 4 * q2 * az,
            4 * q1q1 * q3 - 2 * q1 * ax + 4 * q2q2 * q3 - 2 * q2 * ay,
        )
    }

    private fun norm(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
}

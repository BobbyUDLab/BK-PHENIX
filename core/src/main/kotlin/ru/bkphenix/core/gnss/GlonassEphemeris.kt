package ru.bkphenix.core.gnss

import ru.bkphenix.core.geo.EcefCoordinate
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Эфемериды ГЛОНАСС (ИКД ГЛОНАСС, ред. 5.1, прил. А.3.1.2): вектор состояния в ПЗ-90.11 на
 * момент tb и лунно-солнечные ускорения. Положение на другой момент получается интегрированием
 * уравнений движения (центральное поле + J₂ + вращение Земли) методом Рунге–Кутты 4-го порядка.
 *
 * ПЗ-90.11 и WGS-84 (G1762) совпадают на уровне сантиметров, преобразование не нужно.
 * Время tb хранится в шкале GPS ([tbWeek], [tbTow]): из UTC оно переводится при разборе.
 */
public data class GlonassEphemeris(
    override val sat: SatId,
    /** Литера частоты k (−7 … +6): f(G1) = 1602 + 0,5625·k МГц. */
    val frequencyChannel: Int,
    val health: Int,
    val tbWeek: Int,
    val tbTow: Double,
    /** −τn: смещение часов спутника, секунды. */
    val clockBias: Double,
    /** γn: относительный уход частоты. */
    val relativeFrequencyBias: Double,
    /** Положение (м), скорость (м/с), лунно-солнечное ускорение (м/с²) на tb. */
    val position: EcefCoordinate,
    val velocity: EcefCoordinate,
    val acceleration: EcefCoordinate,
) : NavEphemeris {
    override val isHealthy: Boolean get() = health == 0
    override val referenceWeek: Int get() = tbWeek
    override val referenceTow: Double get() = tbTow
    override val fitHalfWidthS: Double get() = FIT_HALF_WIDTH_S

    /** Несущая G1, Гц. */
    public val g1FrequencyHz: Double get() = G1_BASE_HZ + frequencyChannel * G1_STEP_HZ

    override fun stateAt(gpsTow: Double): SatelliteState {
        val dt = GpsTime.wrapWeek(gpsTow - tbTow)
        var s = doubleArrayOf(position.x, position.y, position.z, velocity.x, velocity.y, velocity.z)
        var done = 0.0
        val sign = if (dt >= 0) 1.0 else -1.0
        while (abs(dt - done) > 1e-9) {
            val h = sign * min(STEP_S, abs(dt - done))
            s = rk4(s, h)
            done += h
        }
        return SatelliteState(EcefCoordinate(s[0], s[1], s[2]), clockBias + relativeFrequencyBias * dt)
    }

    private fun rk4(s: DoubleArray, h: Double): DoubleArray {
        val k1 = derivative(s)
        val k2 = derivative(add(s, k1, h / 2))
        val k3 = derivative(add(s, k2, h / 2))
        val k4 = derivative(add(s, k3, h))
        return DoubleArray(6) { i -> s[i] + h / 6 * (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]) }
    }

    private fun add(s: DoubleArray, k: DoubleArray, h: Double) = DoubleArray(6) { s[it] + h * k[it] }

    private fun derivative(s: DoubleArray): DoubleArray {
        val (x, y, z) = Triple(s[0], s[1], s[2])
        val (vx, vy, vz) = Triple(s[3], s[4], s[5])
        val r2 = x * x + y * y + z * z
        val r = sqrt(r2)
        val mur3 = MU / (r2 * r)
        val j = 1.5 * J2 * MU * AE * AE / (r2 * r2 * r)
        val z2r2 = 5 * z * z / r2
        val w2 = OMEGA_E * OMEGA_E
        return doubleArrayOf(
            vx, vy, vz,
            -mur3 * x - j * x * (1 - z2r2) + w2 * x + 2 * OMEGA_E * vy + acceleration.x,
            -mur3 * y - j * y * (1 - z2r2) + w2 * y - 2 * OMEGA_E * vx + acceleration.y,
            -mur3 * z - j * z * (3 - z2r2) + acceleration.z,
        )
    }

    public companion object {
        /** Параметры ПЗ-90.11. */
        public const val MU: Double = 398_600.4418e9
        public const val AE: Double = 6_378_136.0
        public const val J2: Double = 1_082.62575e-6
        public const val OMEGA_E: Double = 7.292115e-5

        public const val G1_BASE_HZ: Double = 1_602.0e6
        public const val G1_STEP_HZ: Double = 0.5625e6

        /** Эфемериды обновляются каждые 30 мин, применимы ±15 мин; запас до 20 мин. */
        public const val FIT_HALF_WIDTH_S: Double = 1_200.0
        private const val STEP_S = 30.0
    }
}

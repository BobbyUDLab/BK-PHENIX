package ru.bkphenix.core.terrain

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Барометрический высотомер с калибровкой смещения. Барометр хорошо меряет перепады высоты, но
 * абсолютную высоту даёт с ошибкой в десятки метров из-за погоды, поэтому смещение калибруется по
 * опоре: высоте рельефа под позицией (телефон на земле, пешком/авто) или по якорю. Высота ГНСС для
 * калибровки не используется никогда — иначе подмена высоты ГНСС «откалибровала» бы барометр.
 *
 * Неопределённость смещения растёт со временем (дрейф давления ~1 гПа/ч ≈ 8 м/ч).
 */
public class BarometricAltimeter(private val driftMps: Double = DRIFT_M_PER_S) {
    private var offsetM: Double? = null
    private var varianceM2 = 0.0
    private var atMillis = 0L
    private var lastPressureAltM: Double? = null
    private var lastSource: CalibrationSource? = null

    public enum class CalibrationSource(public val displayName: String) { TERRAIN("по рельефу"), ANCHOR("по якорю") }

    /** Новое показание давления, гПа. */
    public fun onPressure(hPa: Double) {
        lastPressureAltM = pressureAltitudeM(hPa)
    }

    /**
     * Учесть опорную ортометрическую высоту [referenceM] ± [sigmaM] для текущего показания.
     * Слияние по весам (фильтр Калмана на одно смещение).
     */
    public fun calibrate(referenceM: Double, sigmaM: Double, nowMillis: Long, source: CalibrationSource) {
        val p = lastPressureAltM ?: return
        val measured = referenceM - p
        val r = sigmaM * sigmaM
        val current = offsetM
        if (current == null) {
            offsetM = measured; varianceM2 = r
        } else {
            val prior = varianceAt(nowMillis)
            val k = prior / (prior + r)
            offsetM = current + k * (measured - current)
            varianceM2 = (1 - k) * prior
        }
        atMillis = nowMillis
        if (source == CalibrationSource.ANCHOR || lastSource == null || varianceM2 >= r * 0.5) lastSource = source
    }

    public fun reset() { offsetM = null; lastSource = null }

    /** Откалиброванная ортометрическая высота и её σ, или null, если нет давления или калибровки. */
    public fun altitude(nowMillis: Long): Altitude? {
        val p = lastPressureAltM ?: return null
        val o = offsetM ?: return null
        return Altitude(p + o, sqrt(varianceAt(nowMillis) + SENSOR_SIGMA_M * SENSOR_SIGMA_M), lastSource!!)
    }

    private fun varianceAt(nowMillis: Long): Double {
        val dt = (nowMillis - atMillis).coerceAtLeast(0) / 1000.0
        return varianceM2 + driftMps * driftMps * dt
    }

    public data class Altitude(val heightM: Double, val sigmaM: Double, val source: CalibrationSource)

    public companion object {
        /** Случайное блуждание смещения: σ ≈ 8 м за час → 8/√3600 м/√с. */
        public const val DRIFT_M_PER_S: Double = 8.0 / 60.0
        public const val SENSOR_SIGMA_M: Double = 1.0
        public const val STANDARD_HPA: Double = 1013.25

        /** Высота по стандартной атмосфере, м (как SensorManager.getAltitude). */
        public fun pressureAltitudeM(hPa: Double): Double = 44330.0 * (1.0 - (hPa / STANDARD_HPA).pow(1.0 / 5.255))
    }
}

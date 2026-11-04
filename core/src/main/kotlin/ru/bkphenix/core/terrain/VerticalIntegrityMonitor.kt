package ru.bkphenix.core.terrain

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.integrity.IntegrityReport
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.track.MotionMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Итог вертикальной проверки одной эпохи — для экрана и журнала. */
public data class VerticalCheck(
    /** Высота ГНСС над геоидом, м (эллипсоидальная минус N EGM2008). */
    val gnssOrthometricM: Double?,
    /** Высота рельефа под позицией, м. */
    val terrainM: Double?,
    /** Высота геоида N, м. */
    val geoidM: Double?,
    /** Откалиброванная высота барометра, м. */
    val barometric: BarometricAltimeter.Altitude?,
    /** Опорная высота, с которой сравнивается ГНСС, и её σ. */
    val referenceM: Double?,
    val referenceSigmaM: Double?,
    /** ГНСС − опора, м. */
    val residualM: Double?,
    val thresholdM: Double?,
    val report: IntegrityReport,
)

/**
 * Детектор расхождения высоты ГНСС с рельефом и барометром (шаг 5c).
 *
 * Опора — откалиброванный барометр, а пока его нет — рельеф под позицией (только пешком и в авто:
 * в режиме «без привязки» телефон может быть на этаже). Если |H_ГНСС − опора| > max(3σ, [MIN_THRESHOLD_M])
 * [CONSECUTIVE] эпох подряд — состояние «подозрительно»: подмена ГНСС часто выдаёт себя высотой.
 *
 * Барометр калибруется по рельефу (не чаще раза в [TERRAIN_CALIBRATION_MS], пешком и в авто, когда
 * горизонталь не под подозрением) и по якорю ([calibrateAtAnchor]); по высоте ГНСС — никогда.
 */
public class VerticalIntegrityMonitor(
    private val terrain: TerrainModel,
    public val barometer: BarometricAltimeter = BarometricAltimeter(),
    private val consecutive: Int = CONSECUTIVE,
) {
    private var count = 0
    private var lastTerrainCalibration: Long? = null

    public fun onPressure(hPa: Double): Unit = barometer.onPressure(hPa)

    /** Калибровка барометра по якорю: пользователь стоит в известной точке на земле. */
    public fun calibrateAtAnchor(anchor: GeoPoint, nowMillis: Long): Boolean {
        val h = terrain.elevationM(anchor) ?: return false
        barometer.calibrate(h + PHONE_HEIGHT_M, ANCHOR_SIGMA_M, nowMillis, BarometricAltimeter.CalibrationSource.ANCHOR)
        return true
    }

    /**
     * @param point горизонтальная позиция решения; [gnssEllipsoidalM] — высота ГНСС над эллипсоидом
     * (Location.getAltitude), [gnssVerticalSigmaM] — её σ (verticalAccuracyMeters, если есть).
     * @param horizontalTrusted решение эпохи без подозрений — можно калибровать барометр по рельефу.
     */
    public fun update(
        point: GeoPoint?,
        gnssEllipsoidalM: Double?,
        gnssVerticalSigmaM: Double?,
        mode: MotionMode,
        horizontalTrusted: Boolean,
        nowMillis: Long,
    ): VerticalCheck {
        val terrainM = point?.let(terrain::elevationM)
        val geoidM = point?.let(terrain::geoidM)
        val onGround = mode != MotionMode.NONE
        if (onGround && horizontalTrusted && terrainM != null && (lastTerrainCalibration?.let { nowMillis - it >= TERRAIN_CALIBRATION_MS } ?: true)) {
            barometer.calibrate(terrainM + PHONE_HEIGHT_M, TerrainModel.TERRAIN_SIGMA_M, nowMillis, BarometricAltimeter.CalibrationSource.TERRAIN)
            lastTerrainCalibration = nowMillis
        }
        val baro = barometer.altitude(nowMillis)
        val (ref, refSigma) = when {
            baro != null -> baro.heightM to baro.sigmaM
            onGround && terrainM != null -> (terrainM + PHONE_HEIGHT_M) to TerrainModel.TERRAIN_SIGMA_M
            else -> null to null
        }
        val gnssH = if (gnssEllipsoidalM != null && geoidM != null) gnssEllipsoidalM - geoidM else null
        if (gnssH == null || ref == null || refSigma == null) {
            count = 0
            return VerticalCheck(gnssH, terrainM, geoidM, baro, ref, refSigma, null, null, IntegrityReport.OK)
        }
        val residual = gnssH - ref
        val sg = gnssVerticalSigmaM ?: DEFAULT_GNSS_VERTICAL_SIGMA_M
        val threshold = max(SIGMAS * sqrt(sg * sg + refSigma * refSigma), MIN_THRESHOLD_M)
        count = if (abs(residual) > threshold) count + 1 else 0
        val report = if (count < consecutive) IntegrityReport.OK else {
            val what = if (baro != null) "барометра (${baro.source.displayName})" else "рельефа"
            val dir = if (residual > 0) "выше" else "ниже"
            IntegrityReport(
                IntegrityState.SUSPICIOUS,
                listOf("Высота ГНСС на ${abs(residual).roundToInt()} м $dir $what (порог ${threshold.roundToInt()} м): возможна подмена ГНСС"),
            )
        }
        return VerticalCheck(gnssH, terrainM, geoidM, baro, ref, refSigma, residual, threshold, report)
    }

    public companion object {
        public const val CONSECUTIVE: Int = 3
        public const val SIGMAS: Double = 3.0
        public const val MIN_THRESHOLD_M: Double = 25.0
        public const val DEFAULT_GNSS_VERTICAL_SIGMA_M: Double = 15.0
        public const val PHONE_HEIGHT_M: Double = 1.0
        public const val ANCHOR_SIGMA_M: Double = 4.0
        public const val TERRAIN_CALIBRATION_MS: Long = 60_000
    }
}

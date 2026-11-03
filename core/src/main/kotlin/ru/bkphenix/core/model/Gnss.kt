package ru.bkphenix.core.model

/** Спутниковые системы. */
public enum class GnssConstellation(public val displayName: String) {
    GPS("GPS"),
    GLONASS("ГЛОНАСС"),
    GALILEO("Galileo"),
    BEIDOU("BeiDou"),
    QZSS("QZSS"),
    SBAS("SBAS"),
    IRNSS("NavIC"),
    UNKNOWN("Неизвестно"),
}

/** Частотные диапазоны ГНСС. */
public enum class GnssBand(public val centerFrequencyMhz: Double) {
    L1(1575.42),
    L2(1227.60),
    L5(1176.45),
    G1(1602.00),
    G2(1246.00),
    E5B(1207.14),
    B1I(1561.098),
    UNKNOWN(0.0),
}

/** Состояние одного спутника в текущей эпохе. */
public data class SatelliteStatus(
    val constellation: GnssConstellation,
    val svid: Int,
    val band: GnssBand,
    val cn0DbHz: Double,
    val elevationDeg: Double,
    val azimuthDeg: Double,
    val usedInFix: Boolean,
)

/** Сведения о ГНСС-чипе устройства. */
public data class GnssReceiverInfo(
    val hardwareModel: String?,
    val supportsRawMeasurements: Boolean,
    val supportsNavigationMessages: Boolean,
    val supportedBands: Set<GnssBand>,
) {
    val isDualFrequency: Boolean get() = GnssBand.L5 in supportedBands || GnssBand.E5B in supportedBands
}

/**
 * Время ГНСС, приведённое к единой шкале GPS.
 *
 * @property gpsWeek полная неделя GPS (без переполнения на 1024).
 * @property timeOfWeekSeconds секунды от начала недели.
 */
public data class GnssTime(val gpsWeek: Int, val timeOfWeekSeconds: Double) {
    init {
        require(gpsWeek >= 0) { "Неделя GPS не может быть отрицательной" }
        require(timeOfWeekSeconds >= 0.0 && timeOfWeekSeconds < SECONDS_PER_WEEK) { "TOW вне диапазона" }
    }

    /** Секунды от эпохи GPS (06.01.1980). */
    public val totalSeconds: Double get() = gpsWeek * SECONDS_PER_WEEK + timeOfWeekSeconds

    public companion object {
        public const val SECONDS_PER_WEEK: Double = 604_800.0

        /** ГЛОНАСС ведёт время UTC(SU)+3 ч; GPS опережает UTC на [leapSeconds] секунд. */
        public fun fromGlonass(glonassSecondsSinceGpsEpoch: Double, leapSeconds: Int): GnssTime {
            val gps = glonassSecondsSinceGpsEpoch - 3 * 3600 + leapSeconds
            val week = (gps / SECONDS_PER_WEEK).toInt()
            return GnssTime(week, gps - week * SECONDS_PER_WEEK)
        }
    }
}

package ru.bkphenix.core.gnss

import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation

/** Идентификатор спутника: система + номер. */
public data class SatId(val constellation: GnssConstellation, val svid: Int) {
    /** Обозначение в стиле RINEX: G05, R12, E07, C30. */
    public val label: String
        get() = when (constellation) {
            GnssConstellation.GPS -> "G"
            GnssConstellation.GLONASS -> "R"
            GnssConstellation.GALILEO -> "E"
            GnssConstellation.BEIDOU -> "C"
            GnssConstellation.QZSS -> "J"
            GnssConstellation.SBAS -> "S"
            GnssConstellation.IRNSS -> "I"
            GnssConstellation.UNKNOWN -> "?"
        } + svid.toString().padStart(2, '0')

    override fun toString(): String = label
}

/** Часы приёмника из GnssClock, без зависимости от Android. */
public data class RawGnssClock(
    val timeNanos: Long,
    /** `null`, если приёмник ещё не оценил время GPS. */
    val fullBiasNanos: Long?,
    val biasNanos: Double = 0.0,
    val driftNanosPerSecond: Double? = null,
    /** Счётчик разрывов аппаратных часов: при изменении сравнивать эпохи нельзя. */
    val hardwareClockDiscontinuityCount: Int = 0,
    /** Монотонное время эпохи, нс (elapsedRealtimeNanos). */
    val elapsedRealtimeNanos: Long? = null,
)

/** Одно сырое измерение из GnssMeasurement. */
public data class RawGnssMeasurement(
    val sat: SatId,
    val band: GnssBand,
    val cn0DbHz: Double,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long,
    val timeOffsetNanos: Double = 0.0,
    /** Время недели декодировано или известно (STATE_TOW_DECODED | STATE_TOW_KNOWN). */
    val towKnown: Boolean,
    val pseudorangeRateMps: Double? = null,
    /** Уровень АРУ, дБ, если чип его сообщает. */
    val agcDb: Double? = null,
)

/** Эпоха сырых измерений. */
public data class RawGnssEpoch(val clock: RawGnssClock, val measurements: List<RawGnssMeasurement>)

/** Псевдодальность, восстановленная из полей Android (white paper «Raw GNSS Measurements», 2017). */
public data class Pseudorange(
    val measurement: RawGnssMeasurement,
    /** Время приёма по часам приёмника, секунды GPS-недели. */
    val receiveTow: Double,
    val gpsWeek: Int,
    val rangeM: Double,
    val sigmaM: Double,
)

public object Pseudoranges {
    public const val NANOS_PER_WEEK: Long = 604_800L * 1_000_000_000L
    private const val NANOS_PER_SECOND = 1e9
    private const val MAX_RANGE_M = 1.0e8
    private const val MIN_RANGE_M = 1.5e7

    /**
     * Псевдодальности GPS (и QZSS, общая шкала времени): ρ = c · (t_rx − t_tx).
     * Измерения без известного TOW и вне правдоподобного диапазона отбрасываются.
     */
    public fun compute(epoch: RawGnssEpoch): List<Pseudorange> {
        val fullBias = epoch.clock.fullBiasNanos ?: return emptyList()
        val gpsNanos = epoch.clock.timeNanos - fullBias
        if (gpsNanos <= 0) return emptyList()
        val week = (gpsNanos / NANOS_PER_WEEK).toInt()
        val towNanosLong = gpsNanos - week * NANOS_PER_WEEK
        return epoch.measurements.mapNotNull { m ->
            if (!m.towKnown) return@mapNotNull null
            if (m.sat.constellation != GnssConstellation.GPS && m.sat.constellation != GnssConstellation.QZSS) {
                return@mapNotNull null
            }
            val rxNanos = towNanosLong.toDouble() + m.timeOffsetNanos - epoch.clock.biasNanos
            var dt = rxNanos - m.receivedSvTimeNanos.toDouble()
            // Переход через начало недели между излучением и приёмом.
            if (dt < 0) dt += NANOS_PER_WEEK.toDouble()
            val range = dt / NANOS_PER_SECOND * GpsEphemeris.C
            if (range !in MIN_RANGE_M..MAX_RANGE_M) return@mapNotNull null
            Pseudorange(m, rxNanos / NANOS_PER_SECOND, week, range, sigmaFor(m))
        }
    }

    /** Модель ошибки: шум кода по C/N0 и заявленная неопределённость времени. */
    public fun sigmaFor(m: RawGnssMeasurement): Double {
        val byCn0 = 3.0 * Math.pow(10.0, (40.0 - m.cn0DbHz.coerceIn(10.0, 55.0)) / 20.0)
        val byUncertainty = m.receivedSvTimeUncertaintyNanos * 1e-9 * GpsEphemeris.C
        return maxOf(3.0, byCn0, byUncertainty)
    }
}

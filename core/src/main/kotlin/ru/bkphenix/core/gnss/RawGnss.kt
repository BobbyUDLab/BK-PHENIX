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

/** Биты GnssMeasurement.getState(), от которых зависит, известно ли время излучения целиком. */
public object MeasurementState {
    public const val TOW_DECODED: Int = 0x8
    public const val TOW_KNOWN: Int = 0x4000
    public const val GLO_TOD_DECODED: Int = 0x80
    public const val GLO_TOD_KNOWN: Int = 0x8000

    /** ГЛОНАСС передаёт время суток (TOD), остальные системы — время недели (TOW). */
    public fun isTimeKnown(constellation: GnssConstellation, state: Int): Boolean =
        if (constellation == GnssConstellation.GLONASS) state and (GLO_TOD_DECODED or GLO_TOD_KNOWN) != 0
        else state and (TOW_DECODED or TOW_KNOWN) != 0
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
    /** Время излучения известно целиком: TOW, а для ГЛОНАСС — TOD ([MeasurementState.isTimeKnown]). */
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
    private val SAME_WEEK_TIME_SCALES = setOf(GnssConstellation.GPS, GnssConstellation.QZSS, GnssConstellation.GALILEO)

    /** Начало шкалы GPS (06.01.1980) в мс Unix. */
    public const val GPS_EPOCH_UNIX_MILLIS: Long = 315_964_800_000L

    /** Текущая разница GPS − UTC, секунды (с 2017 г.). */
    public const val DEFAULT_LEAP_SECONDS: Int = 18

    /** Время UTC по часам приёмника, мс; `null`, если время GPS ещё не оценено. */
    public fun utcMillis(clock: RawGnssClock, leapSeconds: Int = DEFAULT_LEAP_SECONDS): Long? {
        val fullBias = clock.fullBiasNanos ?: return null
        val gpsNanos = clock.timeNanos - fullBias - clock.biasNanos.toLong()
        return GPS_EPOCH_UNIX_MILLIS + gpsNanos / 1_000_000 - leapSeconds * 1000L
    }

    /**
     * Псевдодальности всех систем с известным временем излучения: ρ = c · (t_rx − t_tx), где t_rx
     * переводится в шкалу системы (GPS, QZSS, Galileo — неделя GPST; BeiDou — неделя BDT;
     * ГЛОНАСС — сутки UTC(SU) + 3 ч). Время приёма в [Pseudorange.receiveTow] — всегда GPST.
     */
    public fun compute(epoch: RawGnssEpoch, leapSeconds: Int = DEFAULT_LEAP_SECONDS): List<Pseudorange> {
        val fullBias = epoch.clock.fullBiasNanos ?: return emptyList()
        val gpsNanos = epoch.clock.timeNanos - fullBias
        if (gpsNanos <= 0) return emptyList()
        val week = (gpsNanos / NANOS_PER_WEEK).toInt()
        val towNanosLong = gpsNanos - week * NANOS_PER_WEEK
        return epoch.measurements.mapNotNull { m ->
            if (!m.towKnown) return@mapNotNull null
            val rxGps = towNanosLong.toDouble() + m.timeOffsetNanos - epoch.clock.biasNanos
            val (rxSystem, period) = when (m.sat.constellation) {
                in SAME_WEEK_TIME_SCALES -> rxGps to NANOS_PER_WEEK.toDouble()
                GnssConstellation.BEIDOU -> (rxGps + GpsTime.BDT_MINUS_GPST_S * NANOS_PER_SECOND).mod(NANOS_PER_WEEK.toDouble()) to
                    NANOS_PER_WEEK.toDouble()
                GnssConstellation.GLONASS -> {
                    val day = GpsTime.SECONDS_PER_DAY * NANOS_PER_SECOND
                    (rxGps + (GpsTime.GLONASS_UTC_OFFSET_S - leapSeconds) * NANOS_PER_SECOND).mod(day) to day
                }
                else -> return@mapNotNull null
            }
            var dt = rxSystem - m.receivedSvTimeNanos.toDouble()
            // Переход через начало недели (суток) между излучением и приёмом.
            if (dt < 0) dt += period
            val range = dt / NANOS_PER_SECOND * GpsEphemeris.C
            if (range !in MIN_RANGE_M..MAX_RANGE_M) return@mapNotNull null
            Pseudorange(m, rxGps / NANOS_PER_SECOND, week, range, sigmaFor(m))
        }
    }

    /** Модель ошибки: шум кода по C/N0 и заявленная неопределённость времени. */
    public fun sigmaFor(m: RawGnssMeasurement): Double {
        val byCn0 = 3.0 * Math.pow(10.0, (40.0 - m.cn0DbHz.coerceIn(10.0, 55.0)) / 20.0)
        val byUncertainty = m.receivedSvTimeUncertaintyNanos * 1e-9 * GpsEphemeris.C
        return maxOf(3.0, byCn0, byUncertainty)
    }
}

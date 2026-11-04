package ru.bkphenix.core.gnss

import kotlin.math.abs

/**
 * Бортовые эфемериды спутника любой системы, приведённые к единой шкале — времени GPS.
 *
 * Все системы считаются по секундам недели GPS ([stateAt]): преобразование в собственную шкалу
 * системы (BDT = GPST − 14 с, ГЛОНАСС = UTC(SU) + 3 ч) делается внутри реализации. Смещение
 * шкалы системы относительно GPST (τ_GPS, GGTO, BGTO — десятки нс) не моделируется: его
 * поглощает межсистемное смещение в решении.
 */
public interface NavEphemeris {
    public val sat: SatId
    public val isHealthy: Boolean

    /** Опорный момент эфемерид: неделя и секунды недели GPS. */
    public val referenceWeek: Int
    public val referenceTow: Double

    /** Полуширина окна применимости, секунды. */
    public val fitHalfWidthS: Double

    /** Положение (ECEF на момент излучения) и смещение часов спутника, секунды. */
    public fun stateAt(gpsTow: Double): SatelliteState

    /** Возраст эфемерид в момент (неделя GPS, секунды недели), секунды. */
    public fun ageSeconds(gpsWeek: Int, gpsTow: Double): Double =
        (gpsWeek - referenceWeek) * GpsTime.SECONDS_PER_WEEK + gpsTow - referenceTow

    public fun isValidAt(gpsWeek: Int, gpsTow: Double): Boolean = abs(ageSeconds(gpsWeek, gpsTow)) <= fitHalfWidthS
}

/** Единая шкала ядра — время GPS — и переходы к шкалам других систем. */
public object GpsTime {
    public const val SECONDS_PER_WEEK: Double = 604_800.0
    public const val SECONDS_PER_DAY: Double = 86_400.0

    /** BDT отстаёт от GPST на 14 с; неделя BDT 0 = неделя GPS 1356. */
    public const val BDT_MINUS_GPST_S: Double = -14.0
    public const val BDT_WEEK_OFFSET: Int = 1356

    /** ГЛОНАСС: время суток UTC(SU) + 3 ч. */
    public const val GLONASS_UTC_OFFSET_S: Double = 10_800.0

    /** Приведение разности времён к ±полнедели. */
    public fun wrapWeek(dt: Double): Double = GpsEphemeris.wrapWeek(dt)

    /** Время суток ГЛОНАСС по секундам недели GPS, секунды (0 … 86400). */
    public fun glonassTimeOfDay(gpsTow: Double, leapSeconds: Int): Double =
        (gpsTow - leapSeconds + GLONASS_UTC_OFFSET_S).mod(SECONDS_PER_DAY)
}

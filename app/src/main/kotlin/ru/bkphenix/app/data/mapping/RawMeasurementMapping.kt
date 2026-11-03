package ru.bkphenix.app.data.mapping

import ru.bkphenix.core.gnss.RawGnssClock
import ru.bkphenix.core.gnss.RawGnssMeasurement
import ru.bkphenix.core.gnss.SatId

/** Перенос полей GnssClock/GnssMeasurement в модель ядра без зависимости ядра от Android. */
object RawMeasurementMapping {
    /** GnssMeasurement.STATE_TOW_DECODED. */
    const val STATE_TOW_DECODED: Int = 0x8

    /** GnssMeasurement.STATE_TOW_KNOWN (API 28+). */
    const val STATE_TOW_KNOWN: Int = 0x4000

    /** GnssMeasurement.STATE_GLO_TOD_DECODED — у ГЛОНАСС другая шкала времени, отдельный флаг. */
    const val STATE_GLO_TOD_DECODED: Int = 0x80

    fun isTowKnown(state: Int): Boolean = state and (STATE_TOW_DECODED or STATE_TOW_KNOWN) != 0

    @Suppress("LongParameterList")
    fun measurement(
        constellationType: Int,
        svid: Int,
        carrierFrequencyHz: Double?,
        cn0DbHz: Double,
        receivedSvTimeNanos: Long,
        receivedSvTimeUncertaintyNanos: Long,
        timeOffsetNanos: Double,
        state: Int,
        pseudorangeRateMps: Double?,
        agcDb: Double?,
    ): RawGnssMeasurement = RawGnssMeasurement(
        sat = SatId(GnssMapping.constellation(constellationType), svid),
        band = GnssMapping.band(carrierFrequencyHz),
        cn0DbHz = cn0DbHz,
        receivedSvTimeNanos = receivedSvTimeNanos,
        receivedSvTimeUncertaintyNanos = receivedSvTimeUncertaintyNanos,
        timeOffsetNanos = timeOffsetNanos,
        towKnown = isTowKnown(state),
        pseudorangeRateMps = pseudorangeRateMps,
        agcDb = agcDb,
    )

    fun clock(
        timeNanos: Long,
        fullBiasNanos: Long?,
        biasNanos: Double?,
        driftNanosPerSecond: Double?,
        hardwareClockDiscontinuityCount: Int,
        elapsedRealtimeNanos: Long?,
    ): RawGnssClock = RawGnssClock(
        timeNanos = timeNanos,
        fullBiasNanos = fullBiasNanos,
        biasNanos = biasNanos ?: 0.0,
        driftNanosPerSecond = driftNanosPerSecond,
        hardwareClockDiscontinuityCount = hardwareClockDiscontinuityCount,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )
}

package ru.bkphenix.app.data.log

import java.util.Locale

/** Строка Raw формата Google GnssLogger: поля GnssClock и GnssMeasurement, null — пустое поле. */
data class RawLogRow(
    val utcTimeMillis: Long,
    val timeNanos: Long,
    val leapSecond: Int?,
    val timeUncertaintyNanos: Double?,
    val fullBiasNanos: Long?,
    val biasNanos: Double?,
    val biasUncertaintyNanos: Double?,
    val driftNanosPerSecond: Double?,
    val driftUncertaintyNanosPerSecond: Double?,
    val hardwareClockDiscontinuityCount: Int,
    val svid: Int,
    val timeOffsetNanos: Double,
    val state: Int,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long,
    val cn0DbHz: Double,
    val pseudorangeRateMps: Double,
    val pseudorangeRateUncertaintyMps: Double,
    val accumulatedDeltaRangeState: Int,
    val accumulatedDeltaRangeMeters: Double,
    val accumulatedDeltaRangeUncertaintyMeters: Double,
    val carrierFrequencyHz: Double?,
    val multipathIndicator: Int,
    val snrInDb: Double?,
    val constellationType: Int,
    val agcDb: Double?,
)

/** Строка Status: один сигнал из GnssStatus. */
data class StatusLogRow(
    val unixTimeMillis: Long,
    val signalCount: Int,
    val signalIndex: Int,
    val constellationType: Int,
    val svid: Int,
    val carrierFrequencyHz: Double?,
    val cn0DbHz: Double,
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    val usedInFix: Boolean,
    val hasAlmanac: Boolean,
    val hasEphemeris: Boolean,
)

/**
 * Текстовый лог, совместимый с Google GnssLogger v2: его читают GnssAnalysis, gnss_lib_py,
 * RTKLIB-конвертеры. Строки комментариев начинаются с «#», заголовки полей — «# Raw,…».
 */
object GnssLoggerFormat {
    private const val RAW_FIELDS = "Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos," +
        "BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond," +
        "HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos," +
        "ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond," +
        "PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters," +
        "AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase," +
        "CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,ConstellationType,AgcDb"
    private const val FIX_FIELDS = "Fix,Provider,LatitudeDegrees,LongitudeDegrees,AltitudeMeters,SpeedMps," +
        "AccuracyMeters,BearingDegrees,UnixTimeMillis,SpeedAccuracyMps,BearingAccuracyDegrees," +
        "elapsedRealtimeNanos,VerticalAccuracyMeters,MockLocation"
    private const val STATUS_FIELDS = "Status,UnixTimeMillis,SignalCount,SignalIndex,ConstellationType,Svid," +
        "CarrierFrequencyHz,Cn0DbHz,AzimuthDegrees,ElevationDegrees,UsedInFix,HasAlmanacData,HasEphemerisData," +
        "BasebandCn0DbHz"
    private const val NAV_FIELDS = "Nav,Svid,Type,Status,MessageId,Sub-messageId,Data(Bytes)"

    fun header(appVersion: String, manufacturer: String, model: String, platform: String): String = buildString {
        appendLine("# ")
        appendLine("# Header Description:")
        appendLine("# ")
        appendLine("# Version: v2.0.0.1 Platform: $platform Manufacturer: $manufacturer Model: $model")
        appendLine("# Writer: BK-PHENIX $appVersion")
        appendLine("# ")
        appendLine("# $RAW_FIELDS")
        appendLine("# ")
        appendLine("# $FIX_FIELDS")
        appendLine("# ")
        appendLine("# $STATUS_FIELDS")
        appendLine("# ")
        appendLine("# $NAV_FIELDS")
        appendLine("# ")
    }

    fun raw(r: RawLogRow): String = join(
        "Raw", r.utcTimeMillis, r.timeNanos, r.leapSecond, r.timeUncertaintyNanos, r.fullBiasNanos, r.biasNanos,
        r.biasUncertaintyNanos, r.driftNanosPerSecond, r.driftUncertaintyNanosPerSecond,
        r.hardwareClockDiscontinuityCount, r.svid, r.timeOffsetNanos, r.state, r.receivedSvTimeNanos,
        r.receivedSvTimeUncertaintyNanos, r.cn0DbHz, r.pseudorangeRateMps, r.pseudorangeRateUncertaintyMps,
        r.accumulatedDeltaRangeState, r.accumulatedDeltaRangeMeters, r.accumulatedDeltaRangeUncertaintyMeters,
        r.carrierFrequencyHz, null, null, null, r.multipathIndicator, r.snrInDb, r.constellationType, r.agcDb,
    )

    fun status(s: StatusLogRow): String = join(
        "Status", s.unixTimeMillis, s.signalCount, s.signalIndex, s.constellationType, s.svid, s.carrierFrequencyHz,
        s.cn0DbHz, s.azimuthDegrees, s.elevationDegrees, s.usedInFix.bit(), s.hasAlmanac.bit(), s.hasEphemeris.bit(),
        null,
    )

    @Suppress("LongParameterList")
    fun fix(
        provider: String,
        latitude: Double,
        longitude: Double,
        altitude: Double?,
        accuracyM: Float?,
        unixTimeMillis: Long,
        elapsedRealtimeNanos: Long,
        isMock: Boolean,
    ): String = join(
        "Fix", provider.uppercase(Locale.ROOT), latitude, longitude, altitude, null, accuracyM, null, unixTimeMillis,
        null, null, elapsedRealtimeNanos, null, isMock.bit(),
    )

    fun nav(svid: Int, type: Int, status: Int, messageId: Int, submessageId: Int, data: ByteArray): String =
        join("Nav", svid, type, status, messageId, submessageId) + "," + data.joinToString(",")

    private fun Boolean.bit() = if (this) 1 else 0

    private fun join(vararg values: Any?): String = values.joinToString(",") { v ->
        when (v) {
            null -> ""
            is Double -> if (v.isNaN()) "" else v.toString()
            is Float -> if (v.isNaN()) "" else v.toString()
            else -> v.toString()
        }
    }
}

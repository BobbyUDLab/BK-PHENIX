package ru.bkphenix.core.replay

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.gnss.MeasurementState
import ru.bkphenix.core.gnss.RawGnssClock
import ru.bkphenix.core.gnss.RawGnssEpoch
import ru.bkphenix.core.gnss.RawGnssMeasurement
import ru.bkphenix.core.gnss.SatId
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation
import kotlin.math.abs

/** Фикс чипа из строки `Fix` журнала GnssLogger. */
public data class LoggedFix(
    val provider: String,
    val point: GeoPoint,
    val accuracyM: Double,
    val utcMillis: Long,
)

/** Содержимое журнала GnssLogger: эпохи сырых измерений и фиксы в порядке записи. */
public data class GnssLog(val epochs: List<RawGnssEpoch>, val fixes: List<LoggedFix>)

/**
 * Разбор текстовых журналов Google GnssLogger (`gnss_log_*.txt`, `pseudoranges_log_*.txt`).
 * Колонки берутся по именам из заголовка `# Raw,...` и `# Fix,...`, поэтому подходят и старые
 * (1.4), и новые версии формата. Строки `Raw` группируются в эпохи по TimeNanos.
 * Время излучения считается известным по битам State: TOW, а для ГЛОНАСС — TOD.
 */
public object GnssLoggerParser {
    private const val BAND_TOLERANCE_MHZ = 6.0

    public fun parse(lines: Sequence<String>): GnssLog {
        var raw: Map<String, Int> = emptyMap()
        var fix: Map<String, Int> = emptyMap()
        val epochs = ArrayList<RawGnssEpoch>()
        val fixes = ArrayList<LoggedFix>()
        var clock: RawGnssClock? = null
        var current = ArrayList<RawGnssMeasurement>()

        fun flush() {
            clock?.let { if (current.isNotEmpty()) epochs += RawGnssEpoch(it, current) }
            current = ArrayList()
            clock = null
        }

        for (line in lines) {
            when {
                line.startsWith("# Raw,") -> raw = header(line.removePrefix("# "))
                line.startsWith("# Fix,") -> fix = header(line.removePrefix("# "))
                line.startsWith("Raw,") && raw.isNotEmpty() -> {
                    val f = line.split(',')
                    val c = clockOf(f, raw) ?: continue
                    if (clock?.timeNanos != c.timeNanos) {
                        flush()
                        clock = c
                    }
                    measurementOf(f, raw)?.let(current::add)
                }
                line.startsWith("Fix,") && fix.isNotEmpty() -> fixOf(line.split(','), fix)?.let(fixes::add)
            }
        }
        flush()
        return GnssLog(epochs, fixes)
    }

    private fun header(line: String): Map<String, Int> =
        line.split(',').withIndex().associate { (i, name) -> name.trim() to i }

    private fun List<String>.str(h: Map<String, Int>, name: String): String? =
        h[name]?.let { getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

    private fun List<String>.long(h: Map<String, Int>, name: String): Long? = str(h, name)?.toLongOrNull()
    private fun List<String>.double(h: Map<String, Int>, name: String): Double? = str(h, name)?.toDoubleOrNull()
    private fun List<String>.int(h: Map<String, Int>, name: String): Int? = str(h, name)?.toIntOrNull()

    private fun clockOf(f: List<String>, h: Map<String, Int>): RawGnssClock? {
        val timeNanos = f.long(h, "TimeNanos") ?: return null
        return RawGnssClock(
            timeNanos = timeNanos,
            fullBiasNanos = f.long(h, "FullBiasNanos"),
            biasNanos = f.double(h, "BiasNanos") ?: 0.0,
            driftNanosPerSecond = f.double(h, "DriftNanosPerSecond"),
            hardwareClockDiscontinuityCount = f.int(h, "HardwareClockDiscontinuityCount") ?: 0,
            elapsedRealtimeNanos = f.long(h, "ElapsedRealtimeMillis")?.let { it * 1_000_000 },
        )
    }

    private fun measurementOf(f: List<String>, h: Map<String, Int>): RawGnssMeasurement? {
        val constellation = constellation(f.int(h, "ConstellationType") ?: return null)
        val state = f.int(h, "State") ?: 0
        return RawGnssMeasurement(
            sat = SatId(constellation, f.int(h, "Svid") ?: return null),
            band = band(f.double(h, "CarrierFrequencyHz")),
            cn0DbHz = f.double(h, "Cn0DbHz") ?: return null,
            receivedSvTimeNanos = f.long(h, "ReceivedSvTimeNanos") ?: return null,
            receivedSvTimeUncertaintyNanos = f.long(h, "ReceivedSvTimeUncertaintyNanos") ?: Long.MAX_VALUE,
            timeOffsetNanos = f.double(h, "TimeOffsetNanos") ?: 0.0,
            towKnown = MeasurementState.isTimeKnown(constellation, state),
            pseudorangeRateMps = f.double(h, "PseudorangeRateMetersPerSecond"),
            agcDb = f.double(h, "AgcDb"),
        )
    }

    private fun fixOf(f: List<String>, h: Map<String, Int>): LoggedFix? {
        val lat = (f.double(h, "Latitude") ?: f.double(h, "LatitudeDegrees")) ?: return null
        val lon = (f.double(h, "Longitude") ?: f.double(h, "LongitudeDegrees")) ?: return null
        val time = f.long(h, "(UTC)TimeInMs") ?: f.long(h, "UnixTimeMillis") ?: return null
        return LoggedFix(
            provider = f.str(h, "Provider") ?: "?",
            point = GeoPoint(lat, lon, f.double(h, "Altitude") ?: f.double(h, "AltitudeMeters")),
            accuracyM = (f.double(h, "Accuracy") ?: f.double(h, "AccuracyMeters"))?.takeIf { it > 0 } ?: 10.0,
            utcMillis = time,
        )
    }

    /** Коды ConstellationType из android.location.GnssStatus. */
    public fun constellation(type: Int): GnssConstellation = when (type) {
        1 -> GnssConstellation.GPS
        2 -> GnssConstellation.SBAS
        3 -> GnssConstellation.GLONASS
        4 -> GnssConstellation.QZSS
        5 -> GnssConstellation.BEIDOU
        6 -> GnssConstellation.GALILEO
        7 -> GnssConstellation.IRNSS
        else -> GnssConstellation.UNKNOWN
    }

    /** Без частоты (старые журналы) считается L1: тогда чипы сообщали только его. */
    private fun band(hz: Double?): GnssBand {
        if (hz == null || hz <= 0.0) return GnssBand.L1
        val mhz = hz / 1e6
        return GnssBand.entries.filter { it != GnssBand.UNKNOWN }
            .minByOrNull { abs(it.centerFrequencyMhz - mhz) }
            ?.takeIf { abs(it.centerFrequencyMhz - mhz) <= BAND_TOLERANCE_MHZ } ?: GnssBand.UNKNOWN
    }
}

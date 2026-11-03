package ru.bkphenix.app.data.mapping

import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation
import kotlin.math.abs

/** Отображение констант android.location.GnssStatus.CONSTELLATION_* на модель ядра. */
object GnssMapping {
    fun constellation(androidType: Int): GnssConstellation = when (androidType) {
        1 -> GnssConstellation.GPS
        2 -> GnssConstellation.SBAS
        3 -> GnssConstellation.GLONASS
        4 -> GnssConstellation.QZSS
        5 -> GnssConstellation.BEIDOU
        6 -> GnssConstellation.GALILEO
        7 -> GnssConstellation.IRNSS
        else -> GnssConstellation.UNKNOWN
    }

    /** Диапазон по несущей частоте, Гц. `null` или 0 — неизвестно. */
    fun band(carrierFrequencyHz: Double?): GnssBand {
        if (carrierFrequencyHz == null || carrierFrequencyHz <= 0.0) return GnssBand.UNKNOWN
        val mhz = carrierFrequencyHz / 1e6
        if (mhz in 1598.0..1606.0) return GnssBand.G1
        if (mhz in 1242.0..1249.0) return GnssBand.G2
        return GnssBand.entries
            .filter { it != GnssBand.UNKNOWN && it != GnssBand.G1 && it != GnssBand.G2 }
            .minByOrNull { abs(it.centerFrequencyMhz - mhz) }
            ?.takeIf { abs(it.centerFrequencyMhz - mhz) <= BAND_TOLERANCE_MHZ }
            ?: GnssBand.UNKNOWN
    }

    /** Человекочитаемое имя диапазона с учётом созвездия (L1/E1/B1C, L5/E5a/B2a). */
    fun bandLabel(band: GnssBand, constellation: GnssConstellation): String = when (band) {
        GnssBand.L1 -> when (constellation) {
            GnssConstellation.GALILEO -> "E1"
            GnssConstellation.BEIDOU -> "B1C"
            else -> "L1"
        }
        GnssBand.L5 -> when (constellation) {
            GnssConstellation.GALILEO -> "E5a"
            GnssConstellation.BEIDOU -> "B2a"
            else -> "L5"
        }
        GnssBand.E5B -> if (constellation == GnssConstellation.BEIDOU) "B2b" else "E5b"
        GnssBand.B1I -> "B1I"
        GnssBand.L2 -> "L2"
        GnssBand.G1 -> "G1"
        GnssBand.G2 -> "G2"
        GnssBand.UNKNOWN -> "?"
    }

    private const val BAND_TOLERANCE_MHZ = 3.0
}

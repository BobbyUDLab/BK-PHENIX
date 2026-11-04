package ru.bkphenix.core.gnss

import ru.bkphenix.core.geo.EcefCoordinate
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation
import kotlin.math.roundToLong
import java.util.Random

/**
 * Генератор синтетических сырых измерений: истинное положение, ошибка часов приёмника,
 * задержка тропосферы, шум и умышленные ошибки отдельных спутников. С [multiGnss] добавляется второе
 * созвездие (метки Galileo, орбиты сдвинуты на 30°) с межсистемным смещением [interSystemBiasM].
 */
internal class SyntheticEpoch(
    val truth: GeoPoint = GeoPoint(55.75393, 37.620795, 150.0),
    val week: Int = 2440,
    val tow: Double = 302_400.0,
    val clockErrorNanos: Long = 123_456,
    val noiseM: Double = 0.0,
    val seed: Long = 1,
    val multiGnss: Boolean = false,
    val interSystemBiasM: Double = 30.0,
) {
    val ephemerides: List<GpsEphemeris> = SyntheticConstellation.all(toe = tow)
    val galileo: List<NavEphemeris> = if (multiGnss) SyntheticConstellation.galileo(toe = tow) else emptyList()
    val store: EphemerisStore = EphemerisStore().also { s ->
        ephemerides.forEach(s::put)
        if (galileo.isNotEmpty()) s.replaceExternalNav(galileo)
    }
    private val receiver = EcefCoordinate.fromGeodetic(truth)
    private val timeNanos = 500_000_000_000L
    private val biasNanos = 0.25

    /** Смещение часов приёмника, метры, которое должно получиться в решении. */
    val clockBiasM: Double get() = (clockErrorNanos - biasNanos) * 1e-9 * GpsEphemeris.C

    val clock: RawGnssClock
        get() {
            val trueNanos = week * Pseudoranges.NANOS_PER_WEEK + (tow * 1e9).roundToLong()
            return RawGnssClock(timeNanos, timeNanos - trueNanos - clockErrorNanos, biasNanos, 0.0, 0)
        }

    /** Видимые (угол места ≥ [mask]) спутники. */
    fun visible(mask: Double = 12.0): List<GpsEphemeris> = ephemerides.filter { eph ->
        val p = eph.stateAt(tow - 0.075).position
        receiver.enuTo(p).elevationDeg >= mask
    }

    /** Видимые спутники всех созвездий. */
    fun visibleAll(mask: Double = 12.0): List<NavEphemeris> = visible(mask) + galileo.filter { eph ->
        receiver.enuTo(eph.stateAt(tow - 0.075).position).elevationDeg >= mask
    }

    fun epoch(
        sats: List<NavEphemeris> = visibleAll(),
        faultsM: Map<Int, Double> = emptyMap(),
        cn0: (Int) -> Double = { 30.0 + it % 15 },
        band: GnssBand = GnssBand.L1,
        extraDelayM: (Int) -> Double = { 0.0 },
        agcDb: Double? = null,
        faultsBySat: Map<SatId, Double> = emptyMap(),
    ): RawGnssEpoch {
        val rnd = Random(seed)
        val ms = sats.map { eph ->
            var tau = 0.07
            var el = 0.0
            repeat(5) {
                val s = WlsSolver.earthRotationCorrected(eph.stateAt(tow - tau).position, receiver)
                el = receiver.enuTo(s).elevationDeg
                tau = (s - receiver).norm / GpsEphemeris.C
            }
            val svid = eph.sat.svid
            val gps = eph.sat.constellation == GnssConstellation.GPS
            val fault = faultsBySat[eph.sat] ?: (if (gps) faultsM[svid] else null) ?: 0.0
            val isb = if (gps) 0.0 else interSystemBiasM
            val delayM = Troposphere.delayM(el) + fault + isb + extraDelayM(svid) + rnd.nextGaussian() * noiseM
            val tTx = tow - tau
            val svTime = tTx + eph.stateAt(tTx).clockBiasS - delayM / GpsEphemeris.C
            RawGnssMeasurement(
                sat = eph.sat,
                band = band,
                cn0DbHz = cn0(svid),
                receivedSvTimeNanos = (svTime * 1e9).roundToLong(),
                receivedSvTimeUncertaintyNanos = 5,
                towKnown = true,
                agcDb = agcDb,
            )
        }
        return RawGnssEpoch(clock, ms)
    }
}

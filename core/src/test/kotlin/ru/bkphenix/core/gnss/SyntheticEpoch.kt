package ru.bkphenix.core.gnss

import ru.bkphenix.core.geo.EcefCoordinate
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation
import kotlin.math.roundToLong
import java.util.Random

/**
 * Генератор синтетических сырых измерений: истинное положение, ошибка часов приёмника,
 * задержка тропосферы, шум и умышленные ошибки отдельных спутников.
 */
internal class SyntheticEpoch(
    val truth: GeoPoint = GeoPoint(55.75393, 37.620795, 150.0),
    val week: Int = 2440,
    val tow: Double = 302_400.0,
    val clockErrorNanos: Long = 123_456,
    val noiseM: Double = 0.0,
    val seed: Long = 1,
) {
    val ephemerides: List<GpsEphemeris> = SyntheticConstellation.all(toe = tow)
    val store: EphemerisStore = EphemerisStore().also { s -> ephemerides.forEach(s::put) }
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

    fun epoch(
        sats: List<GpsEphemeris> = visible(),
        faultsM: Map<Int, Double> = emptyMap(),
        cn0: (Int) -> Double = { 30.0 + it % 15 },
        band: GnssBand = GnssBand.L1,
        extraDelayM: (Int) -> Double = { 0.0 },
        agcDb: Double? = null,
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
            val delayM = Troposphere.delayM(el) + (faultsM[eph.svid] ?: 0.0) + extraDelayM(eph.svid) + rnd.nextGaussian() * noiseM
            val tTx = tow - tau
            val svTime = tTx + eph.stateAt(tTx).clockBiasS - delayM / GpsEphemeris.C
            RawGnssMeasurement(
                sat = SatId(GnssConstellation.GPS, eph.svid),
                band = band,
                cn0DbHz = cn0(eph.svid),
                receivedSvTimeNanos = (svTime * 1e9).roundToLong(),
                receivedSvTimeUncertaintyNanos = 5,
                towKnown = true,
                agcDb = agcDb,
            )
        }
        return RawGnssEpoch(clock, ms)
    }
}

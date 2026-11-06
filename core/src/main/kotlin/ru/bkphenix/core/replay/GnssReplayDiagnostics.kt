package ru.bkphenix.core.replay

import ru.bkphenix.core.gnss.EphemerisStore
import ru.bkphenix.core.gnss.Pseudoranges
import ru.bkphenix.core.gnss.RinexNav
import ru.bkphenix.core.gnss.WlsSolver
import ru.bkphenix.core.model.GnssConstellation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Research-only summaries. These statistics are not an integrity alarm or a spoofing verdict. */
public data class ConstellationReplayStats(
    val constellation: GnssConstellation,
    val measurementCount: Int,
    val epochsWithMeasurements: Int,
    val epochsWithPrr: Int,
    val epochsWithPrrAndUncertainty: Int,
    val medianPrrUncertaintyMps: Double?,
    val standaloneWlsEpochs: Int,
)

public data class ConstellationSeparationStats(
    val first: GnssConstellation,
    val second: GnssConstellation,
    val pairedEpochs: Int,
    val medianHorizontalSeparationM: Double,
    val maximumHorizontalSeparationM: Double,
)

public data class GnssReplayDiagnostics(
    val byConstellation: List<ConstellationReplayStats>,
    val pairwiseSeparations: List<ConstellationSeparationStats>,
)

/**
 * Replays standalone per-constellation WLS against broadcast navigation data and reports
 * cross-constellation separation. A disagreement is only a diagnostic clue: it can also arise
 * from weak geometry, multipath, stale ephemerides, inter-system timing, or receiver behavior.
 */
public object GnssReplayDiagnosticsAnalyzer {
    private val systems = listOf(
        GnssConstellation.GPS, GnssConstellation.GLONASS, GnssConstellation.GALILEO,
        GnssConstellation.BEIDOU,
    )

    public fun analyze(log: GnssLog, nav: RinexNav): GnssReplayDiagnostics {
        val stores = systems.associateWith { system ->
            EphemerisStore().also { store ->
                store.replaceExternalNav(nav.ephemerides.filter { it.sat.constellation == system }, nav.ionosphere)
            }
        }
        val solutions = systems.associateWith { LinkedHashMap<Int, ru.bkphenix.core.gnss.WlsSolution>() }
        val measurementGroups = log.epochs.flatMap { epoch -> epoch.measurements }.groupBy { it.sat.constellation }

        log.epochs.forEachIndexed { index, epoch ->
            systems.forEach { system ->
                val measurements = epoch.measurements.filter { it.sat.constellation == system }
                if (measurements.isEmpty()) return@forEach
                val isolated = epoch.copy(measurements = measurements)
                val ranges = Pseudoranges.compute(isolated, nav.leapSeconds ?: Pseudoranges.DEFAULT_LEAP_SECONDS)
                val inputs = WlsSolver().prepare(ranges, stores.getValue(system))
                WlsSolver().solve(inputs)?.let { solutions.getValue(system)[index] = it }
            }
        }

        val stats = systems.map { system ->
            val measurements = measurementGroups[system].orEmpty()
            val uncertainties = measurements.mapNotNull { m ->
                m.pseudorangeRateUncertaintyMps?.takeIf { it.isFinite() && it >= 0.0 }
            }.sorted()
            ConstellationReplayStats(
                constellation = system,
                measurementCount = measurements.size,
                epochsWithMeasurements = log.epochs.count { epoch -> epoch.measurements.any { it.sat.constellation == system } },
                epochsWithPrr = log.epochs.count { epoch -> epoch.measurements.any { it.sat.constellation == system && it.pseudorangeRateMps?.isFinite() == true } },
                epochsWithPrrAndUncertainty = log.epochs.count { epoch -> epoch.measurements.any {
                    it.sat.constellation == system && it.pseudorangeRateMps?.isFinite() == true &&
                        it.pseudorangeRateUncertaintyMps?.let { u -> u.isFinite() && u >= 0.0 } == true
                } },
                medianPrrUncertaintyMps = uncertainties.let {
                    when {
                        it.isEmpty() -> null
                        it.size % 2 == 1 -> it[it.size / 2]
                        else -> (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
                    }
                },
                standaloneWlsEpochs = solutions.getValue(system).size,
            )
        }

        val separations = ArrayList<ConstellationSeparationStats>()
        for (i in systems.indices) for (j in i + 1 until systems.size) {
            val a = systems[i]
            val b = systems[j]
            val common = solutions.getValue(a).keys.intersect(solutions.getValue(b).keys)
            val distances = common.map { epoch ->
                horizontalDistanceM(solutions.getValue(a).getValue(epoch).point, solutions.getValue(b).getValue(epoch).point)
            }.sorted()
            if (distances.isNotEmpty()) separations += ConstellationSeparationStats(
                a, b, distances.size, distances[distances.size / 2], distances.last(),
            )
        }
        return GnssReplayDiagnostics(stats, separations)
    }

    private fun horizontalDistanceM(a: ru.bkphenix.core.geo.GeoPoint, b: ru.bkphenix.core.geo.GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * 6_371_000.0 * atan2(sqrt(h), sqrt(1.0 - h))
    }
}

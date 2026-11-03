package ru.bkphenix.app.ui.satellites

import ru.bkphenix.app.data.mapping.GnssMapping
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.GnssConstellation
import ru.bkphenix.core.model.SatelliteStatus

/** Сводка по созвездию для экрана «Спутники». */
data class ConstellationSummary(
    val constellation: GnssConstellation,
    val visible: Int,
    val used: Int,
    val bandLabels: List<String>,
    val meanCn0DbHz: Double?,
)

object SatelliteSummary {
    fun byConstellation(satellites: List<SatelliteStatus>): List<ConstellationSummary> =
        satellites.groupBy { it.constellation }.map { (c, list) ->
            val tracked = list.filter { it.cn0DbHz > 0 }
            ConstellationSummary(
                constellation = c,
                visible = list.distinctBy { it.svid }.size,
                used = list.filter { it.usedInFix }.distinctBy { it.svid }.size,
                bandLabels = list.map { it.band }.distinct().sortedBy { it.ordinal }.map { GnssMapping.bandLabel(it, c) },
                meanCn0DbHz = tracked.takeIf { it.isNotEmpty() }?.map { it.cn0DbHz }?.average(),
            )
        }.sortedByDescending { it.visible }

    /** Двухчастотный приём: есть сигналы L5/E5a/B2a. */
    fun isDualFrequency(satellites: List<SatelliteStatus>): Boolean = satellites.any { it.band == GnssBand.L5 }
}

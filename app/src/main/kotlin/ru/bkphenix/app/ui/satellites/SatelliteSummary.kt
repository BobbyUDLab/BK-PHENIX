package ru.bkphenix.app.ui.satellites

import ru.bkphenix.app.data.mapping.GnssMapping
import ru.bkphenix.core.gnss.RawGnssEpoch
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

    /**
     * Двухчастотный приём: сигналы L5/E5a/B2a есть в статусе спутников или в сырых измерениях.
     * null — данных ещё нет ни из одного источника.
     */
    fun isDualFrequency(satellites: List<SatelliteStatus>, measurementBands: Set<GnssBand>?): Boolean? {
        val bands = satellites.map { it.band }.toSet() + measurementBands.orEmpty()
        if (bands.none { it != GnssBand.UNKNOWN }) return null
        return GnssBand.L5 in bands
    }

    /**
     * Список спутников по сырым измерениям — запасной вариант для чипов, которые не присылают
     * GnssStatus. Угол места и азимут в измерениях отсутствуют, поэтому NaN.
     */
    fun fromMeasurements(epoch: RawGnssEpoch): List<SatelliteStatus> = epoch.measurements.map { m ->
        SatelliteStatus(
            constellation = m.sat.constellation,
            svid = m.sat.svid,
            band = m.band,
            cn0DbHz = m.cn0DbHz,
            elevationDeg = Double.NaN,
            azimuthDeg = Double.NaN,
            usedInFix = false,
        )
    }.distinctBy { Triple(it.constellation, it.svid, it.band) }
}

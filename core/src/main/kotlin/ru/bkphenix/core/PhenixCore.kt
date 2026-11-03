package ru.bkphenix.core

import ru.bkphenix.core.anchor.AnchorEvaluation
import ru.bkphenix.core.anchor.AnchorManager
import ru.bkphenix.core.anchor.AnchorState
import ru.bkphenix.core.compare.ComparisonResult
import ru.bkphenix.core.compare.PositionComparator
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.gnss.GnssIntegrityProcessor
import ru.bkphenix.core.gnss.GnssIntegrityResult
import ru.bkphenix.core.gnss.NavigationSubframe
import ru.bkphenix.core.gnss.RawGnssEpoch
import ru.bkphenix.core.integrity.CompositeIntegrityEngine
import ru.bkphenix.core.integrity.ConsistencyIntegrityEngine
import ru.bkphenix.core.integrity.HammingIntegrityEngine
import ru.bkphenix.core.integrity.IntegrityContext
import ru.bkphenix.core.integrity.IntegrityEngine
import ru.bkphenix.core.integrity.IntegrityReport
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.integrity.NavigationWord
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.TimeObservation

/** Итоговое решение одной эпохи. */
public data class PhenixSolution(
    val point: GeoPoint?,
    val accuracyM: Double?,
    val integrity: IntegrityReport,
    val anchor: AnchorEvaluation,
    val comparison: ComparisonResult,
    /** Контроль по сырым измерениям ГНСС, если они были в эпохе. */
    val gnss: GnssIntegrityResult? = null,
)

/**
 * Единый контур доверия: компаратор → контроль целостности (включая RAIM и детекторы подмены
 * по сырым измерениям) → проверка якорем. Собственное решение по псевдодальностям в компаратор
 * не добавляется: оно использует те же сигналы, что и чип, и не является независимым источником.
 */
public class PhenixCore(
    private val comparator: PositionComparator = PositionComparator(),
    private val integrityEngine: IntegrityEngine = CompositeIntegrityEngine(
        listOf(ConsistencyIntegrityEngine(), HammingIntegrityEngine()),
    ),
    public val anchorManager: AnchorManager = AnchorManager(),
    public val gnssProcessor: GnssIntegrityProcessor = GnssIntegrityProcessor(),
) {
    public fun solve(
        observations: List<PositionObservation>,
        nowMillis: Long,
        navigationWords: List<NavigationWord> = emptyList(),
        timeObservations: List<TimeObservation> = emptyList(),
        rawGnss: RawGnssEpoch? = null,
        navigationSubframes: List<NavigationSubframe> = emptyList(),
    ): PhenixSolution {
        gnssProcessor.addSubframes(navigationSubframes)
        val words = navigationWords + navigationSubframes.flatMap { it.words }
        val comparison = comparator.compare(observations)
        val gnss = rawGnss?.let { gnssProcessor.process(it, observations, timeObservations) }
        var integrity = integrityEngine.evaluate(
            IntegrityContext(observations, comparison, words, timeObservations),
        )
        if (gnss != null) integrity = IntegrityReport.merge(listOf(integrity, gnss.report))
        val anchor = anchorManager.evaluate(comparison.estimate, comparison.accuracyM, nowMillis)
        if (anchor.state == AnchorState.ANCHOR_CONFLICT) {
            integrity = IntegrityReport.merge(
                listOf(integrity, IntegrityReport(IntegrityState.SPOOFING_SUSPECTED, listOf(anchor.message))),
            )
        }
        val usesAnchorFallback = comparison.estimate == null && anchor.anchor != null
        return PhenixSolution(
            point = if (usesAnchorFallback) anchor.anchor?.point else comparison.estimate,
            accuracyM = if (usesAnchorFallback) anchor.anchor?.accuracyM else comparison.accuracyM,
            integrity = integrity,
            anchor = anchor,
            comparison = comparison,
            gnss = gnss,
        )
    }
}

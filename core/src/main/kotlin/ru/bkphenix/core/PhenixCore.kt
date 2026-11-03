package ru.bkphenix.core

import ru.bkphenix.core.anchor.AnchorEvaluation
import ru.bkphenix.core.anchor.AnchorManager
import ru.bkphenix.core.anchor.AnchorState
import ru.bkphenix.core.compare.ComparisonResult
import ru.bkphenix.core.compare.PositionComparator
import ru.bkphenix.core.geo.GeoPoint
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
)

/** Единый контур доверия: компаратор → контроль целостности → проверка якорем. */
public class PhenixCore(
    private val comparator: PositionComparator = PositionComparator(),
    private val integrityEngine: IntegrityEngine = CompositeIntegrityEngine(
        listOf(ConsistencyIntegrityEngine(), HammingIntegrityEngine()),
    ),
    public val anchorManager: AnchorManager = AnchorManager(),
) {
    public fun solve(
        observations: List<PositionObservation>,
        nowMillis: Long,
        navigationWords: List<NavigationWord> = emptyList(),
        timeObservations: List<TimeObservation> = emptyList(),
    ): PhenixSolution {
        val comparison = comparator.compare(observations)
        var integrity = integrityEngine.evaluate(
            IntegrityContext(observations, comparison, navigationWords, timeObservations),
        )
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
        )
    }
}
